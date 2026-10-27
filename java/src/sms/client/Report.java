package sms.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Writes the summary report of one run to reports/sms_report_yyyyMMdd_HHmmss.txt  (next to the list).
 */
final class Report {

    private Report() {}

    static Path write(SmsFile sms, Set<Integer> processed, String device,
                      LocalDateTime started, LocalDateTime finished) throws IOException {
        List<SmsFile.Row> rows = sms.rows;

        int sent = 0, failed = 0, skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            SmsFile.Row r = rows.get(i);
            if (!processed.contains(i))            skipped++;
            else if (r.status.equals("SENT"))      sent++;
            else if (r.status.startsWith("FAILED")) failed++;
        }

        DateTimeFormatter ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("SMS SUMMARY REPORT\r\n");
        sb.append("==================\r\n");
        sb.append(String.format("Started    : %s\r\n", started.format(ts)));
        sb.append(String.format("Finished   : %s\r\n", finished.format(ts)));
        sb.append(String.format("List file  : %s\r\n", sms.path()));
        sb.append(String.format("Device     : %s\r\n", device == null ? "-" : device));
        sb.append("\r\n");
        sb.append(String.format("Lines in file : %d\r\n", rows.size()));
        sb.append(String.format("Sent          : %d\r\n", sent));
        sb.append(String.format("Failed        : %d\r\n", failed));
        sb.append(String.format("Skipped       : %d  (already SENT in an earlier run)\r\n", skipped));
        sb.append("\r\n");

        // ---- detail table ----
        int wNum = 6, wStatus = 6;
        for (SmsFile.Row r : rows) {
            wNum = Math.max(wNum, r.number.length());
            wStatus = Math.max(wStatus, statusText(r).length());
        }
        wNum = Math.min(wNum, 20);
        wStatus = Math.min(wStatus, 60);
        String fmt = "%-4s %-" + wNum + "s  %-" + wStatus + "s  %-19s  %s\r\n";

        sb.append(String.format(fmt, "#", "NUMBER", "STATUS", "TIME", "MESSAGE"));
        sb.append(String.format(fmt, "----", "-".repeat(wNum), "-".repeat(wStatus), "-".repeat(19), "-------"));
        for (int i = 0; i < rows.size(); i++) {
            SmsFile.Row r = rows.get(i);
            String status = processed.contains(i) ? statusText(r) : "SKIPPED (sent earlier)";
            sb.append(String.format(fmt, i + 1, cut(r.number, wNum), cut(status, wStatus), r.time,
                                    r.message.replace('\n', ' ')));
        }

        if (failed > 0) {
            sb.append("\r\nFAILED LINES\r\n------------\r\n");
            for (int i = 0; i < rows.size(); i++) {
                SmsFile.Row r = rows.get(i);
                if (processed.contains(i) && r.status.startsWith("FAILED")) {
                    sb.append(String.format("%d. %s  %s\r\n   %s\r\n", i + 1, r.number, r.message.replace('\n', ' '), r.status));
                }
            }
        }

        Path dir = sms.path().resolveSibling("reports");
        Files.createDirectories(dir);
        Path out = dir.resolve("sms_report_" + finished.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private static String statusText(SmsFile.Row r) {
        return r.status.isEmpty() ? "-" : r.status;
    }

    private static String cut(String s, int w) {
        return s.length() <= w ? s : s.substring(0, w - 3) + "...";
    }
}
