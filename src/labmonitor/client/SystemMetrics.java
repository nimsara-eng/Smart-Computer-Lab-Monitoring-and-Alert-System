package labmonitor.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the health of this PC: CPU, memory and disk usage and the running processes.
 *
 * In simulation mode the values are generated instead, so several "PCs" can be demonstrated
 * on one machine and alerts can be triggered on demand.
 */
public class SystemMetrics {

    private final boolean simulate;
    /** In simulation mode: fixed CPU value, or a negative number for a random walk. */
    private final double fixedCpu;
    private final List<String> extraProcesses;

    private final Random random = new Random();
    private double simCpu = 20 + random.nextInt(25);
    private double simMemory = 45 + random.nextInt(20);
    private final double simDisk = 40 + random.nextInt(35);
    private int spikeLeft;

    private final long startTime = System.currentTimeMillis();

    public SystemMetrics(boolean simulate, double fixedCpu, List<String> extraProcesses) {
        this.simulate = simulate;
        this.fixedCpu = fixedCpu;
        this.extraProcesses = extraProcesses;
    }

    public boolean isSimulated() {
        return simulate;
    }

    /** CPU usage of the whole machine, 0 to 100. */
    public synchronized double cpu() {
        if (simulate) {
            if (fixedCpu >= 0) {
                return fixedCpu;
            }
            if (spikeLeft > 0) {
                spikeLeft--;
                return 90 + random.nextInt(10);
            }
            if (random.nextInt(40) == 0) {
                spikeLeft = 6; // now and then, a burst long enough to raise a HIGH_CPU alert
            }
            simCpu = clamp(simCpu + random.nextGaussian() * 6, 3, 75);
            return simCpu;
        }
        double load = osBean().getSystemCpuLoad(); // 0.0 to 1.0, negative until the JVM has a first sample
        return load < 0 ? 0 : load * 100;
    }

    /** Physical memory in use, 0 to 100. */
    public synchronized double memory() {
        if (simulate) {
            simMemory = clamp(simMemory + random.nextGaussian() * 1.5, 25, 88);
            return simMemory;
        }
        double total = osBean().getTotalPhysicalMemorySize();
        double free = osBean().getFreePhysicalMemorySize();
        return total <= 0 ? 0 : (total - free) * 100 / total;
    }

    /** Used space on the system drive, 0 to 100. */
    public double disk() {
        if (simulate) {
            return simDisk;
        }
        Path root = Paths.get(System.getProperty("user.home")).toAbsolutePath().getRoot();
        File drive = root == null ? new File("/") : root.toFile();
        double total = drive.getTotalSpace();
        return total <= 0 ? 0 : (total - drive.getUsableSpace()) * 100 / total;
    }

    /** Seconds since the agent started. */
    public long uptimeSec() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    /** Names of the running processes, without duplicates, sorted. */
    public List<String> processes() {
        Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (simulate) {
            names.addAll(Arrays.asList("chrome.exe", "Code.exe", "explorer.exe", "javaw.exe", "WINWORD.EXE"));
        } else {
            readRealProcesses(names);
        }
        names.addAll(extraProcesses);
        return new ArrayList<String>(names);
    }

    private static void readRealProcesses(Set<String> names) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = windows ? new ProcessBuilder("tasklist", "/fo", "csv", "/nh")
                : new ProcessBuilder("ps", "-e", "-o", "comm=");
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (windows) {
                        // "chrome.exe","1234","Console","1","150,000 K"
                        int end = line.indexOf('"', 1);
                        if (!line.startsWith("\"") || end < 0) {
                            continue;
                        }
                        line = line.substring(1, end);
                    } else if (line.contains("/")) {
                        line = line.substring(line.lastIndexOf('/') + 1);
                    }
                    if (!line.isEmpty()) {
                        names.add(line.replace(';', '_'));
                    }
                }
            }
            p.waitFor();
        } catch (IOException e) {
            System.err.println("Could not read the process list: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static com.sun.management.OperatingSystemMXBean osBean() {
        return (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
