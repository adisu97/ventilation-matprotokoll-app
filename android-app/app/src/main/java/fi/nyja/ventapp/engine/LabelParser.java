package fi.nyja.ventapp.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of matprotokoll/labels.py for vent labels. */
public final class LabelParser {
    private static final int[] SIZES = {630, 500, 450, 400, 355, 315, 250, 200, 160, 125, 100, 80};
    private static volatile VentCatalog catalog = VentCatalog.defaults();
    private static final Pattern AIR = Pattern.compile(
            "(?<![A-Z0-9])([+-])?\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)(?:\\s*(L\\s*/\\s*S|L/S|LS|M\\s*3\\s*/\\s*H|M³/H))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE_RANGE = Pattern.compile("(?<!\\d)(\\d{2,4})\\s*[-/]\\s*(\\d{2,4})(?!\\d)");
    private static final Pattern RECT_SIZE = Pattern.compile("(?<!\\d)(\\d{2,4})\\s*[xX×]\\s*(\\d{2,4})(?!\\d)");
    private static final Pattern MULT_SIZE = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[xX×*]\\s*(\\d{2,4})(?!\\d)");
    private static final Pattern NOISE = Pattern.compile("\\b(SP1|YL[OÖ]S|CIRCO|DOUBLE|PP\\d+|AV\\d+|IVL|KANAV|TUULET)\\b", Pattern.CASE_INSENSITIVE);

    private LabelParser() {
    }

    public static VentCatalog current() {
        return catalog;
    }

    public static void use(VentCatalog names) {
        catalog = names == null ? VentCatalog.defaults() : names;
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = text.replace('–', '-').replace('—', '-').replace('−', '-').replace('=', '-')
                .replace('×', 'x').replace("’", "").replace("`", "").replace("´", "");
        s = s.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        s = s.replaceAll("KS[O0]", "KSO");
        s = s.replaceAll("KT[S5]", "KTS");
        s = s.replace("PEL1CAN", "PELICAN").replace("EAG1E", "EAGLE").replace("EAGIE", "EAGLE")
                .replace("EACLE", "EAGLE").replace("DYK H", "DYKH").replace("CL1K", "CLIK")
                .replace("KT5", "KTS").replace("KLS", "KTS").replace("K1S", "KTS")
                .replace("KS0", "KSO").replace("K5O", "KSO").replace("A1G", "ALG");
        return s;
    }

    public static Vent parse(String text, double x, double y, double confidence) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String raw = text.trim();
        String upper = raw.toUpperCase(Locale.ROOT);
        if (NOISE.matcher(raw).find() && !catalog.containsFolded(upper)) {
            return null;
        }
        String norm = normalize(raw);
        Matcher match = catalog.productPattern().matcher(norm);
        String token;
        String name;
        String rest;
        int qty = 1;
        if (match.find()) {
            token = match.group(2).toUpperCase(Locale.ROOT);
            name = catalog.storeName(token);
            if ("hu".equals(name) && !token.startsWith("HUK")
                    && (norm.matches(".*[A-Z]HU.*") || norm.matches(".*HU[A-Z].*"))) {
                return null;
            }
            if (match.group(1) != null) {
                try {
                    qty = Integer.parseInt(match.group(1));
                } catch (NumberFormatException ignored) {
                    qty = 1;
                }
            }
            rest = norm.substring(match.end());
        } else {
            VentCatalog.Hit hit = catalog.fuzzyHit(norm);
            if (hit == null) {
                return null;
            }
            name = catalog.storeName(hit.key);
            rest = norm.substring(Math.min(hit.end, norm.length()));
        }
        if (qty < 1 || qty > 12) {
            qty = 1;
        }
        String size = sizeFromRest(rest, name);
        Double airflow = null;
        boolean signed = false;
        Matcher air = AIR.matcher(norm);
        while (air.find()) {
            String unit = air.group(3);
            boolean hasSign = air.group(1) != null;
            boolean hasUnit = unit != null && !unit.isEmpty();
            if (!hasSign && !hasUnit) {
                continue;
            }
            airflow = Double.valueOf(air.group(2).replace(',', '.'));
            signed = hasSign;
            if ("-".equals(air.group(1))) {
                airflow = -airflow;
            }
            if (hasUnit && unit.toUpperCase(Locale.ROOT).contains("M")) {
                airflow = airflow / 3.6;
            }
            if (Math.abs(airflow) < 2) {
                airflow = null;
                signed = false;
                continue;
            }
            break;
        }
        String kind = classify(name, airflow, signed);
        if (kind == null) {
            return null;
        }
        Double abs = airflow == null ? null : Math.abs(airflow);
        return new Vent(name, kind, size, abs, qty, x, y, raw, confidence);
    }

    /** Every vent label in one line, including 2xEAGLE and a second product after the first. */
    public static java.util.List<Vent> parseAll(String text, double x, double y, double confidence) {
        java.util.List<Vent> found = new java.util.ArrayList<Vent>();
        if (text == null || text.trim().isEmpty()) {
            return found;
        }
        String norm = normalize(text);
        Matcher match = catalog.productPattern().matcher(norm);
        java.util.List<Integer> starts = new java.util.ArrayList<Integer>();
        while (match.find()) {
            if (!starts.isEmpty() && match.start() < starts.get(starts.size() - 1)) {
                continue;
            }
            starts.add(match.start());
        }
        if (starts.isEmpty()) {
            Vent one = parse(text, x, y, confidence);
            if (one != null) {
                found.add(one);
            }
            return found;
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : norm.length();
            Vent vent = parse(norm.substring(from, to).trim(), x, y, confidence);
            if (vent != null) {
                found.add(vent);
            }
        }
        return found;
    }

    public static String classify(String name, Double airflow, boolean signed) {
        if (VentFormats.signOverrides() && signed && airflow != null && airflow.doubleValue() != 0) {
            return airflow.doubleValue() < 0 ? "exhaust" : "supply";
        }
        return catalog.classify(name, airflow, signed);
    }

    public static Airflow parseAirflowToken(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String s = normalize(text).replace(" ", "");
        boolean cubic = s.contains("M3/H") || s.contains("M³/H");
        boolean hasUnit = s.contains("L/S") || s.contains("LS") || cubic;
        s = s.replace("L/S", "").replace("LS", "").replace("M3/H", "").replace("M³/H", "");
        Matcher m = Pattern.compile("^([+-])?(\\d{1,4}(?:[.,]\\d{1,2})?)$").matcher(s);
        if (!m.matches()) {
            return null;
        }
        double val = Double.parseDouble(m.group(2).replace(',', '.'));
        boolean signed = m.group(1) != null;
        if ("-".equals(m.group(1))) {
            val = -val;
        }
        if (cubic) {
            val = val / 3.6;
        }
        if (Math.abs(val) < 2) {
            return null;
        }
        if (!signed && !hasUnit && isStandardSize((int) Math.abs(val))) {
            return null;
        }
        return new Airflow(val, signed);
    }

    public static boolean isAirflowToken(String text) {
        return parseAirflowToken(text) != null;
    }

    /**
     * A size can be written automatically when it is one clear value.
     * A missing size, several ranges, or extra words such as Ca, RO and ALSC need the user.
     */
    public static boolean sizeIsClear(String raw, String size) {
        if (size == null || size.trim().isEmpty()) {
            return false;
        }
        if (raw == null || raw.trim().isEmpty()) {
            return true;
        }
        String norm = normalize(raw);
        int ranges = 0;
        Matcher range = SIZE_RANGE.matcher(norm);
        while (range.find()) {
            ranges++;
        }
        if (ranges > 1) {
            return false;
        }
        if (VentFormats.active() && size.trim().matches("\\d+")) {
            return true;
        }
        String letters = norm;
        VentCatalog names = current();
        for (VentCatalog.Row row : names.tilluftRows()) {
            letters = letters.replace(row.key.toUpperCase(Locale.ROOT), " ");
        }
        for (VentCatalog.Row row : names.franluftRows()) {
            letters = letters.replace(row.key.toUpperCase(Locale.ROOT), " ");
        }
        letters = letters.replaceAll("[0-9+\\-*/xX.,()%]", " ");
        String[] words = letters.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.length() < 2) {
                continue;
            }
            if ("LS".equals(word) || "M3".equals(word) || "M3H".equals(word)) {
                continue;
            }
            return false;
        }
        return true;
    }

    public static boolean looksLikeDuctSize(String text) {
        if (text == null) {
            return false;
        }
        String bare = text.trim().replace("Ø", "").replace("ø", "").replace("Φ", "").replace("⌀", "");
        if (!bare.matches("\\d{2,3}")) {
            return false;
        }
        return isStandardSize(Integer.parseInt(bare));
    }

    private static boolean isStandardSize(int value) {
        for (int size : SIZES) {
            if (size == value) {
                return true;
            }
        }
        return false;
    }

    private static String sizeFromRest(String rest, String name) {
        if (rest == null || rest.isEmpty()) {
            return null;
        }
        rest = rest.replace("Ø", " ").replace("ø", " ").replace("Φ", " ").replace("⌀", " ");
        String low = name.toLowerCase(Locale.ROOT);
        if ("hfk".equals(low) || "alg".equals(low) || "pra".equals(low) || "eagle".equals(low) || "pelican".equals(low)) {
            Matcher m = MULT_SIZE.matcher(rest);
            if (m.find() && isStandardSize(Integer.parseInt(m.group(2)))) {
                return Integer.parseInt(m.group(1)) + "*" + Integer.parseInt(m.group(2));
            }
        }
        Matcher range = SIZE_RANGE.matcher(rest);
        if (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            if (a >= 80 && b >= a) {
                return a + "-" + b;
            }
        }
        Matcher rect = RECT_SIZE.matcher(rest);
        if (rect.find()) {
            return Integer.parseInt(rect.group(1)) + "x" + Integer.parseInt(rect.group(2));
        }
        for (int size : SIZES) {
            Matcher m = Pattern.compile("(?<!\\d)" + size + "(?!\\d)").matcher(rest);
            if (m.find()) {
                return Integer.toString(size);
            }
        }
        return null;
    }

    public static final class Airflow {
        public final double value;
        public final boolean signed;

        public Airflow(double value, boolean signed) {
            this.value = value;
            this.signed = signed;
        }
    }
}
