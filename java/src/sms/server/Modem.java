package sms.server;

import com.fazecast.jSerialComm.SerialPort;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Low level AT-command access to the GSM dongle over its serial (COM) port.
 * Port of the "Serial port" section of sms_modem.ps1, built on jSerialComm.
 *
 * Not thread safe - SmsSender serialises all access.
 */
public final class Modem implements AutoCloseable {

    private static final Pattern CDS = Pattern.compile("\\+CDS:\\s*(\\d+)\\s*\\r?\\n\\s*([0-9A-Fa-f]+)\\r?\\n");
    private static final Pattern FINAL_RESULT =
        Pattern.compile("(OK|\\+CM[SE] ERROR:[^\\r\\n]*|ERROR)\\r");

    private final SerialPort port;
    private final StringBuilder buf = new StringBuilder();
    private Consumer<Pdu.StatusReport> reportHandler = r -> {};

    public Modem(String portName, int baud) {
        port = SerialPort.getCommPort(portName);
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        port.setDTR();
        port.setRTS();
    }

    public String name() { return port.getSystemPortName(); }

    /** @return false if the port could not be opened (in use by Mobile Partner, wrong COM number, ...) */
    public boolean open() { return port.openPort(); }

    @Override public void close() { port.closePort(); }

    /** Called for every delivery report (+CDS) seen while reading the modem. */
    public void onStatusReport(Consumer<Pdu.StatusReport> handler) { this.reportHandler = handler; }

    // ---------------- I/O ----------------

    void write(String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        port.writeBytes(b, b.length);
    }

    /** Reads whatever the modem has produced so far and strips out delivery reports. */
    void pump() {
        int avail = port.bytesAvailable();
        if (avail > 0) {
            byte[] b = new byte[avail];
            int n = port.readBytes(b, avail);
            if (n > 0) buf.append(new String(b, 0, n, StandardCharsets.ISO_8859_1));
        }

        Matcher m;
        while ((m = CDS.matcher(buf)).find()) {
            int len = Integer.parseInt(m.group(1));
            String hex = m.group(2);
            buf.delete(m.start(), m.end());
            Pdu.StatusReport r = Pdu.parseStatusReport(len, hex);
            if (r != null) reportHandler.accept(r);
        }
    }

    void clearBuffer() {
        pump();
        buf.setLength(0);
    }

    /** Polls the modem until {@code pattern} appears or the timeout passes. Consumes up to the match. */
    Matcher waitFor(Pattern pattern, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            pump();
            Matcher m = pattern.matcher(buf);
            if (m.find()) {
                // copy the match before we mutate the buffer
                Matcher result = pattern.matcher(buf.substring(m.start(), m.end()));
                result.find();
                buf.delete(0, m.end());
                return result;
            }
            sleep(100);
        }
        return null;
    }

    /** Sends an AT command and returns true only if the modem answered OK. */
    boolean command(String cmd, long timeoutMs) {
        clearBuffer();
        write(cmd + "\r");
        Matcher m = waitFor(FINAL_RESULT, timeoutMs);
        return m != null && "OK".equals(m.group(1));
    }

    boolean command(String cmd) { return command(cmd, 5000); }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Lists COM ports visible to the OS - handy when the configured port is wrong. */
    public static String availablePorts() {
        StringBuilder sb = new StringBuilder();
        for (SerialPort p : SerialPort.getCommPorts()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSystemPortName()).append(" (").append(p.getDescriptivePortName()).append(')');
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
