package fi.nyja.ventapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
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
        List<String> examples = new ArrayList<String>();
        if (raw != null && raw.length() > 0) {
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().length() > 0) {
                    examples.add(lines[i].trim());
                }
            }
        }
        RoomFormats.use(RoomFormats.deriveAll(examples));
        return examples;
    }

    public static void save(Context context, List<String> examples) {
        StringBuilder raw = new StringBuilder();
        if (examples != null) {
            for (int i = 0; i < examples.size(); i++) {
                if (i > 0) {
                    raw.append('\n');
                }
                raw.append(examples.get(i).trim());
            }
        }
        prefs(context).edit().putString(KEY, raw.toString()).apply();
        RoomFormats.use(RoomFormats.deriveAll(examples));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
