package labmonitor.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

import labmonitor.common.Protocol;

/** Receives the UDP heartbeat datagrams of all lab PCs on a single socket. */
public class HeartbeatListener implements Runnable {

    private final LabServer server;
    private final DatagramSocket socket;

    public HeartbeatListener(LabServer server, DatagramSocket socket) {
        this.server = server;
        this.socket = socket;
    }

    @Override
    public void run() {
        byte[] buf = new byte[512];
        while (server.isRunning()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                continue; // socket closed on shutdown, or a transient error
            }
            // Use getLength(): the buffer is reused, so it may still hold bytes of an older datagram.
            String text = new String(packet.getData(), 0, packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);
            String[] f = Protocol.parse(text.trim());
            if (f.length < 7 || !Protocol.HEARTBEAT.equals(f[0])) {
                continue;
            }
            try {
                server.onHeartbeat(f[1], packet.getAddress(), Long.parseLong(f[2]), percent(f[3]), percent(f[4]),
                        percent(f[5]), Long.parseLong(f[6]));
            } catch (NumberFormatException e) {
                // malformed datagram: ignore it
            }
        }
    }

    private static double percent(String field) {
        double v = Double.parseDouble(field);
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 100) {
            throw new NumberFormatException(field);
        }
        return v;
    }
}
