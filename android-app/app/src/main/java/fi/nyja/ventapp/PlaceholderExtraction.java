package fi.nyja.ventapp;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Stand-in for the real PDF/photo extraction.
 * Replace the timer with calls that publish {@link ProgressSnapshot} from the pipeline.
 */
public final class PlaceholderExtraction {
    public interface Listener {
        void onProgress(ProgressSnapshot snapshot);

        void onComplete(ProgressSnapshot snapshot);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable tick;
    private Listener listener;
    private long startedAt;
    private int total;
    private long perFileMs;
    private boolean running;

    public void start(int total, long perFileMs, long alreadyElapsedMs, Listener listener) {
        cancel();
        this.total = total;
        this.perFileMs = perFileMs;
        this.listener = listener;
        this.startedAt = SystemClock.elapsedRealtime() - Math.max(0L, alreadyElapsedMs);
        this.running = true;
        tick = new Runnable() {
            @Override
            public void run() {
                if (!running || PlaceholderExtraction.this.listener == null) {
                    return;
                }
                long elapsed = SystemClock.elapsedRealtime() - startedAt;
                long totalMs = (long) PlaceholderExtraction.this.total * PlaceholderExtraction.this.perFileMs;
                ProgressSnapshot snapshot = ProgressSnapshot.fromElapsed(
                        elapsed,
                        PlaceholderExtraction.this.total,
                        PlaceholderExtraction.this.perFileMs);
                if (elapsed >= totalMs) {
                    running = false;
                    PlaceholderExtraction.this.listener.onProgress(snapshot);
                    PlaceholderExtraction.this.listener.onComplete(snapshot);
                    return;
                }
                PlaceholderExtraction.this.listener.onProgress(snapshot);
                handler.postDelayed(this, 100L);
            }
        };
        handler.post(tick);
    }

    public void cancel() {
        running = false;
        if (tick != null) {
            handler.removeCallbacks(tick);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long elapsedMs() {
        if (!running) {
            return 0L;
        }
        return Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
    }
}
