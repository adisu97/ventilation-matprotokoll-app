package fi.nyja.ventapp.engine;

public final class Room {
    public final String number;
    public final String name;
    public final double x;
    public final double y;
    public final double confidence;

    public Room(String number, String name, double x, double y, double confidence) {
        this.number = number == null ? "" : number;
        this.name = name == null ? "" : name;
        this.x = x;
        this.y = y;
        this.confidence = confidence;
    }

    public String label() {
        String num = number.trim();
        String namn = name.trim();
        if (!num.isEmpty() && !namn.isEmpty()) {
            return num + " " + namn;
        }
        return num.isEmpty() ? namn : num;
    }
}
