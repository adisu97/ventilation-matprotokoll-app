package fi.nyja.ventapp;

import java.util.Arrays;
import java.util.List;

/** Starta asks for the room example first, then the vent example, and only then reads. */
public final class ReadSetup {
    public static final String ROOM = "room";
    public static final String VENT = "vent";

    private ReadSetup() {
    }

    public static List<String> promptOrder() {
        return Arrays.asList(ROOM, VENT);
    }
}
