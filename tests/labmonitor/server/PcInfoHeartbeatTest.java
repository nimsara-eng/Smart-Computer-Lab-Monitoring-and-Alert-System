package labmonitor.server;

/** Minimal regression test without external libraries; run with java -cp out. */
public class PcInfoHeartbeatTest {

    public static void main(String[] args) {
        PcInfo pc = new PcInfo("PC-TEST");
        require(pc.updateHeartbeat(0, 20, 30, 40, 100, 85), "initial heartbeat accepted");
        require(pc.getReceived() == 1 && pc.getCpu() == 20, "initial metrics stored");

        require(!pc.updateHeartbeat(0, 99, 99, 99, 101, 85), "duplicate rejected");
        require(pc.getReceived() == 1 && pc.getCpu() == 20, "duplicate did not overwrite metrics");

        require(pc.updateHeartbeat(2, 45, 35, 50, 102, 85), "newer heartbeat accepted");
        require(pc.getLost() == 1, "sequence gap recorded");
        require(!pc.updateHeartbeat(1, 98, 98, 98, 103, 85), "delayed heartbeat rejected");
        require(pc.getReceived() == 2 && pc.getCpu() == 45, "delayed heartbeat did not overwrite metrics");
        require(!pc.updateHeartbeat(-1, 100, 100, 100, 104, 85), "negative sequence rejected");
        require(pc.getReceived() == 2 && pc.getLost() == 1, "invalid heartbeat did not affect counters");

        // A newly registered session starts a new heartbeat sequence.
        pc.connect(null, "host", "user", "Windows", null);
        require(pc.updateHeartbeat(0, 25, 30, 40, 105, 85), "fresh session accepted sequence 0");
        require(pc.getReceived() == 1 && pc.getLost() == 0, "counters reset on reconnect");
        System.out.println("Heartbeat ordering tests passed");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
