package sms.server;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xml.sax.InputSource;

/**
 * HTTP client for the ASR/Marvell "LTE Wireless Router" firmware used by the Dialog 4G wingle
 * (USB VID_1286&amp;PID_4E31, web UI at http://192.168.0.1).
 *
 * The device speaks XML over {@code xml_action.cgi} and protects writes with a home-grown
 * HTTP Digest scheme. Everything here was derived from the device's own JavaScript
 * (js/base/utils.js, js/base/ajax_calls.js, js/panel/SMS/SMS.js) so the requests are
 * byte-for-byte what the browser UI sends.
 *
 * Not thread safe - {@link WingleSmsSender} serialises access.
 */
public final class WingleClient {

    /** One entry of the device's SMS boxes. */
    public record BoxItem(String id, String number, String text, String time, int status) {}

    /** One entry of {@code sms_report_status_list}. */
    public record Report(String number, int status) {}

    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"US-ASCII\"?> ";
    private static final String CGI_URI  = "/cgi/xml_action.cgi";     // uri= value the firmware expects
    private static final Pattern HTTP_OK = Pattern.compile("\\b200 OK\\b");
    private static final Pattern CMD_STATUS = Pattern.compile("<sms_cmd_status_result>(\\d+)</sms_cmd_status_result>");
    private static final Pattern SENT_COUNT = Pattern.compile("<sms_nv_send_num>(\\d+)</sms_nv_send_num>");
    private static final Pattern LOGIN_STATUS = Pattern.compile("<login_status>([^<]*)</login_status>");
    private static final Pattern SMS_RESULT = Pattern.compile("<sms_result>([^<]*)</sms_result>");

    /** Result of one SEND_SMS. {@code sentBoxCount} is how many messages the device currently has stored. */
    public record SendResult(boolean ok, String error, int sentBoxCount) {}

    private final String base;          // http://192.168.0.1
    private final String user, pass;
    private final HttpClient http;

    // digest session state
    private String realm, nonce, qop, cnonce, ha1;
    private int nc;

    public WingleClient(String host, String user, String pass) {
        this.base = host.startsWith("http") ? host : "http://" + host;
        this.user = user;
        this.pass = pass;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public String host() { return base.replaceFirst("^https?://", ""); }

    // ---------------- login ----------------

    /** Obtains the digest challenge from /login.cgi and authenticates. */
    public void login() throws IOException {
        HttpResponse<String> ch = send(HttpRequest.newBuilder(URI.create(base + "/login.cgi"))
            .header("Pragma", "no-cache").GET().build());
        String www = ch.headers().firstValue("WWW-Authenticate").orElse("");
        realm = attr(www, "realm");
        nonce = attr(www, "nonce");
        qop   = attr(www, "qop");
        if (realm.isEmpty() || nonce.isEmpty()) {
            throw new IOException("No Digest challenge from " + base + "/login.cgi (HTTP " + ch.statusCode() + ")");
        }
        if (qop.isEmpty()) qop = "auth";

        cnonce = md5(ThreadLocalRandom.current().nextInt(100001) + "" + System.currentTimeMillis()).substring(0, 16);
        ha1 = md5(user + ":" + realm + ":" + pass);
        nc = 0;

        // login.cgi wants the digest of GET /cgi/protected.cgi in the query string ...
        String loginResp = md5(ha1 + ":" + nonce + ":00000001:" + cnonce + ":" + qop + ":" + md5("GET:/cgi/protected.cgi"));
        String url = base + "/login.cgi?Action=Digest&username=" + enc(user) + "&realm=" + enc(realm)
                   + "&nonce=" + enc(nonce) + "&response=" + loginResp + "&qop=" + qop
                   + "&cnonce=" + cnonce + "&temp=marvell";
        // ... plus a normal Authorization header for /cgi/xml_action.cgi
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", authHeader("GET"))
            .header("Pragma", "no-cache").GET().build());
        if (r.statusCode() != 200 || !HTTP_OK.matcher(r.body()).find()) {
            throw new IOException("Wingle login failed for user '" + user + "' (check password in the web UI)");
        }
    }

    private String authHeader(String method) {
        nc++;
        String ncs = String.format("%08x", nc);
        String response = md5(ha1 + ":" + nonce + ":" + ncs + ":" + cnonce + ":" + qop + ":" + md5(method + ":" + CGI_URI));
        return "Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\"" + nonce
             + "\", uri=\"" + CGI_URI + "\", response=\"" + response + "\", qop=" + qop
             + ", nc=" + ncs + ", cnonce=\"" + cnonce + "\"";
    }

    // ---------------- raw XML API ----------------

    /** GET xml_action.cgi?method=get&amp;module=duster&amp;file=NAME */
    public Document get(String file) throws IOException {
        HttpResponse<String> r = send(HttpRequest.newBuilder(
                URI.create(base + "/xml_action.cgi?method=get&module=duster&file=" + file))
            .header("Authorization", authHeader("GET")).GET().build());
        return parse(r.body());
    }

    /** POST xml_action.cgi?method=set&amp;module=duster&amp;file=NAME with an RGW document. */
    public Document set(String file, Map<String, String> fields, Duration timeout) throws IOException {
        return parse(setRaw(file, fields, timeout));
    }

    /** Same as {@link #set} but returns the raw XML so the caller can skip DOM parsing. */
    public String setRaw(String file, Map<String, String> fields, Duration timeout) throws IOException {
        String body = XML_HEAD + buildRgw(fields);
        HttpResponse<String> r = send(HttpRequest.newBuilder(
                URI.create(base + "/xml_action.cgi?method=set&module=duster&file=" + file))
            .timeout(timeout)
            .header("Authorization", authHeader("POST"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Connection", "keep-alive")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.US_ASCII)).build());
        String xml = r.body();
        if ("UNAUTHORIZED".equals(group(LOGIN_STATUS, xml))) {
            throw new IOException("Wingle rejected the request: UNAUTHORIZED (session expired?)");
        }
        return xml;
    }

    /**
     * Turns {"RGW/message/flag/message_flag": "SEND_SMS", ...} into nested XML,
     * same as g_objXML.createXML() in the firmware's xml_helper.js.
     */
    static String buildRgw(Map<String, String> fields) {
        // Build a tiny tree in insertion order, then serialise.
        Node root = new Node("RGW");
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String[] path = e.getKey().split("/");
            Node cur = root;
            for (int i = 1; i < path.length; i++) {          // path[0] == RGW
                cur = cur.child(path[i]);
            }
            cur.value = e.getValue();
        }
        StringBuilder sb = new StringBuilder();
        root.write(sb);
        return sb.toString();
    }

    private static final class Node {
        final String name;
        final List<Node> children = new ArrayList<>();
        String value;
        Node(String name) { this.name = name; }
        Node child(String n) {
            for (Node c : children) if (c.name.equals(n)) return c;
            Node c = new Node(n); children.add(c); return c;
        }
        void write(StringBuilder sb) {
            sb.append('<').append(name).append('>');
            if (value != null) sb.append(escape(value));
            for (Node c : children) c.write(sb);
            sb.append("</").append(name).append('>');
        }
    }

    // ---------------- SMS operations ----------------

    /** Current value of &lt;sms_setting&gt;/&lt;status_report&gt; ("1" = delivery reports on). */
    public boolean deliveryReportsEnabled() throws IOException {
        return "1".equals(text(get("message"), "status_report"));
    }

    /** Sets the delivery-report flag, preserving the other SMS settings (mirrors SMSSettings.js). */
    public void setDeliveryReports(boolean on) throws IOException {
        Document cur = get("message");
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("RGW/message/flag/message_flag",           "SET_SMS_CENTER");
        f.put("RGW/message/sms_setting/status_report",   on ? "1" : "0");
        f.put("RGW/message/sms_setting/sms_center",      text(cur, "sms_center"));
        f.put("RGW/message/sms_setting/save_time",       text(cur, "save_time"));
        f.put("RGW/message/sms_setting/save_location",   text(cur, "save_location"));
        f.put("RGW/message/sms_setting/sms_over_cs",     text(cur, "sms_over_cs"));
        set("message", f, Duration.ofSeconds(30));
    }

    /**
     * Sends one SMS. The firmware blocks until the SMS centre accepts it
     * ({@code sms_cmd_status_result} 3 = sent) — typically ~3 seconds on Dialog.
     */
    public SendResult sendSms(String phone, String message) throws IOException {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("RGW/message/flag/message_flag",              "SEND_SMS");
        f.put("RGW/message/flag/sms_cmd",                   "4");
        f.put("RGW/message/send_save_message/contacts",     phone);
        f.put("RGW/message/send_save_message/content",      ucs2Hex(message));
        f.put("RGW/message/send_save_message/encode_type",  isGsm7(message) ? "GSM7_default" : "UNICODE");
        f.put("RGW/message/send_save_message/sms_time",     smsTime());
        String xml = setRaw("message", f, Duration.ofSeconds(90));
        String result = group(CMD_STATUS, xml);
        int box = parseIntOr(group(SENT_COUNT, xml), 0);
        if ("3".equals(result)) return new SendResult(true, null, box);
        String detail = group(SMS_RESULT, xml);
        return new SendResult(false,
            "Wingle send failed (status " + (result.isEmpty() ? "?" : result)
                + (detail.isEmpty() ? "" : ", " + detail) + ")",
            box);
    }

    /** Reads the whole Sent box (all pages of 10, newest first). */
    public List<BoxItem> sentBox() throws IOException {
        List<BoxItem> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int page = 1; page <= 20; page++) {                // box holds 50 -> at most 5 pages
            Map<String, String> f = new java.util.LinkedHashMap<>();
            f.put("RGW/message/flag/message_flag",        "GET_SENT_SMS_LOCAL");
            f.put("RGW/message/get_message/page_number",  String.valueOf(page));
            set("message", f, Duration.ofSeconds(30));
            Document d = get("message");
            NodeList nl = d.getElementsByTagName("Item");
            int added = 0;
            for (int i = 0; i < nl.getLength(); i++) {
                Element it = (Element) nl.item(i);
                String id = childText(it, "index");
                if (!seen.add(id)) continue;
                items.add(new BoxItem(
                    id,
                    ucs2Decode(childText(it, "from")),
                    ucs2Decode(childText(it, "subject")),
                    childText(it, "received"),
                    parseIntOr(childText(it, "status"), -1)));
                added++;
            }
            if (added == 0) break;
        }
        return items;
    }

    /** Deletes messages from the Sent box so it never fills up (capacity is only 50). */
    public void deleteSent(List<String> ids) throws IOException {
        if (ids.isEmpty()) return;
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("RGW/message/flag/message_flag",              "DELETE_SMS");
        f.put("RGW/message/flag/sms_cmd",                   "6");
        f.put("RGW/message/get_message/tags",               "2");     // sent
        f.put("RGW/message/get_message/mem_store",          "1");     // device memory
        f.put("RGW/message/set_message/delete_message_id",  String.join(",", ids) + ",");
        set("message", f, Duration.ofSeconds(30));
    }

    /** Delivery reports the device currently holds (sms_report_status_list). */
    public List<Report> deliveryReports() throws IOException {
        Document d = get("message");
        List<Report> out = new ArrayList<>();
        NodeList lists = d.getElementsByTagName("sms_report_status_list");
        for (int l = 0; l < lists.getLength(); l++) {
            NodeList nl = ((Element) lists.item(l)).getElementsByTagName("Item");
            for (int i = 0; i < nl.getLength(); i++) {
                Element it = (Element) nl.item(i);
                out.add(new Report(childText(it, "submit_num"), parseIntOr(childText(it, "status"), -1)));
            }
        }
        return out;
    }

    /** SIM present according to the "wan" document (sim_status 0 = OK). */
    public String simStatus() throws IOException {
        return text(get("wan"), "sim_status");
    }

    // ---------------- helpers ----------------

    private HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while talking to the wingle", e);
        }
    }

    private static Document parse(String xml) throws IOException {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IOException("Wingle returned invalid XML: " + abbreviate(xml), e);
        }
    }

    static String text(Document d, String tag) {
        NodeList nl = d.getElementsByTagName(tag);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent().trim();
    }

    private static String childText(Element e, String tag) {
        NodeList nl = e.getElementsByTagName(tag);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent().trim();
    }

    private static String attr(String header, String name) {
        Matcher m = Pattern.compile(name + "=\"?([^\",\\s]+)").matcher(header);
        return m.find() ? m.group(1) : "";
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** UniEncode(): every UTF-16 code unit as 4 hex digits. */
    static String ucs2Hex(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 4);
        for (char c : s.toCharArray()) sb.append(String.format("%04x", (int) c));
        return sb.toString();
    }

    static String ucs2Decode(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 4 <= hex.length(); i += 4) {
            try { sb.append((char) Integer.parseInt(hex.substring(i, i + 4), 16)); }
            catch (NumberFormatException e) { return hex; }
        }
        return sb.toString();
    }

    /** IsGSM7Code(): true when the firmware would send with the GSM 7-bit alphabet. */
    static boolean isGsm7(String s) {
        for (char c : s.toCharArray()) {
            boolean ok = (c >= 0x20 && c <= 0x7F && c != 0x60)
                      || c == 0x0A || c == 0x0D || c == 0x0C || c == 0x20AC
                      || "¡£¥§¿ÄÅÆÇÉÑÖØÜßàäåæèéìòöøùüΦΩΨΣΠΞΛΘΔΓ".indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }

    /** GetSmsTime(): "yy,M,d,H,m,s,%2B5.5" in Sri Lanka time. */
    static String smsTime() {
        ZonedDateTime t = ZonedDateTime.now(ZoneId.of("Asia/Colombo"));
        double tz = t.getOffset().getTotalSeconds() / 3600.0;
        String tzs = (tz > 0 ? "%2B" : "-") + trimZero(Math.abs(tz));
        return String.format("%02d,%d,%d,%d,%d,%d,%s",
            t.getYear() % 100, t.getMonthValue(), t.getDayOfMonth(),
            t.getHour(), t.getMinute(), t.getSecond(), tzs);
    }

    private static String trimZero(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static int parseIntOr(String s, int dflt) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return dflt; }
    }

    private static String abbreviate(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String md5(String s) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
