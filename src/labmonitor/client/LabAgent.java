package labmonitor.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import labmonitor.common.Protocol;

/**
 * The agent that runs on every lab PC.
 *
 * Threads (per connection):
 *   main / reader  keeps the TCP connection and handles messages from the server
 *   heartbeat      sends a UDP datagram with CPU, memory and disk usage every few seconds
 *   processes      reports the list of running processes over TCP
 *
 * If the server cannot be reached, or the connection drops, the agent retries automatically.
 */
public class LabAgent {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int RECONNECT_DELAY_MS = 5000;

    private final String host;
    private final int port;
    private final String pcId;
    private final String user;
    private final SystemMetrics metrics;

    private volatile AgentListener listener;
    private volatile boolean running = true;
    private volatile Socket socket;
    private PrintWriter out;
    private final Object sendLock = new Object();

    public LabAgent(String host, int port, String pcId, String user, SystemMetrics metrics) {
        this.host = host;
        this.port = port;
        this.pcId = Protocol.clean(pcId).trim();
        if (this.pcId.isEmpty()) {
            throw new IllegalArgumentException("PC id must not be empty");
        }
        this.user = user;
        this.metrics = metrics;
    }

    public void setListener(AgentListener listener) {
        this.listener = listener;
    }

    public String getPcId() {
        return pcId;
    }

    public String getUser() {
        return user;
    }

    public String getServer() {
        return host + ":" + port;
    }

    /** Connects to the server and stays connected. Returns only after stop(). */
    public void run() {
        while (running) {
            try {
                session();
            } catch (IOException e) {
                if (running) {
                    listener.statusChanged(false, "Not connected: " + e.getMessage() + ". Retrying in "
                            + RECONNECT_DELAY_MS / 1000 + " s");
                }
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One connection to the server, from REGISTER until the connection ends. */
    private void session() throws IOException {
        listener.statusChanged(false, "Connecting to " + host + ":" + port + " ...");
        final InetAddress serverAddress = InetAddress.getByName(host);

        final Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(serverAddress, port), CONNECT_TIMEOUT_MS);
            socket = s;
            s.setSoTimeout(CONNECT_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            synchronized (sendLock) {
                out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            }

            send(Protocol.build(Protocol.REGISTER, pcId, InetAddress.getLocalHost().getHostName(), user,
                    System.getProperty("os.name")));

            String reply = in.readLine();
            if (reply == null) {
                throw new IOException("server closed the connection");
            }
            String[] f = Protocol.parse(reply);
            if (Protocol.ERROR.equals(f[0])) {
                throw new IOException("server refused the registration (" + (f.length > 1 ? f[1] : "") + ")");
            }
            if (!Protocol.WELCOME.equals(f[0]) || f.length < 4) {
                throw new IOException("unexpected reply from server: " + reply);
            }
            final int udpPort;
            final int heartbeatSec;
            final int processSec;
            try {
                udpPort = Integer.parseInt(f[1]);
                if (udpPort < 1 || udpPort > 65535) {
                    throw new NumberFormatException("invalid UDP port");
                }
                heartbeatSec = Math.max(1, Integer.parseInt(f[2]));
                processSec = Math.max(1, Integer.parseInt(f[3]));
            } catch (NumberFormatException e) {
                throw new IOException("unexpected reply from server: " + reply);
            }
            s.setSoTimeout(0);
            listener.statusChanged(true, "Connected to " + host + ":" + port + " as " + pcId);

            Thread heartbeat = new Thread(new Runnable() {
                @Override
                public void run() {
                    heartbeatLoop(s, serverAddress, udpPort, heartbeatSec);
                }
            }, "heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            Thread processes = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running && !s.isClosed()) {
                        sendProcessList();
                        if (!sleep(processSec * 1000L)) {
                            return;
                        }
                    }
                }
            }, "processes");
            processes.setDaemon(true);
            processes.start();

            String line;
            while ((line = in.readLine()) != null) {
                handle(Protocol.parse(line));
            }
            throw new IOException("server closed the connection");
        } finally {
            synchronized (sendLock) {
                out = null;
            }
            s.close(); // also stops the heartbeat and process threads of this session
        }
    }

    private void handle(String[] m) {
        if (Protocol.MSG.equals(m[0]) && m.length >= 3) {
            listener.messageReceived(m[1], m[2]);
        } else if (Protocol.ALERT.equals(m[0]) && m.length >= 3) {
            listener.alertReceived(m[1], m[2]);
        } else if (Protocol.GETPROCS.equals(m[0])) {
            sendProcessList();
        }
    }

    /** Sends the heartbeat datagrams. UDP is used because a lost heartbeat does not need resending. */
    private void heartbeatLoop(Socket session, InetAddress serverAddress, int udpPort, int intervalSec) {
        long seq = 0;
        try (DatagramSocket udp = new DatagramSocket()) {
            while (running && !session.isClosed()) {
                double cpu = metrics.cpu();
                double memory = metrics.memory();
                double disk = metrics.disk();
                String text = Protocol.build(Protocol.HEARTBEAT, pcId, seq++, number(cpu), number(memory),
                        number(disk), metrics.uptimeSec());
                byte[] buf = text.getBytes(StandardCharsets.UTF_8);
                udp.send(new DatagramPacket(buf, buf.length, serverAddress, udpPort));
                listener.metricsSent(cpu, memory, disk);
                if (!sleep(intervalSec * 1000L)) {
                    return;
                }
            }
        } catch (IOException e) {
            if (running && !session.isClosed()) {
                listener.statusChanged(true, "Heartbeat error: " + e.getMessage());
            }
        }
    }

    private void sendProcessList() {
        StringBuilder sb = new StringBuilder();
        for (String name : metrics.processes()) {
            if (sb.length() > 0) {
                sb.append(Protocol.LIST_SEPARATOR);
            }
            sb.append(name);
        }
        send(Protocol.build(Protocol.PROCS, sb));
    }

    /** Sends a help request to the instructor. Returns false when not connected. */
    public boolean requestHelp(String text) {
        return send(Protocol.build(Protocol.HELP, text));
    }

    private boolean send(String line) {
        synchronized (sendLock) {
            if (out == null) {
                return false;
            }
            out.println(line);
            return !out.checkError();
        }
    }

    public void stop() {
        running = false;
        send(Protocol.BYE);
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static String number(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) {
        String host = "localhost";
        int port = Protocol.DEFAULT_TCP_PORT;
        String pcId = null;
        String user = System.getProperty("user.name");
        boolean gui = true;
        boolean serverGiven = false;
        boolean simulate = false;
        double simCpu = -1;
        List<String> fakeProcesses = new ArrayList<String>();
        try {
            for (int i = 0; i < args.length; i++) {
                if ("--server".equals(args[i])) {
                    host = args[++i];
                    serverGiven = true;
                } else if ("--port".equals(args[i])) {
                    port = Integer.parseInt(args[++i]);
                } else if ("--id".equals(args[i])) {
                    pcId = args[++i];
                } else if ("--user".equals(args[i])) {
                    user = args[++i];
                } else if ("--nogui".equals(args[i])) {
                    gui = false;
                } else if ("--simulate".equals(args[i])) {
                    simulate = true;
                } else if ("--sim-cpu".equals(args[i])) {
                    simulate = true;
                    simCpu = Double.parseDouble(args[++i]);
                } else if ("--fake-proc".equals(args[i])) {
                    fakeProcesses.add(args[++i]);
                } else {
                    throw new IllegalArgumentException(args[i]);
                }
            }
            if (port < 1 || port > 65535 || (pcId != null && Protocol.clean(pcId).trim().isEmpty())
                    || Double.isNaN(simCpu) || Double.isInfinite(simCpu) || simCpu > 100) {
                throw new IllegalArgumentException("Invalid port, PC id or simulated CPU percentage");
            }
        } catch (RuntimeException e) {
            System.err.println("Usage: LabAgent [--server host] [--port n] [--id PC-01] [--user name] [--nogui]\n"
                    + "                [--simulate] [--sim-cpu percent] [--fake-proc name.exe]");
            System.exit(2);
        }
        if (pcId == null) {
            try {
                pcId = InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                pcId = "PC-" + System.currentTimeMillis() % 10000;
            }
        }

        // Started by double-click, without --server: ask where the server is.
        if (gui && !serverGiven) {
            String[] answer = AgentWindow.askConnection(host, pcId);
            if (answer == null) {
                return; // cancelled
            }
            host = answer[0];
            pcId = answer[1];
        }

        LabAgent agent = new LabAgent(host, port, pcId, user, new SystemMetrics(simulate, simCpu, fakeProcesses));
        if (gui) {
            AgentWindow.open(agent, simulate); // returns once the window is listening
        } else {
            agent.setListener(new ConsoleListener());
        }
        agent.run();
    }

    /** Text output for --nogui mode. */
    private static class ConsoleListener implements AgentListener {
        @Override
        public void statusChanged(boolean connected, String text) {
            System.out.println("[STATUS] " + text);
        }

        @Override
        public void metricsSent(double cpu, double memory, double disk) {
            System.out.println(String.format(Locale.US, "[HEARTBEAT] cpu=%.0f%% memory=%.0f%% disk=%.0f%%", cpu,
                    memory, disk));
        }

        @Override
        public void messageReceived(String from, String text) {
            System.out.println("[MESSAGE] " + from + ": " + text);
        }

        @Override
        public void alertReceived(String severity, String text) {
            System.out.println("[ALERT] " + severity + ": " + text);
        }
    }
}
