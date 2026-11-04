package fi.nyja.ventapp.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Built-in and user vent names. The parser matches only what this list enables.
 * Tilluft is supply, frånluft is exhaust. HUK and HU are one frånluft family.
 */
public final class VentCatalog {
    public static final class Row {
        public final String key;
        public final String label;
        public final boolean tilluft;
        public final boolean builtin;
        public final boolean enabled;

        Row(String key, String label, boolean tilluft, boolean builtin, boolean enabled) {
            this.key = key;
            this.label = label;
            this.tilluft = tilluft;
            this.builtin = builtin;
            this.enabled = enabled;
        }
    }

    private static final class Spec {
        final String key;
        final boolean tilluft;
        final boolean franluft;
        final boolean builtin;
        final boolean tilluftOn;
        final boolean franluftOn;

        Spec(String key, boolean tilluft, boolean franluft, boolean builtin, boolean tilluftOn, boolean franluftOn) {
            this.key = key;
            this.tilluft = tilluft;
            this.franluft = franluft;
            this.builtin = builtin;
            this.tilluftOn = tilluft && tilluftOn;
            this.franluftOn = franluft && franluftOn;
        }

        Spec withSide(boolean tilluftSide, boolean enabled) {
            boolean tOn = tilluftSide ? enabled : tilluftOn;
            boolean fOn = tilluftSide ? franluftOn : enabled;
            return new Spec(key, tilluft, franluft, builtin, tOn, fOn);
        }

        Spec plusSide(boolean tilluftSide) {
            boolean t = tilluft || tilluftSide;
            boolean f = franluft || !tilluftSide;
            return new Spec(key, t, f, builtin, t && (tilluftSide || tilluftOn), f && (!tilluftSide || franluftOn));
        }
    }

    private final Map<String, Spec> specs;
    private final Pattern productPattern;

    private VentCatalog(Map<String, Spec> specs) {
        this.specs = specs;
        this.productPattern = compile(specs);
    }

    public static VentCatalog defaults() {
        Map<String, Spec> specs = new LinkedHashMap<String, Spec>();
        addBuiltin(specs, "DYKH", true, true);
        addBuiltin(specs, "CLIK", true, true);
        addBuiltin(specs, "KTS", true, false);
        addBuiltin(specs, "EAGLE", true, false);
        addBuiltin(specs, "PELICAN", true, false);
        addBuiltin(specs, "HFK", true, true);
        addBuiltin(specs, "KSO", false, true);
        addBuiltin(specs, "ALG", false, true);
        addBuiltin(specs, "PRA", false, true);
        addBuiltin(specs, "HUK", false, true);
        return new VentCatalog(specs);
    }

    public List<Row> tilluftRows() {
        return rows(true);
    }

    public List<Row> franluftRows() {
        return rows(false);
    }

    public int activeCount() {
        int n = 0;
        for (Spec spec : specs.values()) {
            if (spec.tilluftOn || spec.franluftOn) {
                n++;
            }
        }
        return n;
    }

    public String rejection(String raw, boolean tilluft) {
        String key = normalizeKey(raw);
        if (key.length() < 2) {
            return "Skriv ett namn.";
        }
        if (!key.matches("[A-Z0-9][A-Z0-9-]{0,15}")) {
            return "Använd bara bokstäver, siffror och bindestreck.";
        }
        Spec existing = find(key);
        if (existing != null && (tilluft ? existing.tilluft : existing.franluft)) {
            return "Namnet finns redan.";
        }
        return null;
    }

    public VentCatalog add(String raw, boolean tilluft) {
        if (rejection(raw, tilluft) != null) {
            return this;
        }
        String key = normalizeKey(raw);
        Map<String, Spec> next = copy();
        Spec existing = find(key);
        if (existing == null) {
            next.put(key, new Spec(key, tilluft, !tilluft, false, tilluft, !tilluft));
        } else {
            next.put(key, existing.plusSide(tilluft));
        }
        return new VentCatalog(next);
    }

    public VentCatalog removeCustom(String key, boolean tilluft) {
        Spec spec = find(normalizeKey(key));
        if (spec == null || spec.builtin) {
            return this;
        }
        boolean keepSupply = spec.tilluft && !tilluft;
        boolean keepExhaust = spec.franluft && tilluft;
        Map<String, Spec> next = copy();
        if (!keepSupply && !keepExhaust) {
            next.remove(spec.key);
        } else {
            next.put(spec.key, new Spec(spec.key, keepSupply, keepExhaust, false, keepSupply, keepExhaust));
        }
        return new VentCatalog(next);
    }

    public VentCatalog setEnabled(String key, boolean tilluft, boolean enabled) {
        Spec spec = find(normalizeKey(key));
        if (spec == null || !spec.builtin) {
            return this;
        }
        if (tilluft && !spec.tilluft) {
            return this;
        }
        if (!tilluft && !spec.franluft) {
            return this;
        }
        Map<String, Spec> next = copy();
        next.put(spec.key, spec.withSide(tilluft, enabled));
        return new VentCatalog(next);
    }

    public String classify(String name, Double airflow, boolean signed) {
        String key = canonicalKey(name);
        Spec spec = find(key);
        if (spec == null) {
            return null;
        }
        boolean supply = spec.tilluftOn;
        boolean exhaust = spec.franluftOn;
        if (exhaust && !supply) {
            return "exhaust";
        }
        if (supply && !exhaust) {
            return "supply";
        }
        if (!supply) {
            return null;
        }
        if (signed && airflow != null) {
            if (airflow < 0) {
                return "exhaust";
            }
            if (airflow > 0) {
                return "supply";
            }
        }
        if ("HFK".equals(spec.key)) {
            return "exhaust";
        }
        return "supply";
    }

    public String canonicalKey(String matched) {
        if (matched == null) {
            return "";
        }
        String upper = matched.trim().toUpperCase(Locale.ROOT);
        if (fold(upper).startsWith(fold("KTS-360")) || fold(upper).startsWith(fold("KT5-360"))) {
            return "KTS";
        }
        if ("HU".equals(fold(upper)) || "HUK".equals(fold(upper))) {
            return "HUK";
        }
        Spec best = null;
        for (Spec spec : specs.values()) {
            if (fold(upper).equals(fold(spec.key)) && (best == null || spec.key.length() > best.key.length())) {
                best = spec;
            }
        }
        return best == null ? upper : best.key;
    }

    public String storeName(String matched) {
        String key = canonicalKey(matched);
        if ("HUK".equals(key)) {
            return "hu";
        }
        if ("KTS-360".equals(key)) {
            return "kts";
        }
        return key.toLowerCase(Locale.ROOT);
    }

    public boolean containsFolded(String upperText) {
        String folded = fold(upperText);
        for (Spec spec : specs.values()) {
            if ((spec.tilluftOn || spec.franluftOn) && folded.contains(fold(spec.key))) {
                return true;
            }
        }
        return false;
    }

    public static final class Hit {
        public final String key;
        public final int end;

        Hit(String key, int end) {
            this.key = key;
            this.end = end;
        }
    }

    /** Unique nearest name inside OCR text. Length >= 4 may differ by one character. */
    public Hit fuzzyHit(String norm) {
        if (norm == null || norm.isEmpty()) {
            return null;
        }
        String folded = fold(norm);
        String bestKey = null;
        int bestEnd = 0;
        for (Spec spec : specs.values()) {
            if (!spec.tilluftOn && !spec.franluftOn) {
                continue;
            }
            String name = fold(spec.key);
            for (int i = 0; i + name.length() <= folded.length(); i++) {
                if (!folded.substring(i, i + name.length()).equals(name)) {
                    continue;
                }
                if (bestKey == null || spec.key.length() > bestKey.length()) {
                    bestKey = spec.key;
                    bestEnd = i + spec.key.length();
                }
            }
        }
        if (bestKey == null) {
            return null;
        }
        return new Hit(bestKey, bestEnd);
    }

    public Pattern productPattern() {
        return productPattern;
    }

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"disabled\":[");
        boolean first = true;
        for (Spec spec : specs.values()) {
            if (!spec.builtin) {
                continue;
            }
            if (spec.tilluft && !spec.tilluftOn) {
                first = writeItem(json, first, spec.key + "|t");
            }
            if (spec.franluft && !spec.franluftOn) {
                first = writeItem(json, first, spec.key + "|f");
            }
        }
        json.append("],\"custom\":[");
        first = true;
        for (Spec spec : specs.values()) {
            if (spec.builtin) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"name\":\"").append(escape(spec.key)).append("\",\"t\":")
                    .append(spec.tilluft).append(",\"f\":").append(spec.franluft).append('}');
        }
        json.append("]}");
        return json.toString();
    }

    public static VentCatalog fromJson(String json) {
        VentCatalog catalog = defaults();
        if (json == null || json.trim().isEmpty()) {
            return catalog;
        }
        for (String item : array(json, "disabled")) {
            int bar = item.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            String key = item.substring(0, bar);
            boolean tilluft = "t".equals(item.substring(bar + 1));
            catalog = catalog.setEnabled(key, tilluft, false);
        }
        for (String object : objects(json, "custom")) {
            String name = field(object, "name");
            boolean tilluft = field(object, "t").equals("true");
            boolean franluft = field(object, "f").equals("true");
            if (tilluft) {
                catalog = catalog.add(name, true);
            }
            if (franluft) {
                catalog = catalog.add(name, false);
            }
        }
        return catalog;
    }

    private List<Row> rows(boolean tilluft) {
        List<Row> rows = new ArrayList<Row>();
        for (Spec spec : specs.values()) {
            if (tilluft ? !spec.tilluft : !spec.franluft) {
                continue;
            }
            String label = "HUK".equals(spec.key) ? "HUK / HU" : spec.key;
            boolean enabled = tilluft ? spec.tilluftOn : spec.franluftOn;
            rows.add(new Row(spec.key, label, tilluft, spec.builtin, enabled));
        }
        return rows;
    }

    private Spec find(String key) {
        if (key == null) {
            return null;
        }
        Spec direct = specs.get(key);
        if (direct != null) {
            return direct;
        }
        String folded = fold(key);
        for (Spec spec : specs.values()) {
            if (fold(spec.key).equals(folded)) {
                return spec;
            }
        }
        return null;
    }

    private Map<String, Spec> copy() {
        return new LinkedHashMap<String, Spec>(specs);
    }

    private static void addBuiltin(Map<String, Spec> specs, String key, boolean tilluft, boolean franluft) {
        specs.put(key, new Spec(key, tilluft, franluft, true, true, true));
    }

    public static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static String fold(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        String upper = text.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '0') {
                c = 'O';
            } else if (c == '1' || c == 'L') {
                c = 'I';
            } else if (c == 'V') {
                c = 'Y';
            } else if (c == 'F') {
                c = 'E';
            } else if (c == '5') {
                c = 'S';
            }
            out.append(c);
        }
        return out.toString();
    }

    private static Pattern compile(Map<String, Spec> specs) {
        List<String> alts = new ArrayList<String>();
        for (Spec spec : specs.values()) {
            if (!spec.tilluftOn && !spec.franluftOn) {
                continue;
            }
            if ("KTS".equals(spec.key)) {
                alts.add("KTS-360");
                alts.add("KT5-360");
            }
            alts.add(ocrAlt(spec.key));
            if ("HUK".equals(spec.key)) {
                alts.add("HU(?=[-/\\s]\\d)");
            }
        }
        Collections.sort(alts, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < alts.size(); i++) {
            if (i > 0) {
                body.append('|');
            }
            body.append(alts.get(i));
        }
        if (body.length() == 0) {
            body.append("(?!)");
        }
        return Pattern.compile("(?:(\\d{1,2})\\s*[xX×*]\\s*)?(" + body + ")", Pattern.CASE_INSENSITIVE);
    }

    private static String ocrAlt(String key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == 'O') {
                out.append("[O0]");
            } else if (c == 'I' || c == 'L') {
                out.append("[IL1]");
            } else if (c == 'Y' || c == 'V') {
                out.append("[YV]");
            } else if (c == 'E' || c == 'F') {
                out.append("[EF]");
            } else if (c == 'S') {
                out.append("[S5]");
            } else if (c == '-') {
                out.append("\\-");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean writeItem(StringBuilder json, boolean first, String value) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(value)).append('"');
        return false;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> array(String json, String key) {
        String body = arrayBody(json, key);
        List<String> items = new ArrayList<String>();
        if (body == null) {
            return items;
        }
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) != '"') {
                i++;
                continue;
            }
            int end = body.indexOf('"', i + 1);
            if (end < 0) {
                break;
            }
            items.add(body.substring(i + 1, end));
            i = end + 1;
        }
        return items;
    }

    private static List<String> objects(String json, String key) {
        String body = arrayBody(json, key);
        List<String> items = new ArrayList<String>();
        if (body == null) {
            return items;
        }
        int i = 0;
        while (i < body.length()) {
            int start = body.indexOf('{', i);
            if (start < 0) {
                break;
            }
            int end = body.indexOf('}', start);
            if (end < 0) {
                break;
            }
            items.add(body.substring(start, end + 1));
            i = end + 1;
        }
        return items;
    }

    private static String arrayBody(String json, String key) {
        String marker = "\"" + key + "\":[";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = json.indexOf(']', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }

    private static String field(String object, String key) {
        String quoted = "\"" + key + "\":\"";
        int start = object.indexOf(quoted);
        if (start >= 0) {
            start += quoted.length();
            int end = object.indexOf('"', start);
            return end < 0 ? "" : object.substring(start, end);
        }
        String plain = "\"" + key + "\":";
        start = object.indexOf(plain);
        if (start < 0) {
            return "";
        }
        start += plain.length();
        int end = start;
        while (end < object.length() && object.charAt(end) != ',' && object.charAt(end) != '}') {
            end++;
        }
        return object.substring(start, end).trim();
    }
}
