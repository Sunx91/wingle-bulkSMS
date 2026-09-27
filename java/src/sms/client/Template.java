package sms.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional default text (sms_template.txt) used for list lines that contain only a number.
 * {phone} inside the text is replaced with that number.
 */
final class Template {

    static final String DEFAULT_FILE = "sms_template.txt";

    final Path path;
    final String text;

    private Template(Path path, String text) {
        this.path = path;
        this.text = text;
    }

    /** Loads the template; returns null if the file does not exist or is empty. */
    static Template load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        String t = Files.readString(file, StandardCharsets.UTF_8);
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        t = t.replace("\r\n", "\n").replace('\r', '\n').strip();
        return t.isEmpty() ? null : new Template(file, t);
    }

    String render(String phone) {
        return text.replace("{phone}", phone).strip();
    }
}
