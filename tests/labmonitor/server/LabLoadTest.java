package labmonitor.server;

import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import labmonitor.client.*;

/** Optional one-minute lab load check: 40 real agents, no Swing or external dependencies. */
public class LabLoadTest {
    public static void main(String[] args) throws Exception {
        final int size = 40;
        ServerConfig config = new ServerConfig();
        try (ServerSocket tcp = new ServerSocket(0); DatagramSocket udp = new DatagramSocket(0)) {
            config.tcpPort = tcp.getLocalPort();
            config.udpPort = udp.getLocalPort();
        }
        config.heartbeatIntervalSec = 1;
        config.processReportSec = 2;
        config.logFile = Files.createTempDirectory("lab-load-qa-").resolve("alerts.csv").toString();
        LabServer server = new LabServer(config);
        List<LabAgent> agents = new ArrayList<LabAgent>();
        List<Thread> threads = new ArrayList<Thread>();
        AtomicInteger messages = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            server.start();
            for (int i = 0; i < size; i++) {
                LabAgent agent = new LabAgent("127.0.0.1", config.tcpPort, "LOAD-" + i, "student",
                        new SystemMetrics(true, 20, Collections.<String>emptyList()));
                agent.setListener(new AgentListener() {
                    public void statusChanged(boolean connected, String text) { if (connected) connects.incrementAndGet(); }
                    public void metricsSent(double cpu, double memory, double disk) { }
                    public void messageReceived(String from, String text) { messages.incrementAndGet(); }
                    public void alertReceived(String severity, String text) { }
                });
                Thread thread = new Thread(agent::run, "qa-load-" + i);
                thread.setUncaughtExceptionHandler((t, e) -> { e.printStackTrace(); errors.incrementAndGet(); });
                thread.setDaemon(true);
                agents.add(agent);
                threads.add(thread);
                thread.start();
            }
            await(() -> server.getPcs().size() == size && server.getPcs().stream().allMatch(p -> p.getReceived() > 0),
                    10000, "40 registrations and first heartbeats");
            for (int i = 0; i < 10; i++) require(server.broadcast("Round " + i) == size, "broadcast count");
            await(() -> messages.get() == size * 10, 5000, "400 message deliveries");
            for (LabAgent agent : agents) {
                require(agent.requestHelp("Load test"), "help sent");
                require(server.requestProcesses(agent.getPcId()), "process request sent");
            }
            await(() -> server.getAlertHistory().stream().filter(a -> a.type == Alert.Type.HELP_REQUEST).count() == size,
                    5000, "40 help requests");
            for (int i = 0; i < 10; i++) server.getPc("LOAD-" + i).closeConnection();
            await(() -> connects.get() >= 50 && server.getPcs().stream().allMatch(p -> p.isConnected() && p.getReceived() > 0),
                    15000, "10 concurrent reconnects");
            System.out.println("Load phase: 40 agents online, 400 messages, 40 help requests, 10 reconnects passed");
            long deadline = System.nanoTime() + 60000000000L;
            while (System.nanoTime() < deadline) {
                require(server.getPcs().stream().allMatch(p -> p.isConnected()
                        && p.getStatus() != PcInfo.Status.NOT_RESPONDING), "agents remain responsive");
                require(errors.get() == 0, "no agent thread failures");
                Thread.sleep(1000);
            }
            require(server.getPcs().stream().allMatch(p -> p.getReceived() >= 50), "sustained heartbeat delivery");
            for (LabAgent agent : agents) agent.stop();
            await(() -> server.getPcs().stream().noneMatch(PcInfo::isConnected), 5000, "all 40 agents offline");
            System.out.println("Load tests passed: 40 agents, 60-second soak, >=50 heartbeats per PC");
        } finally {
            for (LabAgent agent : agents) agent.stop();
            server.stop();
            for (Thread thread : threads) thread.join(1000);
        }
    }
    private static void await(BooleanSupplier check, long timeout, String label) throws Exception {
        long end = System.nanoTime() + timeout * 1000000L;
        while (System.nanoTime() < end) {
            if (check.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out: " + label);
    }
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
