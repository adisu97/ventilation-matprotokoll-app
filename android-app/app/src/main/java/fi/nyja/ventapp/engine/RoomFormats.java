package fi.nyja.ventapp.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Room shapes learned from the user's examples.
 * When at least one example is saved, it replaces the built-in room-number search.
 * A two-line example means the number is on its own line and the name is stacked under it.
 * Digit runs accept one digit more or less than the example, and never zero digits.
 */
public final class RoomFormats {
    private static final char RECORD = '\u001e';
    private static final String STORE_MARK = "v2" + RECORD;
    private static final Pattern NAME_LINE = Pattern.compile(
            "^[A-ZÅÄÖa-zåäö]{2,}(?:[ -][A-ZÅÄÖa-zåäö]{2,})*$");
    /** Centre-to-centre gap, as a fraction of the text height, for a name on the next line. */
    private static final double STACK_MIN = 0.55;
    private static final double STACK_MAX = 2.4;

    public static final class Derived {
        public final String example;
        public final String summary;
        public final boolean stacked;
        public final boolean wantsName;
        final Pattern pattern;
        final Pattern numberLine;
        final Pattern labelPattern;
        final String exampleName;

        Derived(String example, String summary, Pattern pattern, boolean stacked, Pattern numberLine,
                String numberRegex, boolean wantsName, String exampleName) {
            this.example = example;
            this.summary = summary;
            this.pattern = pattern;
            this.stacked = stacked;
            this.numberLine = numberLine;
            this.wantsName = wantsName;
            this.exampleName = exampleName;
            this.labelPattern = Pattern.compile("^(" + numberRegex + ")\\s+(.+)$", Pattern.CASE_INSENSITIVE);
        }
    }

    private static volatile List<Derived> active = Collections.emptyList();

    private RoomFormats() {
    }

    public static void use(List<Derived> examples) {
        active = examples == null ? Collections.<Derived>emptyList() : examples;
    }

    public static List<Derived> current() {
        return active;
    }

    /** True when the user has saved at least one room example. The broad scan stays off then. */
    public static boolean constrains() {
        return !active.isEmpty();
    }

    public static boolean hasInline() {
        for (int i = 0; i < active.size(); i++) {
            if (!active.get(i).stacked) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesStackedNumber(String text) {
        return matchesNumber(text, true);
    }

    public static boolean matchesInlineNumber(String text) {
        return matchesNumber(text, false);
    }

    /** An example that is only a number, such as 101, with no room name beside or under it. */
    public static boolean matchesNumberOnlyExample(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < active.size(); i++) {
            Derived derived = active.get(i);
            if (!derived.stacked && !derived.wantsName && derived.numberLine.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Square metres under a room name, for example 12,5 m² or m2. Not a name, number, or vent size.
     */
    public static boolean isAreaLabel(String text) {
        if (text == null) {
            return false;
        }
        String flat = text.trim().replace(" ", "").replace("²", "2").replace("³", "3").replace("^", "");
        if (flat.isEmpty()) {
            return false;
        }
        return flat.matches("(?i)^(?:\\d{1,4}(?:[.,]\\d{1,2})?)?m2$") || flat.matches("(?i)^kvm$");
    }

    /** A number or decimal that sits on the same line as m² / m2 / kvm. */
    public static boolean isAreaPiece(List<OcrToken> tokens, OcrToken token) {
        if (token == null) {
            return false;
        }
        if (isAreaLabel(token.text)) {
            return true;
        }
        String text = token.text == null ? "" : token.text.trim();
        if (!text.matches("\\d{1,4}(?:[.,]\\d{1,2})?")) {
            return false;
        }
        if (tokens == null) {
            return false;
        }
        for (int i = 0; i < tokens.size(); i++) {
            OcrToken other = tokens.get(i);
            if (other == token || !isAreaLabel(other.text)) {
                continue;
            }
            double font = Math.max(token.h, other.h);
            if (font < 1) {
                font = 8;
            }
            if (Math.abs(token.y - other.y) <= font * 0.8) {
                return true;
            }
        }
        return false;
    }

    /**
     * The second line of a two-line example means "a word here", not one fixed room name.
     * Letters, including å ä ö, with spaces, hyphens, dots, and an optional trailing digit such as WC2.
     */
    public static boolean looksLikeStackedName(String text) {
        if (text == null || LatinScript.hasCjk(text)) {
            return false;
        }
        String raw = text.trim();
        if (raw.isEmpty() || raw.length() > 40 || isAreaLabel(raw)) {
            return false;
        }
        if (raw.matches("(?i).*(l\\s*/\\s*s|m\\s*3\\s*/\\s*h|m³\\s*/\\s*h).*")) {
            return false;
        }
        String bare = raw.replace("Ø", "").replace("ø", "").replace("Φ", "").replace("⌀", "").replace("∅", "").trim();
        if (bare.matches("\\d{2,4}") || bare.matches("(?i)\\d{2,4}\\s*[xX×]\\s*\\d{2,4}")
                || bare.matches("\\d{2,4}\\s*-\\s*\\d{2,4}") || LabelParser.looksLikeDuctSize(bare)) {
            return false;
        }
        if (!raw.matches("[A-ZÅÄÖa-zåäö][A-ZÅÄÖa-zåäö.]*(?:[ -][A-ZÅÄÖa-zåäö][A-ZÅÄÖa-zåäö.]*)*\\d{0,2}")) {
            return false;
        }
        return LabelParser.parse(raw, 0, 0, 1) == null;
    }

    /**
     * A name from the Swedish, English and Finnish list, or the exact name in a saved example.
     */
    public static boolean acceptedRoomName(String text) {
        if (text == null || text.trim().isEmpty() || LatinScript.hasCjk(text)) {
            return false;
        }
        if (LatinScript.isKnownRoomName(text)) {
            return true;
        }
        for (int i = 0; i < active.size(); i++) {
            String exampleName = active.get(i).exampleName;
            if (exampleName != null && LatinScript.sameRoomName(exampleName, text)) {
                return true;
            }
        }
        return false;
    }

    /** Whole token written as one line, for an example that was itself one line. */
    public static ProtocolBuilder.RoomText parseInline(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        for (int i = 0; i < active.size(); i++) {
            Derived derived = active.get(i);
            if (derived.stacked) {
                continue;
            }
            Matcher matcher = derived.pattern.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.groupCount() >= 2 && matcher.group(2) != null ? matcher.group(2).trim() : "";
            if (!name.isEmpty() && !acceptedRoomName(name)) {
                continue;
            }
            if (derived.wantsName && name.isEmpty()) {
                continue;
            }
            return room(matcher.group(1), name);
        }
        return null;
    }

    /** Excel stores "number name" even when the drawing had the name on the next line. */
    public static ProtocolBuilder.RoomText splitJoinedLabel(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        for (int i = 0; i < active.size(); i++) {
            Derived derived = active.get(i);
            Matcher matcher = derived.labelPattern.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(2).trim();
            boolean nameOk = derived.stacked ? looksLikeStackedName(name) : acceptedRoomName(name);
            if (!nameOk) {
                continue;
            }
            return room(matcher.group(1), name);
        }
        return null;
    }

    /** Geometry score for a name under a number, or above it when nothing is under. */
    public static Double stackedScore(OcrToken number, OcrToken name) {
        return placementScore(number, name);
    }

    /** One record per example. A stacked example keeps its own newline. */
    public static String encodeExamples(List<String> examples) {
        StringBuilder raw = new StringBuilder(STORE_MARK);
        if (examples == null) {
            return raw.toString();
        }
        boolean any = false;
        for (int i = 0; i < examples.size(); i++) {
            if (examples.get(i) == null) {
                continue;
            }
            String text = examples.get(i).replace("\r\n", "\n").replace('\r', '\n').trim();
            if (text.isEmpty()) {
                continue;
            }
            if (any) {
                raw.append(RECORD);
            }
            raw.append(text);
            any = true;
        }
        return raw.toString();
    }

    public static List<String> decodeExamples(String raw) {
        List<String> examples = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) {
            return examples;
        }
        String[] parts;
        if (raw.startsWith(STORE_MARK) || "v2".equals(raw)) {
            String body = raw.startsWith(STORE_MARK) ? raw.substring(STORE_MARK.length()) : "";
            parts = body.split(String.valueOf(RECORD), -1);
        } else {
            parts = raw.split("\n", -1);
        }
        for (int i = 0; i < parts.length; i++) {
            String text = parts[i].replace("\r\n", "\n").replace('\r', '\n').trim();
            if (!text.isEmpty()) {
                examples.add(text);
            }
        }
        return examples;
    }

    public static Derived derive(String example) {
        List<String> lines = contentLines(example);
        if (lines.isEmpty() || lines.size() > 2) {
            return null;
        }
        if (lines.size() == 2) {
            return deriveStacked(lines.get(0), lines.get(1));
        }
        return deriveInline(lines.get(0));
    }

    public static ProtocolBuilder.RoomText parse(String text) {
        if (text == null) {
            return null;
        }
        List<String> lines = contentLines(text);
        if (lines.size() >= 2) {
            return parseStackedLines(lines.get(0), lines.get(1));
        }
        if (lines.isEmpty()) {
            return null;
        }
        String raw = lines.get(0);
        for (Derived derived : active) {
            if (derived.stacked) {
                if (derived.numberLine != null && derived.numberLine.matcher(raw).matches()) {
                    return room(raw, "");
                }
                continue;
            }
            Matcher matcher = derived.pattern.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.groupCount() >= 2 && matcher.group(2) != null ? matcher.group(2).trim() : "";
            if (!name.isEmpty() && !acceptedRoomName(name)) {
                continue;
            }
            if (derived.wantsName && name.isEmpty()) {
                continue;
            }
            return room(matcher.group(1), name);
        }
        return null;
    }

    /**
     * Names stacked under (or, if nothing is under, above) a number that matches a two-line example.
     * PDF text-layer words and OCR boxes both use the token centre and width/height.
     * Consumed name tokens are added to {@code consumedNames}.
     */
    public static Map<OcrToken, String> stackedNames(List<OcrToken> tokens, Set<OcrToken> consumedNames) {
        Map<OcrToken, String> out = new IdentityHashMap<OcrToken, String>();
        if (consumedNames != null) {
            consumedNames.clear();
        }
        if (tokens == null || tokens.isEmpty() || !hasStacked()) {
            return out;
        }
        List<Hit> hits = new ArrayList<Hit>();
        for (int i = 0; i < tokens.size(); i++) {
            OcrToken number = tokens.get(i);
            String numberText = LatinScript.clean(number.text).trim();
            if (!matchesStackedNumber(numberText)) {
                continue;
            }
            for (int j = 0; j < tokens.size(); j++) {
                if (i == j) {
                    continue;
                }
                OcrToken name = tokens.get(j);
                String nameText = LatinScript.clean(name.text).trim();
                if (!acceptedRoomName(nameText)) {
                    continue;
                }
                Double score = placementScore(number, name);
                if (score == null) {
                    continue;
                }
                hits.add(new Hit(number, name, score, LatinScript.correctRoomName(nameText)));
            }
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(a.score, b.score);
            }
        });
        Set<OcrToken> usedNumbers = Collections.newSetFromMap(new IdentityHashMap<OcrToken, Boolean>());
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            if (usedNumbers.contains(hit.number) || (consumedNames != null && consumedNames.contains(hit.name))) {
                continue;
            }
            usedNumbers.add(hit.number);
            if (consumedNames != null) {
                consumedNames.add(hit.name);
            }
            out.put(hit.number, hit.text);
        }
        return out;
    }

    public static String trial(String text) {
        ProtocolBuilder.RoomText hit = parse(text);
        if (hit == null) {
            return "Ingen träff.";
        }
        if (hit.name.isEmpty()) {
            return "Nummer: " + hit.number;
        }
        return "Nummer: " + hit.number + "  Namn: " + hit.name;
    }

    private static Derived deriveInline(String raw) {
        if (raw.isEmpty() || raw.length() > 48) {
            return null;
        }
        String[] parts = raw.split(" ");
        int numberAt = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].matches(".*\\d.*")) {
                numberAt = i;
                break;
            }
        }
        if (numberAt < 0) {
            return null;
        }
        String number = parts[numberAt];
        boolean nameAfter = numberAt + 1 < parts.length;
        String exampleName = null;
        if (nameAfter) {
            StringBuilder joined = new StringBuilder();
            for (int i = numberAt + 1; i < parts.length; i++) {
                if (!nameLine(parts[i]) && !acceptedRoomName(parts[i])) {
                    return null;
                }
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(parts[i]);
            }
            exampleName = joined.toString();
            if (!nameLine(exampleName) && !acceptedRoomName(exampleName)) {
                return null;
            }
        }
        String numberRegex = structure(number);
        String summary = describe(number, nameAfter);
        String namePart = nameAfter ? "\\s+([A-ZÅÄÖa-zåäö][A-ZÅÄÖa-zåäö ._-]{0,40})" : "";
        Pattern pattern = Pattern.compile("^(" + numberRegex + ")" + namePart + "$", Pattern.CASE_INSENSITIVE);
        Pattern numberLine = Pattern.compile("^" + numberRegex + "$", Pattern.CASE_INSENSITIVE);
        return new Derived(raw, summary, pattern, false, numberLine, numberRegex, nameAfter, exampleName);
    }

    private static Derived deriveStacked(String number, String name) {
        if (number.length() > 40 || name.length() > 40 || !number.matches(".*\\d.*") || !nameLine(name)) {
            return null;
        }
        String numberRegex = structure(number);
        String summary = describe(number, false) + ", nummer på en rad + namn under";
        Pattern numberLine = Pattern.compile("^" + numberRegex + "$", Pattern.CASE_INSENSITIVE);
        return new Derived(number + "\n" + name, summary, numberLine, true, numberLine, numberRegex, true, name);
    }

    private static ProtocolBuilder.RoomText parseStackedLines(String numberLine, String nameLine) {
        if (!looksLikeStackedName(nameLine)) {
            return null;
        }
        for (Derived derived : active) {
            if (!derived.stacked || derived.numberLine == null) {
                continue;
            }
            if (!derived.numberLine.matcher(numberLine).matches()) {
                continue;
            }
            return room(numberLine, nameLine);
        }
        return null;
    }

    private static boolean matchesNumber(String text, boolean stacked) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < active.size(); i++) {
            Derived derived = active.get(i);
            if (derived.stacked != stacked || derived.numberLine == null) {
                continue;
            }
            if (derived.numberLine.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasStacked() {
        for (Derived derived : active) {
            if (derived.stacked) {
                return true;
            }
        }
        return false;
    }

    /** Letters, including å ä ö, with spaces or hyphens between words. Vent labels are not names. */
    static boolean nameLine(String text) {
        if (text == null) {
            return false;
        }
        String raw = text.trim();
        if (!NAME_LINE.matcher(raw).matches()) {
            return false;
        }
        return LabelParser.parse(raw, 0, 0, 1) == null;
    }

    private static ProtocolBuilder.RoomText room(String number, String name) {
        String namn = name == null || name.trim().isEmpty() ? "" : LatinScript.correctRoomName(name.trim());
        return new ProtocolBuilder.RoomText(number.toUpperCase(Locale.ROOT), namn);
    }

    /**
     * Lower score is a better place for the name. A name under the number wins over one above it.
     * The gap is measured from centre to centre in units of the text height.
     */
    private static Double placementScore(OcrToken number, OcrToken name) {
        double font = Math.max(number.h, name.h);
        if (font < 1) {
            font = 8;
        }
        double dy = name.y - number.y;
        double ady = Math.abs(dy);
        if (ady < font * STACK_MIN || ady > font * STACK_MAX) {
            return null;
        }
        double dx = Math.abs(name.x - number.x);
        if (!stackedAligned(number, name, font, dx)) {
            return null;
        }
        double tier = dy > 0 ? 0 : 10000;
        return tier + ady + dx * 0.05;
    }

    /** Same x, centred on the number, or the number sitting inside the name's width. */
    private static boolean stackedAligned(OcrToken number, OcrToken name, double font, double dx) {
        double nw = number.w > 0.5 ? number.w : font * 2.5;
        double hw = name.w > 0.5 ? name.w : font * 2.5;
        if (dx <= Math.max(nw, hw) * 0.6) {
            return true;
        }
        double left = name.x - hw / 2.0;
        double right = name.x + hw / 2.0;
        return number.x >= left - font * 0.3 && number.x <= right + font * 0.3;
    }

    private static List<String> contentLines(String example) {
        List<String> lines = new ArrayList<String>();
        if (example == null) {
            return lines;
        }
        String[] split = example.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            String line = split[i].trim().replaceAll("[ \\t]+", " ");
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String structure(String number) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < number.length()) {
            char c = number.charAt(i);
            if (Character.isLetter(c)) {
                int n = 1;
                while (i + n < number.length() && Character.isLetter(number.charAt(i + n))) {
                    n++;
                }
                regex.append("[A-ZÅÄÖa-zåäö]{").append(n).append('}');
                i += n;
            } else if (Character.isDigit(c)) {
                int n = 1;
                while (i + n < number.length() && Character.isDigit(number.charAt(i + n))) {
                    n++;
                }
                int min = Math.max(1, n - 1);
                int max = n + 1;
                regex.append("\\d{").append(min).append(',').append(max).append('}');
                i += n;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return regex.toString();
    }

    private static String describe(String number, boolean nameAfter) {
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < number.length()) {
            if (text.length() > 0) {
                text.append(" + ");
            }
            char c = number.charAt(i);
            if (Character.isLetter(c)) {
                int n = 1;
                while (i + n < number.length() && Character.isLetter(number.charAt(i + n))) {
                    n++;
                }
                text.append(n == 1 ? "bokstav" : n + " bokstäver");
                i += n;
            } else if (Character.isDigit(c)) {
                int n = 1;
                while (i + n < number.length() && Character.isDigit(number.charAt(i + n))) {
                    n++;
                }
                text.append(n).append(n == 1 ? " siffra (±1)" : " siffror (±1)");
                i += n;
            } else {
                text.append("\"").append(c).append("\"");
                i++;
            }
        }
        if (nameAfter) {
            text.append(" + namn");
        }
        return text.toString();
    }

    public static List<Derived> deriveAll(List<String> examples) {
        List<Derived> out = new ArrayList<Derived>();
        if (examples == null) {
            return out;
        }
        for (String example : examples) {
            Derived derived = derive(example);
            if (derived != null) {
                out.add(derived);
            }
        }
        return out;
    }

    private static final class Hit {
        final OcrToken number;
        final OcrToken name;
        final double score;
        final String text;

        Hit(OcrToken number, OcrToken name, double score, String text) {
            this.number = number;
            this.name = name;
            this.score = score;
            this.text = text;
        }
    }
}
