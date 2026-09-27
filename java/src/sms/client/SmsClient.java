package sms.client;

import sms.common.Job;
import sms.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SMS client. Reads the list file (one "NUMBER MESSAGE" per line), sends the unsent lines to
 * the SMS server over TCP and appends SENT / FAILED and the time to each line.
 * Lines already marked SENT are skipped, so it is safe to run again.
 *
 * Usage:
 *   java -cp "out;lib/*" sms.client.SmsClient [list.txt] [--host localhost] [--port 5555]
 *                                             [--template sms_template.txt]
 */
public final class SmsClient {

    static final ZoneId LK = ZoneId.of("Asia/Colombo");                  // Sri Lanka time (UTC+5:30)
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_FILE = "sms_list.txt";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int MAX_CHARS = 765;                              // wingle limit (5 x 153 GSM-7)

    static String nowLk() { return LocalDateTime.now(LK).format(TS); }
    static void say(String text) { System.out.println("[" + nowLk() + "] " + text); }

    public static void main(String[] args) throws IOException {
        String host = "localhost";
        int port = Protocol.DEFAULT_PORT;
        String file = null, templateArg = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host"     -> host = args[++i];
                case "--port"     -> port = Integer.parseInt(args[++i]);
                case "--template" -> templateArg = args[++i];
                default           -> file = args[i];
            }
        }
        if (file == null) file = DEFAULT_FILE;

        // ---------- Read list (+ optional default text) ----------
        Path path = Path.of(file).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            say("File not found: " + path);
            System.exit(1);
        }
        Path templatePath = templateArg != null
            ? Path.of(templateArg).toAbsolutePath()
            : path.resolveSibling(Template.DEFAULT_FILE);
        Template template = Template.load(templatePath);
        if (template != null) say("Default text for number-only lines: " + templatePath);

        SmsFile sms = new SmsFile(path);
        sms.load();
        say("Backup saved: " + sms.backup());
        LocalDateTime started = LocalDateTime.now(LK);

        // ---------- Build the job list ----------
        List<Job> jobs = new ArrayList<>();
        Set<Integer> processed = new HashSet<>();                        // rows handled in this run
        for (int i = 0; i < sms.rows.size(); i++) {
            SmsFile.Row r = sms.rows.get(i);
            if (r.status.equalsIgnoreCase("SENT")) continue;             // already done
            processed.add(i);

            String phone = SmsFile.cleanNumber(r.number);
            String msg = r.message;
            if (msg.isEmpty() && template != null) msg = template.render(phone);
            if (msg.codePointCount(0, msg.length()) > MAX_CHARS) {
                msg = msg.substring(0, msg.offsetByCodePoints(0, MAX_CHARS));
            }

            if (!SmsFile.VALID_NUMBER.matcher(phone).matches()) {
                r.status = "FAILED - invalid number";
                r.time = nowLk();
                continue;
            }
            if (msg.isEmpty()) {
                r.status = "FAILED - empty message";
                r.time = nowLk();
                continue;
            }
            r.status = "QUEUED";
            r.time = "";
            jobs.add(new Job(i, phone, msg));
        }
        sms.save();

        if (jobs.isEmpty()) {
            say("Nothing to send. All lines are already SENT or invalid.");
            finish(sms, processed, started);
            return;
        }
        say(jobs.size() + " message(s) to send.");

        // ---------- Talk to the SMS server ----------
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            say("Connected to SMS server " + host + ":" + port);

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            out.println(Protocol.line(Protocol.HELLO, Protocol.VERSION));
            for (Job j : jobs) {
                out.println(Protocol.line(Protocol.JOB, j.index(), j.phone(), Protocol.escapeText(j.message())));
            }
            out.println(Protocol.line(Protocol.GO, 0));

            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                if (handleEvent(sms, jobs, line)) break;                 // END received
                sms.save();                                              // update the TXT after every event
            }
        } catch (IOException e) {
            String why = "cannot reach SMS server " + host + ":" + port + " (" + e.getMessage() + ")";
            say("ERROR: " + why);
            markQueuedFailed(sms, "FAILED - " + why);
        }

        // Anything still QUEUED means the server stopped unexpectedly
        markQueuedFailed(sms, "FAILED - not sent (server disconnected)");
        finish(sms, processed, started);
    }

    /** Saves the list, prints the summary and writes the report file. */
    private static void finish(SmsFile sms, Set<Integer> processed, LocalDateTime started) {
        sms.save();

        int sent = 0, failed = 0, skipped = 0;
        for (int i = 0; i < sms.rows.size(); i++) {
            SmsFile.Row r = sms.rows.get(i);
            if (!processed.contains(i)) skipped++;
            else if (r.status.equals("SENT")) sent++;
            else if (r.status.startsWith("FAILED")) failed++;
        }
        say("Done. Sent: " + sent + " | Failed: " + failed
            + (skipped > 0 ? " | Skipped (sent earlier): " + skipped : ""));
        say("Results written to: " + sms.path());

        try {
            Path report = Report.write(sms, processed, device, started, LocalDateTime.now(LK));
            say("Summary report: " + report);
        } catch (IOException e) {
            say("WARNING: could not write the report file: " + e.getMessage());
        }
    }

    private static String device;                                         // from the server's HELLO

    /** Applies one server event to the rows. Returns true when the batch is finished. */
    private static boolean handleEvent(SmsFile sms, List<Job> jobs, String line) {
        String[] f = Protocol.split(line, 4);
        switch (f[0]) {
            case Protocol.HELLO -> {
                device = f.length > 2 ? f[2] : "?";
                say("Server " + f[1] + " using " + device);
            }

            case Protocol.LOG   -> say(f[1]);

            case Protocol.ERROR -> say("SERVER ERROR: " + f[1]);

            case Protocol.FATAL -> {
                say("ERROR: " + f[1]);
                markQueuedFailed(sms, "FAILED - " + f[1]);
            }

            case Protocol.SENT -> {
                int idx = Integer.parseInt(f[1]);
                SmsFile.Row r = sms.rows.get(idx);
                String preview = preview(jobs, idx);
                if ("OK".equals(f[2])) {
                    r.status = "SENT";
                    r.time = nowLk();
                    say("SENT      " + r.number + "  " + preview);
                } else {
                    String err = f.length > 3 ? f[3] : "unknown error";
                    r.status = "FAILED - " + err;
                    r.time = nowLk();
                    say("FAILED    " + r.number + "  " + preview + "  (" + err + ")");
                }
            }

            case Protocol.DELIVERY -> { /* delivery reports are not used */ }

            case Protocol.END -> { return true; }

            default -> say("Unknown event from server: " + line);
        }
        return false;
    }

    private static String preview(List<Job> jobs, int idx) {
        for (Job j : jobs) {
            if (j.index() == idx) {
                String m = j.message().replace('\n', ' ');
                return m.length() > 60 ? m.substring(0, 57) + "..." : m;
            }
        }
        return "";
    }

    private static void markQueuedFailed(SmsFile sms, String status) {
        for (SmsFile.Row r : sms.rows) {
            if (r.status.equals("QUEUED")) {
                r.status = status;
                r.time = nowLk();
            }
        }
    }
}
