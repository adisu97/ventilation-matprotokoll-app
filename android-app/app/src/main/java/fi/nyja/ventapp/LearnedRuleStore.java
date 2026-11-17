package fi.nyja.ventapp;

import android.content.Context;

import java.io.File;
import java.util.Collections;

import fi.nyja.ventapp.engine.LabelParser;
import fi.nyja.ventapp.engine.LearnedRules;
import fi.nyja.ventapp.engine.RoomFormats;
import fi.nyja.ventapp.engine.VentCatalog;
import fi.nyja.ventapp.engine.VentFormats;

/** JSON file in app-private storage. Nothing is uploaded. */
public final class LearnedRuleStore {
    private static final String FILE = "learned-rules.json";

    private LearnedRuleStore() {
    }

    public static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    public static LearnedRules load(Context context) {
        LearnedRules rules = LearnedRules.read(file(context));
        LearnedRules.install(rules);
        return rules;
    }

    public static void save(Context context, LearnedRules rules) throws Exception {
        LearnedRules stored = rules == null ? new LearnedRules() : rules;
        stored.write(file(context));
        LearnedRules.install(stored);
    }

    /** Clears learned rules, vent examples, leftover room examples and custom vent names. */
    public static void reset(Context context) {
        resetStored(file(context));
        VentFormatStore.save(context, "");
        RoomFormatStore.save(context, Collections.<String>emptyList());
        VentNameStore.save(context, VentCatalog.defaults());
        AppLocale.set(context, AppLocale.SV);
    }

    /** File and in-memory rules, with vent and room examples turned off. No Android context. */
    public static void resetStored(File rulesFile) {
        if (rulesFile != null && rulesFile.exists() && !rulesFile.delete()) {
            rulesFile.deleteOnExit();
        }
        LearnedRules.install(new LearnedRules());
        VentFormats.use("");
        RoomFormats.use(Collections.<RoomFormats.Derived>emptyList());
        LabelParser.use(VentCatalog.defaults());
    }
}
