package labmonitor.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import labmonitor.common.Protocol;
import labmonitor.client.LabAgent;
import labmonitor.client.SystemMetrics;

/** Real TCP/UDP checks without external libraries or physical lab PCs. */
public class NetworkFlowsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        try (ServerSocket tcp = new ServerSocket(0); DatagramSocket udp = new DatagramSocket(0)) {
            config.tcpPort = tcp.getLocalPort();
            config.udpPort = udp.getLocalPort();
        }
        config.heartbeatTimeoutSec = 1;
        Path log = Files.createTempDirectory("lab-network-qa-").resolve("alerts.csv");
        config.logFile = log.toString();
        LabServer server = new LabServer(config);
        try {
            server.start();
            try (Peer invalid = new Peer(config.tcpPort)) {
                invalid.send("HELP|not registered");
                require(invalid.read().startsWith("ERROR|"), "registration required");
            }
            try (Peer first = new Peer(config.tcpPort); Peer second = new Peer(config.tcpPort);
                    DatagramSocket udp = new DatagramSocket()) {
                first.register("QA-01");
                second.register("QA-02");
                await(() -> count(server, Alert.Type.CONNECTED, Alert.Severity.INFO) == 2, "two registrations");
                try (Peer duplicate = new Peer(config.tcpPort)) {
                    duplicate.send("REGISTER|QA-01|host|student|test");
                    require(duplicate.read().startsWith("ERROR|"), "duplicate id refused");
                }
                require(server.sendMessage("QA-01", "Hello | student\nnext"), "direct message sent");
                require(first.read().equals("MSG|Instructor|Hello / student next"), "message framing preserved");
                require(server.broadcast("Everyone") == 2, "broadcast recipient count");
                require(first.read().endsWith("|Everyone") && second.read().endsWith("|Everyone"), "broadcast delivery");
                require(!server.sendMessage("missing", "hello"), "unknown recipient fails");
                first.send("HELP|Cannot log in, \"please help\"");
                await(() -> count(server, Alert.Type.HELP_REQUEST, Alert.Severity.WARNING) == 1, "help request");
                require(server.requestProcesses("QA-01"), "process request sent");
                require(first.read().equals("GETPROCS"), "process request delivered");
                first.send("PROCS|chrome.exe;VLC.EXE");
                require(first.read().startsWith("ALERT|CRITICAL|Blocked application"), "student blocked-app warning");
                await(() -> server.getPc("QA-01").getProcesses().size() == 2, "process list stored");
                first.send("PROCS|chrome.exe;VLC.EXE");
                first.send("PROCS|chrome.exe");
                await(() -> count(server, Alert.Type.BLOCKED_APP, Alert.Severity.INFO) == 1, "blocked-app recovery");
                require(count(server, Alert.Type.BLOCKED_APP, Alert.Severity.CRITICAL) == 1, "blocked warning deduplicated");

                heartbeat(udp, config, "HB|QA-01|0|20|30|40|1");
                await(() -> server.getPc("QA-01").getReceived() == 1, "UDP heartbeat");
                heartbeat(udp, config, "garbage");
                heartbeat(udp, config, "HB|QA-01|1|NaN|30|40|1");
                heartbeat(udp, config, "HB|QA-01|1|Infinity|30|40|1");
                heartbeat(udp, config, "HB|QA-01|1|-1|30|40|1");
                heartbeat(udp, config, "HB|QA-01|1|101|30|40|1");
                heartbeat(udp, config, "HB|QA-01|1|20|30|40|-1");
                heartbeat(udp, config, "HB|unknown|1|20|30|40|1");
                heartbeat(udp, config, "HB|QA-01|2|25|35|45|2");
                await(() -> server.getPc("QA-01").getReceived() >= 2, "valid packet after malformed packets");
                require(server.getPc("QA-01").getReceived() == 2, "invalid telemetry ignored");
                require(server.getPc("QA-01").getLost() == 1, "packet gap counted");
                heartbeat(udp, config, "HB|QA-01|1|99|99|99|3");
                heartbeat(udp, config, "HB|QA-01|3|95|95|95|3");
                require(first.read().startsWith("ALERT|WARNING|Memory"), "memory warning delivered");
                require(first.read().startsWith("ALERT|WARNING|Disk"), "disk warning delivered");
                heartbeat(udp, config, "HB|QA-01|4|95|95|95|4");
                heartbeat(udp, config, "HB|QA-01|5|95|95|95|5");
                require(first.read().startsWith("ALERT|WARNING|CPU"), "sustained CPU warning delivered");
                require(PcHealth.evaluate(server.getPc("QA-01"), config) == PcHealth.Level.CRITICAL, "critical health");
                for (int i = 6; i <= 8; i++) heartbeat(udp, config, "HB|QA-01|" + i + "|10|20|30|8");
                await(() -> server.getPc("QA-01").getProblems().isEmpty(), "all resources recovered");
                require(PcHealth.evaluate(server.getPc("QA-01"), config) == PcHealth.Level.HEALTHY, "healthy status");
                await(() -> server.getPc("QA-01").getStatus() == PcInfo.Status.NOT_RESPONDING, "heartbeat timeout");
                heartbeat(udp, config, "HB|QA-01|9|10|20|30|9");
                await(() -> server.getPc("QA-01").getStatus() == PcInfo.Status.ONLINE, "heartbeat restored");
                first.send("BYE");
                await(() -> !server.getPc("QA-01").isConnected(), "graceful disconnect");
                require(!server.requestProcesses("QA-01"), "offline process request fails");
                require(PcHealth.evaluate(server.getPc("QA-01"), config) == PcHealth.Level.OFFLINE, "offline health");
                try (Peer reconnect = new Peer(config.tcpPort)) {
                    reconnect.register("QA-01");
                    require(server.getPc("QA-01").getReceived() == 0, "reconnect resets counters");
                    require(PcHealth.evaluate(server.getPc("QA-01"), config) == PcHealth.Level.WAITING, "waiting health");
                }
                await(() -> count(server, Alert.Type.DISCONNECTED, Alert.Severity.WARNING) >= 1, "abrupt disconnect");
            }
            // An accepted socket awaiting REGISTER must also close when the server stops.
            try (Peer pending = new Peer(config.tcpPort)) {
                pending.output.print("REGISTER|"); // Incomplete line: blocks the handler until shutdown.
                pending.output.flush();
                Thread.sleep(100);
                server.stop();
                require(pending.read() == null, "shutdown closes unregistered connections");
            }
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            require(lines.get(0).equals("time,severity,type,pc,message"), "CSV header");
            require(lines.stream().anyMatch(s -> s.contains("Cannot log in, \"\"please help\"\"")), "CSV quote escaping");
            LabAgent id = new LabAgent("localhost", 5000, "  QA|PC  ", "student",
                    new SystemMetrics(true, 20, Collections.<String>emptyList()));
            require(id.getPcId().equals("QA/PC"), "PC id matches its wire representation");
            new LabServer(config).stop();
            require(true, "stop before start");
            System.out.println("Network flows passed (" + checks + " checks)");
        } finally {
            server.stop();
        }
    }

    private static void heartbeat(DatagramSocket socket, ServerConfig config, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), config.udpPort));
    }

    private static long count(LabServer server, Alert.Type type, Alert.Severity severity) {
        return server.getAlertHistory().stream().filter(a -> a.type == type && a.severity == severity).count();
    }

    private static void await(BooleanSupplier condition, String step) throws Exception {
        long deadline = System.nanoTime() + 6000000000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) { checks++; return; }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + step);
    }

    private static void require(boolean value, String step) {
        if (!value) throw new AssertionError(step);
        checks++;
    }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final BufferedReader input;
        final PrintWriter output;
        Peer(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(3000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }
        void send(String text) { output.println(text); }
        String read() throws Exception { return input.readLine(); }
        void register(String id) throws Exception {
            send(Protocol.build(Protocol.REGISTER, id, "host", "student", "test"));
            require(read().startsWith("WELCOME|"), "WELCOME must be first");
        }
        public void close() throws IOException { socket.close(); }
    }
}
