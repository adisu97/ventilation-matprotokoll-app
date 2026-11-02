package fi.nyja.ventapp.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SheetNames {
    public static final int MAX = 31;
    private static final String INVALID = "[]:*?/\\";

    private SheetNames() {
    }

    public static String fromFileName(String filename, List<String> existing) {
        String stem = filename == null ? "" : filename.trim();
        int slash = Math.max(stem.lastIndexOf('/'), stem.lastIndexOf('\\'));
        if (slash >= 0 && (stem.startsWith("/") || stem.startsWith("content:") || stem.contains("://"))) {
            stem = stem.substring(slash + 1);
        }
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        return unique(sanitize(stem), existing);
    }

    public static String sanitize(String name) {
        StringBuilder cleaned = new StringBuilder();
        String raw = name == null ? "" : name;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            cleaned.append(INVALID.indexOf(ch) >= 0 ? '_' : ch);
        }
        String text = cleaned.toString().trim();
        while (text.startsWith("'") || text.endsWith("'")) {
            text = text.replaceAll("^'+|'+$", "").trim();
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            text = "Blad";
        }
        return text.length() > MAX ? text.substring(0, MAX) : text;
    }

    public static String workbookFileName(String name) {
        String raw = name == null ? "" : name.trim();
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        if (slash >= 0) {
            raw = raw.substring(slash + 1).trim();
        }
        if (raw.isEmpty()) {
            raw = "matprotokoll";
        }
        raw = raw.replace('/', '_').replace('\\', '_');
        if (!raw.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            raw += ".xlsx";
        }
        return raw;
    }

    public static String unique(String name, List<String> existing) {
        Set<String> taken = new HashSet<String>();
        if (existing != null) {
            for (String item : existing) {
                if (item != null) {
                    taken.add(item.toLowerCase(Locale.ROOT));
                }
            }
        }
        String base = sanitize(name);
        if (!taken.contains(base.toLowerCase(Locale.ROOT))) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            String suffix = "_" + i;
            String head = base;
            if (head.length() + suffix.length() > MAX) {
                head = head.substring(0, MAX - suffix.length());
            }
            String candidate = sanitize(head + suffix);
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        String fallback = base.length() > MAX - 2 ? base.substring(0, MAX - 2) + "_x" : base + "_x";
        return fallback.length() > MAX ? fallback.substring(0, MAX) : fallback;
    }
}
