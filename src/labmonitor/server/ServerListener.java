package labmonitor.server;

/** Implemented by the user interfaces (dashboard and console) to receive server events. */
public interface ServerListener {

    /** A PC registered, sent new data, changed status or disconnected. */
    void pcUpdated(PcInfo pc);

    void alertRaised(Alert alert);

    /** A PC sent its list of running processes. */
    void processListReceived(PcInfo pc);
}
