package labmonitor.server;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the server knows about one lab PC.
 *
 * The object is shared between the PC's client handler thread, the UDP listener thread,
 * the watchdog thread and the user interface, so every method is synchronized.
 */
public class PcInfo {

    public enum Status { ONLINE, ALERT, NOT_RESPONDING, OFFLINE }

    private final String pcId;
    private String hostname = "";
    private String user = "";
    private String os = "";
    private InetAddress address;
    private ClientHandler handler;

    private double cpu;
    private double memory;
    private double disk;
    private long uptimeSec;

    private long lastHeartbeat;
    private long lastSeq = -1;
    private long received;
    private long lost;
    private int cpuHighCount;

    private List<String> processes = Collections.emptyList();
    private final Set<String> blockedRunning = new TreeSet<String>();
    /** Problems that are active right now, so each one is reported once and not on every heartbeat. */
    private final EnumSet<Alert.Type> conditions = EnumSet.noneOf(Alert.Type.class);
    private final EnumMap<Alert.Type, Integer> warningSamples = new EnumMap<Alert.Type, Integer>(Alert.Type.class);
    private final EnumMap<Alert.Type, Integer> recoverySamples = new EnumMap<Alert.Type, Integer>(Alert.Type.class);

    public PcInfo(String pcId) {
        this.pcId = pcId;
    }

    synchronized void connect(ClientHandler handler, String hostname, String user, String os, InetAddress address) {
        this.handler = handler;
        this.hostname = hostname;
        this.user = user;
        this.os = os;
        this.address = address;
        this.lastHeartbeat = System.currentTimeMillis();
        this.lastSeq = -1;
        this.received = 0;
        this.lost = 0;
        this.cpuHighCount = 0;
        this.blockedRunning.clear();
        this.conditions.clear();
        this.warningSamples.clear();
        this.recoverySamples.clear();
    }

    /** Returns false if this handler was already replaced by a newer connection. */
    synchronized boolean disconnect(ClientHandler closing) {
        if (handler != closing) {
            return false;
        }
        handler = null;
        conditions.clear();
        blockedRunning.clear();
        warningSamples.clear();
        recoverySamples.clear();
        return true;
    }

    /** Accepts only newer UDP heartbeats; duplicates or delayed packets must not overwrite live metrics. */
    synchronized boolean updateHeartbeat(long seq, double cpu, double memory, double disk, long uptimeSec,
            double cpuThreshold) {
        if (seq < 0 || seq <= lastSeq || uptimeSec < 0) {
            return false;
        }
        // A gap indicates missing heartbeats (some may simply be delayed).
        if (lastSeq >= 0) {
            lost += seq - lastSeq - 1;
        }
        lastSeq = seq;
        this.cpu = cpu;
        this.memory = memory;
        this.disk = disk;
        this.uptimeSec = uptimeSec;
        this.lastHeartbeat = System.currentTimeMillis();
        this.received++;
        cpuHighCount = cpu >= cpuThreshold ? cpuHighCount + 1 : 0;
        return true;
    }

    /**
     * Samples a resource's usage with hysteresis. Returns +1 when the warning
     * begins, -1 when it has recovered, or 0 for no change. Warning and
     * recovery streaks are deliberately independent for each resource.
     */
    synchronized int updateMetricCondition(Alert.Type type, double value, double warningAt,
            double recoveredAt, int warningAfter, int recoveredAfter) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 100) {
            return 0;
        }
        if (!conditions.contains(type)) {
            recoverySamples.remove(type);
            int count = value >= warningAt ? getCount(warningSamples, type) + 1 : 0;
            if (count >= warningAfter) {
                warningSamples.remove(type);
                conditions.add(type);
                return 1;
            }
            warningSamples.put(type, count);
        } else {
            warningSamples.remove(type);
            int count = value <= recoveredAt ? getCount(recoverySamples, type) + 1 : 0;
            if (count >= recoveredAfter) {
                recoverySamples.remove(type);
                conditions.remove(type);
                return -1;
            }
            recoverySamples.put(type, count);
        }
        return 0;
    }

    private static int getCount(EnumMap<Alert.Type, Integer> counts, Alert.Type type) {
        Integer current = counts.get(type);
        return current == null ? 0 : current;
    }

    /** Switches a problem on or off. Returns true only when the state actually changed. */
    synchronized boolean setCondition(Alert.Type type, boolean active) {
        return active ? conditions.add(type) : conditions.remove(type);
    }

    /** Stores the blocked applications currently running and returns the ones that just appeared. */
    synchronized List<String> updateBlocked(List<String> found) {
        List<String> fresh = new ArrayList<String>();
        for (String name : found) {
            if (!blockedRunning.contains(name)) {
                fresh.add(name);
            }
        }
        blockedRunning.clear();
        blockedRunning.addAll(found);
        return fresh;
    }

    synchronized void setProcesses(List<String> processes) {
        this.processes = new ArrayList<String>(processes);
    }

    /** Sends one protocol line to this PC over its TCP connection. */
    boolean send(String line) {
        ClientHandler h;
        synchronized (this) {
            h = handler;
        }
        return h != null && h.send(line);
    }

    void closeConnection() {
        ClientHandler h;
        synchronized (this) {
            h = handler;
        }
        if (h != null) {
            h.close();
        }
    }

    public synchronized Status getStatus() {
        if (handler == null) {
            return Status.OFFLINE;
        }
        if (conditions.contains(Alert.Type.HEARTBEAT_LOST)) {
            return Status.NOT_RESPONDING;
        }
        return conditions.isEmpty() ? Status.ONLINE : Status.ALERT;
    }

    public String getPcId() {
        return pcId;
    }

    public synchronized boolean isConnected() {
        return handler != null;
    }

    public synchronized String getHostname() {
        return hostname;
    }

    public synchronized String getUser() {
        return user;
    }

    public synchronized String getOs() {
        return os;
    }

    public synchronized InetAddress getAddress() {
        return address;
    }

    public synchronized double getCpu() {
        return cpu;
    }

    public synchronized double getMemory() {
        return memory;
    }

    public synchronized double getDisk() {
        return disk;
    }

    public synchronized long getUptimeSec() {
        return uptimeSec;
    }

    public synchronized long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public synchronized long getReceived() {
        return received;
    }

    public synchronized long getLost() {
        return lost;
    }

    public synchronized int getCpuHighCount() {
        return cpuHighCount;
    }

    public synchronized List<String> getProcesses() {
        return new ArrayList<String>(processes);
    }

    public synchronized String getProblems() {
        return conditions.isEmpty() ? "" : conditions.toString();
    }
}
