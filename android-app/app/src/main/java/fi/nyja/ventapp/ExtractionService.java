package fi.nyja.ventapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import fi.nyja.ventapp.engine.PendingSize;
import fi.nyja.ventapp.engine.ProtocolXlsx;
import fi.nyja.ventapp.engine.SheetNames;

/** Keeps extraction alive across rotation and while the screen is locked. */
public class ExtractionService extends Service {
    public static final String EXTRA_EXCEL = "excel";
    public static final String EXTRA_EXISTING = "existing";
    public static final String EXTRA_ITEMS = "items";

    private static final State STATE = new State();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<Runnable>();

    public static void listen(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void unlisten(Runnable listener) {
        LISTENERS.remove(listener);
    }

    public static State state() {
        return STATE;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (intent == null || STATE.running) {
            return START_NOT_STICKY;
        }
        final String excel = intent.getStringExtra(EXTRA_EXCEL);
        final String existing = intent.getStringExtra(EXTRA_EXISTING);
        final ArrayList<DrawingEntry> items = intent.getParcelableArrayListExtra(EXTRA_ITEMS);
        STATE.running = true;
        STATE.done = false;
        STATE.needsSize = false;
        STATE.error = null;
        STATE.notes = new ArrayList<String>();
        STATE.debug = "";
        STATE.xlsx = null;
        STATE.pending = new ArrayList<PendingSize>();
        STATE.sheets = null;
        STATE.template = null;
        STATE.filename = excel;
        STATE.startedAt = android.os.SystemClock.elapsedRealtime();
        notifyListeners();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DrawingExtractor.Outcome outcome = DrawingExtractor.extract(
                            ExtractionService.this,
                            items,
                            existing,
                            new DrawingExtractor.Progress() {
                                @Override
                                public void onProgress(int fileIndex, int fileCount, float fraction, String message) {
                                    STATE.fileIndex = fileIndex;
                                    STATE.fileCount = fileCount;
                                    STATE.fraction = fraction;
                                    STATE.currentName = message;
                                    notifyListeners();
                                }
                            });
                    STATE.notes = outcome.notes;
                    STATE.debug = outcome.debug;
                    STATE.sheets = outcome.sheets;
                    STATE.template = outcome.template;
                    STATE.fromTemplate = outcome.fromTemplate;
                    STATE.pending = outcome.pending == null
                            ? new ArrayList<PendingSize>() : outcome.pending;
                    STATE.needsSize = !STATE.pending.isEmpty();
                    STATE.xlsx = outcome.xlsx;
                    STATE.done = true;
                } catch (Exception ex) {
                    STATE.error = ex.getMessage() == null ? "Avläsningen misslyckades." : ex.getMessage();
                    STATE.done = true;
                } finally {
                    STATE.running = false;
                    notifyListeners();
                    if (Build.VERSION.SDK_INT >= 24) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    stopSelf();
                }
            }
        }, "matprotokoll-ocr").start();
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        String channel = "matprotokoll";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel notificationChannel = new NotificationChannel(
                    channel, "Mätprotokoll", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(notificationChannel);
            Notification notification = new Notification.Builder(this, channel)
                    .setContentTitle("Läser ritningar")
                    .setContentText("Mätprotokoll arbetar.")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(7, notification);
            }
        }
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    public static final class State {
        public volatile boolean running;
        public volatile boolean done;
        public volatile int fileIndex;
        public volatile int fileCount;
        public volatile float fraction;
        public volatile String currentName = "";
        public volatile String error;
        public volatile String filename = SheetNames.workbookFileName("matprotokoll");
        public volatile String debug = "";
        public volatile byte[] xlsx;
        public volatile boolean needsSize;
        public volatile boolean fromTemplate;
        public volatile byte[] template;
        public volatile List<ProtocolXlsx.DrawingSheet> sheets;
        public volatile List<PendingSize> pending = new ArrayList<PendingSize>();
        public volatile List<String> notes = new ArrayList<String>();
        public volatile long startedAt;
    }
}
