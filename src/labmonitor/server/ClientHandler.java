package labmonitor.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import labmonitor.common.Protocol;

/** One thread per connected lab PC. Reads the PC's TCP messages until it disconnects. */
public class ClientHandler extends Thread {

    private static final int REGISTER_TIMEOUT_MS = 10000;

    private final LabServer server;
    private final Socket client;
    private final BufferedReader in;
    private final PrintWriter out;
    /** Nothing is sent to the PC before its WELCOME, which must be the first line it receives. */
    private volatile boolean ready;
    private PcInfo pc;

    public ClientHandler(LabServer server, Socket client) throws IOException {
        super("client-" + client.getRemoteSocketAddress());
        this.server = server;
        this.client = client;
        this.in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        this.out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), "UTF-8"), true);
    }

    public InetAddress getAddress() {
        return client.getInetAddress();
    }

    @Override
    public void run() {
        boolean graceful = false;
        try {
            // A client that connects but never registers must not hold a thread forever.
            client.setSoTimeout(REGISTER_TIMEOUT_MS);
            String first = in.readLine();
            String[] f = first == null ? new String[0] : Protocol.parse(first);
            if (f.length < 5 || !Protocol.REGISTER.equals(f[0]) || f[1].trim().isEmpty()) {
                write(Protocol.build(Protocol.ERROR, "Expected REGISTER"));
                return;
            }
            pc = server.register(this, f[1].trim(), f[2], f[3], f[4]);
            if (pc == null) {
                write(Protocol.build(Protocol.ERROR, "PC id " + f[1].trim() + " is already connected"));
                return;
            }
            client.setSoTimeout(0);

            ServerConfig c = server.getConfig();
            write(Protocol.build(Protocol.WELCOME, c.udpPort, c.heartbeatIntervalSec, c.processReportSec));
            ready = true;
            server.onRegistered(pc);

            String line;
            while ((line = in.readLine()) != null) {
                String[] m = Protocol.parse(line);
                if (Protocol.PROCS.equals(m[0])) {
                    server.onProcessList(pc, parseList(m.length > 1 ? m[1] : ""));
                } else if (Protocol.HELP.equals(m[0])) {
                    server.onHelpRequest(pc, m.length > 1 ? m[1] : "");
                } else if (Protocol.BYE.equals(m[0])) {
                    graceful = true;
                    break;
                }
            }
        } catch (IOException e) {
            // connection dropped: handled as a disconnect below
        } finally {
            if (pc != null) {
                server.onDisconnect(pc, this, graceful);
            }
            close();
        }
    }

    /** Sends one line to the PC. Safe to call from any thread. */
    public boolean send(String line) {
        return ready && write(line);
    }

    private boolean write(String line) {
        synchronized (out) {
            out.println(line);
            return !out.checkError();
        }
    }

    public void close() {
        try {
            client.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static List<String> parseList(String field) {
        List<String> list = new ArrayList<String>();
        for (String name : field.split(Protocol.LIST_SEPARATOR)) {
            if (!name.trim().isEmpty()) {
                list.add(name.trim());
            }
        }
        return list;
    }
}
