package labmonitor.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** Keeps the alert history in memory and appends every alert to a CSV log file. */
public class AlertManager {

    private static final int MAX_HISTORY = 1000;

    private final List<Alert> history = new ArrayList<Alert>();
    private final File logFile;

    public AlertManager(String logPath) {
        this.logFile = new File(logPath);
    }

    /** Called from several threads (client handlers, UDP listener, watchdog), so it is synchronized. */
    public synchronized void record(Alert alert) {
        history.add(alert);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        writeToLog(alert);
    }

    public synchronized List<Alert> getHistory() {
        return new ArrayList<Alert>(history);
    }

    private void writeToLog(Alert a) {
        try {
            File dir = logFile.getAbsoluteFile().getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            boolean isNew = !logFile.exists();
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8"))) {
                if (isNew) {
                    w.println("time,severity,type,pc,message");
                }
                w.println(a.timeText() + "," + a.severity + "," + a.type + "," + csv(a.pcId) + "," + csv(a.message));
                if (w.checkError()) {
                    throw new IOException("write failed for " + logFile);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not write alert log: " + e.getMessage());
        }
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
