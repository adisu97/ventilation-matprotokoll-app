package fi.nyja.ventapp.engine;

/** Maps a point in a clockwise-rotated bitmap back to the original pixels. */
public final class OcrGeometry {
    private OcrGeometry() {
    }

    public static double[] unmapClockwise(double x, double y, int width, int height, int degrees) {
        int turn = ((degrees % 360) + 360) % 360;
        if (turn == 90) {
            return new double[]{y, height - 1 - x};
        }
        if (turn == 270) {
            return new double[]{width - 1 - y, x};
        }
        if (turn == 180) {
            return new double[]{width - 1 - x, height - 1 - y};
        }
        return new double[]{x, y};
    }

    /** Clockwise rotation used by Bitmap.createBitmap(..., postRotate). */
    public static double[] rotateClockwise(double x, double y, int width, int height, int degrees) {
        int turn = ((degrees % 360) + 360) % 360;
        if (turn == 90) {
            return new double[]{height - 1 - y, x};
        }
        if (turn == 270) {
            return new double[]{y, width - 1 - x};
        }
        if (turn == 180) {
            return new double[]{width - 1 - x, height - 1 - y};
        }
        return new double[]{x, y};
    }
}
