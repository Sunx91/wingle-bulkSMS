package sms.server;

import sms.common.Job;

import java.util.List;

/**
 * Something that can push a batch of messages to the mobile network and report progress.
 * Implementations: {@link SerialSmsSender} (AT commands over a COM port) and
 * {@link WingleSmsSender} (HTTP API of an ASR/Marvell "LTE Wireless Router" wingle).
 */
public interface SmsSender {

    /** Same four event types the PowerShell helper emitted as JSON lines. */
    interface Listener {
        void log(String text);
        void fatal(String text);
        void sent(int index, boolean ok, String error);
        void delivery(int index, String status);
    }

    /** Short description for logs / HELLO, e.g. "COM10" or "wingle 192.168.0.1". */
    String describe();

    /**
     * Sends every job and reports delivery status. Implementations must be safe to call
     * from several threads (only one batch may talk to the device at a time).
     * @param waitSeconds how long to wait for delivery reports; &lt;= 0 uses the server default
     */
    void runBatch(List<Job> jobs, int waitSeconds, Listener out);
}
