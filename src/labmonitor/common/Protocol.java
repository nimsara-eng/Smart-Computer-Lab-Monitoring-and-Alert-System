package labmonitor.common;

/**
 * The application-layer protocol shared by the server and the lab PC agents.
 *
 * Every message is one line of text. Fields are separated by '|'.
 *
 * TCP (reliable, connection oriented) carries registration, alerts and messages:
 *
 *   agent  -> server   REGISTER|pcId|hostname|user|os
 *   server -> agent    WELCOME|udpPort|heartbeatSec|processReportSec
 *   server -> agent    ERROR|reason
 *   agent  -> server   PROCS|name1;name2;...
 *   agent  -> server   HELP|text
 *   agent  -> server   BYE
 *   server -> agent    MSG|from|text
 *   server -> agent    ALERT|severity|text
 *   server -> agent    GETPROCS
 *
 * UDP (connectionless, lightweight) carries the periodic heartbeat:
 *
 *   agent  -> server   HB|pcId|seq|cpu|memory|disk|uptimeSec
 */
public final class Protocol {

    private Protocol() {
    }

    public static final int DEFAULT_TCP_PORT = 5000;
    public static final int DEFAULT_UDP_PORT = 5001;

    public static final String REGISTER = "REGISTER";
    public static final String WELCOME = "WELCOME";
    public static final String ERROR = "ERROR";
    public static final String PROCS = "PROCS";
    public static final String HELP = "HELP";
    public static final String BYE = "BYE";
    public static final String MSG = "MSG";
    public static final String ALERT = "ALERT";
    public static final String GETPROCS = "GETPROCS";
    public static final String HEARTBEAT = "HB";

    /** Separator used between process names inside a PROCS message. */
    public static final String LIST_SEPARATOR = ";";

    /** Builds one protocol line, e.g. build("MSG", "Admin", "hello") gives "MSG|Admin|hello". */
    public static String build(String type, Object... fields) {
        StringBuilder sb = new StringBuilder(type);
        for (Object field : fields) {
            sb.append('|').append(clean(String.valueOf(field)));
        }
        return sb.toString();
    }

    /** Splits a received line into its fields. Index 0 is the message type. */
    public static String[] parse(String line) {
        return line.split("\\|", -1);
    }

    /** Removes characters that would break the line based framing. */
    public static String clean(String text) {
        return text.replace('|', '/').replace('\r', ' ').replace('\n', ' ');
    }
}
