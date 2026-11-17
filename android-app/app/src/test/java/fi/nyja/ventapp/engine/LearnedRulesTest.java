package fi.nyja.ventapp.engine;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import fi.nyja.ventapp.LearnedRuleStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LearnedRulesTest {
    @Test
    public void ruleMatchesTheWholeTextOnlyAndRoundTrips() throws Exception {
        LearnedRules rules = new LearnedRules();
        String raw = "LOCKZONE Ca 250-600-RO";
        LearnedRules.Rule saved = rules.put(LearnedRules.SIZE, "  " + raw + "  ", "250");
        assertEquals("250", rules.match(LearnedRules.SIZE, raw).value);
        assertEquals("250", rules.match(LearnedRules.SIZE, "lockzone   ca 250-600-ro").value);
        assertNull(rules.match(LearnedRules.SIZE, raw + " extra"));
        assertNull(rules.match(LearnedRules.SIZE, "Ca 250-600-RO"));
        assertNull(rules.match(LearnedRules.NAME, raw));
        rules.put(LearnedRules.SIZE, raw, "315");
        assertEquals(1, rules.all().size());
        assertEquals("315", rules.match(LearnedRules.SIZE, raw).value);
        assertTrue(rules.remove(saved.id));
        assertNull(rules.match(LearnedRules.SIZE, raw));

        rules.put(LearnedRules.NAME, "KSO \"fg\"\n125", "KSO");
        LearnedRules loaded = LearnedRules.fromJson(rules.toJson());
        assertEquals("KSO", loaded.match(LearnedRules.NAME, "KSO \"fg\"\n125").value);
        File file = new File("build/learned-rules-test.json");
        rules.write(file);
        try {
            assertEquals("KSO", LearnedRules.read(file).match(LearnedRules.NAME, "kso \"fg\"\n125").value);
        } finally {
            file.delete();
        }
    }

    @Test
    public void correctionIsAppliedBeforeASizeIsCalledUnclear() {
        String raw = "LOCKZONE Ca 250-600-RO+ALSC 200-250/60";
        Vent messy = LabelParser.parse(raw, 30, 10, 0.9);
        assertFalse(LabelParser.sizeIsClear(messy.raw, messy.size));
        LearnedRules rules = new LearnedRules();
        rules.put(LearnedRules.SIZE, messy.raw, "250");
        rules.put(LearnedRules.SIDE, messy.raw, "exhaust");
        rules.put(LearnedRules.BEHOV, messy.raw, "27,5");
        LearnedRules.install(rules);
        try {
            Room room = new Room("E125", "WC", 0, 0, 1);
            ProtocolBuilder.Prepared prepared = ProtocolBuilder.prepare(
                    Collections.singletonList(room), Collections.singletonList(messy));
            assertTrue(prepared.pending.isEmpty());
            assertEquals(1, prepared.rows.size());
            assertEquals("250", prepared.rows.get(0).exhaustSize);
            assertEquals(27.5, prepared.rows.get(0).exhaustBehov, 0.01);
            assertEquals("", prepared.rows.get(0).supplyName);

            rules.put(LearnedRules.DROP, messy.raw, "1");
            ProtocolBuilder.Prepared dropped = ProtocolBuilder.prepare(
                    Collections.singletonList(room), Collections.singletonList(messy));
            assertTrue(dropped.rows.isEmpty());
            assertTrue(dropped.pending.isEmpty());
        } finally {
            LearnedRules.install(new LearnedRules());
        }
    }

    @Test
    public void resetDeletesTheRuleFileAndTurnsExamplesOff() throws Exception {
        File dir = new File("build/rule-reset");
        dir.mkdirs();
        File file = new File(dir, "learned-rules.json");
        LearnedRules rules = new LearnedRules();
        rules.put(LearnedRules.SIZE, "EAGLE fg 125", "160");
        rules.write(file);
        LearnedRules.install(rules);
        VentFormats.use("Eagle fg 125-ro\n+27");
        RoomFormats.use(Collections.singletonList(RoomFormats.derive("E111\nVINDFÅNG")));
        try {
            assertTrue(file.isFile());
            assertTrue(VentFormats.active());
            assertTrue(RoomFormats.constrains());
            LearnedRuleStore.resetStored(file);
            assertFalse(file.exists());
            assertTrue(LearnedRules.current().all().isEmpty());
            assertFalse(VentFormats.active());
            assertFalse(RoomFormats.constrains());
            assertNull(LearnedRules.current().match(LearnedRules.SIZE, "EAGLE fg 125"));
        } finally {
            LearnedRules.install(new LearnedRules());
            VentFormats.use("");
            RoomFormats.use(Collections.<RoomFormats.Derived>emptyList());
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void broadSearchKeepsAnyWordNamesAndSkipsAreaWithoutARoomFormat() {
        RoomFormats.use(Collections.<RoomFormats.Derived>emptyList());
        try {
            java.util.List<OcrToken> tokens = Arrays.asList(
                    new OcrToken("E116", 40, 40, 0.98, 36, 14),
                    new OcrToken("Trappa", 48, 62, 0.98, 50, 14),
                    new OcrToken("26,7 m^3", 44, 84, 0.9, 48, 12),
                    new OcrToken("E111", 200, 40, 0.95, 36, 14),
                    new OcrToken("WC2", 208, 62, 0.95, 36, 14),
                    new OcrToken("Kok", 208, 200, 0.95, 30, 14));
            java.util.List<Room> rooms = ProtocolBuilder.roomsFromTokens(tokens);
            assertEquals("Trappa", room(rooms, "E116").name);
            assertEquals("WC2", room(rooms, "E111").name);
            assertTrue(ProtocolBuilder.ventsFromTokens(tokens).isEmpty());
            String joined = rooms.get(0).number + " " + rooms.get(0).name;
            assertFalse(joined.contains("26,7"));
        } finally {
            RoomFormats.use(Collections.<RoomFormats.Derived>emptyList());
        }
    }

    private static Room room(java.util.List<Room> rooms, String number) {
        for (Room room : rooms) {
            if (number.equals(room.number)) {
                return room;
            }
        }
        throw new AssertionError(number);
    }
}
