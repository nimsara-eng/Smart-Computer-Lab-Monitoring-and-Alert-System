package labmonitor.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import labmonitor.common.Protocol;

/** Server settings. Values come from config/server.properties, with defaults for anything missing. */
public class ServerConfig {

    public int tcpPort = Protocol.DEFAULT_TCP_PORT;
    public int udpPort = Protocol.DEFAULT_UDP_PORT;

    /** How often each agent sends a UDP heartbeat. */
    public int heartbeatIntervalSec = 2;
    /** A PC with no heartbeat for this long is reported as not responding. */
    public int heartbeatTimeoutSec = 8;
    /** How often each agent reports its running process list. */
    public int processReportSec = 10;

    public double cpuThreshold = 85;
    /** CPU must stay above the threshold for this many heartbeats before an alert is raised. */
    public int cpuConsecutive = 3;
    public double memoryThreshold = 90;
    public double diskThreshold = 90;
    /** Lower limits prevent alerts from flickering near the warning threshold. */
    public double cpuRecoveryThreshold = 80;
    public double memoryRecoveryThreshold = 85;
    public double diskRecoveryThreshold = 85;
    public int recoveryConsecutive = 3;

    /** Applications that are not allowed in the lab. Stored in lower case without ".exe". */
    public final List<String> blockedApps = new ArrayList<String>();

    public String logFile = "logs/alerts.csv";

    public ServerConfig() {
        setBlockedApps("steam.exe,epicgameslauncher.exe,valorant.exe,vlc.exe,utorrent.exe,bittorrent.exe");
    }

    public static ServerConfig load(String path) {
        ServerConfig c = new ServerConfig();
        File file = new File(path);
        if (!file.isFile()) {
            return c;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("Could not read " + path + ", using defaults: " + e.getMessage());
            return c;
        }
        c.tcpPort = intValue(p, "tcp.port", c.tcpPort);
        c.udpPort = intValue(p, "udp.port", c.udpPort);
        c.heartbeatIntervalSec = Math.max(1, intValue(p, "heartbeat.interval.sec", c.heartbeatIntervalSec));
        c.heartbeatTimeoutSec = Math.max(c.heartbeatIntervalSec + 1,
                intValue(p, "heartbeat.timeout.sec", c.heartbeatTimeoutSec));
        c.processReportSec = Math.max(1, intValue(p, "process.report.sec", c.processReportSec));
        c.cpuThreshold = intValue(p, "cpu.threshold", (int) c.cpuThreshold);
        c.cpuConsecutive = Math.max(1, intValue(p, "cpu.consecutive", c.cpuConsecutive));
        c.memoryThreshold = intValue(p, "memory.threshold", (int) c.memoryThreshold);
        c.diskThreshold = intValue(p, "disk.threshold", (int) c.diskThreshold);
        c.cpuRecoveryThreshold = recoveryValue(p, "cpu.recovery.threshold", c.cpuThreshold);
        c.memoryRecoveryThreshold = recoveryValue(p, "memory.recovery.threshold", c.memoryThreshold);
        c.diskRecoveryThreshold = recoveryValue(p, "disk.recovery.threshold", c.diskThreshold);
        c.recoveryConsecutive = Math.max(1, intValue(p, "alert.recovery.consecutive", c.recoveryConsecutive));
        c.logFile = p.getProperty("log.file", c.logFile).trim();
        if (p.getProperty("blocked.apps") != null) {
            c.setBlockedApps(p.getProperty("blocked.apps"));
        }
        return c;
    }

    private void setBlockedApps(String commaSeparated) {
        blockedApps.clear();
        for (String name : commaSeparated.split(",")) {
            String n = normalize(name);
            if (!n.isEmpty()) {
                blockedApps.add(n);
            }
        }
    }

    public boolean isBlocked(String processName) {
        return blockedApps.contains(normalize(processName));
    }

    /** "VLC.exe" and "vlc" are treated as the same application. */
    private static String normalize(String name) {
        String n = name.trim().toLowerCase();
        return n.endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
    }

    private static double recoveryValue(Properties p, String key, double warningThreshold) {
        double fallback = Math.max(0, warningThreshold - 5);
        int candidate = intValue(p, key, (int) fallback);
        if (candidate < 0 || candidate >= warningThreshold) {
            System.err.println("Invalid recovery threshold for " + key + "; using " + fallback);
            return fallback;
        }
        return candidate;
    }

    private static int intValue(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid value for " + key + ": " + v);
            return fallback;
        }
    }
}
