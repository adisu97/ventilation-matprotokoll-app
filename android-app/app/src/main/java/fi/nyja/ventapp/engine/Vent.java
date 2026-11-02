package fi.nyja.ventapp.engine;

/** One vent found on a drawing. */
public final class Vent {
    public final String name;
    public final String kind;
    public final String size;
    public final Double airflow;
    public final int qty;
    public final double x;
    public final double y;
    public final String raw;
    public final double confidence;

    public Vent(String name, String kind, String size, Double airflow, int qty,
                double x, double y, String raw, double confidence) {
        this.name = name;
        this.kind = kind;
        this.size = size;
        this.airflow = airflow;
        this.qty = qty;
        this.x = x;
        this.y = y;
        this.raw = raw == null ? "" : raw;
        this.confidence = confidence;
    }

    public String canonicalName() {
        String n = name == null ? "" : name.trim().toLowerCase();
        return "huk".equals(n) ? "hu" : n;
    }
}
