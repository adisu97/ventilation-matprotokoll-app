package fi.nyja.ventapp.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of matprotokoll/labels.py for vent labels. */
public final class LabelParser {
    private static final int[] SIZES = {630, 500, 450, 400, 355, 315, 250, 200, 160, 125, 100, 80};
    private static volatile VentCatalog catalog = VentCatalog.defaults();
    private static final Pattern AIR = Pattern.compile(
            "(?<![A-Z0-9])([+-])\\s*(\\d{1,4})(?:\\s*(?:l\\s*/\\s*s|l/s|ls))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE_RANGE = Pattern.compile("(?<!\\d)(\\d{2,4})\\s*[-/]\\s*(\\d{2,4})(?!\\d)");
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
        if (!match.find()) {
            return null;
        }
        String token = match.group(2).toUpperCase(Locale.ROOT);
        String name = catalog.storeName(token);
        if ("hu".equals(name) && !token.startsWith("HUK")
                && (norm.matches(".*[A-Z]HU.*") || norm.matches(".*HU[A-Z].*"))) {
            return null;
        }
        int qty = 1;
        if (match.group(1) != null) {
            try {
                qty = Integer.parseInt(match.group(1));
            } catch (NumberFormatException ignored) {
                qty = 1;
            }
        }
        if (qty < 1 || qty > 12) {
            qty = 1;
        }
        String rest = norm.substring(match.end());
        String size = sizeFromRest(rest, name);
        Double airflow = null;
        boolean signed = false;
        Matcher air = AIR.matcher(norm);
        if (air.find()) {
            airflow = Double.valueOf(air.group(2));
            signed = true;
            if ("-".equals(air.group(1))) {
                airflow = -airflow;
            }
            if (Math.abs(airflow) < 2) {
                airflow = null;
                signed = false;
            }
        }
        String kind = classify(name, airflow, signed);
        if (kind == null) {
            return null;
        }
        Double abs = airflow == null ? null : Math.abs(airflow);
        return new Vent(name, kind, size, abs, qty, x, y, raw, confidence);
    }

    public static String classify(String name, Double airflow, boolean signed) {
        return catalog.classify(name, airflow, signed);
    }

    public static Airflow parseAirflowToken(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String s = normalize(text).replace(" ", "");
        boolean hasUnit = s.contains("L/S") || s.contains("LS");
        s = s.replace("L/S", "").replace("LS", "");
        Matcher m = Pattern.compile("^([+-])?(\\d{1,4})$").matcher(s);
        if (!m.matches()) {
            return null;
        }
        double val = Double.parseDouble(m.group(2));
        boolean signed = m.group(1) != null;
        if ("-".equals(m.group(1))) {
            val = -val;
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
