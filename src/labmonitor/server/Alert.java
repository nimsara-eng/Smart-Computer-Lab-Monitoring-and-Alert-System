package labmonitor.server;

import java.text.SimpleDateFormat;
import java.util.Date;

/** One event shown in the alert panel and written to the alert log. */
public class Alert {

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum Type {
        CONNECTED, DISCONNECTED, HEARTBEAT_LOST, HIGH_CPU, HIGH_MEMORY, DISK_FULL, BLOCKED_APP, HELP_REQUEST
    }

    public final long time = System.currentTimeMillis();
    public final Severity severity;
    public final Type type;
    public final String pcId;
    public final String message;

    public Alert(Severity severity, Type type, String pcId, String message) {
        this.severity = severity;
        this.type = type;
        this.pcId = pcId;
        this.message = message;
    }

    public String timeText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(time));
    }

    @Override
    public String toString() {
        return "[" + timeText() + "] " + severity + " " + pcId + " " + type + " - " + message;
    }
}
