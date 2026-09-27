package sms.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The SMS list (sms_list.txt). One line per SMS:
 *
 *     NUMBER MESSAGE TEXT WITH SPACES
 *
 * The number is everything up to the first space (or tab); the rest of the line is the message.
 * After sending, the result is appended behind a TAB:
 *
 *     NUMBER MESSAGE TEXT WITH SPACES<TAB>SENT<TAB>2026-09-27 17:10:00
 *     NUMBER MESSAGE TEXT WITH SPACES<TAB>FAILED - reason<TAB>2026-09-27 17:10:00
 *
 * Lines whose status is SENT are skipped on the next run. A line with only a number sends
 * the text of sms_template.txt (if that file exists).
 */
final class SmsFile {

    /** One line of the list. */
    static final class Row {
        String number = "", message = "", status = "", time = "";
    }

    static final Pattern VALID_NUMBER = Pattern.compile("^\\+?\\d{3,15}$");
    private static final Pattern STATUS = Pattern.compile("^(SENT|QUEUED|FAILED\\b.*)$");

    private final Path path;
    final List<Row> rows = new ArrayList<>();

    SmsFile(Path path) { this.path = path; }

    Path path() { return path; }

    void load() throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1);          // strip BOM

        for (String line : raw.split("\r\n|\r|\n")) {
            if (line.isBlank()) continue;
            rows.add(parse(line));
        }
    }

    /** NUMBER MESSAGE[<TAB>STATUS[<TAB>TIME]]  (also accepts the older NUMBER<TAB>MESSAGE layout). */
    static Row parse(String line) {
        String[] cols = line.split("\t", -1);

        // the first column (after the number) that looks like a status starts the result part
        int s = cols.length;
        for (int i = 1; i < cols.length; i++) {
            if (STATUS.matcher(cols[i].trim()).matches()) { s = i; break; }
        }

        Row r = new Row();
        String head = String.join(" ", java.util.Arrays.copyOfRange(cols, 0, s)).trim();
        String[] parts = head.split("\\s+", 2);
        r.number  = parts[0];
        r.message = parts.length > 1 ? parts[1].trim() : "";
        r.status  = s < cols.length     ? cols[s].trim()     : "";
        r.time    = s + 1 < cols.length ? cols[s + 1].trim() : "";
        return r;
    }

    /** Copies the file to backups/NAME.yyyyMMdd_HHmmss.bak (next to the list) and returns the backup path. */
    Path backup() throws IOException {
        String stamp = LocalDateTime.now(SmsClient.LK).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = path.resolveSibling("backups");
        Files.createDirectories(dir);
        Path bak = dir.resolve(path.getFileName() + "." + stamp + ".bak");
        Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING);
        return bak;
    }

    /** Rewrites the whole file (CRLF). */
    void save() {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            sb.append(r.number);
            if (!r.message.isEmpty()) sb.append(' ').append(r.message);
            if (!r.status.isEmpty()) {
                sb.append('\t').append(r.status);
                if (!r.time.isEmpty()) sb.append('\t').append(r.time);
            }
            sb.append("\r\n");
        }
        try {
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SmsClient.say("WARNING: could not write to " + path + " (is it open in Excel?)");
        }
    }

    /**
     * Keeps the number as typed apart from dashes and brackets:
     * "077-618-1202" -> "0776181202", "+94704266980" stays, "1122" stays.
     */
    static String cleanNumber(String phone) {
        phone = phone.trim();
        boolean plus = phone.startsWith("+");
        String digits = phone.replaceAll("\\D", "");
        return plus ? "+" + digits : digits;
    }
}
