package labmonitor.server;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import labmonitor.common.Protocol;

/**
 * The monitoring server that runs on the lab instructor's machine.
 *
 * Threads:
 *   tcp-acceptor   waits for lab PCs to connect and starts one ClientHandler thread per PC
 *   udp-heartbeat  receives heartbeat datagrams from every PC (HeartbeatListener)
 *   watchdog       detects PCs whose heartbeats have stopped (Watchdog)
 */
public class LabServer {

    private final ServerConfig config;
    private final AlertManager alertManager;
    private final Map<String, PcInfo> pcs = new ConcurrentHashMap<String, PcInfo>();
    private final List<ServerListener> listeners = new CopyOnWriteArrayList<ServerListener>();
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<ClientHandler>();

    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private volatile boolean running;

    public LabServer(ServerConfig config) {
        this.config = config;
        this.alertManager = new AlertManager(config.logFile);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.tcpPort);
        try {
            udpSocket = new DatagramSocket(config.udpPort);
        } catch (IOException e) {
            serverSocket.close();
            throw e;
        }
        running = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "tcp-acceptor").start();

        new Thread(new HeartbeatListener(this, udpSocket), "udp-heartbeat").start();

        Thread watchdog = new Thread(new Watchdog(this), "watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Listens for incoming client socket requests, one thread per connected PC. */
    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                ClientHandler handler = new ClientHandler(this, client);
                clients.add(handler);
                if (running) {
                    handler.start();
                } else {
                    handler.close();
                    clients.remove(handler);
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Accept failed: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        if (udpSocket != null) udpSocket.close();
        for (ClientHandler client : clients) {
            client.close();
        }
    }

    void onClientClosed(ClientHandler client) {
        clients.remove(client);
    }

    public boolean isRunning() {
        return running;
    }

    public ServerConfig getConfig() {
        return config;
    }

    public void addListener(ServerListener listener) {
        listeners.add(listener);
    }

    public List<PcInfo> getPcs() {
        List<PcInfo> list = new ArrayList<PcInfo>(pcs.values());
        Collections.sort(list, new Comparator<PcInfo>() {
            @Override
            public int compare(PcInfo a, PcInfo b) {
                return a.getPcId().compareToIgnoreCase(b.getPcId());
            }
        });
        return list;
    }

    public PcInfo getPc(String pcId) {
        return pcs.get(pcId);
    }

    public List<Alert> getAlertHistory() {
        return alertManager.getHistory();
    }

    // ------------------------------------------------------------------
    // Events coming from the network threads
    // ------------------------------------------------------------------

    /** Called by a ClientHandler. Returns null if another PC is already connected with the same id. */
    PcInfo register(ClientHandler handler, String pcId, String hostname, String user, String os) {
        PcInfo pc;
        synchronized (pcs) {
            pc = pcs.get(pcId);
            if (pc != null && pc.isConnected()) {
                return null;
            }
            if (pc == null) {
                pc = new PcInfo(pcId);
                pcs.put(pcId, pc);
            }
            pc.connect(handler, hostname, user, os, handler.getAddress());
        }
        return pc;
    }

    /** Called by a ClientHandler once the PC has been sent its WELCOME. */
    void onRegistered(PcInfo pc) {
        raise(Alert.Severity.INFO, Alert.Type.CONNECTED, pc,
                "Connected from " + pc.getAddress().getHostAddress() + " (user " + pc.getUser() + ")");
        firePcUpdated(pc);
    }

    void onDisconnect(PcInfo pc, ClientHandler handler, boolean graceful) {
        if (!pc.disconnect(handler)) {
            return;
        }
        if (graceful) {
            raise(Alert.Severity.INFO, Alert.Type.DISCONNECTED, pc, "Agent closed by the user");
        } else if (running) {
            raise(Alert.Severity.WARNING, Alert.Type.DISCONNECTED, pc,
                    "Connection lost (PC shut down, agent killed or network cable unplugged)");
        }
        firePcUpdated(pc);
    }

    /** Called by the HeartbeatListener for every valid heartbeat datagram. */
    void onHeartbeat(String pcId, InetAddress from, long seq, double cpu, double memory, double disk, long uptime) {
        PcInfo pc = pcs.get(pcId);
        // Only accept heartbeats for a registered PC, coming from the address it registered with.
        if (pc == null || !pc.isConnected() || !from.equals(pc.getAddress())) {
            return;
        }
        if (!pc.updateHeartbeat(seq, cpu, memory, disk, uptime, config.cpuThreshold)) {
            return; // Ignore duplicate, delayed or invalid sequence numbers.
        }

        if (pc.setCondition(Alert.Type.HEARTBEAT_LOST, false)) {
            raise(Alert.Severity.INFO, Alert.Type.HEARTBEAT_LOST, pc, "Heartbeat restored, PC is responding again");
        }

        // Hysteresis and several recovery samples prevent repeated warning/clear cycles.
        updateMetricAlert(pc, Alert.Type.HIGH_CPU, cpu, config.cpuThreshold,
                config.cpuRecoveryThreshold, config.cpuConsecutive,
                String.format(Locale.US, "CPU usage is %.0f%% (limit %.0f%%)", cpu, config.cpuThreshold),
                "CPU usage is back to normal");
        updateMetricAlert(pc, Alert.Type.HIGH_MEMORY, memory, config.memoryThreshold,
                config.memoryRecoveryThreshold, 1,
                String.format(Locale.US, "Memory usage is %.0f%% (limit %.0f%%)", memory, config.memoryThreshold),
                "Memory usage is back to normal");
        updateMetricAlert(pc, Alert.Type.DISK_FULL, disk, config.diskThreshold,
                config.diskRecoveryThreshold, 1,
                String.format(Locale.US, "Disk is %.0f%% full (limit %.0f%%)", disk, config.diskThreshold),
                "Disk usage is back to normal");

        firePcUpdated(pc);
    }

    void onProcessList(PcInfo pc, List<String> processes) {
        pc.setProcesses(processes);

        List<String> blocked = new ArrayList<String>();
        for (String name : processes) {
            if (config.isBlocked(name)) {
                blocked.add(name);
            }
        }
        List<String> fresh = pc.updateBlocked(blocked);
        boolean changed = pc.setCondition(Alert.Type.BLOCKED_APP, !blocked.isEmpty());
        if (!fresh.isEmpty()) {
            raise(Alert.Severity.CRITICAL, Alert.Type.BLOCKED_APP, pc, "Blocked application running: " + join(fresh));
            // Warn the student as well as the instructor.
            pc.send(Protocol.build(Protocol.ALERT, Alert.Severity.CRITICAL,
                    "Blocked application detected: " + join(fresh) + ". Please close it now."));
        } else if (changed && blocked.isEmpty()) {
            raise(Alert.Severity.INFO, Alert.Type.BLOCKED_APP, pc, "Blocked application was closed");
        }

        for (ServerListener l : listeners) {
            l.processListReceived(pc);
        }
        firePcUpdated(pc);
    }

    void onHelpRequest(PcInfo pc, String text) {
        raise(Alert.Severity.WARNING, Alert.Type.HELP_REQUEST, pc,
                pc.getUser() + " asks for help: " + (text.trim().isEmpty() ? "(no message)" : text.trim()));
    }

    /** Called by the Watchdog thread once per second. */
    void checkHeartbeats() {
        long now = System.currentTimeMillis();
        long timeoutMs = config.heartbeatTimeoutSec * 1000L;
        for (PcInfo pc : pcs.values()) {
            if (pc.isConnected() && now - pc.getLastHeartbeat() > timeoutMs
                    && pc.setCondition(Alert.Type.HEARTBEAT_LOST, true)) {
                raise(Alert.Severity.CRITICAL, Alert.Type.HEARTBEAT_LOST, pc, "No heartbeat for "
                        + config.heartbeatTimeoutSec + " s (PC frozen or network problem)");
                firePcUpdated(pc);
            }
        }
    }

    // ------------------------------------------------------------------
    // Actions started by the instructor
    // ------------------------------------------------------------------

    public boolean sendMessage(String pcId, String text) {
        PcInfo pc = pcs.get(pcId);
        return pc != null && pc.send(Protocol.build(Protocol.MSG, "Instructor", text));
    }

    /** Sends a message to every connected PC and returns how many received it. */
    public int broadcast(String text) {
        int count = 0;
        String line = Protocol.build(Protocol.MSG, "Instructor (to all)", text);
        for (PcInfo pc : pcs.values()) {
            if (pc.send(line)) {
                count++;
            }
        }
        return count;
    }

    /** Asks a PC to report its running processes immediately. */
    public boolean requestProcesses(String pcId) {
        PcInfo pc = pcs.get(pcId);
        return pc != null && pc.send(Protocol.GETPROCS);
    }

    // ------------------------------------------------------------------

    /** Only emit a resource alert when its stabilized state actually changes. */
    private void updateMetricAlert(PcInfo pc, Alert.Type type, double value,
            double threshold, double recoveryThreshold, int warningConsecutive,
            String warningMessage, String recoveryMessage) {
        int transition = pc.updateMetricCondition(type, value, threshold, recoveryThreshold,
                warningConsecutive, config.recoveryConsecutive);
        if (transition == 1) {
            raise(Alert.Severity.WARNING, type, pc, warningMessage);
            pc.send(Protocol.build(Protocol.ALERT, Alert.Severity.WARNING, warningMessage));
        } else if (transition == -1) {
            raise(Alert.Severity.INFO, type, pc, recoveryMessage);
        }
    }

    private void raise(Alert.Severity severity, Alert.Type type, PcInfo pc, String message) {
        Alert alert = new Alert(severity, type, pc.getPcId(), message);
        alertManager.record(alert);
        for (ServerListener l : listeners) {
            l.alertRaised(alert);
        }
    }

    private void firePcUpdated(PcInfo pc) {
        for (ServerListener l : listeners) {
            l.pcUpdated(pc);
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) {
        String configPath = "config/server.properties";
        boolean gui = true;
        Integer tcp = null;
        Integer udp = null;
        try {
            for (int i = 0; i < args.length; i++) {
                if ("--nogui".equals(args[i])) {
                    gui = false;
                } else if ("--config".equals(args[i])) {
                    configPath = args[++i];
                } else if ("--tcp".equals(args[i])) {
                    tcp = Integer.valueOf(args[++i]);
                } else if ("--udp".equals(args[i])) {
                    udp = Integer.valueOf(args[++i]);
                } else {
                    throw new IllegalArgumentException(args[i]);
                }
            }
            if ((tcp != null && (tcp < 1 || tcp > 65535))
                    || (udp != null && (udp < 1 || udp > 65535))) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
        } catch (RuntimeException e) {
            System.err.println("Usage: LabServer [--nogui] [--config file] [--tcp port] [--udp port]");
            System.exit(2);
        }

        ServerConfig config = ServerConfig.load(configPath);
        if (tcp != null) {
            config.tcpPort = tcp;
        }
        if (udp != null) {
            config.udpPort = udp;
        }

        LabServer server = new LabServer(config);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Could not start the server on TCP " + config.tcpPort + " / UDP " + config.udpPort
                    + ": " + e.getMessage());
            System.exit(1);
        }
        System.out.println("Lab monitoring server started. TCP port " + config.tcpPort + ", UDP port " + config.udpPort);

        if (gui) {
            ServerDashboard.open(server);
        } else {
            new ServerConsole(server).run();
        }
    }
}
