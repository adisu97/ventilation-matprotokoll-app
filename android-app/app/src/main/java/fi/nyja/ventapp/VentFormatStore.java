package fi.nyja.ventapp;

import android.content.Context;
import android.content.SharedPreferences;

import fi.nyja.ventapp.engine.VentFormats;

/** Remembers the last vent-label example and installs it in the parser. */
public final class VentFormatStore {
    private static final String PREF = "vent_format";
    private static final String KEY = "example";

    private VentFormatStore() {
    }

    public static String load(Context context) {
        String raw = prefs(context).getString(KEY, "");
        if (raw == null) {
            raw = "";
        }
        VentFormats.use(raw);
        return raw;
    }

    public static void save(Context context, String example) {
        String text = example == null ? "" : example.replace("\r\n", "\n").replace('\r', '\n').trim();
        prefs(context).edit().putString(KEY, text).apply();
        VentFormats.use(text);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
