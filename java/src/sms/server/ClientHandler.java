package sms.server;

import sms.common.Job;
import sms.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serves one client connection: collects JOB lines, runs the batch on GO,
 * and streams the modem events back over the socket (see {@link Protocol}).
 */
final class ClientHandler implements Runnable, SmsSender.Listener {

    private final Socket socket;
    private final SmsSender sender;
    private final String peer;
    private PrintWriter out;
    private int sentOk, sentFail;

    ClientHandler(Socket socket, SmsSender sender) {
        this.socket = socket;
        this.sender = sender;
        this.peer = socket.getRemoteSocketAddress().toString();
    }

    @Override public void run() {
        SmsServer.log("Client connected: " + peer);
        try (socket;
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter pw = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            out = pw;
            List<Job> jobs = new ArrayList<>();
            String line;

            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = Protocol.split(line, 4);

                switch (f[0]) {
                    case Protocol.HELLO -> send(Protocol.HELLO, Protocol.SERVER_NAME, sender.describe());

                    case Protocol.JOB -> {
                        if (f.length < 4) { send(Protocol.ERROR, "JOB needs index, phone, message"); break; }
                        try {
                            jobs.add(new Job(Integer.parseInt(f[1]), f[2].trim(), Protocol.unescapeText(f[3])));
                        } catch (NumberFormatException e) {
                            send(Protocol.ERROR, "Bad job index: " + f[1]);
                        }
                    }

                    case Protocol.GO -> {
                        int wait = 0;
                        if (f.length > 1) {
                            try { wait = Integer.parseInt(f[1].trim()); } catch (NumberFormatException ignored) {}
                        }
                        if (jobs.isEmpty()) {
                            send(Protocol.ERROR, "No jobs received");
                        } else {
                            SmsServer.log(peer + ": sending " + jobs.size() + " message(s)");
                            log("Server accepted " + jobs.size() + " message(s); waiting for modem...");
                            sender.runBatch(jobs, wait, this);
                        }
                        send(Protocol.END, sentOk, sentFail);
                        return;                                    // one batch per connection
                    }

                    default -> send(Protocol.ERROR, "Unknown command: " + f[0]);
                }
            }
        } catch (IOException e) {
            SmsServer.log(peer + ": connection error - " + e.getMessage());
        } finally {
            SmsServer.log("Client disconnected: " + peer);
        }
    }

    private synchronized void send(Object... fields) {
        out.println(Protocol.line(fields));
    }

    // ---------- SmsSender.Listener -> socket ----------

    @Override public void log(String text)   { send(Protocol.LOG, text); }

    @Override public void fatal(String text) {
        SmsServer.log(peer + ": FATAL " + text);
        send(Protocol.FATAL, text);
    }

    @Override public void sent(int index, boolean ok, String error) {
        if (ok) { sentOk++;   send(Protocol.SENT, index, "OK"); }
        else    { sentFail++; send(Protocol.SENT, index, "FAIL", error); }
    }

    @Override public void delivery(int index, String status) { send(Protocol.DELIVERY, index, status); }
}
