package fi.nyja.ventapp.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout learned from one vent example, such as "Eagle fg 125-ro" and "+27" on the next line.
 * The name in the example is only a sample. Any catalog name can use the same shape.
 * A signed airflow decides tilluft or frånluft. Without a sign, the catalog decides.
 */
public final class VentFormats {
    public static final class Derived {
        public final String example;
        public final boolean signed;

        Derived(String example, boolean signed) {
            this.example = example;
            this.signed = signed;
        }
    }

    private static volatile boolean active;
    private static volatile boolean signOverrides;

    private VentFormats() {
    }

    public static void use(String example) {
        if (example == null || example.trim().isEmpty()) {
            active = false;
            signOverrides = false;
            return;
        }
        Derived derived = derive(example);
        active = derived != null;
        signOverrides = derived != null && derived.signed;
    }

    public static boolean active() {
        return active;
    }

    public static boolean signOverrides() {
        return signOverrides;
    }

    public static Derived derive(String example) {
        List<String> lines = lines(example);
        if (lines.isEmpty() || lines.size() > 2) {
            return null;
        }
        Vent vent = LabelParser.parse(lines.get(0), 0, 0, 1);
        if (vent == null || vent.size == null || !vent.size.matches("\\d+")) {
            return null;
        }
        boolean signed = false;
        if (lines.size() == 2) {
            LabelParser.Airflow air = LabelParser.parseAirflowToken(lines.get(1));
            if (air == null || !air.signed) {
                return null;
            }
            signed = true;
        }
        String stored = lines.get(0);
        if (lines.size() == 2) {
            stored = stored + "\n" + lines.get(1);
        }
        return new Derived(stored, signed);
    }

    private static List<String> lines(String example) {
        List<String> lines = new ArrayList<String>();
        if (example == null) {
            return lines;
        }
        String[] split = example.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            String line = split[i].trim().replaceAll("[ \\t]+", " ");
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
