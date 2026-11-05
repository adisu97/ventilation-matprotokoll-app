package fi.nyja.ventapp.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extra room shapes learned from the user's examples, used together with the built-in patterns.
 */
public final class RoomFormats {
    public static final class Derived {
        public final String example;
        public final String summary;
        final Pattern pattern;

        Derived(String example, String summary, Pattern pattern) {
            this.example = example;
            this.summary = summary;
            this.pattern = pattern;
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

    public static Derived derive(String example) {
        if (example == null) {
            return null;
        }
        String raw = example.trim().replaceAll("\\s+", " ");
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
        String numberRegex = structure(number);
        String summary = describe(number, nameAfter);
        String namePart = nameAfter ? "(?:\\s+([A-ZÅÄÖa-zåäö][A-ZÅÄÖa-zåäö ._-]{0,40}))?" : "";
        Pattern pattern = Pattern.compile("^(" + numberRegex + ")" + namePart + "$", Pattern.CASE_INSENSITIVE);
        return new Derived(raw, summary, pattern);
    }

    public static ProtocolBuilder.RoomText parse(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        for (Derived derived : active) {
            Matcher matcher = derived.pattern.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            String number = matcher.group(1).toUpperCase(Locale.ROOT);
            String name = matcher.groupCount() >= 2 && matcher.group(2) != null
                    ? LatinScript.correctRoomName(matcher.group(2).trim()) : "";
            return new ProtocolBuilder.RoomText(number, name);
        }
        return null;
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
                regex.append("\\d{").append(n).append('}');
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
                text.append(n).append(n == 1 ? " siffra" : " siffror");
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
}
