package fi.nyja.ventapp;

import android.content.Context;
import android.content.SharedPreferences;

import fi.nyja.ventapp.engine.LabelParser;
import fi.nyja.ventapp.engine.VentCatalog;

/** Persists disabled built-ins and user-added names, then installs them in the parser. */
public final class VentNameStore {
    private static final String PREF = "vent_names";
    private static final String KEY = "json";

    private VentNameStore() {
    }

    public static VentCatalog load(Context context) {
        String json = prefs(context).getString(KEY, "");
        VentCatalog catalog = VentCatalog.fromJson(json);
        LabelParser.use(catalog);
        return catalog;
    }

    public static void save(Context context, VentCatalog catalog) {
        prefs(context).edit().putString(KEY, catalog.toJson()).apply();
        LabelParser.use(catalog);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
