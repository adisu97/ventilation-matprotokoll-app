package fi.nyja.ventapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

import fi.nyja.ventapp.engine.RoomFormats;

/** Remembers the user's room-number examples and installs them in the parser. */
public final class RoomFormatStore {
    private static final String PREF = "room_formats";
    private static final String KEY = "examples";

    private RoomFormatStore() {
    }

    public static List<String> load(Context context) {
        String raw = prefs(context).getString(KEY, "");
        List<String> examples = RoomFormats.decodeExamples(raw);
        RoomFormats.use(RoomFormats.deriveAll(examples));
        return examples;
    }

    public static void save(Context context, List<String> examples) {
        prefs(context).edit().putString(KEY, RoomFormats.encodeExamples(examples)).apply();
        RoomFormats.use(RoomFormats.deriveAll(examples));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
