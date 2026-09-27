package sms.server;

import sms.common.Protocol;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * SMS gateway server. Owns the SMS device and accepts TCP clients that submit
 * batches of messages (see {@link Protocol}). Replaces sms_modem.ps1.
 *
 * Two device back-ends:
 *   --wingle 192.168.0.1 [--user admin --pass admin]
 *        Dialog 4G wingle (ASR/Marvell "LTE Wireless Router") over its HTTP API. Default.
 *   --com COM10 [--baud 9600] [--wait 120]
 *        Classic "stick mode" dongle with an AT command COM port (--wait = delivery report wait).
 *
 * Common: [--listen 5555]
 */
public final class SmsServer {

    private static final ZoneId LK = ZoneId.of("Asia/Colombo");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static void log(String text) {
        System.out.println("[" + LocalDateTime.now(LK).format(TS) + "] " + text);
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: SmsServer [--wingle 192.168.0.1 --user admin --pass admin]");
        System.err.println("                 [--com COM10 --baud 9600 --wait 120] [--listen 5555]");
        System.exit(2);
    }

    public static void main(String[] args) throws IOException {
        String wingle = null, user = "admin", pass = "admin";
        String com = null;
        int baud = 9600;
        int listen = Protocol.DEFAULT_PORT;
        int wait = 120;

        for (int i = 0; i < args.length; i++) {
            if (i + 1 >= args.length) usage("Missing value for " + args[i]);
            String v = args[++i];
            switch (args[i - 1]) {
                case "--wingle"  -> wingle = v;
                case "--user"    -> user = v;
                case "--pass"    -> pass = v;
                case "--com"     -> com = v;
                case "--baud"    -> baud = Integer.parseInt(v);
                case "--listen"  -> listen = Integer.parseInt(v);
                case "--wait"    -> wait = Integer.parseInt(v);
                default          -> usage("Unknown option: " + args[i - 1]);
            }
        }
        if (wingle != null && com != null) usage("Use either --wingle or --com, not both");
        if (wingle == null && com == null) wingle = "192.168.0.1";

        SmsSender sender = (com != null)
            ? new SerialSmsSender(com, baud, wait)
            : new WingleSmsSender(wingle, user, pass);

        try (ServerSocket server = new ServerSocket(listen)) {
            log("SMS server listening on TCP port " + listen
                + " | device: " + sender.describe()
                + (com != null ? " @ " + baud + " | report wait " + wait + "s" : " (user " + user + ")"));
            if (com != null) log("Serial ports visible now: " + Modem.availablePorts());

            while (true) {
                Socket client = server.accept();
                Thread t = new Thread(new ClientHandler(client, sender), "client-" + client.getPort());
                t.setDaemon(true);
                t.start();
            }
        }
    }
}
