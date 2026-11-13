package labmonitor.server;

import java.util.ArrayList;
import java.util.List;

/** Bounded in-memory metric samples for one PC; each heartbeat is recorded once. */
public final class PerformanceHistory {
    public static final int LIMIT = 120;
    private final List<double[]> samples = new ArrayList<double[]>();
    private long lastSequence = -1;

    public synchronized void add(long received, double cpu, double ram, double disk) {
        if (received <= 0 || received == lastSequence) return;
        // A reconnect resets the received counter; begin a fresh graph.
        if (received < lastSequence) samples.clear();
        lastSequence = received;
        samples.add(new double[] { clamp(cpu), clamp(ram), clamp(disk) });
        if (samples.size() > LIMIT) samples.remove(0);
    }

    public synchronized List<double[]> snapshot() {
        List<double[]> copy = new ArrayList<double[]>();
        for (double[] sample : samples) copy.add(sample.clone());
        return copy;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return Math.max(0, Math.min(100, value));
    }
}
