package labmonitor.server;

/** Wakes up every second and asks the server to look for PCs whose heartbeats have stopped. */
public class Watchdog implements Runnable {

    private final LabServer server;

    public Watchdog(LabServer server) {
        this.server = server;
    }

    @Override
    public void run() {
        while (server.isRunning()) {
            server.checkHeartbeats();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
