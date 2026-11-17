package fi.nyja.ventapp.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Exact raw-text rules learned on the device. A rule applies only when the whole text
 * matches its pattern, never as a substring of a longer string.
 */
public final class LearnedRules {
    public static final String SIZE = "size";
    public static final String BEHOV = "behov";
    public static final String NAME = "name";
    public static final String ROOM = "room";
    public static final String SIDE = "side";
    public static final String DROP = "drop";

    public static final class Rule {
        public final String id;
        public final String field;
        public final String pattern;
        public final String value;

        public Rule(String id, String field, String pattern, String value) {
            this.id = id == null ? "" : id;
            this.field = field == null ? "" : field;
            this.pattern = pattern == null ? "" : pattern;
            this.value = value == null ? "" : value;
        }
    }

    private static volatile LearnedRules current = new LearnedRules();

    private final List<Rule> rules = new ArrayList<Rule>();
    private int nextId = 1;

    public static LearnedRules current() {
        return current;
    }

    public static void install(LearnedRules rules) {
        current = rules == null ? new LearnedRules() : rules;
    }

    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public List<Rule> all() {
        return Collections.unmodifiableList(new ArrayList<Rule>(rules));
    }

    /** Whole-text match only. A longer string that merely contains the pattern does not match. */
    public Rule match(String field, String raw) {
        String wanted = key(raw);
        if (wanted.isEmpty() || field == null) {
            return null;
        }
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (field.equals(rule.field) && wanted.equals(key(rule.pattern))) {
                return rule;
            }
        }
        return null;
    }

    public Rule put(String field, String pattern, String value) {
        String stored = pattern == null ? "" : pattern.trim();
        if (stored.isEmpty() || field == null || field.isEmpty()) {
            return null;
        }
        String wanted = key(stored);
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (field.equals(rule.field) && wanted.equals(key(rule.pattern))) {
                Rule next = new Rule(rule.id, field, stored, value == null ? "" : value);
                rules.set(i, next);
                return next;
            }
        }
        Rule created = new Rule(Integer.toString(nextId++), field, stored, value == null ? "" : value);
        rules.add(created);
        return created;
    }

    public boolean remove(String id) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(id)) {
                rules.remove(i);
                return true;
            }
        }
        return false;
    }

    public void clear() {
        rules.clear();
    }

    /** Drop the vent when the raw text was marked "ska inte finnas". Otherwise fill saved fields. */
    public Vent applyVent(Vent vent) {
        if (vent == null) {
            return null;
        }
        if (match(DROP, vent.raw) != null) {
            return null;
        }
        String name = vent.name;
        String size = vent.size;
        String kind = vent.kind;
        Double airflow = vent.airflow;
        Rule nameRule = match(NAME, vent.raw);
        if (nameRule != null && !nameRule.value.trim().isEmpty()) {
            name = nameRule.value.trim();
        }
        Rule sizeRule = match(SIZE, vent.raw);
        if (sizeRule != null && !sizeRule.value.trim().isEmpty()) {
            size = sizeRule.value.trim();
        }
        Rule sideRule = match(SIDE, vent.raw);
        if (sideRule != null) {
            String side = sideRule.value.trim().toLowerCase(Locale.ROOT);
            if ("exhaust".equals(side) || "frånluft".equals(side) || "franluft".equals(side)) {
                kind = "exhaust";
            } else if ("supply".equals(side) || "tilluft".equals(side)) {
                kind = "supply";
            }
        }
        Rule flowRule = match(BEHOV, vent.raw);
        if (flowRule != null && !flowRule.value.trim().isEmpty()) {
            try {
                airflow = Double.valueOf(flowRule.value.trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                airflow = vent.airflow;
            }
        }
        return new Vent(name, kind, size, airflow, vent.qty, vent.x, vent.y, vent.raw, vent.confidence);
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"id\":\"").append(esc(rule.id))
                    .append("\",\"field\":\"").append(esc(rule.field))
                    .append("\",\"pattern\":\"").append(esc(rule.pattern))
                    .append("\",\"value\":\"").append(esc(rule.value))
                    .append("\"}");
        }
        out.append(']');
        return out.toString();
    }

    public static LearnedRules fromJson(String json) {
        LearnedRules rules = new LearnedRules();
        if (json == null || json.trim().isEmpty()) {
            return rules;
        }
        int cursor = 0;
        while (true) {
            int idAt = json.indexOf("\"id\"", cursor);
            if (idAt < 0) {
                break;
            }
            String id = readString(json, idAt);
            String field = readString(json, json.indexOf("\"field\"", idAt));
            String pattern = readString(json, json.indexOf("\"pattern\"", idAt));
            String value = readString(json, json.indexOf("\"value\"", idAt));
            if (!field.isEmpty() && !pattern.isEmpty()) {
                rules.rules.add(new Rule(id.isEmpty() ? Integer.toString(rules.nextId) : id, field, pattern, value));
            }
            try {
                rules.nextId = Math.max(rules.nextId, Integer.parseInt(id) + 1);
            } catch (NumberFormatException ignored) {
                rules.nextId++;
            }
            cursor = idAt + 4;
        }
        return rules;
    }

    public void write(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException(file.getPath());
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(toJson().getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    public static LearnedRules read(File file) {
        if (file == null || !file.isFile()) {
            return new LearnedRules();
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] data = new byte[(int) file.length()];
                int offset = 0;
                while (offset < data.length) {
                    int n = in.read(data, offset, data.length - offset);
                    if (n < 0) {
                        break;
                    }
                    offset += n;
                }
                return fromJson(new String(data, 0, offset, StandardCharsets.UTF_8));
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            return new LearnedRules();
        }
    }

    private static String readString(String json, int keyAt) {
        if (keyAt < 0) {
            return "";
        }
        int colon = json.indexOf(':', keyAt);
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                if (n == 'n') {
                    value.append('\n');
                } else if (n == 't') {
                    value.append('\t');
                } else {
                    value.append(n);
                }
                continue;
            }
            if (c == '"') {
                break;
            }
            value.append(c);
        }
        return value.toString();
    }

    private static String esc(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
