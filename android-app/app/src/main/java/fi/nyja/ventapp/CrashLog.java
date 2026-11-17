package fi.nyja.ventapp;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/** Writes a crash report and opens the Swedish error screen. */
public final class CrashLog {
    public static final String FILE_NAME = "ventapp-crashlog.txt";

    private CrashLog() {
    }

    public static void install(final Context app) {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                String text = format(app, thread, error);
                write(app, text);
                if (!fromCrashScreen(error)) {
                    try {
                        Intent intent = new Intent(app, CrashActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        intent.putExtra(CrashActivity.EXTRA_TEXT, text);
                        app.startActivity(intent);
                        Thread.sleep(400);
                    } catch (Throwable ignored) {
                        // The file is already written. Dying is safer than looping.
                    }
                }
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    public static void report(Context context, Throwable error) {
        String text = format(context, Thread.currentThread(), error);
        write(context, text);
        Intent intent = new Intent(context, CrashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(CrashActivity.EXTRA_TEXT, text);
        context.startActivity(intent);
    }

    public static String versionLabel(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return "Version " + info.versionName + " (" + code + ")";
        } catch (Exception error) {
            return "Version okänd";
        }
    }

    public static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    buf.write(chunk, 0, n);
                }
                return buf.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception error) {
            return null;
        }
    }

    private static boolean fromCrashScreen(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (CrashActivity.class.getName().equals(frame.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    static String format(Context context, Thread thread, Throwable error) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf);
        out.println(versionLabel(context));
        out.println("Tråd: " + (thread == null ? "?" : thread.getName()));
        out.println("Enhet: " + Build.MANUFACTURER + " " + Build.MODEL + " API " + Build.VERSION.SDK_INT);
        error.printStackTrace(out);
        out.flush();
        try {
            return buf.toString("UTF-8");
        } catch (Exception ignored) {
            return buf.toString();
        }
    }

    static String write(Context context, String text) {
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (Exception error) {
            bytes = text.getBytes();
        }
        try {
            FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME));
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
            // The screen still shows the text.
        }
        String where = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = context.getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        try {
                            out.write(bytes);
                        } finally {
                            out.close();
                        }
                        where = "Downloads/" + FILE_NAME;
                    }
                }
            } else {
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    FileOutputStream out = new FileOutputStream(new File(dir, FILE_NAME));
                    try {
                        out.write(bytes);
                    } finally {
                        out.close();
                    }
                    where = dir.getAbsolutePath() + "/" + FILE_NAME;
                }
            }
        } catch (Exception ignored) {
            where = null;
        }
        return where;
    }
}
