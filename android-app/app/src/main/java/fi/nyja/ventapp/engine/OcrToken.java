package fi.nyja.ventapp.engine;

/** Text found on a drawing, in PDF points. */
public final class OcrToken {
    public final String text;
    public final double x;
    public final double y;
    public final double confidence;
    public final double w;
    public final double h;

    public OcrToken(String text, double x, double y, double confidence) {
        this(text, x, y, confidence, 0, 0);
    }

    public OcrToken(String text, double x, double y, double confidence, double w, double h) {
        this.text = text == null ? "" : text;
        this.x = x;
        this.y = y;
        this.confidence = confidence;
        this.w = w;
        this.h = h;
    }
}
