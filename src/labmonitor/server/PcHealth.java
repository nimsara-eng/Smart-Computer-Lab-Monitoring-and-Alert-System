package labmonitor.server;

/** Derives a readable health label from current connectivity and system metrics. */
public final class PcHealth {
    private PcHealth() { }

    public enum Level { HEALTHY, WARNING, CRITICAL, OFFLINE, WAITING }

    public static Level evaluate(PcInfo pc, ServerConfig config) {
        PcInfo.Status status = pc.getStatus();
        if (status == PcInfo.Status.OFFLINE) return Level.OFFLINE;
        if (status == PcInfo.Status.NOT_RESPONDING) return Level.CRITICAL;
        if (pc.getReceived() == 0) return Level.WAITING;
        if (pc.getCpu() >= config.cpuThreshold || pc.getMemory() >= config.memoryThreshold
                || pc.getDisk() >= config.diskThreshold || status == PcInfo.Status.ALERT) {
            return Level.CRITICAL;
        }
        // Early warning at 80% of each configured critical threshold.
        if (pc.getCpu() >= config.cpuThreshold * 0.8
                || pc.getMemory() >= config.memoryThreshold * 0.8
                || pc.getDisk() >= config.diskThreshold * 0.8) {
            return Level.WARNING;
        }
        return Level.HEALTHY;
    }
}
