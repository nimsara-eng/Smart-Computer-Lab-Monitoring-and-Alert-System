package labmonitor;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One thread per connected lab PC (created by extending Thread).
 * It reads the PC's TCP messages and also stores the PC's latest data for the dashboard.
 */
class ClientHandler extends Thread {

    private final Socket socket;
    private final LabServer server;
    private final BufferedReader in;
    private final PrintWriter out;

    // PC data. Written by this thread and the UDP thread, read by the timeout thread and the GUI,
    // so every method that touches these fields is synchronized.
    private String pcId = "";
    private boolean simulated;
    private boolean online;
    private boolean notResponding;
    private boolean highUsage;
    private double cpu;
    private double ram;
    private double disk;
    private long lastHeartbeat;

    ClientHandler(Socket socket, LabServer server) throws IOException {
        super("client-" + socket.getInetAddress().getHostAddress()); // thread name
        this.socket = socket;
        this.server = server;
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    @Override
    public void run() {
        String reason = "connection lost";
        try {
            if (!register(in.readLine())) {
                return;
            }
            String line;
            while ((line = in.readLine()) != null) { // blocks until the PC sends a line
                if (line.startsWith("HELP|")) {
                    server.helpRequested(this, line.substring(5));
                } else if (line.equals("BYE")) {
                    reason = "closed by the student";
                    break;
                }
            }
        } catch (IOException e) {
            // network error: handled below as a lost connection
        } finally {
            closeSocket();
            if (markOffline()) {
                server.clientDisconnected(this, reason);
            }
        }
    }

    // First line must be REGISTER|pcId|REAL or REGISTER|pcId|SIMULATED
    private boolean register(String line) {
        String[] parts = (line == null) ? new String[0] : line.split("\\|");
        if (parts.length != 3 || !parts[0].equals("REGISTER") || parts[1].trim().isEmpty()) {
            sendLine("ERROR|Expected REGISTER|pcId|REAL or SIMULATED");
            return false;
        }
        synchronized (this) {
            pcId = parts[1].trim();
            simulated = parts[2].equals("SIMULATED");
            online = true;
            lastHeartbeat = System.currentTimeMillis();
        }
        if (!server.registerClient(this)) {
            markOffline();
            sendLine("ERROR|PC ID " + parts[1].trim() + " is already in use");
            return false;
        }
        sendLine("WELCOME");
        return true;
    }

    /** Sends one line to this PC. Called by this thread and by the GUI thread. */
    synchronized void sendLine(String line) {
        out.println(line);
    }

    /** Called by the UDP thread. Returns true if the PC was "not responding" until now. */
    synchronized boolean updateUsage(double cpu, double ram, double disk) {
        this.cpu = cpu;
        this.ram = ram;
        this.disk = disk;
        lastHeartbeat = System.currentTimeMillis();
        boolean wasNotResponding = notResponding;
        notResponding = false;
        return wasNotResponding;
    }

    /** Called by the timeout thread. Returns true only at the moment the PC becomes "not responding". */
    synchronized boolean checkTimeout(long timeoutMs) {
        if (online && !notResponding && System.currentTimeMillis() - lastHeartbeat > timeoutMs) {
            notResponding = true;
            return true;
        }
        return false;
    }

    /** Returns true if the high-usage state changed. */
    synchronized boolean setHighUsage(boolean high) {
        boolean changed = (high != highUsage);
        highUsage = high;
        return changed;
    }

    /** Returns true if the PC was online until now. */
    private synchronized boolean markOffline() {
        boolean wasOnline = online;
        online = false;
        return wasOnline;
    }

    synchronized boolean isOnline() {
        return online;
    }

    synchronized String getPcId() {
        return pcId;
    }

    String getAddress() {
        return socket.getInetAddress().getHostAddress();
    }

    /** PC ID, CPU, RAM, disk, status, data source: one row of the dashboard table. */
    synchronized String[] getTableRow() {
        String status = !online ? "OFFLINE" : (notResponding ? "NOT RESPONDING" : "ONLINE");
        return new String[] {pcId, format(cpu), format(ram), format(disk), status,
                simulated ? "SIMULATED" : "Real"};
    }

    private static String format(double value) {
        return String.format("%.1f", value);
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            // already closed
        }
    }
}
