package labmonitor.server;

import java.util.Locale;
import java.util.Scanner;

/** Text mode interface for the server (started with --nogui). */
public class ServerConsole implements ServerListener {

    private final LabServer server;

    public ServerConsole(LabServer server) {
        this.server = server;
        server.addListener(this);
    }

    public void run() {
        System.out.println("Commands: list | alerts | msg <pcId> <text> | all <text> | procs <pcId> | quit");
        Scanner scan = new Scanner(System.in);
        while (scan.hasNextLine()) {
            String line = scan.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\\s+", 3);
            if ("quit".equals(p[0])) {
                server.stop();
                System.exit(0);
            } else if ("list".equals(p[0])) {
                printPcs();
            } else if ("alerts".equals(p[0])) {
                for (Alert a : server.getAlertHistory()) {
                    System.out.println(a);
                }
            } else if ("msg".equals(p[0]) && p.length == 3) {
                System.out.println(server.sendMessage(p[1], p[2]) ? "Sent." : "PC " + p[1] + " is not connected.");
            } else if ("all".equals(p[0]) && p.length >= 2) {
                String text = line.substring(3).trim();
                System.out.println("Sent to " + server.broadcast(text) + " PC(s).");
            } else if ("procs".equals(p[0]) && p.length >= 2) {
                PcInfo pc = server.getPc(p[1]);
                if (pc == null) {
                    System.out.println("Unknown PC " + p[1]);
                } else {
                    System.out.println(pc.getPcId() + " processes: " + pc.getProcesses());
                }
            } else {
                System.out.println("Unknown command.");
            }
        }
        // Standard input was closed (for example when started in the background): keep serving.
    }

    private void printPcs() {
        System.out.println(String.format("%-10s %-15s %-12s %-15s %-15s %5s %5s %5s %5s", "PC", "HOST", "USER", "IP",
                "STATUS", "CPU", "RAM", "DISK", "LOST"));
        for (PcInfo pc : server.getPcs()) {
            System.out.println(String.format(Locale.US, "%-10s %-15s %-12s %-15s %-15s %5.0f %5.0f %5.0f %5d",
                    pc.getPcId(), pc.getHostname(), pc.getUser(), pc.getAddress().getHostAddress(), pc.getStatus(),
                    pc.getCpu(), pc.getMemory(), pc.getDisk(), pc.getLost()));
        }
    }

    @Override
    public void pcUpdated(PcInfo pc) {
        // heartbeats arrive every few seconds: too noisy to print, use the "list" command instead
    }

    @Override
    public void alertRaised(Alert alert) {
        System.out.println(alert);
    }

    @Override
    public void processListReceived(PcInfo pc) {
        // available through the "procs" command
    }
}
