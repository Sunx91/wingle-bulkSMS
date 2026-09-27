package sms.server;

import sms.common.Job;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends a batch through the HTTP API of the Dialog 4G wingle.
 *
 * Each SEND_SMS blocks inside the wingle until Dialog's SMS centre accepts it
 * (~3 seconds). There is no sleep in this class. Extra HTTP (SIM check, delivery
 * settings, Sent-box wipe) is done only when needed so it does not sit on the
 * critical path of a bulk run.
 */
public final class WingleSmsSender implements SmsSender {

    /** Wipe the device Sent box only when it is close to the 50-message cap. */
    private static final int SENT_BOX_CLEAN_AT = 40;

    private final String host, user, pass;
    private WingleClient session;

    public WingleSmsSender(String host, String user, String pass) {
        this.host = host;
        this.user = user;
        this.pass = pass;
    }

    @Override public String describe() { return "wingle " + host; }

    @Override
    public synchronized void runBatch(List<Job> jobs, int waitSeconds, Listener out) {
        WingleClient w;
        try {
            w = session();
        } catch (IOException e) {
            out.fatal("Cannot reach wingle at " + host + " - " + e.getMessage());
            return;
        }
        out.log("Wingle ready at " + host + ".");

        int lastBox = 0;
        for (Job job : jobs) {
            WingleClient.SendResult r;
            try {
                r = w.sendSms(job.phone(), job.message());
            } catch (IOException e) {
                if (isSessionError(e)) {
                    session = null;
                    try {
                        w = session();
                        r = w.sendSms(job.phone(), job.message());
                    } catch (IOException e2) {
                        out.sent(job.index(), false, "Wingle error: " + e2.getMessage());
                        continue;
                    }
                } else {
                    out.sent(job.index(), false, "Wingle error: " + e.getMessage());
                    continue;
                }
            }
            lastBox = r.sentBoxCount();
            if (r.ok()) out.sent(job.index(), true, null);
            else        out.sent(job.index(), false, r.error());
        }

        if (lastBox >= SENT_BOX_CLEAN_AT) {
            try {
                List<String> ids = new ArrayList<>();
                for (WingleClient.BoxItem it : w.sentBox()) ids.add(it.id());
                if (!ids.isEmpty()) {
                    w.deleteSent(ids);
                    out.log("Cleared " + ids.size() + " message(s) from the wingle Sent box.");
                }
            } catch (IOException e) {
                out.log("Could not clean the wingle Sent box: " + e.getMessage());
            }
        }
    }

    private WingleClient session() throws IOException {
        if (session == null) {
            WingleClient w = new WingleClient(host, user, pass);
            w.login();
            session = w;
        }
        return session;
    }

    private static boolean isSessionError(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("UNAUTHORIZED") || m.contains("login"));
    }
}
