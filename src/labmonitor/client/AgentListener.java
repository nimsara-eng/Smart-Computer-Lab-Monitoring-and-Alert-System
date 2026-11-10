package labmonitor.client;

/** Implemented by the agent's user interfaces (window and console). */
public interface AgentListener {

    void statusChanged(boolean connected, String text);

    /** Called each time a heartbeat is sent, with the values that were reported. */
    void metricsSent(double cpu, double memory, double disk);

    void messageReceived(String from, String text);

    void alertReceived(String severity, String text);
}
