package fi.nyja.ventapp.engine;

import java.util.Locale;

/**
 * Drawings use Swedish, English or Finnish. The OCR dictionary is mostly CJK and has no å or ö.
 */
public final class LatinScript {
    /** Ordinary room names in Swedish, English and Finnish. The folded key is the lookup. */
    private static final String[][] ROOM_NAMES = {
            {"kök", "Kök"},
            {"kok", "Kök"},
            {"kyl", "Kyl"},
            {"frys", "Frys"},
            {"wc", "WC"},
            {"toalett", "Toalett"},
            {"badrum", "Badrum"},
            {"dusch", "Dusch"},
            {"tvätt", "Tvätt"},
            {"tvättstuga", "Tvättstuga"},
            {"sovrum", "Sovrum"},
            {"vardagsrum", "Vardagsrum"},
            {"allrum", "Allrum"},
            {"hall", "Hall"},
            {"entré", "Entré"},
            {"tambur", "Tambur"},
            {"vindfång", "Vindfång"},
            {"förråd", "Förråd"},
            {"klädkammare", "Klädkammare"},
            {"kapprum", "Kapprum"},
            {"arbetsrum", "Arbetsrum"},
            {"barnrum", "Barnrum"},
            {"gästrum", "Gästrum"},
            {"matsal", "Matsal"},
            {"grovkök", "Grovkök"},
            {"pannrum", "Pannrum"},
            {"teknik", "Teknik"},
            {"korridor", "Korridor"},
            {"balkong", "Balkong"},
            {"bastu", "Bastu"},
            {"kontor", "Kontor"},
            {"aula", "Aula"},
            {"terrass", "Terrass"},
            {"garage", "Garage"},
            {"kitchen", "Kitchen"},
            {"bathroom", "Bathroom"},
            {"bedroom", "Bedroom"},
            {"living room", "Living room"},
            {"livingroom", "Living room"},
            {"dining room", "Dining room"},
            {"office", "Office"},
            {"storage", "Storage"},
            {"hallway", "Hallway"},
            {"entrance", "Entrance"},
            {"lobby", "Lobby"},
            {"corridor", "Corridor"},
            {"laundry", "Laundry"},
            {"closet", "Closet"},
            {"pantry", "Pantry"},
            {"utility", "Utility"},
            {"toilet", "Toilet"},
            {"shower", "Shower"},
            {"balcony", "Balcony"},
            {"sauna", "Sauna"},
            {"keittiö", "Keittiö"},
            {"kylpyhuone", "Kylpyhuone"},
            {"makuuhuone", "Makuuhuone"},
            {"olohuone", "Olohuone"},
            {"eteinen", "Eteinen"},
            {"varasto", "Varasto"},
            {"ruokailu", "Ruokailu"},
            {"suihku", "Suihku"},
            {"kodinhoitohuone", "Kodinhoitohuone"},
            {"vaatehuone", "Vaatehuone"},
            {"pesuhuone", "Pesuhuone"},
            {"tuulikaappi", "Tuulikaappi"},
            {"toimisto", "Toimisto"},
            {"käytävä", "Käytävä"},
            {"parveke", "Parveke"},
            {"khh", "KHH"},
            {"ky", "Ky"},
            {"ts", "TS"},
            {"tm", "TM"}
    };

    private static final java.util.Map<String, String> ROOM_BY_KEY = new java.util.HashMap<String, String>();

    static {
        for (int i = 0; i < ROOM_NAMES.length; i++) {
            ROOM_BY_KEY.put(fold(ROOM_NAMES[i][0]), ROOM_NAMES[i][1]);
        }
    }

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
        String canon = ROOM_BY_KEY.get(fold(raw));
        if (canon == null) {
            return raw;
        }
        if (raw.equalsIgnoreCase(canon)) {
            return raw;
        }
        if (!fold(canon).equals(canon.toLowerCase(Locale.ROOT))) {
            return canon;
        }
        return raw;
    }

    public static boolean isKnownRoomName(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return ROOM_BY_KEY.containsKey(fold(text.trim()));
    }

    /** Same room word, ignoring case and å/ä/ö folded to a/a/o. */
    public static boolean sameRoomName(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return fold(a.trim()).equals(fold(b.trim()));
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
