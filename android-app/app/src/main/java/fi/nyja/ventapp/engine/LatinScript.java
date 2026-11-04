package fi.nyja.ventapp.engine;

import java.util.Locale;

/**
 * Drawings use Swedish, English or Finnish. The OCR dictionary is mostly CJK and has no å or ö.
 */
public final class LatinScript {
    private static final String[][] ROOM_NAMES = {
            {"kök", "Kök"},
            {"kyl", "Kyl"},
            {"frys", "Frys"},
            {"wc", "WC"},
            {"dusch", "Dusch"},
            {"tvättstuga", "Tvättstuga"},
            {"sovrum", "Sovrum"},
            {"vardagsrum", "Vardagsrum"},
            {"hall", "Hall"},
            {"förråd", "Förråd"},
            {"teknik", "Teknik"},
            {"ruokailu", "Ruokailu"},
            {"keittiö", "Keittiö"},
            {"kylpyhuone", "Kylpyhuone"},
            {"makuuhuone", "Makuuhuone"},
            {"olohuone", "Olohuone"},
            {"eteinen", "Eteinen"},
            {"varasto", "Varasto"},
            {"aula", "Aula"},
            {"terrass", "Terrass"},
            {"allrum", "Allrum"},
            {"bastu", "Bastu"},
            {"kontor", "Kontor"},
            {"kapprum", "Kapprum"},
            {"suihku", "Suihku"},
            {"ky", "Ky"},
            {"ts", "TS"},
            {"tm", "TM"}
    };

    private LatinScript() {
    }

    public static boolean hasCjk(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Dictionary keys the recogniser may emit. CJK keys are refused. */
    public static boolean allowedKey(String key) {
        if (key == null || key.isEmpty() || hasCjk(key)) {
            return false;
        }
        for (int i = 0; i < key.length(); ) {
            int cp = key.codePointAt(i);
            if (!allowedChar(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /** Drop CJK. A box that is mostly CJK becomes empty so it cannot become a room. */
    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!hasCjk(text)) {
            return text.trim();
        }
        int latin = 0;
        int cjk = 0;
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                if (allowedChar(cp) || Character.isWhitespace(cp)) {
                    kept.appendCodePoint(cp);
                }
                if (Character.isLetterOrDigit(cp)) {
                    latin++;
                }
            }
            i += Character.charCount(cp);
        }
        if (cjk > 0 && latin == 0) {
            return "";
        }
        return kept.toString().trim();
    }

    /**
     * Restore å/ä/ö when the OCR dictionary spelled a known room name without them.
     * Text that is already the right word keeps its own capitals, so AULA stays AULA.
     */
    public static String correctRoomName(String text) {
        if (text == null) {
            return "";
        }
        String raw = text.trim();
        if (raw.isEmpty() || hasCjk(raw)) {
            return clean(raw);
        }
        String folded = fold(raw);
        for (String[] pair : ROOM_NAMES) {
            if (!folded.equals(fold(pair[0]))) {
                continue;
            }
            if (raw.equalsIgnoreCase(pair[1])) {
                return raw;
            }
            if (!fold(pair[1]).equals(pair[1].toLowerCase(Locale.ROOT))) {
                return pair[1];
            }
        }
        return raw;
    }

    public static boolean isKnownRoomName(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String folded = fold(text.trim());
        for (String[] pair : ROOM_NAMES) {
            if (folded.equals(fold(pair[0]))) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowedChar(int cp) {
        if (cp < 128) {
            return Character.isLetterOrDigit(cp) || ".,;:+-/%'()".indexOf(cp) >= 0;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                || block == Character.UnicodeBlock.LATIN_EXTENDED_A
                || block == Character.UnicodeBlock.LATIN_EXTENDED_B;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.replace("å", "a").replace("ä", "a").replace("ö", "o")
                .replace("é", "e").replace("ü", "u");
    }
}
