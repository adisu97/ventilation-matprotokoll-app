package fi.nyja.ventapp.engine;

/** A vent that stays out of the sheet until the user types a size. */
public final class PendingSize {
    public final int sheetIndex;
    public final String room;
    public final Vent vent;

    public PendingSize(int sheetIndex, String room, Vent vent) {
        this.sheetIndex = sheetIndex;
        this.room = room == null ? "" : room;
        this.vent = vent;
    }

    public PendingSize atSheet(int index) {
        return new PendingSize(index, room, vent);
    }
}
