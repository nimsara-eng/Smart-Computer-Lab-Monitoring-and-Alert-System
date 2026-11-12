package labmonitor.server;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/** Searchable, sortable alert history for the current server session. */
public final class AlertHistoryDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final LabServer server;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Time", "Severity", "PC", "Type", "Message"}, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<DefaultTableModel>(model);
    private final JTextField search = new JTextField(20);
    private final JComboBox<String> severity = new JComboBox<String>(
            new String[] {"All severities", "INFO", "WARNING", "CRITICAL"});
    private final JLabel count = new JLabel();

    public AlertHistoryDialog(JFrame owner, LabServer server) {
        super(owner, "Alert History Viewer", false);
        this.server = server;
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(false);
        table.setRowSorter(sorter);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filters.add(new JLabel("Search PC / type / message:"));
        filters.add(search);
        filters.add(new JLabel("Severity:"));
        filters.add(severity);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshHistory());
        filters.add(refresh);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        severity.addActionListener(e -> applyFilter());
        setLayout(new BorderLayout(8, 8));
        add(filters, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT));
        footer.add(count);
        footer.add(new JLabel("  (Current server session; refresh to load new alerts)"));
        add(footer, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(960, 500));
        pack();
        setLocationRelativeTo(owner);
        refreshHistory();
    }

    private void refreshHistory() {
        List<Alert> alerts = server.getAlertHistory();
        model.setRowCount(0);
        for (int i = alerts.size() - 1; i >= 0; i--) {
            Alert a = alerts.get(i);
            model.addRow(new Object[] {a.timeText(), a.severity.toString(), a.pcId,
                    a.type.toString(), a.message});
        }
        applyFilter();
    }

    private void applyFilter() {
        final String query = search.getText().trim();
        final String level = (String) severity.getSelectedItem();
        sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                if (!"All severities".equals(level) && !level.equals(entry.getStringValue(1))) return false;
                if (query.isEmpty()) return true;
                String needle = query.toLowerCase(java.util.Locale.ROOT);
                for (int col = 0; col < entry.getValueCount(); col++) {
                    if (entry.getStringValue(col).toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
                }
                return false;
            }
        });
        count.setText("Showing " + sorter.getViewRowCount() + " of " + model.getRowCount() + " alerts");
    }
}
