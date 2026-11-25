package labmonitor;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Reads CPU, RAM and disk usage of this PC in percent.
 * In simulated mode it returns made-up values with a high CPU, so alerts can be shown in a demo.
 * getSystemCpuLoad() is deprecated in new Java versions but still works and also exists in Java 8.
 */
@SuppressWarnings("deprecation")
class SystemMetrics {

    private final boolean simulated;
    private final Random random = new Random();
    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    SystemMetrics(boolean simulated) {
        this.simulated = simulated;
    }

    double cpuPercent() {
        if (simulated) {
            return 92 + random.nextDouble() * 6; // 92 to 98 %
        }
        double load = os.getSystemCpuLoad(); // 0.0 to 1.0, negative if not measured yet
        return load < 0 ? 0 : load * 100;
    }

    double ramPercent() {
        if (simulated) {
            return 40 + random.nextDouble() * 20;
        }
        long total = os.getTotalPhysicalMemorySize();
        long free = os.getFreePhysicalMemorySize();
        return total == 0 ? 0 : (total - free) * 100.0 / total;
    }

    double diskPercent() {
        if (simulated) {
            return 50 + random.nextDouble() * 10;
        }
        // The drive that holds the user's home folder (C:\ on Windows, / on Linux)
        Path root = Paths.get(System.getProperty("user.home")).toAbsolutePath().getRoot();
        File drive = (root != null) ? root.toFile() : new File("/");
        long total = drive.getTotalSpace();
        return total == 0 ? 0 : (total - drive.getUsableSpace()) * 100.0 / total;
    }
}
