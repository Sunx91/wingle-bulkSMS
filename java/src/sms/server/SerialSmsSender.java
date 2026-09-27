package sms.server;

import sms.common.Job;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a batch of messages through a "stick mode" USB dongle that exposes an AT command COM port.
 * Port of the "Modem setup / Send messages / Wait for delivery reports" sections of sms_modem.ps1.
 *
 * {@link #runBatch} is synchronized: only one client batch talks to the modem at a time,
 * other connections simply wait their turn.
 */
public final class SerialSmsSender implements SmsSender {

    private static final Pattern PROMPT = Pattern.compile(">");
    private static final Pattern CMGS_RESULT =
        Pattern.compile("\\+CMGS:\\s*(\\d+)|\\+CM[SE] ERROR:\\s*[^\\r\\n]*|ERROR");

    private final String portName;
    private final int baud;
    private final int defaultWaitSeconds;

    public SerialSmsSender(String portName, int baud, int defaultWaitSeconds) {
        this.portName = portName;
        this.baud = baud;
        this.defaultWaitSeconds = defaultWaitSeconds;
    }

    @Override public String describe() { return portName; }

    /** Opens the modem, sends every job, waits for delivery reports, closes the modem. */
    @Override
    public synchronized void runBatch(List<Job> jobs, int waitSeconds, Listener out) {
        if (waitSeconds <= 0) waitSeconds = defaultWaitSeconds;

        Map<Integer, Integer> refMap = new HashMap<>();   // modem message reference -> job index
        Set<Integer> done = new HashSet<>();               // job index -> final status received

        try (Modem modem = new Modem(portName, baud)) {
            if (!modem.open()) {
                out.log("Serial ports visible to the server: " + Modem.availablePorts());
                out.fatal("Cannot open " + portName + " - close Mobile Partner / check COM number");
                return;
            }

            modem.onStatusReport(r -> {
                Integer idx = refMap.get(r.messageRef());
                if (idx == null) return;                       // not ours (older batch)
                String status = Pdu.describeStatus(r.status());
                if (status == null) return;                    // network still trying
                if (done.add(idx)) out.delivery(idx, status);
            });

            // ---------------- Modem setup ----------------
            if (!modem.command("AT")) {
                out.fatal("Modem not responding on " + portName + " - try the other dongle COM port");
                return;
            }
            modem.command("ATE0");
            if (!modem.command("AT+CMGF=0")) {
                out.fatal("Modem rejected PDU mode (AT+CMGF=0)");
                return;
            }
            boolean reports = modem.command("AT+CNMI=2,1,0,1,0");
            out.log(reports
                ? "Modem ready - PDU mode (delivery reports ON)."
                : "Modem ready - PDU mode (this port does not support delivery reports).");

            // ---------------- Send messages ----------------
            for (Job job : jobs) {
                Pdu.Encoded enc = Pdu.encodeSubmit(job.phone(), job.message());

                modem.clearBuffer();
                modem.write("AT+CMGS=" + enc.tpduLength() + "\r");

                if (modem.waitFor(PROMPT, 10_000) == null) {
                    modem.write("\u001B");                     // ESC - abort
                    out.sent(job.index(), false, "No prompt from modem");
                    continue;
                }

                modem.write(enc.hex() + "\u001A");             // PDU + Ctrl-Z
                Matcher r = modem.waitFor(CMGS_RESULT, 60_000);

                if (r == null) {
                    out.sent(job.index(), false, "Timeout (no reply from network)");
                } else if (r.group(1) != null) {
                    refMap.put(Integer.parseInt(r.group(1)), job.index());
                    out.sent(job.index(), true, null);
                } else {
                    out.sent(job.index(), false, r.group().trim());
                }
                Modem.sleep(1000);                              // let the modem settle between messages
            }

            // ---------------- Wait for delivery reports ----------------
            if (reports && !refMap.isEmpty()) {
                out.log("Waiting up to " + waitSeconds + " seconds for delivery reports...");
                long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
                while (System.currentTimeMillis() < deadline && done.size() < refMap.size()) {
                    modem.pump();
                    Modem.sleep(500);
                }
            }

            for (int idx : refMap.values()) {
                if (!done.contains(idx)) out.delivery(idx, "NO REPORT");
            }
        } catch (RuntimeException e) {
            out.fatal("Modem error: " + e);
        }
    }
}
