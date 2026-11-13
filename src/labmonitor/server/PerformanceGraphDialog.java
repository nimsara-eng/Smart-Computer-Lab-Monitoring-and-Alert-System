package labmonitor.server;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/** Live CPU/RAM/Disk chart, using only built-in Swing/Java2D (no dependencies). */
public final class PerformanceGraphDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final Timer timer;

    public PerformanceGraphDialog(JFrame owner, String pcId, PerformanceHistory history) {
        super(owner, "Live Performance - " + pcId, false);
        setLayout(new BorderLayout());
        JLabel note = new JLabel("  " + pcId + "  |  Last 120 received heartbeats (in memory)  |  CPU / RAM / Disk (%)");
        note.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 8));
        add(note, BorderLayout.NORTH);
        final GraphPanel graph = new GraphPanel(history);
        add(graph, BorderLayout.CENTER);
        setSize(820, 460);
        setMinimumSize(new Dimension(520, 300));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        timer = new Timer(1000, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { graph.repaint(); }
        });
        timer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { timer.stop(); }
        });
    }

    private static final class GraphPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final Color[] COLORS = {
            new Color(35, 112, 214), new Color(30, 145, 95), new Color(221, 116, 25)
        };
        private static final String[] LABELS = { "CPU", "RAM", "Disk" };
        private final PerformanceHistory history;
        GraphPanel(PerformanceHistory history) {
            this.history = history;
            setBackground(Color.WHITE);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D d = (Graphics2D) g.create();
            try {
                d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int left = 62, top = 38, right = getWidth() - 28, bottom = getHeight() - 54;
                int width = right - left, height = bottom - top;
                if (width <= 0 || height <= 0) return;
                d.setColor(new Color(230, 234, 238));
                for (int pct = 0; pct <= 100; pct += 20) {
                    int y = bottom - pct * height / 100;
                    d.drawLine(left, y, right, y);
                    d.setColor(Color.DARK_GRAY);
                    d.drawString(pct + "%", 20, y + 4);
                    d.setColor(new Color(230, 234, 238));
                }
                List<double[]> samples = history.snapshot();
                d.setColor(Color.DARK_GRAY);
                d.drawString("Older samples", left, bottom + 22);
                d.drawString("Newest", right - 45, bottom + 22);
                for (int metric = 0; metric < 3; metric++) {
                    d.setColor(COLORS[metric]);
                    d.setStroke(new BasicStroke(2.2f));
                    for (int i = 1; i < samples.size(); i++) {
                        int x1 = left + (i - 1) * width / Math.max(1, samples.size() - 1);
                        int x2 = left + i * width / Math.max(1, samples.size() - 1);
                        int y1 = bottom - (int) Math.round(samples.get(i - 1)[metric] * height / 100);
                        int y2 = bottom - (int) Math.round(samples.get(i)[metric] * height / 100);
                        d.drawLine(x1, y1, x2, y2);
                    }
                    int lx = left + metric * 130;
                    d.fillRect(lx, 12, 18, 4);
                    d.drawString(LABELS[metric], lx + 24, 18);
                }
                if (samples.isEmpty()) {
                    d.setColor(Color.GRAY);
                    String msg = "Waiting for heartbeat data from this PC...";
                    FontMetrics fm = d.getFontMetrics();
                    d.drawString(msg, left + (width - fm.stringWidth(msg)) / 2, top + height / 2);
                }
            } finally { d.dispose(); }
        }
    }
}
