package fi.nyja.ventapp;

/**
 * What the front screen shows while files are read.
 * A real extractor can publish the same snapshot later.
 */
public final class ProgressSnapshot {
    public final int currentFile;
    public final int totalFiles;
    public final int percent;
    public final long remainingMs;

    public ProgressSnapshot(int currentFile, int totalFiles, int percent, long remainingMs) {
        this.currentFile = currentFile;
        this.totalFiles = totalFiles;
        this.percent = percent;
        this.remainingMs = remainingMs;
    }

    public String statusText() {
        return "Läser fil " + currentFile + " av " + totalFiles;
    }

    public String remainingLabel() {
        long seconds = (remainingMs + 999L) / 1000L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        String secondsText = rest < 10 ? "0" + rest : Long.toString(rest);
        return "Beräknad tid kvar: " + minutes + ":" + secondsText;
    }

    public static ProgressSnapshot fromElapsed(long elapsedMs, int total, long perFileMs) {
        if (total < 1) {
            return new ProgressSnapshot(0, 0, 0, 0);
        }
        long totalMs = (long) total * perFileMs;
        long clamped = Math.max(0L, Math.min(elapsedMs, totalMs));
        long remaining = totalMs - clamped;
        int percent = totalMs == 0 ? 0 : (int) Math.min(100L, clamped * 100L / totalMs);
        int current = (int) Math.min(total, clamped / perFileMs + 1L);
        if (current < 1) {
            current = 1;
        }
        if (clamped >= totalMs) {
            current = total;
            percent = 100;
            remaining = 0;
        }
        return new ProgressSnapshot(current, total, percent, remaining);
    }
}
