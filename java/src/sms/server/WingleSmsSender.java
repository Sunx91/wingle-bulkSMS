package sms.server;

import sms.common.Job;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends a batch through the HTTP API of the Dialog 4G wingle.
 *
 * The device Sent box holds only 50 messages and SEND_SMS fails when it is
 * full. This class empties the box at the start of a run if it is already
 * near the cap, and again during the loop every time the count reaches
 * {@link #SENT_BOX_CLEAN_AT}. The client's report file is the permanent record.
 */
public final class WingleSmsSender implements SmsSender {

    /** Wipe the device Sent box before it hits the 50-message cap. */
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

        try {
            int already = w.sentBoxCount();
            if (already >= SENT_BOX_CLEAN_AT) {
                clearSentBox(w, out);
            }
        } catch (IOException e) {
            out.log("Could not read the wingle Sent box: " + e.getMessage());
        }

        for (Job job : jobs) {
            WingleClient.SendResult r = sendOne(w, job);
            if (session != null) w = session;
            if (r == null) {
                out.sent(job.index(), false, "Wingle error: send failed");
                continue;
            }
            if (!r.ok() && r.sentBoxCount() >= SENT_BOX_CLEAN_AT) {
                clearSentBox(w, out);
                r = sendOne(w, job);
                if (r == null) {
                    out.sent(job.index(), false, "Wingle error: send failed");
                    continue;
                }
            }
            if (r.ok()) out.sent(job.index(), true, null);
            else        out.sent(job.index(), false, r.error());

            if (r.sentBoxCount() >= SENT_BOX_CLEAN_AT) {
                clearSentBox(w, out);
            }
        }
    }

    private WingleClient.SendResult sendOne(WingleClient w, Job job) {
        try {
            return w.sendSms(job.phone(), job.message());
        } catch (IOException e) {
            if (!isSessionError(e)) {
                return new WingleClient.SendResult(false, "Wingle error: " + e.getMessage(), 0);
            }
            session = null;
            try {
                return session().sendSms(job.phone(), job.message());
            } catch (IOException e2) {
                return new WingleClient.SendResult(false, "Wingle error: " + e2.getMessage(), 0);
            }
        }
    }

    private void clearSentBox(WingleClient w, Listener out) {
        try {
            List<String> ids = new ArrayList<>();
            for (WingleClient.BoxItem it : w.sentBox()) ids.add(it.id());
            if (ids.isEmpty()) return;
            w.deleteSent(ids);
            out.log("Cleared " + ids.size() + " message(s) from the wingle Sent box.");
        } catch (IOException e) {
            out.log("Could not clean the wingle Sent box: " + e.getMessage());
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
