package fi.nyja.ventapp.engine;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProtocolLogicTest {
    @Test
    public void roomNumberCanSitInsideALongerLine() {
        assertEquals("E125", ProtocolBuilder.roomNumber("E125 WC"));
        assertEquals("E125", ProtocolBuilder.roomNumber("e125wc"));
        assertEquals("WC", ProtocolBuilder.splitRoom("E125 WC").name);
        assertEquals("wc", ProtocolBuilder.splitRoom("e125wc").name);
        assertEquals("125", ProtocolBuilder.splitRoom("125WC").number);
        assertEquals("WC", ProtocolBuilder.splitRoom("125WC").name);
        assertEquals("102", ProtocolBuilder.splitRoom("102AULA").number);
        assertEquals("AULA", ProtocolBuilder.splitRoom("102AULA").name);
        assertEquals("111A", ProtocolBuilder.splitRoom("111a").number);
        assertEquals("", ProtocolBuilder.splitRoom("111a").name);
        assertNull(ProtocolBuilder.roomNumber("KSO-125"));
        java.util.List<Room> rooms = ProtocolBuilder.roomsFromTokens(
                java.util.Collections.singletonList(new OcrToken("E125 WC", 10, 20, 1)));
        assertEquals(1, rooms.size());
        assertEquals("E125", rooms.get(0).number);
        assertEquals("WC", rooms.get(0).name);
    }

    @Test
    public void nearbyRoomNameComesFromTheTextAboveTheNumber() {
        List<OcrToken> tokens = Arrays.asList(
                new OcrToken("WC", 49.2, 35.7, 1),
                new OcrToken("E125", 52.8, 50.7, 1),
                new OcrToken("KSO-125-C", 60, 55, 1),
                new OcrToken("AULA", 54.4, 175.7, 1),
                new OcrToken("E102", 52.8, 190.7, 1),
                new OcrToken("Ky", 10, 10, 1),
                new OcrToken("E201", 12, 24, 1),
                new OcrToken("TS", 80, 10, 1),
                new OcrToken("130", 82, 22, 1),
                new OcrToken("Terrass", 200, 8, 1),
                new OcrToken("E140", 202, 28, 1));
        List<Room> rooms = ProtocolBuilder.roomsFromTokens(tokens);
        assertEquals("WC", roomNamed(rooms, "E125").name);
        assertEquals("AULA", roomNamed(rooms, "E102").name);
        assertEquals("Ky", roomNamed(rooms, "E201").name);
        assertEquals("TS", roomNamed(rooms, "130").name);
        assertEquals("Terrass", roomNamed(rooms, "E140").name);
    }

    @Test
    public void parsesExhaustAndSupplyLabels() {
        Vent exhaust = LabelParser.parse("KSO-125 -20", 10, 20, 0.9);
        assertNotNull(exhaust);
        assertEquals("kso", exhaust.canonicalName());
        assertEquals("exhaust", exhaust.kind);
        assertEquals("125", exhaust.size);
        assertEquals(20.0, exhaust.airflow, 0.01);

        Vent supply = LabelParser.parse("2xKTS-160 +40", 0, 0, 1);
        assertNotNull(supply);
        assertEquals("kts", supply.canonicalName());
        assertEquals("supply", supply.kind);
        assertEquals(2, supply.qty);
        assertEquals("160", supply.size);
    }

    @Test
    public void fuzzyNameSurvivesOneOcrSubstitution() {
        Vent misread = LabelParser.parse("DVKH 200 +40", 0, 0, 0.8);
        assertNotNull(misread);
        assertEquals("dykh", misread.canonicalName());
        assertEquals("supply", misread.kind);
        assertEquals("200", misread.size);
        Vent shortName = LabelParser.parse("HEK-125 -15", 0, 0, 0.8);
        assertNotNull(shortName);
        assertEquals("hfk", shortName.canonicalName());
        assertEquals("exhaust", shortName.kind);
    }

    @Test
    public void rotatedPointMapsBack() {
        double[] rotated = OcrGeometry.rotateClockwise(10, 4, 30, 20, 90);
        double[] back = OcrGeometry.unmapClockwise(rotated[0], rotated[1], 30, 20, 90);
        assertEquals(10, back[0], 0.01);
        assertEquals(4, back[1], 0.01);
        double[] turned = OcrGeometry.rotateClockwise(10, 4, 30, 20, 270);
        double[] home = OcrGeometry.unmapClockwise(turned[0], turned[1], 30, 20, 270);
        assertEquals(10, home[0], 0.01);
        assertEquals(4, home[1], 0.01);
    }

    @Test
    public void nearbyFragmentsBecomeOneLabel() {
        OcrToken name = new OcrToken("DYKH", 10, 20, 0.8, 18, 6);
        OcrToken size = new OcrToken("200", 28, 20, 0.8, 12, 6);
        java.util.List<OcrToken> merged = TokenCleanup.prepare(java.util.Arrays.asList(size, name));
        assertEquals(1, merged.size());
        assertEquals("DYKH 200", merged.get(0).text);
    }

    @Test
    public void userAddedNameIsUsedAndOcrIsTolerant() {
        VentCatalog previous = LabelParser.current();
        try {
            assertEquals("Skriv ett namn.", VentCatalog.defaults().rejection("  ", true));
            assertEquals("Namnet finns redan.", VentCatalog.defaults().rejection("kso", false));
            VentCatalog custom = VentCatalog.defaults().add(" nova ", true);
            assertEquals("Namnet finns redan.", custom.rejection("NOVA", true));
            LabelParser.use(custom);
            Vent added = LabelParser.parse("N0VA-125 +30", 1, 2, 0.8);
            assertNotNull(added);
            assertEquals("nova", added.canonicalName());
            assertEquals("supply", added.kind);
            assertEquals("125", added.size);
            assertEquals(30.0, added.airflow, 0.01);

            LabelParser.use(custom.setEnabled("KSO", false, false));
            assertNull(LabelParser.parse("KSO-125 -20", 0, 0, 1));

            VentCatalog restored = VentCatalog.fromJson(custom.toJson());
            LabelParser.use(restored);
            Vent again = LabelParser.parse("nova-160", 0, 0, 1);
            assertNotNull(again);
            assertEquals("nova", again.canonicalName());
            assertEquals("supply", again.kind);
        } finally {
            LabelParser.use(previous);
        }
    }

    @Test
    public void secondVentInSameRoomLeavesRoomBlank() {
        Room room = new Room("E125", "wc", 0, 0, 1);
        Vent first = new Vent("kso", "exhaust", "125", 20.0, 1, 1, 1, "KSO", 0.9);
        Vent second = new Vent("kso", "exhaust", "100", 15.0, 1, 2, 2, "KSO", 0.9);
        List<ProtocolRow> rows = ProtocolBuilder.pair(Collections.singletonList(room), Arrays.asList(first, second));
        assertEquals(2, rows.size());
        assertEquals("E125 wc", rows.get(0).room);
        assertEquals("E125", ProtocolBuilder.splitRoom(rows.get(0).room).number);
        assertEquals("wc", ProtocolBuilder.splitRoom(rows.get(0).room).name);
        assertEquals("", rows.get(1).room);
        assertNull(ProtocolBuilder.splitRoom(rows.get(1).room));
        assertEquals("kso", rows.get(1).exhaustName);
    }

    @Test
    public void sheetNamesStayWithinExcelLimits() {
        String name = SheetNames.fromFileName("301:TK/PK*plan.pdf", Collections.singletonList("301_TK_PK_plan"));
        assertFalse(name.contains(":"));
        assertFalse(name.contains("/"));
        assertFalse(name.contains("*"));
        assertTrue(name.length() <= 31);
        assertTrue(name.toLowerCase().endsWith("_2") || name.contains("2"));
    }

    @Test
    public void templateWorkbookKeepsCoverAndAddsOsakra() throws Exception {
        File template = new File("../../templates/matprotokoll_grund.xlsx");
        if (!template.isFile()) {
            template = new File("../templates/matprotokoll_grund.xlsx");
        }
        assertTrue(template.isFile());
        ProtocolRow sure = new ProtocolRow("E125 WC", "kso", "125", 20.0, "kts", "125", 20.0, false, "", 0.95);
        ProtocolRow second = new ProtocolRow("", "kso", "100", 15.0, "", null, null, false, "", 0.9);
        ProtocolRow unsure = new ProtocolRow("E102 AULA", "dykh", null, null, "", null, null, true, "storlek saknas frånluft", 0.4);
        byte[] xlsx = ProtocolXlsx.build(
                Files.readAllBytes(template.toPath()),
                Collections.singletonList(new ProtocolXlsx.DrawingSheet("plan-a", Arrays.asList(sure, second, unsure))),
                true);
        String workbook = zipText(xlsx, "xl/workbook.xml");
        assertTrue(workbook.contains("Koneet Maskiner"));
        assertTrue(workbook.contains("plan-a"));
        assertTrue(workbook.contains("Osäkra"));
        assertFalse(workbook.contains("name=\"Sheet1\""));
        String plan = zipEntryContaining(xlsx, "xl/worksheets/sheet", "Rumsnummer");
        assertTrue(plan.contains("Rumsnamn"));
        assertTrue(plan.contains("Frånluftsdon"));
        assertTrue(plan.contains("<c r=\"A2\""));
        assertTrue(plan.contains(">E125</t>"));
        assertTrue(plan.contains("<c r=\"B2\""));
        assertTrue(plan.contains(">WC</t>"));
        assertTrue(plan.contains("<c r=\"C2\""));
        assertTrue(plan.contains(">kso</t>"));
        assertTrue(plan.contains("<c r=\"I2\""));
        assertTrue(plan.contains(">kts</t>"));
        assertFalse(plan.contains("r=\"A3\""));
        assertFalse(plan.contains("r=\"B3\""));
        assertTrue(plan.contains("<c r=\"C3\""));
        assertTrue(plan.contains(">AULA</t>"));
        assertTrue(plan.contains("Kokonaisilmamäärät/Totalt"));
        assertTrue(plan.contains("SUM(E2:E4)"));
        String unsureSheet = zipEntryContaining(xlsx, "xl/worksheets/sheet", "storlek saknas");
        assertTrue(unsureSheet.contains("E102 AULA"));
    }

    private static Room roomNamed(List<Room> rooms, String number) {
        for (Room room : rooms) {
            if (number.equals(room.number)) {
                return room;
            }
        }
        throw new AssertionError(number);
    }

    private static String zipText(byte[] zip, String name) throws Exception {
        ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip));
        ZipEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            if (name.equals(entry.getName())) {
                return new String(read(in), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(name);
    }

    private static String zipEntryContaining(byte[] zip, String prefix, String marker) throws Exception {
        ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip));
        ZipEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            if (entry.getName().startsWith(prefix)) {
                String text = new String(read(in), StandardCharsets.UTF_8);
                if (text.contains(marker)) {
                    return text;
                }
            }
        }
        throw new IllegalStateException(marker);
    }

    private static String zipTextMatching(byte[] zip, String prefix) throws Exception {
        StringBuilder all = new StringBuilder();
        ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip));
        ZipEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            if (entry.getName().startsWith(prefix)) {
                all.append(new String(read(in), StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }

    private static byte[] read(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }
}
