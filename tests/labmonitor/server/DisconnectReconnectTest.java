package labmonitor.server;

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import labmonitor.client.AgentListener;
import labmonitor.client.LabAgent;
import labmonitor.client.SystemMetrics;

/**
 * End-to-end localhost test: the agent must go offline when the server stops,
 * then automatically register and send fresh heartbeats after a server restart.
 * No second PC, Swing window or external test library is needed.
 */
public class DisconnectReconnectTest {

    private static final String PC_ID = "PC-RECONNECT-TEST";

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        config.tcpPort = unusedTcpPort();
        config.udpPort = unusedUdpPort();
        config.heartbeatIntervalSec = 1;
        config.heartbeatTimeoutSec = 4;
        config.processReportSec = 2;
        Path logDir = Files.createTempDirectory("lab-reconnect-test-");
        config.logFile = logDir.resolve("alerts.csv").toString();

        final AtomicInteger connectedEvents = new AtomicInteger();
        final AtomicInteger droppedEvents = new AtomicInteger();
        final LabAgent agent = new LabAgent("127.0.0.1", config.tcpPort, PC_ID, "test-student",
                new SystemMetrics(true, 20, Collections.<String>emptyList()));
        agent.setListener(new AgentListener() {
            @Override
            public void statusChanged(boolean connected, String text) {
                if (connected) {
                    connectedEvents.incrementAndGet();
                } else if (text.startsWith("Not connected:")) {
                    droppedEvents.incrementAndGet();
                }
            }
            @Override public void metricsSent(double cpu, double memory, double disk) { }
            @Override public void messageReceived(String from, String text) { }
            @Override public void alertReceived(String severity, String text) { }
        });

        Thread agentThread = new Thread(new Runnable() {
            @Override
            public void run() {
                agent.run();
            }
        }, "reconnect-test-agent");
        agentThread.setDaemon(true);

        LabServer firstServer = new LabServer(config);
        LabServer restartedServer = null;
        try {
            firstServer.start();
            agentThread.start();

            final LabServer before = firstServer;
            await(new BooleanSupplier() {
                @Override public boolean getAsBoolean() { return hasHeartbeat(before); }
            }, 8000, "initial registration and heartbeat");
            require(firstServer.getPc(PC_ID).getStatus() == PcInfo.Status.ONLINE,
                    "PC should initially be ONLINE");

            // Simulate the server going away without closing the client agent.
            firstServer.stop();
            await(new BooleanSupplier() {
                @Override public boolean getAsBoolean() {
                    PcInfo pc = before.getPc(PC_ID);
                    return pc != null && pc.getStatus() == PcInfo.Status.OFFLINE;
                }
            }, 4000, "old server reporting OFFLINE");
            await(new BooleanSupplier() {
                @Override public boolean getAsBoolean() { return droppedEvents.get() >= 1; }
            }, 4000, "agent noticing a lost connection");

            restartedServer = new LabServer(config);
            restartedServer.start();
            final LabServer after = restartedServer;
            // The agent retries every five seconds; allow enough time for that and a heartbeat.
            await(new BooleanSupplier() {
                @Override public boolean getAsBoolean() { return hasHeartbeat(after); }
            }, 15000, "agent reconnecting and sending a fresh heartbeat");
            require(restartedServer.getPc(PC_ID).getStatus() == PcInfo.Status.ONLINE,
                    "PC should be ONLINE again");
            require(restartedServer.getPc(PC_ID).getReceived() >= 1,
                    "reconnected session must receive a heartbeat");
            require(connectedEvents.get() >= 2, "expected two successful connections");

            System.out.println("Disconnect/reconnect integration test passed");
        } finally {
            agent.stop();
            if (restartedServer != null) {
                restartedServer.stop();
            }
            firstServer.stop();
            agentThread.join(6000);
        }
    }

    private static boolean hasHeartbeat(LabServer server) {
        PcInfo pc = server.getPc(PC_ID);
        return pc != null && pc.isConnected() && pc.getReceived() > 0;
    }

    private static int unusedTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int unusedUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, long timeoutMs, String step) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + step);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
