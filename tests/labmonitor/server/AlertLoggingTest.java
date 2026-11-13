package labmonitor.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Retention, concurrent appends, restart, and safe log failure checks. */
public class AlertLoggingTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("lab-log-qa-");
        Path log = root.resolve("nested/alerts.csv");
        AlertManager manager = new AlertManager(log.toString());
        for (int i = 0; i < 1005; i++) manager.record(alert("entry-" + i));
        require(manager.getHistory().size() == 1000, "history bounded at 1000");
        require(manager.getHistory().get(0).message.equals("entry-5"), "oldest entries evicted");
        manager.getHistory().clear();
        require(manager.getHistory().size() == 1000, "history returned as independent copy");
        List<Thread> writers = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            final int id = t;
            Thread writer = new Thread(() -> {
                for (int i = 0; i < 100; i++) manager.record(alert("thread-" + id + "-" + i));
            });
            writers.add(writer);
            writer.start();
        }
        for (Thread writer : writers) writer.join();
        require(Files.readAllLines(log, StandardCharsets.UTF_8).size() == 1406, "all concurrent appends persisted");
        AlertManager restarted = new AlertManager(log.toString());
        require(restarted.getHistory().isEmpty(), "history is per session");
        restarted.record(alert("after-restart"));
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        require(lines.size() == 1407 && lines.stream().filter(s -> s.startsWith("time,severity,")).count() == 1,
                "restart appends without duplicate CSV header");
        Path fileAsParent = root.resolve("not-a-directory");
        Files.write(fileAsParent, new byte[] {1});
        checkFailure(fileAsParent.resolve("alerts.csv").toString(), "unwritable destination");
        if (Files.exists(Paths.get("/dev/full"))) checkFailure("/dev/full", "full device");
        else System.out.println("SKIP full-device check: /dev/full unavailable on this platform");
        System.out.println("Alert logging tests passed (retention, concurrent writes, restart, failure reporting)");
    }
    private static void checkFailure(String path, String label) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        AlertManager manager = new AlertManager(path);
        try (PrintStream capture = new PrintStream(captured, true, "UTF-8")) {
            System.setErr(capture);
            manager.record(alert("must remain in memory"));
        } finally { System.setErr(previous); }
        require(manager.getHistory().size() == 1, label + ": keep in-memory alert");
        require(captured.toString("UTF-8").contains("Could not write alert log"), label + ": report failed write");
    }
    private static Alert alert(String text) {
        return new Alert(Alert.Severity.WARNING, Alert.Type.HELP_REQUEST, "QA", text);
    }
    private static void require(boolean okay, String text) {
        if (!okay) throw new AssertionError(text);
    }
}
