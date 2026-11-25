package labmonitor;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.swing.*;

/**
 * Student PC agent. Registers with the server over TCP, then sends a UDP heartbeat
 * with CPU, RAM and disk usage every 2 seconds.
 * Usage: LabAgent serverIP PC-ID [--simulate]
 */
public class LabAgent implements Runnable {

    static final int HEARTBEAT_INTERVAL_MS = 2000;

    private final String serverIp;
    private final String pcId;
    private final boolean simulated;
    private final SystemMetrics metrics;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader inputReader;
    private volatile boolean running = true; // volatile: changed by one thread, read by others

    private JLabel statusLabel;
    private JLabel usageLabel;
    private JTextArea messageArea;

    LabAgent(String serverIp, String pcId, boolean simulated) {
        this.serverIp = serverIp;
        this.pcId = pcId;
        this.simulated = simulated;
        this.metrics = new SystemMetrics(simulated);
    }

    public static void main(String[] args) {
        String ip = args.length > 0 ? args[0]
                : JOptionPane.showInputDialog("Server IP address:", "localhost");
        String id = args.length > 1 ? args[1]
                : JOptionPane.showInputDialog("PC ID:", "PC-01");
        boolean simulate = args.length > 2 && args[2].equals("--simulate");
        if (ip == null || id == null || id.trim().isEmpty()) {
            return; // the user pressed Cancel
        }
        LabAgent agent = new LabAgent(ip.trim(), id.trim(), simulate);
        try {
            agent.connect();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Cannot connect to the server at " + ip + ":"
                    + LabServer.TCP_PORT + "\n" + e.getMessage());
            System.exit(1);
        }
        SwingUtilities.invokeLater(agent::createWindow);

        // Two extra threads: one created from a lambda, one from this Runnable object.
        Thread tcpReceiver = new Thread(agent::receiveMessages, "tcp-receiver");
        Thread udpSender = new Thread(agent, "udp-heartbeat");
        tcpReceiver.start();
        udpSender.start();
    }

    // TCP: connect and register. The server answers WELCOME or ERROR|reason.
    private void connect() throws IOException {
        socket = new Socket(serverIp, LabServer.TCP_PORT);
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        out.println("REGISTER|" + pcId + "|" + (simulated ? "SIMULATED" : "REAL"));
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String reply = in.readLine();
        if (reply == null || !reply.equals("WELCOME")) {
            socket.close();
            throw new IOException("Server refused: " + reply);
        }
        System.out.println(pcId + " registered with " + serverIp);
        inputReader = in;
    }

    // Thread "tcp-receiver": waits for instructor messages.
    private void receiveMessages() {
        try {
            String line;
            while ((line = inputReader.readLine()) != null) { // blocks until a line arrives
                if (line.startsWith("MSG|")) {
                    String text = line.substring(4);
                    System.out.println(pcId + " got message: " + text);
                    showMessage("Instructor: " + text);
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(null, text, "Message from instructor",
                                    JOptionPane.INFORMATION_MESSAGE));
                }
            }
        } catch (IOException e) {
            // socket closed
        }
        running = false;
        System.out.println(pcId + " disconnected from server");
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: DISCONNECTED from server");
            statusLabel.setForeground(Color.RED);
        });
    }

    // Thread "udp-heartbeat": run() of this Runnable. Sends HB|pcId|cpu|ram|disk every 2 seconds.
    @Override
    public void run() {
        try (DatagramSocket udp = new DatagramSocket()) {
            InetAddress server = InetAddress.getByName(serverIp);
            while (running) {
                double cpu = metrics.cpuPercent();
                double ram = metrics.ramPercent();
                double disk = metrics.diskPercent();
                String text = String.format(Locale.US, "HB|%s|%.1f|%.1f|%.1f", pcId, cpu, ram, disk);
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                udp.send(new DatagramPacket(data, data.length, server, LabServer.UDP_PORT));
                SwingUtilities.invokeLater(() -> usageLabel.setText(
                        String.format("CPU %.0f%%   RAM %.0f%%   Disk %.0f%%", cpu, ram, disk)));
                Thread.sleep(HEARTBEAT_INTERVAL_MS); // TIMED_WAITING for 2 seconds
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("Heartbeat thread stopped: " + e.getMessage());
        }
    }

    // ---- Student window (Swing, runs on the EDT) ----

    private void createWindow() {
        statusLabel = new JLabel("Status: CONNECTED to " + serverIp);
        statusLabel.setForeground(new Color(0, 128, 0));
        usageLabel = new JLabel("CPU -   RAM -   Disk -");
        JLabel modeLabel = new JLabel(simulated ? "Mode: SIMULATED DATA (demo only)" : "Mode: real measurements");
        if (simulated) {
            modeLabel.setForeground(Color.RED);
        }
        JPanel top = new JPanel(new GridLayout(4, 1));
        top.add(new JLabel(" PC ID: " + pcId));
        top.add(modeLabel);
        top.add(statusLabel);
        top.add(usageLabel);

        messageArea = new JTextArea(8, 40);
        messageArea.setEditable(false);

        JTextField helpField = new JTextField();
        JButton helpButton = new JButton("Ask for help");
        helpButton.addActionListener(e -> {
            String text = helpField.getText().trim();
            if (!text.isEmpty()) {
                sendLine("HELP|" + text);
                showMessage("You asked: " + text);
                helpField.setText("");
            }
        });
        JPanel help = new JPanel(new BorderLayout());
        help.add(helpField, BorderLayout.CENTER);
        help.add(helpButton, BorderLayout.EAST);

        JFrame frame = new JFrame("Lab Agent - " + pcId + (simulated ? " (SIMULATED)" : ""));
        frame.add(top, BorderLayout.NORTH);
        frame.add(new JScrollPane(messageArea), BorderLayout.CENTER);
        frame.add(help, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        frame.setSize(440, 340);
        frame.setVisible(true);
    }

    /** Called from the GUI thread; PrintWriter is shared, so this is synchronized. */
    private synchronized void sendLine(String line) {
        out.println(line);
    }

    private void showMessage(String text) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + text + "\n";
        SwingUtilities.invokeLater(() -> messageArea.append(line));
    }

    // Tell the server we are leaving, then close the connection and exit.
    private void shutdown() {
        running = false;
        sendLine("BYE");
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
        System.exit(0);
    }
}
