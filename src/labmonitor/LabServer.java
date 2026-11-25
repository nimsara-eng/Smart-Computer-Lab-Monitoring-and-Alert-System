package labmonitor;

import java.awt.BorderLayout;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * Instructor server. TCP: registration, messages and help requests.
 * UDP: heartbeats with CPU, RAM and disk usage. Swing: the instructor dashboard.
 */
public class LabServer {

    static final int TCP_PORT = 5000;
    static final int UDP_PORT = 5001;
    static final int HEARTBEAT_TIMEOUT_MS = 8000;
    static final double HIGH_USAGE_LIMIT = 90.0;

    // PC ID -> the handler of that PC. Used by several threads, so always locked with synchronized (clients).
    private final Map<String, ClientHandler> clients = new HashMap<>();

    // Swing components: created and changed only on the Event Dispatch Thread (EDT).
    private JFrame frame;
    private DefaultTableModel tableModel;
    private JTable table;
    private JTextArea alertArea;

    public static void main(String[] args) {
        LabServer server = new LabServer();
        SwingUtilities.invokeLater(server::createWindow);
        try {
            server.startNetworkThreads();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Cannot open ports " + TCP_PORT + "/" + UDP_PORT
                    + ". Is the server already running?\n" + e.getMessage());
            System.exit(1);
        }
    }

    private void startNetworkThreads() throws IOException {
        ServerSocket serverSocket = new ServerSocket(TCP_PORT);
        DatagramSocket udpSocket = new DatagramSocket(UDP_PORT);

        // Each Runnable below is written as a lambda: () -> method(...)
        Thread acceptThread = new Thread(() -> acceptClients(serverSocket), "tcp-acceptor");
        Thread udpThread = new Thread(() -> receiveHeartbeats(udpSocket), "udp-receiver");
        Thread timeoutThread = new Thread(this::checkTimeouts, "timeout-monitor");
        timeoutThread.setDaemon(true); // a daemon thread does not keep the program running

        acceptThread.start();
        udpThread.start();
        timeoutThread.start();
        addAlert("Server started. TCP port " + TCP_PORT + ", UDP port " + UDP_PORT);
    }

    // Thread "tcp-acceptor": waits for lab PCs and gives each one its own ClientHandler thread.
    private void acceptClients(ServerSocket serverSocket) {
        while (true) {
            try {
                Socket socket = serverSocket.accept(); // blocks until a PC connects
                new ClientHandler(socket, this).start();
            } catch (IOException e) {
                addAlert("Could not accept a connection: " + e.getMessage());
            }
        }
    }

    // Thread "udp-receiver": one socket receives the heartbeats of all PCs.
    private void receiveHeartbeats(DatagramSocket udpSocket) {
        byte[] buffer = new byte[256];
        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet); // blocks until a datagram arrives
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                handleHeartbeat(text.trim());
            } catch (IOException e) {
                addAlert("UDP receive error: " + e.getMessage());
            }
        }
    }

    // Heartbeat format: HB|pcId|cpu|ram|disk
    private void handleHeartbeat(String text) {
        String[] parts = text.split("\\|");
        if (parts.length != 5 || !parts[0].equals("HB")) {
            return;
        }
        ClientHandler client = findOnlineClient(parts[1]);
        if (client == null) {
            return; // heartbeat from a PC that is not registered over TCP
        }
        try {
            double cpu = Double.parseDouble(parts[2]);
            double ram = Double.parseDouble(parts[3]);
            double disk = Double.parseDouble(parts[4]);
            if (client.updateUsage(cpu, ram, disk)) {
                addAlert("INFO " + client.getPcId() + " is responding again");
            }
            boolean high = cpu >= HIGH_USAGE_LIMIT || ram >= HIGH_USAGE_LIMIT || disk >= HIGH_USAGE_LIMIT;
            // Alert only when the PC becomes busy, not again on every heartbeat
            if (client.setHighUsage(high) && high) {
                addAlert(String.format("WARNING %s high usage: CPU %.0f%%, RAM %.0f%%, Disk %.0f%%",
                        client.getPcId(), cpu, ram, disk));
            }
            updateRow(client);
        } catch (NumberFormatException e) {
            // damaged datagram: ignore it
        }
    }

    // Thread "timeout-monitor": every second, finds online PCs with no heartbeat for 8 seconds.
    private void checkTimeouts() {
        while (true) {
            for (ClientHandler client : getAllClients()) {
                if (client.checkTimeout(HEARTBEAT_TIMEOUT_MS)) {
                    addAlert("CRITICAL " + client.getPcId() + " is not responding (no heartbeat for 8 s)");
                    updateRow(client);
                }
            }
            try {
                Thread.sleep(1000); // TIMED_WAITING state for one second
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ---- Called by ClientHandler threads ----

    /** Adds the PC to the table. Returns false if an online PC already uses this ID. */
    boolean registerClient(ClientHandler client) {
        synchronized (clients) {
            ClientHandler existing = clients.get(client.getPcId());
            if (existing != null && existing.isOnline()) {
                return false;
            }
            clients.put(client.getPcId(), client);
        }
        addAlert("INFO " + client.getPcId() + " connected from " + client.getAddress());
        updateRow(client);
        return true;
    }

    void clientDisconnected(ClientHandler client, String reason) {
        addAlert("WARNING " + client.getPcId() + " disconnected (" + reason + ")");
        updateRow(client);
    }

    void helpRequested(ClientHandler client, String text) {
        addAlert("HELP " + client.getPcId() + " asks: " + text);
    }

    private ClientHandler findOnlineClient(String pcId) {
        synchronized (clients) {
            ClientHandler client = clients.get(pcId);
            return (client != null && client.isOnline()) ? client : null;
        }
    }

    private List<ClientHandler> getAllClients() {
        synchronized (clients) {
            return new ArrayList<>(clients.values()); // a copy, so the lock is held only briefly
        }
    }

    // ---- Dashboard (Swing) ----

    private void createWindow() {
        tableModel = new DefaultTableModel(new String[] {"PC ID", "CPU %", "RAM %", "Disk %", "Status", "Data"}, 0);
        table = new JTable(tableModel);
        table.setDefaultEditor(Object.class, null); // read-only table
        alertArea = new JTextArea(12, 60);
        alertArea.setEditable(false);

        JButton messageButton = new JButton("Message selected PC");
        JButton broadcastButton = new JButton("Broadcast to all");
        messageButton.addActionListener(e -> sendToSelectedPc());
        broadcastButton.addActionListener(e -> broadcast());

        JPanel buttons = new JPanel();
        buttons.add(messageButton);
        buttons.add(broadcastButton);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JLabel(" Alerts and help requests"), BorderLayout.NORTH);
        bottom.add(new JScrollPane(alertArea), BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.SOUTH);

        frame = new JFrame("Smart Computer Lab Monitoring - Instructor Dashboard");
        frame.add(new JLabel(" Server IP: " + getLocalIp() + "   TCP " + TCP_PORT + "   UDP " + UDP_PORT),
                BorderLayout.NORTH);
        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(820, 620);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // Runs on the EDT (button click).
    private void sendToSelectedPc() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(frame, "Select a PC in the table first.");
            return;
        }
        String pcId = (String) tableModel.getValueAt(row, 0);
        String text = JOptionPane.showInputDialog(frame, "Message to " + pcId + ":");
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        ClientHandler client = findOnlineClient(pcId);
        if (client == null) {
            JOptionPane.showMessageDialog(frame, pcId + " is offline.");
            return;
        }
        client.sendLine("MSG|" + text.trim());
        addAlert("Message sent to " + pcId + ": " + text.trim());
    }

    // Runs on the EDT (button click).
    private void broadcast() {
        String text = JOptionPane.showInputDialog(frame, "Message to all PCs:");
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        int count = 0;
        for (ClientHandler client : getAllClients()) {
            if (client.isOnline()) {
                client.sendLine("MSG|" + text.trim());
                count++;
            }
        }
        addAlert("Broadcast sent to " + count + " PC(s): " + text.trim());
    }

    /** Updates (or adds) the PC's row. The values are copied here, the table is changed on the EDT. */
    private void updateRow(ClientHandler client) {
        String[] values = client.getTableRow();
        SwingUtilities.invokeLater(() -> {
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                if (tableModel.getValueAt(row, 0).equals(values[0])) {
                    for (int col = 1; col < values.length; col++) {
                        tableModel.setValueAt(values[col], row, col);
                    }
                    return;
                }
            }
            tableModel.addRow(values);
        });
    }

    /** Can be called from any thread. */
    private void addAlert(String text) {
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + text;
        System.out.println(line);
        SwingUtilities.invokeLater(() -> alertArea.append(line + "\n"));
    }

    private static String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            return "unknown (use ipconfig)";
        }
    }
}
