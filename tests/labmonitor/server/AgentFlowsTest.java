package labmonitor.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import labmonitor.client.*;

/** Exercises the real agent, including invalid and silent registration servers. */
public class AgentFlowsTest {
    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        try (ServerSocket tcp = new ServerSocket(0); DatagramSocket udp = new DatagramSocket(0)) {
            config.tcpPort = tcp.getLocalPort();
            config.udpPort = udp.getLocalPort();
        }
        config.heartbeatIntervalSec = 1;
        config.processReportSec = 1;
        config.logFile = Files.createTempDirectory("lab-agent-qa-").resolve("alerts.csv").toString();
        LabServer server = new LabServer(config);
        LabAgent agent = new LabAgent("127.0.0.1", config.tcpPort, "  QA|Agent  ", "student",
                new SystemMetrics(true, 96, Collections.singletonList("vlc.exe")));
        Events events = new Events();
        agent.setListener(events);
        Thread thread = run(agent);
        try {
            server.start();
            thread.start();
            await(() -> server.getPc("QA/Agent") != null && server.getPc("QA/Agent").getReceived() >= 3,
                    8000, "heartbeats with normalized PC id");
            await(() -> events.alerts.get() >= 2, 3000, "blocked app and sustained CPU alerts delivered");
            require(server.sendMessage("QA/Agent", "Direct message"), "direct send");
            require(server.broadcast("Broadcast message") == 1, "broadcast count");
            await(() -> events.messages.get() == 2, 3000, "real agent received direct and broadcast messages");
            require(agent.requestHelp("Network issue"), "real help request");
            await(() -> server.getAlertHistory().stream().anyMatch(a -> a.type == Alert.Type.HELP_REQUEST
                    && a.message.contains("Network issue")), 3000, "real help delivered");
            AtomicInteger reports = new AtomicInteger();
            server.addListener(new ServerListener() {
                public void pcUpdated(PcInfo pc) { }
                public void alertRaised(Alert alert) { }
                public void processListReceived(PcInfo pc) { reports.incrementAndGet(); }
            });
            require(server.requestProcesses("QA/Agent"), "GETPROCS request");
            await(() -> reports.get() > 0, 3000, "real process response");
            agent.stop();
            await(() -> !server.getPc("QA/Agent").isConnected(), 3000, "real agent graceful shutdown");
        } finally {
            agent.stop();
            server.stop();
            thread.join(6000);
        }
        failedRegistration("WELCOME|70000|1|1", 3000);
        failedRegistration(null, 7000);
        SystemMetrics real = new SystemMetrics(false, -1, Collections.<String>emptyList());
        for (double value : new double[] {real.cpu(), real.memory(), real.disk()}) {
            require(!Double.isNaN(value) && value >= 0 && value <= 100, "real metric range");
        }
        require(!real.processes().isEmpty(), "real process enumeration");
        System.out.println("Agent flows passed (messaging, help, processes, alerts, identity, registration failures, real metrics)");
    }

    private static void failedRegistration(String reply, long timeout) throws Exception {
        try (ServerSocket fake = new ServerSocket(0)) {
            fake.setSoTimeout(3000);
            LabAgent agent = new LabAgent("127.0.0.1", fake.getLocalPort(), "QA", "student",
                    new SystemMetrics(true, 20, Collections.<String>emptyList()));
            Events events = new Events();
            agent.setListener(events);
            Thread thread = run(agent);
            thread.start();
            try (Socket peer = fake.accept()) {
                peer.setSoTimeout(3000);
                BufferedReader input = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                require(input.readLine().startsWith("REGISTER|"), "agent registers first");
                if (reply != null) {
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true);
                    output.println(reply);
                }
                await(() -> events.failures.get() > 0, timeout, "bad/silent registration enters reconnect path");
            } finally {
                agent.stop();
                thread.join(6000);
                require(!thread.isAlive(), "agent exits after stop");
            }
        }
    }
    private static Thread run(LabAgent agent) {
        Thread thread = new Thread(agent::run, "qa-real-agent");
        thread.setDaemon(true);
        return thread;
    }
    private static void await(BooleanSupplier check, long timeout, String label) throws Exception {
        long deadline = System.nanoTime() + timeout * 1000000L;
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + label);
    }
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static class Events implements AgentListener {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger messages = new AtomicInteger();
        final AtomicInteger alerts = new AtomicInteger();
        public void statusChanged(boolean connected, String text) {
            if (!connected && text.startsWith("Not connected:")) failures.incrementAndGet();
        }
        public void metricsSent(double cpu, double memory, double disk) { }
        public void messageReceived(String from, String text) { messages.incrementAndGet(); }
        public void alertReceived(String severity, String text) { alerts.incrementAndGet(); }
    }
}
