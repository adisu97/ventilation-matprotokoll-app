package fi.nyja.ventapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/** Svenska is the default. Suomi is values-fi. */
public final class AppLocale {
    public static final String SV = "sv";
    public static final String FI = "fi";
    private static final String PREF = "app_settings";
    private static final String KEY = "lang";

    private AppLocale() {
    }

    public static String get(Context context) {
        String lang = prefs(context).getString(KEY, SV);
        return FI.equals(lang) ? FI : SV;
    }

    public static void set(Context context, String lang) {
        prefs(context).edit().putString(KEY, FI.equals(lang) ? FI : SV).apply();
    }

    public static Context wrap(Context base) {
        Locale locale = new Locale(get(base));
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
