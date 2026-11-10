package labmonitor.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** The small window shown on each lab PC. */
public class AgentWindow extends JFrame implements AgentListener {

    private static final long serialVersionUID = 1L;

    private final LabAgent agent;
    private final JLabel status = new JLabel("Starting ...");
    private final JProgressBar cpuBar = bar();
    private final JProgressBar memoryBar = bar();
    private final JProgressBar diskBar = bar();
    private final JTextArea log = new JTextArea();
    private final JTextField helpText = new JTextField();

    /**
     * Asks for the server address and this PC's id. Returns {server, pcId}, or null if cancelled.
     */
    public static String[] askConnection(String defaultServer, String defaultPcId) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        JTextField server = new JTextField(defaultServer, 18);
        JTextField pcId = new JTextField(defaultPcId, 18);
        JPanel panel = new JPanel(new GridLayout(4, 1, 4, 4));
        panel.add(new JLabel("Server IP address or name (the instructor's PC):"));
        panel.add(server);
        panel.add(new JLabel("Name of this PC (must be different on every PC):"));
        panel.add(pcId);
        while (true) {
            int choice = JOptionPane.showConfirmDialog(null, panel, "Lab Agent - connect to server",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return null;
            }
            if (!server.getText().trim().isEmpty() && !pcId.getText().trim().isEmpty()) {
                return new String[] { server.getText().trim(), pcId.getText().trim() };
            }
        }
    }

    /** Creates the window on the Swing thread and registers it as the agent's listener. */
    public static void open(final LabAgent agent, final boolean simulated) {
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    } catch (Exception ignored) {
                        // default look and feel is fine
                    }
                    AgentWindow w = new AgentWindow(agent, simulated);
                    agent.setListener(w);
                    w.setVisible(true);
                }
            });
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private AgentWindow(LabAgent agent, boolean simulated) {
        super("Lab Agent - " + agent.getPcId() + (simulated ? " (simulated)" : ""));
        this.agent = agent;

        status.setOpaque(true);
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        status.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel meters = new JPanel(new GridLayout(3, 2, 6, 6));
        meters.setBorder(BorderFactory.createTitledBorder("This PC (" + agent.getUser() + ")"));
        meters.add(new JLabel("CPU usage"));
        meters.add(cpuBar);
        meters.add(new JLabel("Memory usage"));
        meters.add(memoryBar);
        meters.add(new JLabel("Disk usage"));
        meters.add(diskBar);

        JPanel top = new JPanel(new BorderLayout());
        top.add(status, BorderLayout.NORTH);
        top.add(meters, BorderLayout.CENTER);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createTitledBorder("Messages and alerts from the instructor"));

        JButton helpButton = new JButton("Request help");
        ActionListener sendHelp = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestHelp();
            }
        };
        helpButton.addActionListener(sendHelp);
        helpText.addActionListener(sendHelp);
        JPanel help = new JPanel(new BorderLayout(6, 0));
        help.setBorder(BorderFactory.createTitledBorder("Need help? Describe the problem"));
        help.add(helpText, BorderLayout.CENTER);
        help.add(helpButton, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(help, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(460, 430));
        pack();
        setLocationByPlatform(true);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                AgentWindow.this.agent.stop();
                System.exit(0);
            }
        });
        showStatus(false, "Starting ...");
    }

    private static JProgressBar bar() {
        JProgressBar b = new JProgressBar(0, 100);
        b.setStringPainted(true);
        return b;
    }

    private void requestHelp() {
        String text = helpText.getText().trim();
        if (agent.requestHelp(text)) {
            append("Help request sent to the instructor" + (text.isEmpty() ? "" : ": " + text));
            helpText.setText("");
        } else {
            JOptionPane.showMessageDialog(this, "Not connected to the server. Please call the instructor.");
        }
    }

    private void append(String text) {
        log.append("[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void showStatus(boolean connected, String text) {
        status.setText(text);
        status.setBackground(connected ? new Color(214, 245, 214) : new Color(255, 200, 200));
    }

    // ------------------------------------------------------------------
    // Agent events. They arrive on network threads, so they are passed to the Swing thread.
    // ------------------------------------------------------------------

    @Override
    public void statusChanged(final boolean connected, final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showStatus(connected, text);
            }
        });
    }

    @Override
    public void metricsSent(final double cpu, final double memory, final double disk) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                cpuBar.setValue((int) Math.round(cpu));
                memoryBar.setValue((int) Math.round(memory));
                diskBar.setValue((int) Math.round(disk));
            }
        });
    }

    @Override
    public void messageReceived(final String from, final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                append(from + ": " + text);
                toFront();
            }
        });
    }

    @Override
    public void alertReceived(final String severity, final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                append("*** " + severity + " ALERT: " + text);
                Toolkit.getDefaultToolkit().beep();
                toFront();
            }
        });
    }
}
