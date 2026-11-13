package labmonitor.server;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import labmonitor.client.*;

/** Actual button handlers and modal dialogs against a real connected agent. */
public class GuiInteractionTest {
    private static ServerDashboard dashboard;
    private static AgentWindow window;

    public static void main(String[] args) {
        int exit = 0;
        LabServer server = null;
        LabAgent agent = null;
        try {
            ServerConfig config = new ServerConfig();
            try (ServerSocket tcp = new ServerSocket(0); DatagramSocket udp = new DatagramSocket(0)) {
                config.tcpPort = tcp.getLocalPort();
                config.udpPort = udp.getLocalPort();
            }
            config.logFile = Files.createTempDirectory("lab-gui-interaction-").resolve("alerts.csv").toString();
            server = new LabServer(config);
            server.start();
            ServerDashboard.open(server);
            agent = new LabAgent("127.0.0.1", config.tcpPort, "GUI-FLOW", "student",
                    new SystemMetrics(true, 20, Collections.<String>emptyList()));
            AgentWindow.open(agent, true);
            final LabAgent runningAgent = agent;
            Thread runner = new Thread(runningAgent::run, "gui-test-agent");
            runner.setDaemon(true);
            runner.start();
            await(() -> {
                dashboard = findWindow(ServerDashboard.class);
                window = findWindow(AgentWindow.class);
                return dashboard != null && window != null && component(dashboard, JTable.class).getRowCount() == 1;
            }, "connected PC in dashboard");
            click(dashboard, "Message selected PC");
            JDialog selection = dialog("Message");
            click(selection, "OK");
            await(() -> !selection.isShowing(), "select-PC warning dismissed");
            edt(() -> { component(dashboard, JTable.class).setRowSelectionInterval(0, 0); return null; });
            click(dashboard, "Message selected PC");
            JDialog input = inputDialog();
            edt(() -> { component(input, JTextField.class).setText("GUI direct delivery"); return null; });
            click(input, "OK");
            await(() -> logContains("GUI direct delivery"), "direct message from actual dialog");
            click(dashboard, "Broadcast to all");
            JDialog broadcast = inputDialog();
            edt(() -> { component(broadcast, JTextField.class).setText("GUI broadcast delivery"); return null; });
            click(broadcast, "OK");
            JDialog confirmation = dialog("Message");
            await(() -> component(confirmation, JOptionPane.class).getMessage().toString().contains("1 PC"),
                    "broadcast recipient confirmation");
            click(confirmation, "OK");
            await(() -> logContains("GUI broadcast delivery"), "broadcast reaches agent");
            edt(() -> { component(window, JTextField.class).setText("GUI help button"); return null; });
            click(window, "Request help");
            final LabServer runningServer = server;
            await(() -> runningServer.getAlertHistory().stream().anyMatch(a -> a.message.contains("GUI help button")),
                    "help button reaches server");
            edt(() -> {
                window.toFront();
                JTextField field = component(window, JTextField.class);
                field.setText("GUI keyboard help");
                field.requestFocusInWindow();
                return null;
            });
            Robot robot = new Robot();
            Point helpPoint = edt(() -> component(window, JTextField.class).getLocationOnScreen());
            robot.mouseMove(helpPoint.x + 20, helpPoint.y + 10);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            await(() -> component(window, JTextField.class).isFocusOwner(), "help field keyboard focus");
            robot.keyPress(KeyEvent.VK_ENTER);
            robot.keyRelease(KeyEvent.VK_ENTER);
            await(() -> runningServer.getAlertHistory().stream().anyMatch(a -> a.message.contains("GUI keyboard help")),
                    "Enter key submits help");
            click(dashboard, "View processes");
            JDialog processes = dialog("running processes");
            await(() -> component(processes, JList.class).getModel().getSize() >= 5, "fresh process dialog");
            click(processes, "OK");
            click(dashboard, "View Alert History");
            JDialog history = dialog("Alert History Viewer");
            int before = edt(() -> component(history, JTable.class).getRowCount());
            agent.requestHelp("Refresh history verification");
            await(() -> runningServer.getAlertHistory().stream().anyMatch(a -> a.message.contains("Refresh history verification")),
                    "new alert before history refresh");
            click(history, "Refresh");
            await(() -> component(history, JTable.class).getRowCount() > before, "history refresh button loads new alert");
            edt(() -> { history.dispose(); return null; });
            click(dashboard, "Message selected PC");
            JDialog cancel = inputDialog();
            click(cancel, "Cancel");
            await(() -> !cancel.isShowing(), "message dialog cancellation");
            // Capture the rendered UI for optional visual inspection.
            if (args.length > 0) {
                BufferedImage rendered = edt(() -> {
                    BufferedImage result = new BufferedImage(dashboard.getWidth() + window.getWidth(),
                            Math.max(dashboard.getHeight(), window.getHeight()), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = result.createGraphics();
                    dashboard.printAll(graphics);
                    graphics.translate(dashboard.getWidth(), 0);
                    window.printAll(graphics);
                    graphics.dispose();
                    return result;
                });
                ImageIO.write(rendered, "png", new File(args[0]));
            }
            System.out.println("GUI interactions passed: selection guard, direct message, broadcast, help button, Enter key, processes, history refresh, cancel");
        } catch (Throwable e) {
            e.printStackTrace();
            exit = 1;
        } finally {
            if (agent != null) agent.stop();
            if (server != null) server.stop();
        }
        System.exit(exit);
    }

    private static boolean logContains(String text) {
        return component(window, JTextArea.class).getText().contains(text);
    }
    private static JDialog inputDialog() throws Exception {
        final JDialog[] result = new JDialog[1];
        await(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog && w.isShowing() && componentOrNull((Container) w, JTextField.class) != null) {
                    result[0] = (JDialog) w;
                    return true;
                }
            }
            return false;
        }, "input dialog");
        return result[0];
    }
    private static JDialog dialog(String title) throws Exception {
        final JDialog[] result = new JDialog[1];
        await(() -> {
            for (Window w : Window.getWindows()) if (w instanceof JDialog && w.isShowing()
                    && ((JDialog) w).getTitle().contains(title)) { result[0] = (JDialog) w; return true; }
            return false;
        }, title + " dialog");
        return result[0];
    }
    private static void click(Container owner, String text) throws Exception {
        JButton button = edt(() -> findButton(owner, text));
        if (button == null) throw new AssertionError("Missing button " + text);
        SwingUtilities.invokeLater(button::doClick);
    }
    private static JButton findButton(Container parent, String text) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) return (JButton) c;
            if (c instanceof Container) {
                JButton result = findButton((Container) c, text);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static <T> T findWindow(Class<T> type) {
        for (Window w : Window.getWindows()) if (type.isInstance(w) && w.isShowing()) return type.cast(w);
        return null;
    }
    private static <T> T component(Container parent, Class<T> type) {
        T result = componentOrNull(parent, type);
        if (result == null) throw new AssertionError("Missing " + type.getSimpleName());
        return result;
    }
    private static <T> T componentOrNull(Container parent, Class<T> type) {
        if (type.isInstance(parent)) return type.cast(parent);
        for (Component c : parent.getComponents()) if (c instanceof Container) {
            T result = componentOrNull((Container) c, type);
            if (result != null) return result;
        }
        return null;
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    private static void await(Callable<Boolean> check, String label) throws Exception {
        long end = System.nanoTime() + 6000000000L;
        while (System.nanoTime() < end) {
            if (edt(check)) return;
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out: " + label);
    }
}
