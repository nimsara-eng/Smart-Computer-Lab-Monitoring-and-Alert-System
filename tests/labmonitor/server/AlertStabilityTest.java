package labmonitor.server;

/** No external dependencies: checks independent warning/recovery streaks. */
public class AlertStabilityTest {

    public static void main(String[] args) {
        PcInfo pc = new PcInfo("PC-TEST");
        Alert.Type cpu = Alert.Type.HIGH_CPU;
        Alert.Type memory = Alert.Type.HIGH_MEMORY;
        Alert.Type disk = Alert.Type.DISK_FULL;

        require(sample(pc, cpu, 86, 85, 80, 3, 3) == 0, "CPU: first high sample");
        require(sample(pc, cpu, 87, 85, 80, 3, 3) == 0, "CPU: second high sample");
        require(sample(pc, cpu, 84, 85, 80, 3, 3) == 0, "CPU: spike reset");
        require(sample(pc, cpu, 90, 85, 80, 3, 3) == 0, "CPU: new first high sample");
        require(sample(pc, cpu, 90, 85, 80, 3, 3) == 0, "CPU: new second high sample");
        require(sample(pc, cpu, 90, 85, 80, 3, 3) == 1, "CPU: warning raised");
        require(pc.getProblems().contains("HIGH_CPU"), "CPU: state active");
        require(sample(pc, cpu, 82, 85, 80, 3, 3) == 0, "CPU: 82 is not enough to recover");
        require(sample(pc, cpu, 79, 85, 80, 3, 3) == 0, "CPU: first recovery sample");
        require(sample(pc, cpu, 78, 85, 80, 3, 3) == 0, "CPU: second recovery sample");
        require(sample(pc, cpu, 83, 85, 80, 3, 3) == 0, "CPU: recovery streak reset");
        require(pc.getProblems().contains("HIGH_CPU"), "CPU: still in alert");
        require(sample(pc, cpu, 80, 85, 80, 3, 3) == 0, "CPU: new first low sample");
        require(sample(pc, cpu, 79, 85, 80, 3, 3) == 0, "CPU: new second low sample");
        require(sample(pc, cpu, 80, 85, 80, 3, 3) == -1, "CPU: recovered");
        require(!pc.getProblems().contains("HIGH_CPU"), "CPU: cleared state");

        require(sample(pc, memory, 90, 90, 85, 1, 3) == 1, "Memory: immediately warns");
        require(sample(pc, disk, 94, 90, 85, 1, 3) == 1, "Disk: independently warns");
        require(sample(pc, memory, 85, 90, 85, 1, 3) == 0, "Memory: low 1");
        require(sample(pc, memory, 85, 90, 85, 1, 3) == 0, "Memory: low 2");
        require(sample(pc, memory, 85, 90, 85, 1, 3) == -1, "Memory: recovered");
        require(pc.getProblems().contains("DISK_FULL"), "Disk stays active when memory recovers");
        require(sample(pc, disk, 86, 90, 85, 1, 3) == 0, "Disk: still above recovery limit");
        require(sample(pc, disk, 84, 90, 85, 1, 3) == 0, "Disk: low 1");
        require(sample(pc, disk, 83, 90, 85, 1, 3) == 0, "Disk: low 2");
        require(sample(pc, disk, 82, 90, 85, 1, 3) == -1, "Disk: recovered");
        require(pc.getProblems().isEmpty(), "All three alerts cleared");

        require(sample(pc, memory, 91, 90, 85, 1, 3) == 1, "Memory warning again");
        require(sample(pc, memory, Double.NaN, 90, 85, 1, 3) == 0, "Invalid sample ignored");
        pc.connect(null, "host", "user", "Windows", null);
        require(pc.getProblems().isEmpty(), "Reconnect clears metric alert state");
        require(sample(pc, cpu, 95, 85, 80, 3, 3) == 0, "Reconnect starts a fresh CPU streak");

        System.out.println("Alert stability tests passed");
    }

    private static int sample(PcInfo pc, Alert.Type type, double value, double warning,
            double recovered, int raiseSamples, int clearSamples) {
        return pc.updateMetricCondition(type, value, warning, recovered, raiseSamples, clearSamples);
    }

    private static void require(boolean okay, String message) {
        if (!okay) {
            throw new AssertionError(message);
        }
    }
}
