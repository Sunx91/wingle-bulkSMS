package sms.common;

/*
 * Wire protocol between SmsClient and SmsServer.
 *
 * Plain text, UTF-8, one message per line, fields separated by TAB.
 * The last field may contain spaces but never a TAB or a line break.
 *
 *   Client -> Server                       Server -> Client
 *   ----------------------------------     ------------------------------------------
 *   HELLO <version>                        HELLO <serverName> <modemPort>
 *   JOB <index> <phone> <message>          LOG <text>
 *   ...                                    FATAL <text>          (modem problem, batch aborted)
 *   GO <waitSeconds>                       SENT <index> OK
 *                                          SENT <index> FAIL <error>
 *                                          DELIVERY <index> <status>
 *                                          END <sent> <failed>
 *                                          ERROR <text>          (protocol misuse)
 *
 * This replaces the JSON-per-line stdout pipe that send_sms.php read from
 * sms_modem.ps1, so the same events flow over a TCP socket instead.
 */
public final class Protocol {
    private Protocol() {}

    public static final int    VERSION      = 1;
    public static final String SERVER_NAME  = "SMS-GW/1";
    public static final int    DEFAULT_PORT = 5555;
    public static final char   SEP          = '\t';

    // client -> server
    public static final String HELLO = "HELLO";
    public static final String JOB   = "JOB";
    public static final String GO    = "GO";

    // server -> client
    public static final String LOG      = "LOG";
    public static final String FATAL    = "FATAL";
    public static final String SENT     = "SENT";
    public static final String DELIVERY = "DELIVERY";
    public static final String END      = "END";
    public static final String ERROR    = "ERROR";

    /** Joins fields with TAB after stripping any TAB / CR / LF from each field. */
    public static String line(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(SEP);
            sb.append(clean(String.valueOf(fields[i])));
        }
        return sb.toString();
    }

    /** Splits a received line into at most {@code max} fields (last one keeps remaining TABs). */
    public static String[] split(String line, int max) {
        return line.split("\t", max);
    }

    public static String clean(String s) {
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /** Escapes message text so line breaks and tabs survive the line-based protocol. */
    public static String escapeText(String s) {
        return s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n").replace("\t", "\\t");
    }

    public static String unescapeText(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default  -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
