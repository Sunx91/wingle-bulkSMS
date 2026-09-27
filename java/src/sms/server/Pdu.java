package sms.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SMS PDU helpers (GSM 03.40). Port of Encode-Pdu / Parse-StatusReport from sms_modem.ps1.
 */
public final class Pdu {
    private Pdu() {}

    /** Result of encoding: the full hex string to send and the TPDU length for AT+CMGS. */
    public record Encoded(String hex, int tpduLength) {}

    /** Message reference and TP-Status decoded from a +CDS status report. */
    public record StatusReport(int messageRef, int status) {}

    // ASCII characters that exist only in the GSM 7-bit extension table (need 0x1B prefix)
    private static final Map<Integer, Integer> GSM_EXT = Map.of(
        (int) '^', 0x14,
        (int) '{', 0x28,
        (int) '}', 0x29,
        (int) '\\', 0x2F,
        (int) '[', 0x3C,
        (int) '~', 0x3D,
        (int) ']', 0x3E,
        (int) '|', 0x40
    );

    /** "947761..." -> "49671..." with an 'F' pad for odd lengths (semi-octet swap). */
    static String swapDigits(String d) {
        if (d.length() % 2 != 0) d += "F";
        StringBuilder o = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i += 2) {
            o.append(d.charAt(i + 1)).append(d.charAt(i));
        }
        return o.toString();
    }

    /** Text -> GSM 7-bit septets, max 160, never ending on a dangling escape. */
    static List<Integer> toSeptets(String msg) {
        List<Integer> sept = new ArrayList<>();
        for (char ch : msg.toCharArray()) {
            int c = ch;
            if      (c == '@')                  sept.add(0);
            else if (c == '$')                  sept.add(2);
            else if (c == '_')                  sept.add(0x11);
            else if (c == '`')                  sept.add((int) '\'');
            else if (GSM_EXT.containsKey(c))    { sept.add(27); sept.add(GSM_EXT.get(c)); }
            else if (c >= 32 && c <= 126)       sept.add(c);
            else if (c == '\n' || c == '\r')    sept.add(c);
            else                                sept.add((int) '?');
        }
        while (sept.size() > 160) sept.remove(sept.size() - 1);
        if (!sept.isEmpty() && sept.get(sept.size() - 1) == 27) sept.remove(sept.size() - 1);
        return sept;
    }

    /** Packs 7-bit septets into 8-bit octets, LSB first. */
    static byte[] pack(List<Integer> sept) {
        List<Byte> out = new ArrayList<>();
        long bits = 0;
        int nbits = 0;
        for (int s : sept) {
            bits |= ((long) s) << nbits;
            nbits += 7;
            while (nbits >= 8) {
                out.add((byte) (bits & 0xFF));
                bits >>>= 8;
                nbits -= 8;
            }
        }
        if (nbits > 0) out.add((byte) (bits & 0xFF));
        byte[] arr = new byte[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    /**
     * Builds an SMS-SUBMIT PDU with delivery report requested.
     * @param phone destination, "+9477..." (international) or national digits
     */
    public static Encoded encodeSubmit(String phone, String msg) {
        boolean intl = phone.startsWith("+");
        String num  = intl ? phone.substring(1) : phone;
        String type = intl ? "91" : "81";

        List<Integer> sept = toSeptets(msg);
        String ud = hex(pack(sept));

        // 31 = SMS-SUBMIT + validity period (relative) + status report request
        String tpdu = "31" + "00"
                    + String.format("%02X", num.length()) + type + swapDigits(num)
                    + "00" + "00" + "A7"
                    + String.format("%02X", sept.size()) + ud;

        return new Encoded("00" + tpdu, tpdu.length() / 2);   // 00 = use SMSC stored in modem
    }

    /**
     * Decodes an SMS-STATUS-REPORT PDU from a +CDS unsolicited result.
     * @param len the length announced by the modem (TPDU bytes, excluding SMSC header)
     * @return message reference and TP-Status, or null if the PDU can't be parsed
     */
    public static StatusReport parseStatusReport(int len, String hexPdu) {
        try {
            int n = hexPdu.length() / 2;
            int[] b = new int[n];
            for (int i = 0; i < n; i++) b[i] = Integer.parseInt(hexPdu.substring(i * 2, i * 2 + 2), 16);

            int p = 0;
            if (n != len) p = 1 + b[0];          // skip SMSC address if present
            p++;                                 // first octet (MTI etc.)
            int mr = b[p++];                     // TP-MR
            int raLen = b[p];                    // TP-RA length in digits
            p += 2 + (raLen + 1) / 2;            // len + type + digits
            p += 14;                             // TP-SCTS + TP-DT (7 bytes each)
            return new StatusReport(mr, b[p]);   // TP-ST
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Human readable delivery status for a TP-Status value, or null if the network is still trying. */
    public static String describeStatus(int st) {
        if (st < 32) return "DELIVERED";
        if (st < 64) return null;                // temporary error, network still trying
        return "NOT DELIVERED (code " + st + ")";
    }
}
