package fi.nyja.ventapp;

import java.util.Arrays;
import java.util.List;

/** Starta asks for the vent example, then reads. Room format is no longer collected. */
public final class ReadSetup {
    public static final String VENT = "vent";

    private ReadSetup() {
    }

    public static List<String> promptOrder() {
        return Arrays.asList(VENT);
    }
}
