package labmonitor.server;

import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.Collections;
import javax.swing.*;
import labmonitor.client.*;

/** Run in a desktop session or with xvfb-run; all component access uses the EDT. */
public class SwingSmokeTest {
    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        config.logFile = Files.createTempDirectory("lab-swing-qa-").resolve("alerts.csv").toString();
        LabServer server = new LabServer(config);
        LabAgent agent = new LabAgent("localhost", 5000, "GUI-QA", "student",
                new SystemMetrics(true, 20, Collections.<String>emptyList()));
        try {
            ServerDashboard.open(server);
            SwingUtilities.invokeAndWait(() -> { });
            AgentWindow.open(agent, true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ServerDashboard dashboard = find(ServerDashboard.class);
                    AgentWindow window = find(AgentWindow.class);
                    require(dashboard.isShowing() && window.isShowing(), "main windows visible");
                    Alert alert = new Alert(Alert.Severity.WARNING, Alert.Type.HELP_REQUEST, "GUI-QA", "Need help");
                    dashboard.alertRaised(alert);
                    window.statusChanged(true, "Connected for QA");
                    window.metricsSent(25, 50, 75);
                    window.messageReceived("Instructor", "Hello student");
                    window.alertReceived("WARNING", "CPU high");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            SwingUtilities.invokeAndWait(() -> {
                try {
                    AgentWindow window = find(AgentWindow.class);
                    require(((JLabel) field(window, "status")).getText().equals("Connected for QA"), "status update");
                    require(((JProgressBar) field(window, "cpuBar")).getValue() == 25, "CPU bar");
                    require(((JProgressBar) field(window, "memoryBar")).getValue() == 50, "memory bar");
                    require(((JProgressBar) field(window, "diskBar")).getValue() == 75, "disk bar");
                    String text = ((JTextArea) field(window, "log")).getText();
                    require(text.contains("Hello student") && text.contains("CPU high"), "message and alert log");
                    javax.swing.table.DefaultTableModel alerts = (javax.swing.table.DefaultTableModel)
                            field(find(ServerDashboard.class), "alertModel");
                    require(alerts.getRowCount() == 1, "dashboard alert delivery");
                    // Exercise the actual history table and its search/severity controls.
                    server.onHelpRequest(new PcInfo("HISTORY-QA"), "Searchable request");
                    AlertHistoryDialog history = new AlertHistoryDialog(find(ServerDashboard.class), server);
                    history.setVisible(true);
                    require(((JLabel) field(history, "count")).getText().equals("Showing 1 of 1 alerts"), "history loaded");
                    JTextField search = (JTextField) field(history, "search");
                    search.setText("no match");
                    require(((JLabel) field(history, "count")).getText().equals("Showing 0 of 1 alerts"), "history search");
                    search.setText("searchable");
                    require(((JLabel) field(history, "count")).getText().equals("Showing 1 of 1 alerts"), "case-insensitive search");
                    ((JComboBox<?>) field(history, "severity")).setSelectedItem("CRITICAL");
                    require(((JLabel) field(history, "count")).getText().equals("Showing 0 of 1 alerts"), "severity filter");
                    ((JComboBox<?>) field(history, "severity")).setSelectedItem("WARNING");
                    require(((JLabel) field(history, "count")).getText().equals("Showing 1 of 1 alerts"), "matching severity");
                    System.out.println("Swing smoke tests passed (12 checks)");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
        }
        // The dashboard's repeating Swing timer keeps the EDT alive after disposal.
        System.exit(0);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static <T> T find(Class<T> type) {
        for (Window window : Window.getWindows()) if (type.isInstance(window)) return type.cast(window);
        throw new AssertionError("Missing window: " + type.getSimpleName());
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
