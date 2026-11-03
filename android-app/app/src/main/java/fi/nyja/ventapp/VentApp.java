package fi.nyja.ventapp;

import android.app.Application;
import android.content.Context;

/** Installs the crash handler before content providers and the first activity. */
public class VentApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        CrashLog.install(this);
    }
}
