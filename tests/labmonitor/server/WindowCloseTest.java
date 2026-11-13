package labmonitor.server;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import labmonitor.client.*;

/** Separate JVMs exercise the production window-close handlers, which call System.exit. */
public class WindowCloseTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) { child(args); return; }
        ServerConfig config = config();
        LabServer server = new LabServer(config);
        Process agent = null;
        Process instructor = null;
        try {
            server.start();
            agent = launch("agent", config.tcpPort, config.udpPort);
            require(agent.waitFor(15, TimeUnit.SECONDS) && agent.exitValue() == 0, "agent close exits successfully");
            long end = System.nanoTime() + 3000000000L;
            while (System.nanoTime() < end && server.getAlertHistory().stream().noneMatch(a -> a.type == Alert.Type.DISCONNECTED))
                Thread.sleep(30);
            require(server.getAlertHistory().stream().anyMatch(a -> a.type == Alert.Type.CONNECTED), "agent connected before closing");
            require(server.getAlertHistory().stream().anyMatch(a -> a.type == Alert.Type.DISCONNECTED && a.severity == Alert.Severity.INFO),
                    "agent window sends graceful BYE");
            server.stop();

            ServerConfig other = config();
            instructor = launch("server", other.tcpPort, other.udpPort);
            try (Socket socket = connect(other.tcpPort)) {
                socket.setSoTimeout(10000);
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out.println("REGISTER|CLOSE-QA|host|student|test");
                require(input.readLine().startsWith("WELCOME|"), "instructor accepts client before closing");
                require(input.readLine() == null, "instructor close shuts down client connection");
            }
            require(instructor.waitFor(5, TimeUnit.SECONDS) && instructor.exitValue() == 0, "instructor close exits successfully");
            System.out.println("Window close tests passed: agent graceful disconnect and instructor socket/process shutdown");
        } finally {
            server.stop();
            if (agent != null && agent.isAlive()) agent.destroyForcibly().waitFor();
            if (instructor != null && instructor.isAlive()) instructor.destroyForcibly().waitFor();
        }
    }
    private static void child(String[] args) throws Exception {
        if ("agent".equals(args[0])) {
            LabAgent agent = new LabAgent("127.0.0.1", Integer.parseInt(args[1]), "WINDOW-CLOSE-QA", "student",
                    new SystemMetrics(true, 20, Collections.<String>emptyList()));
            AgentWindow.open(agent, true);
            closeAfterDelay(AgentWindow.class);
            agent.run();
        } else {
            ServerConfig config = config();
            config.tcpPort = Integer.parseInt(args[1]);
            config.udpPort = Integer.parseInt(args[2]);
            LabServer server = new LabServer(config);
            server.start();
            ServerDashboard.open(server);
            closeAfterDelay(ServerDashboard.class);
        }
    }
    private static void closeAfterDelay(Class<?> type) {
        SwingUtilities.invokeLater(() -> {
            Timer timer = new Timer(2500, event -> {
                for (Window w : Window.getWindows()) if (type.isInstance(w)) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
            });
            timer.setRepeats(false);
            timer.start();
        });
    }
    private static Process launch(String mode, int tcp, int udp) throws Exception {
        Path output = Files.createTempFile("lab-close-" + mode, ".txt");
        return new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), WindowCloseTest.class.getName(), mode,
                Integer.toString(tcp), Integer.toString(udp)).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }
    private static Socket connect(int port) throws Exception {
        long end = System.nanoTime() + 8000000000L;
        while (System.nanoTime() < end) {
            try { return new Socket("127.0.0.1", port); }
            catch (ConnectException e) { Thread.sleep(30); }
        }
        throw new AssertionError("Instructor did not start");
    }
    private static ServerConfig config() throws Exception {
        ServerConfig config = new ServerConfig();
        try (ServerSocket tcp = new ServerSocket(0); DatagramSocket udp = new DatagramSocket(0)) {
            config.tcpPort = tcp.getLocalPort();
            config.udpPort = udp.getLocalPort();
        }
        config.logFile = Files.createTempDirectory("lab-close-qa-").resolve("alerts.csv").toString();
        return config;
    }
    private static void require(boolean okay, String label) { if (!okay) throw new AssertionError(label); }
}
