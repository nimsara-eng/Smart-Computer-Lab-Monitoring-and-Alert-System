package labmonitor.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ServerConfigTest {
    public static void main(String[] args) throws Exception {
        Path path = Files.createTempFile("lab-config-qa-", ".properties");
        try {
            Files.write(path, Arrays.asList("tcp.port=-1", "udp.port=70000", "cpu.threshold=101",
                    "memory.threshold=-2", "disk.threshold=nope", "cpu.recovery.threshold=99",
                    "heartbeat.interval.sec=0", "heartbeat.timeout.sec=0", "process.report.sec=-1",
                    "cpu.consecutive=0", "alert.recovery.consecutive=0", "blocked.apps=WINWORD.EXE"),
                    StandardCharsets.UTF_8);
            ServerConfig config = ServerConfig.load(path.toString());
            require(config.tcpPort == 5000 && config.udpPort == 5001, "invalid ports use defaults");
            require(config.cpuThreshold == 85 && config.memoryThreshold == 90 && config.diskThreshold == 90,
                    "invalid percentage thresholds use defaults");
            require(config.cpuRecoveryThreshold == 80, "invalid recovery uses safe default");
            require(config.heartbeatIntervalSec == 1 && config.heartbeatTimeoutSec == 2
                    && config.processReportSec == 1, "positive timing limits");
            require(config.cpuConsecutive == 1 && config.recoveryConsecutive == 1, "positive streak limits");
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(new Locale("tr", "TR"));
                config = ServerConfig.load(path.toString());
                require(config.isBlocked("winword.exe"), "block list works in Turkish locale");
            } finally { Locale.setDefault(previous); }
            ServerConfig defaults = ServerConfig.load(path.resolveSibling("missing-qa-config.properties").toString());
            require(defaults.tcpPort == 5000 && defaults.isBlocked("VLC.EXE"), "missing config defaults");
            System.out.println("Server configuration tests passed (7 checks)");
        } finally { Files.deleteIfExists(path); }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
