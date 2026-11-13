package labmonitor.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Optional: takes a jpackage app-image directory and runs both bundled launchers. */
public class PackagedLauncherTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the app-image directory");
        Path image = Paths.get(args[0]).toAbsolutePath();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path launchers = windows ? image : image.resolve("bin");
        String suffix = windows ? ".exe" : "";
        Path root = Files.createTempDirectory("lab-packaged-qa-");
        int tcpPort;
        int udpPort;
        try (ServerSocket tcp = new ServerSocket(0); DatagramSocket udp = new DatagramSocket(0)) {
            tcpPort = tcp.getLocalPort();
            udpPort = udp.getLocalPort();
        }
        Path config = root.resolve("server.properties");
        Path log = root.resolve("alerts.csv");
        Properties settings = new Properties();
        settings.setProperty("tcp.port", Integer.toString(tcpPort));
        settings.setProperty("udp.port", Integer.toString(udpPort));
        settings.setProperty("heartbeat.interval.sec", "1");
        settings.setProperty("process.report.sec", "1");
        settings.setProperty("log.file", log.toString());
        try (OutputStream out = Files.newOutputStream(config)) { settings.store(out, "Packaged QA"); }
        Path serverOutput = root.resolve("server-output.txt");
        Path agentOutput = root.resolve("agent-output.txt");
        Process server = start(root, serverOutput, launchers.resolve("LabServer" + suffix).toString(),
                "--nogui", "--config", config.toString());
        Process agent = null;
        try {
            await(() -> text(serverOutput).contains("TCP port " + tcpPort), 10000, "bundled server startup with explicit config from another directory");
            agent = start(root, agentOutput, launchers.resolve("LabAgent" + suffix).toString(),
                    "--nogui", "--server", "127.0.0.1", "--port", Integer.toString(tcpPort),
                    "--id", "PACKAGED-QA", "--fake-proc", "vlc.exe");
            await(() -> text(agentOutput).contains("[HEARTBEAT]"), 10000, "bundled agent reads real metrics");
            await(() -> text(log).contains("BLOCKED_APP"), 10000, "packaged agent process report and blocked warning");
            PrintWriter commands = new PrintWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8), true);
            commands.println("msg PACKAGED-QA packaged direct");
            commands.println("all packaged broadcast");
            commands.println("procs PACKAGED-QA");
            await(() -> text(agentOutput).contains("packaged direct") && text(agentOutput).contains("packaged broadcast"),
                    5000, "packaged direct and broadcast delivery");
            await(() -> text(serverOutput).contains("PACKAGED-QA processes: ["), 5000, "packaged process list");
            require(!text(agentOutput).contains("Could not read the process list"), "system process command succeeds");
            agent.destroy();
            require(agent.waitFor(5, TimeUnit.SECONDS), "agent terminates");
            await(() -> text(log).contains("DISCONNECTED"), 5000, "packaged agent disconnect detected");
            commands.println("quit");
            require(server.waitFor(5, TimeUnit.SECONDS) && server.exitValue() == 0, "packaged server console quit");
            System.out.println("Packaged launchers passed: bundled runtime, real metrics/processes, custom config, messaging, alerts, disconnect, quit");
            System.out.println("Artifacts: " + root);
        } finally {
            if (agent != null && agent.isAlive()) agent.destroyForcibly().waitFor();
            if (server.isAlive()) server.destroyForcibly().waitFor();
        }
    }
    private static Process start(Path cwd, Path output, String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        // Prove Linux launchers use their bundled runtime without a Java executable on PATH.
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Path tools = Files.createDirectories(cwd.resolve("system-tools"));
            Path ps = tools.resolve("ps");
            if (!Files.exists(ps)) Files.createSymbolicLink(ps, Paths.get("/usr/bin/ps"));
            builder.environment().put("PATH", tools.toString());
            builder.environment().remove("JAVA_HOME");
        }
        return builder.start();
    }
    private static String text(Path path) {
        try { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
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
