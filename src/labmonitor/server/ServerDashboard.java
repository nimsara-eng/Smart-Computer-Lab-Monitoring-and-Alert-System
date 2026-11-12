package labmonitor.server;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** The instructor's window: a live table of all lab PCs and the list of alerts. */
public class ServerDashboard extends JFrame implements ServerListener {

    private static final long serialVersionUID = 1L;

    private static final Color GREEN = new Color(214, 245, 214);
    private static final Color ORANGE = new Color(255, 229, 180);
    private static final Color RED = new Color(255, 200, 200);
    private static final Color GREY = new Color(225, 225, 225);
    private static final int MAX_ALERT_ROWS = 500;

    private final LabServer server;
    private final PcTableModel pcModel;
    private final JTable pcTable = new JTable();
    private final DefaultTableModel alertModel = new DefaultTableModel(
            new Object[] { "Time", "Severity", "PC", "Type", "Message" }, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JLabel summary = new JLabel(" ");
    /** PC whose process list the instructor asked to see. */
    private String pendingProcessView;

    public static void open(final LabServer server) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // default look and feel is fine
                }
                new ServerDashboard(server).setVisible(true);
            }
        });
    }

    private ServerDashboard(LabServer server) {
        super("Smart Computer Lab Monitoring and Alert System - Instructor Dashboard");
        this.server = server;
        this.pcModel = new PcTableModel(server.getConfig());
        this.pcTable.setModel(pcModel);

        pcTable.setRowHeight(24);
        pcTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pcTable.setDefaultRenderer(Object.class, new PcRenderer());

        JTable alertTable = new JTable(alertModel);
        alertTable.setRowHeight(22);
        alertTable.setDefaultRenderer(Object.class, new AlertRenderer());
        alertTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        alertTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        alertTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        alertTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        alertTable.getColumnModel().getColumn(4).setPreferredWidth(560);

        JScrollPane pcScroll = new JScrollPane(pcTable);
        pcScroll.setBorder(BorderFactory.createTitledBorder("Lab computers"));
        JScrollPane alertScroll = new JScrollPane(alertTable);
        alertScroll.setBorder(BorderFactory.createTitledBorder("Alerts (newest first)"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pcScroll, alertScroll);
        split.setResizeWeight(0.55);

        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 13f));
        summary.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button("Message selected PC", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                messageSelected();
            }
        }));
        buttons.add(button("Broadcast to all", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                broadcast();
            }
        }));
        buttons.add(button("View processes", new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                viewProcesses();
            }
        }));

        setLayout(new BorderLayout());
        add(summary, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1050, 640));
        pack();
        setLocationRelativeTo(null);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                ServerDashboard.this.server.stop();
                System.exit(0);
            }
        });

        // Show anything that happened before the window opened, then listen for new events.
        for (PcInfo pc : server.getPcs()) {
            pcModel.upsert(pc);
        }
        for (Alert a : server.getAlertHistory()) {
            addAlertRow(a);
        }
        server.addListener(this);

        // Refreshes the "Last heartbeat" column and the summary once per second.
        new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pcModel.refresh();
                updateSummary();
            }
        }).start();
        updateSummary();
    }

    private static JButton button(String text, ActionListener action) {
        JButton b = new JButton(text);
        b.addActionListener(action);
        return b;
    }

    // ------------------------------------------------------------------
    // Server events. They arrive on network threads, so they are passed to the Swing thread.
    // ------------------------------------------------------------------

    @Override
    public void pcUpdated(final PcInfo pc) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                pcModel.upsert(pc);
            }
        });
    }

    @Override
    public void alertRaised(final Alert alert) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                addAlertRow(alert);
                if (alert.severity == Alert.Severity.CRITICAL) {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
        });
    }

    @Override
    public void processListReceived(final PcInfo pc) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (pc.getPcId().equals(pendingProcessView)) {
                    pendingProcessView = null;
                    showProcesses(pc);
                }
            }
        });
    }

    // ------------------------------------------------------------------

    private void addAlertRow(Alert a) {
        alertModel.insertRow(0, new Object[] { a.timeText(), a.severity, a.pcId, a.type, a.message });
        if (alertModel.getRowCount() > MAX_ALERT_ROWS) {
            alertModel.removeRow(alertModel.getRowCount() - 1);
        }
    }

    private void updateSummary() {
        int online = 0;
        int problems = 0;
        int healthy = 0;
        int warning = 0;
        int critical = 0;
        List<PcInfo> pcs = server.getPcs();
        for (PcInfo pc : pcs) {
            PcInfo.Status s = pc.getStatus();
            PcHealth.Level health = PcHealth.evaluate(pc, server.getConfig());
            if (health == PcHealth.Level.HEALTHY) healthy++;
            if (health == PcHealth.Level.WARNING) warning++;
            if (health == PcHealth.Level.CRITICAL) critical++;
            if (s != PcInfo.Status.OFFLINE) {
                online++;
            }
            if (s == PcInfo.Status.ALERT || s == PcInfo.Status.NOT_RESPONDING) {
                problems++;
            }
        }
        summary.setText("Connected: " + online + " of " + pcs.size() + " known PCs      PCs with problems: " + problems
                + "      Health: " + healthy + " healthy / " + warning + " warning / " + critical + " critical"
                + "      Listening on TCP " + server.getConfig().tcpPort + " / UDP " + server.getConfig().udpPort);
    }

    private PcInfo selectedPc() {
        int row = pcTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a PC in the table first.");
            return null;
        }
        return pcModel.get(row);
    }

    private void messageSelected() {
        PcInfo pc = selectedPc();
        if (pc == null) {
            return;
        }
        String text = JOptionPane.showInputDialog(this, "Message to " + pc.getPcId() + ":");
        if (text != null && !text.trim().isEmpty() && !server.sendMessage(pc.getPcId(), text.trim())) {
            JOptionPane.showMessageDialog(this, pc.getPcId() + " is not connected.");
        }
    }

    private void broadcast() {
        String text = JOptionPane.showInputDialog(this, "Message to all connected PCs:");
        if (text != null && !text.trim().isEmpty()) {
            int count = server.broadcast(text.trim());
            JOptionPane.showMessageDialog(this, "Message sent to " + count + " PC(s).");
        }
    }

    private void viewProcesses() {
        PcInfo pc = selectedPc();
        if (pc == null) {
            return;
        }
        if (server.requestProcesses(pc.getPcId())) {
            pendingProcessView = pc.getPcId(); // the dialog opens when the fresh list arrives
        } else {
            showProcesses(pc); // offline: show the last list we received
        }
    }

    private void showProcesses(PcInfo pc) {
        List<String> names = pc.getProcesses();
        List<String> shown = new ArrayList<String>();
        for (String name : names) {
            shown.add(server.getConfig().isBlocked(name) ? name + "   <-- BLOCKED" : name);
        }
        JList<String> list = new JList<String>(shown.toArray(new String[0]));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(360, 420));
        JOptionPane.showMessageDialog(this, scroll,
                pc.getPcId() + " - " + names.size() + " running processes" + (pc.isConnected() ? "" : " (last known)"),
                JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------

    private static class PcTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = { "PC ID", "Host name", "User", "IP address", "Status", "CPU %",
                "RAM %", "Disk %", "Health", "Last heartbeat", "Lost packets", "Problems" };

        private final List<PcInfo> rows = new ArrayList<PcInfo>();
        private final ServerConfig config;

        PcTableModel(ServerConfig config) {
            this.config = config;
        }

        void upsert(PcInfo pc) {
            int index = rows.indexOf(pc);
            if (index < 0) {
                rows.add(pc);
                fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            } else {
                fireTableRowsUpdated(index, index);
            }
        }

        void refresh() {
            if (!rows.isEmpty()) {
                fireTableRowsUpdated(0, rows.size() - 1);
            }
        }

        PcInfo get(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            PcInfo pc = rows.get(row);
            boolean live = pc.isConnected() && pc.getReceived() > 0;
            switch (column) {
            case 0:
                return pc.getPcId();
            case 1:
                return pc.getHostname();
            case 2:
                return pc.getUser();
            case 3:
                return pc.getAddress() == null ? "" : pc.getAddress().getHostAddress();
            case 4:
                return pc.getStatus().toString().replace('_', ' ');
            case 5:
                return live ? percent(pc.getCpu()) : "-";
            case 6:
                return live ? percent(pc.getMemory()) : "-";
            case 7:
                return live ? percent(pc.getDisk()) : "-";
            case 8:
                return PcHealth.evaluate(pc, config).toString();
            case 9:
                if (!pc.isConnected()) {
                    return "-";
                }
                return (System.currentTimeMillis() - pc.getLastHeartbeat()) / 1000 + " s ago";
            case 10:
                return pc.getLost() + " of " + (pc.getLost() + pc.getReceived());
            default:
                return pc.getProblems();
            }
        }

        private static String percent(double value) {
            return String.format(Locale.US, "%.0f", value);
        }
    }

    /** Colours each PC row by its status. */
    private class PcRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                PcHealth.Level health = PcHealth.evaluate(pcModel.get(row), server.getConfig());
                switch (health) {
                case HEALTHY:
                    c.setBackground(GREEN);
                    break;
                case WARNING:
                    c.setBackground(ORANGE);
                    break;
                case CRITICAL:
                    c.setBackground(RED);
                    break;
                default:
                    c.setBackground(GREY);
                    break;
                }
                c.setForeground(Color.BLACK);
            }
            return c;
        }
    }

    /** Colours each alert row by its severity. */
    private static class AlertRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                Object severity = table.getModel().getValueAt(row, 1);
                if (severity == Alert.Severity.CRITICAL) {
                    c.setBackground(RED);
                } else if (severity == Alert.Severity.WARNING) {
                    c.setBackground(ORANGE);
                } else {
                    c.setBackground(Color.WHITE);
                }
                c.setForeground(Color.BLACK);
            }
            return c;
        }
    }
}
