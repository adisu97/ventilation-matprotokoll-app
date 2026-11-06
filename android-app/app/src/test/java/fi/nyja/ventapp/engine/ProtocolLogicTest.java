package fi.nyja.ventapp.engine;

import org.junit.Test;

import fi.nyja.ventapp.PdfTextLayer;
import fi.nyja.ventapp.engine.RoomFormats;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.fail;
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
    public void oneLineCanHoldSeveralVentsAndSplitWords() {
        assertEquals(2, LabelParser.parseAll("KSO-125-C -20 KTS-100 +20", 0, 0, 1).size());
        Vent spaced = LabelParser.parse("KSO 125", 0, 0, 1);
        assertNotNull(spaced);
        assertEquals("kso", spaced.canonicalName());
        assertEquals("125", spaced.size);
        Vent dashed = LabelParser.parse("KSO–125", 0, 0, 1);
        assertNotNull(dashed);
        assertEquals("125", dashed.size);
        List<OcrToken> merged = TokenCleanup.prepare(Arrays.asList(
                new OcrToken("KSO-125 -20", 20, 10, 1, 36, 8),
                new OcrToken("KTS-160 +20", 52, 10, 1, 30, 8),
                new OcrToken("2x", 10, 40, 1, 8, 6),
                new OcrToken("EAGLE", 24, 40, 1, 22, 6),
                new OcrToken("200-600", 52, 40, 1, 28, 6)));
        assertEquals(3, merged.size());
        List<Vent> vents = ProtocolBuilder.ventsFromTokens(merged);
        assertEquals(3, vents.size());
        boolean eagle = false;
        for (Vent vent : vents) {
            if ("eagle".equals(vent.canonicalName())) {
                eagle = true;
                assertEquals(2, vent.qty);
                assertEquals("200-600", vent.size);
            }
        }
        assertTrue(eagle);
    }

    @Test
    public void roomWithoutAVentIsKeptAsUnsure() {
        List<ProtocolRow> rows = ProtocolBuilder.pair(
                Collections.singletonList(new Room("E140", "Terrass", 0, 0, 0.9)),
                Collections.<Vent>emptyList());
        assertEquals(1, rows.size());
        assertEquals("E140 Terrass", rows.get(0).room);
        assertTrue(rows.get(0).unsure);
        assertTrue(rows.get(0).unsureReason.contains("inget don"));
    }

    @Test
    public void samplePdfTextLayerKeepsRoomsVentsAndRotatedText() throws Exception {
        File pdf = new File("../../tests/fixtures/mini_ritning.pdf");
        if (!pdf.isFile()) {
            pdf = new File("../tests/fixtures/mini_ritning.pdf");
        }
        assertTrue(pdf.isFile());
        PdfTextLayer.Result text = PdfTextLayer.read(java.nio.file.Files.readAllBytes(pdf.toPath()));
        assertNull(text.error);
        List<OcrToken> prepared = TokenCleanup.prepare(text.tokens);
        List<Room> rooms = ProtocolBuilder.roomsFromTokens(prepared);
        List<Vent> vents = ProtocolBuilder.ventsFromTokens(prepared);
        assertTrue(rooms.size() >= 2);
        assertEquals("WC", roomNamed(rooms, "E125").name);
        assertEquals("AULA", roomNamed(rooms, "E102").name);
        assertTrue(vents.size() >= 3);
        assertTrue(hasVent(vents, "kso"));
        assertTrue(hasVent(vents, "kts"));
        assertTrue(hasVent(vents, "eagle"));

        com.tom_roush.pdfbox.pdmodel.PDDocument doc = new com.tom_roush.pdfbox.pdmodel.PDDocument();
        com.tom_roush.pdfbox.pdmodel.PDPage first = new com.tom_roush.pdfbox.pdmodel.PDPage(
                com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4);
        com.tom_roush.pdfbox.pdmodel.PDPage second = new com.tom_roush.pdfbox.pdmodel.PDPage(
                com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4);
        doc.addPage(first);
        doc.addPage(second);
        writeLine(doc, first, "E301 WC", 72, 700, false);
        writeLine(doc, second, "E302", 72, 700, false);
        writeLine(doc, second, "KSO-160", 140, 500, true);
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        doc.save(buf);
        doc.close();
        PdfTextLayer.Result both = PdfTextLayer.read(buf.toByteArray());
        assertNull(both.error);
        StringBuilder seen = new StringBuilder();
        for (OcrToken token : both.tokens) {
            seen.append(token.text).append('\n');
        }
        String all = seen.toString().toUpperCase();
        assertTrue(all, all.contains("E301"));
        assertTrue(all, all.contains("E302"));
        assertTrue(all, all.contains("KSO-160") || all.contains("KSO"));
    }

    private static void writeLine(com.tom_roush.pdfbox.pdmodel.PDDocument doc,
            com.tom_roush.pdfbox.pdmodel.PDPage page, String text, float x, float y, boolean rotated)
            throws Exception {
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream stream =
                new com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page,
                        com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, true);
        stream.beginText();
        stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
        if (rotated) {
            stream.setTextMatrix(0, 1, -1, 0, x, y);
        } else {
            stream.newLineAtOffset(x, y);
        }
        stream.showText(text);
        stream.endText();
        stream.close();
    }

    private static boolean hasVent(List<Vent> vents, String name) {
        for (Vent vent : vents) {
            if (name.equals(vent.canonicalName())) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void sizesAreNotRoomsAndAirflowKeepsItsUnit() {
        assertNull(ProtocolBuilder.roomNumber("Ø125"));
        List<Room> sized = ProtocolBuilder.roomsFromTokens(Arrays.asList(
                new OcrToken("125", 40, 40, 0.9),
                new OcrToken("KSO-125", 48, 40, 0.9)));
        assertEquals(0, sized.size());
        Vent diameter = LabelParser.parse("KSO-Ø125 -20", 0, 0, 1);
        assertNotNull(diameter);
        assertEquals("125", diameter.size);
        assertEquals(20.0, diameter.airflow, 0.01);
        Vent rect = LabelParser.parse("KSO 200x100", 0, 0, 1);
        assertNotNull(rect);
        assertEquals("200x100", rect.size);
        Vent comma = LabelParser.parse("KTS-100 +20,5", 0, 0, 1);
        assertNotNull(comma);
        assertEquals(20.5, comma.airflow, 0.01);
        Vent cubic = LabelParser.parse("KTS-160 +72 m3/h", 0, 0, 1);
        assertNotNull(cubic);
        assertEquals(20.0, cubic.airflow, 0.05);
        assertEquals("Kök", LatinScript.correctRoomName("Kok"));
        assertEquals("Förråd", LatinScript.correctRoomName("Forrad"));
        assertEquals("AULA", LatinScript.correctRoomName("AULA"));
        assertEquals("", LatinScript.clean("房间"));
        assertFalse(LatinScript.hasCjk(LatinScript.clean("E125房间")));
        assertFalse(LatinScript.allowedKey("疗"));
        assertTrue(LatinScript.allowedKey("ä"));
    }

    @Test
    public void sampleFixturePrecisionAndAFarVentIsUnsure() throws Exception {
        File pdf = new File("../../tests/fixtures/mini_ritning.pdf");
        if (!pdf.isFile()) {
            pdf = new File("../tests/fixtures/mini_ritning.pdf");
        }
        PdfTextLayer.Result text = PdfTextLayer.read(java.nio.file.Files.readAllBytes(pdf.toPath()));
        assertNull(text.error);
        List<OcrToken> prepared = TokenCleanup.prepare(text.tokens);
        List<Room> rooms = ProtocolBuilder.roomsFromTokens(prepared);
        List<Vent> vents = ProtocolBuilder.ventsFromTokens(prepared);
        int roomHits = 0;
        for (Room room : rooms) {
            if ("E125".equals(room.number) || "E102".equals(room.number)) {
                roomHits++;
            } else {
                fail("unexpected room " + room.number + " " + room.name);
            }
        }
        assertEquals(2, roomHits);
        assertEquals(1.0, roomHits / (double) rooms.size(), 0.001);
        int kso = 0;
        int kts = 0;
        int eagle = 0;
        for (Vent vent : vents) {
            if ("kso".equals(vent.canonicalName())) {
                kso++;
                assertEquals("125", vent.size);
                assertEquals(20.0, vent.airflow, 0.01);
            } else if ("kts".equals(vent.canonicalName())) {
                kts++;
                assertEquals("100", vent.size);
                assertEquals(20.0, vent.airflow, 0.01);
            } else if ("eagle".equals(vent.canonicalName())) {
                eagle++;
                assertEquals(2, vent.qty);
                assertEquals(90.0, vent.airflow, 0.01);
            } else {
                fail("unexpected vent " + vent.canonicalName());
            }
        }
        assertEquals(2, kso);
        assertEquals(1, kts);
        assertEquals(1, eagle);
        List<ProtocolRow> far = ProtocolBuilder.pair(
                Collections.singletonList(new Room("E125", "WC", 0, 0, 0.95)),
                Collections.singletonList(new Vent("kso", "exhaust", "125", 20.0, 1, 300, 0, "KSO", 0.9)));
        assertTrue(far.get(0).unsure);
        assertTrue(far.get(0).unsureReason.contains("långt"));
    }

    @Test
    public void lockzoneAndExcaUseTheDefaultListAndTheVentColumns() throws Exception {
        boolean supply = false;
        boolean extract = false;
        for (VentCatalog.Row row : VentCatalog.defaults().tilluftRows()) {
            if ("LOCKZONE".equals(row.key) && row.builtin && row.enabled) {
                supply = true;
            }
        }
        for (VentCatalog.Row row : VentCatalog.defaults().franluftRows()) {
            if ("EXCA".equals(row.key) && row.builtin && row.enabled) {
                extract = true;
            }
        }
        assertTrue(supply);
        assertTrue(extract);

        Vent glued = LabelParser.parse("2xLOCKZONE", 0, 0, 1);
        assertNotNull(glued);
        assertEquals("lockzone", glued.canonicalName());
        assertEquals("supply", glued.kind);
        assertEquals(2, glued.qty);
        Vent spaced = LabelParser.parse("2 x EXCA", 0, 0, 1);
        assertNotNull(spaced);
        assertEquals("exca", spaced.canonicalName());
        assertEquals("exhaust", spaced.kind);
        assertEquals(2, spaced.qty);
        Vent sized = LabelParser.parse("L0CKZ0NE-125-C +40", 0, 0, 1);
        assertNotNull(sized);
        assertEquals("lockzone", sized.canonicalName());
        assertEquals("supply", sized.kind);
        assertEquals("125", sized.size);
        assertEquals(40.0, sized.airflow, 0.01);
        Vent exca = LabelParser.parse("2x EXCA-100 -15", 0, 0, 1);
        assertNotNull(exca);
        assertEquals("exca", exca.canonicalName());
        assertEquals("exhaust", exca.kind);
        assertEquals(2, exca.qty);
        assertEquals("100", exca.size);
        assertEquals(15.0, exca.airflow, 0.01);

        List<ProtocolRow> rows = ProtocolBuilder.pair(
                Collections.singletonList(new Room("E125", "WC", 0, 0, 1)),
                Arrays.asList(glued, spaced));
        assertEquals(2, rows.size());
        assertEquals("lockzone", rows.get(0).supplyName);
        assertEquals("exca", rows.get(0).exhaustName);
        assertEquals("", rows.get(1).room);
        assertEquals("lockzone", rows.get(1).supplyName);
        assertEquals("exca", rows.get(1).exhaustName);

        File template = new File("../../templates/matprotokoll_grund.xlsx");
        if (!template.isFile()) {
            template = new File("../templates/matprotokoll_grund.xlsx");
        }
        byte[] xlsx = ProtocolXlsx.build(
                java.nio.file.Files.readAllBytes(template.toPath()),
                Collections.singletonList(new ProtocolXlsx.DrawingSheet("plan-a", rows)),
                true);
        String plan = zipEntryContaining(xlsx, "xl/worksheets/sheet", "Rumsnummer");
        assertTrue(cellWindow(plan, "C2").contains(">exca</t>"));
        assertTrue(cellWindow(plan, "I2").contains(">lockzone</t>"));
        assertTrue(cellWindow(plan, "C3").contains(">exca</t>"));
        assertTrue(cellWindow(plan, "I3").contains(">lockzone</t>"));
        assertFalse(plan.contains("r=\"A3\""));
    }

    private static String cellWindow(String sheet, String ref) {
        int at = sheet.indexOf("r=\"" + ref + "\"");
        assertTrue(ref, at >= 0);
        return sheet.substring(at, Math.min(sheet.length(), at + 180));
    }

    @Test
    public void roomExamplesAddPatternsBesideTheDefaults() {
        java.util.List<RoomFormats.Derived> formats = new java.util.ArrayList<RoomFormats.Derived>();
        RoomFormats.Derived letter = RoomFormats.derive("E125 WC");
        assertEquals("bokstav + 3 siffror (±1) + namn", letter.summary);
        formats.add(letter);
        formats.add(RoomFormats.derive("101"));
        RoomFormats.Derived dotted = RoomFormats.derive("A.102 Kontor");
        assertTrue(dotted.summary.contains("\".\""));
        formats.add(dotted);
        RoomFormats.Derived hyphen = RoomFormats.derive("1-203 Ruokailu");
        assertTrue(hyphen.summary.contains("\"-\""));
        formats.add(hyphen);
        RoomFormats.use(formats);
        try {
            assertEquals("E125", RoomFormats.parse("E125 WC").number);
            assertEquals("WC", RoomFormats.parse("E125 WC").name);
            assertEquals("101", RoomFormats.parse("101").number);
            assertEquals("A.102", RoomFormats.parse("A.102 Kontor").number);
            assertEquals("kontor", RoomFormats.parse("a.102 kontor").name);
            assertEquals("1-203", ProtocolBuilder.splitRoom("1-203 Ruokailu").number);
            assertEquals("Ruokailu", ProtocolBuilder.splitRoom("1-203 Ruokailu").name);
            assertNull(RoomFormats.parse("KSO-125"));
            assertEquals("Nummer: 1-203  Namn: Ruokailu", RoomFormats.trial("1-203 Ruokailu"));
        } finally {
            RoomFormats.use(java.util.Collections.<RoomFormats.Derived>emptyList());
        }
    }

    @Test
    public void stackedRoomExamplePairsTheNameUnderTheNumber() throws Exception {
        RoomFormats.Derived wind = RoomFormats.derive("E111\nVINDFÅNG");
        assertEquals("bokstav + 3 siffror (±1), nummer på en rad + namn under", wind.summary);
        assertTrue(wind.stacked);
        assertEquals("E111\nVINDFÅNG", wind.example);
        assertEquals("3 siffror (±1), nummer på en rad + namn under", RoomFormats.derive("101\nKÖK").summary);
        RoomFormats.Derived dotted = RoomFormats.derive("A.102\nKontor");
        assertTrue(dotted.summary.contains("\".\""));
        assertTrue(dotted.summary.contains("nummer på en rad + namn under"));
        assertNotNull(RoomFormats.derive("E111\nVIND-FÅNG"));
        assertNotNull(RoomFormats.derive("E111\nSTOR HALL"));
        assertNull(RoomFormats.derive("E111\nKSO"));
        assertNull(RoomFormats.derive("VINDFÅNG\nE111"));
        assertEquals("bokstav + 3 siffror (±1) + namn", RoomFormats.derive("E125 WC").summary);

        String stored = RoomFormats.encodeExamples(java.util.Arrays.asList("E111\nVINDFÅNG", "E125 WC"));
        java.util.List<String> back = RoomFormats.decodeExamples(stored);
        assertEquals("E111\nVINDFÅNG", back.get(0));
        assertEquals("E125 WC", back.get(1));
        assertEquals("E125 WC", RoomFormats.decodeExamples("E125 WC\n101").get(0));
        assertEquals("101", RoomFormats.decodeExamples("E125 WC\n101").get(1));

        java.util.List<RoomFormats.Derived> formats = new java.util.ArrayList<RoomFormats.Derived>();
        formats.add(wind);
        formats.add(RoomFormats.derive("101\nKÖK"));
        formats.add(dotted);
        formats.add(RoomFormats.derive("E125 WC"));
        RoomFormats.use(formats);
        try {
            assertEquals("E111", RoomFormats.parse("E111\nVINDFÅNG").number);
            assertEquals("VINDFÅNG", RoomFormats.parse("E111\nVINDFÅNG").name);
            assertEquals("101", RoomFormats.parse("101\nKÖK").number);
            assertEquals("KÖK", RoomFormats.parse("101\nKÖK").name);
            assertEquals("A.102", RoomFormats.parse("A.102\nKontor").number);
            assertEquals("Kontor", RoomFormats.parse("A.102\nKontor").name);
            assertEquals("Nummer: E111  Namn: VINDFÅNG", RoomFormats.trial("E111\nVINDFÅNG"));
            assertEquals("Nummer: 101  Namn: KÖK", RoomFormats.trial("101\nKÖK"));
            assertNull(RoomFormats.parse("A.102 Kontor"));
            assertEquals("A.102", ProtocolBuilder.splitRoom("A.102 Kontor").number);
            assertEquals("Kontor", ProtocolBuilder.splitRoom("A.102 Kontor").name);
            assertEquals("E125", ProtocolBuilder.splitRoom("E125 WC").number);
            assertEquals("WC", ProtocolBuilder.splitRoom("E125 WC").name);

            // Centres and box sizes, the same geometry PDF words and OCR boxes use.
            // 30 pt text: the name 48 pt below is outside the old 42 pt nearest-word search.
            java.util.List<OcrToken> tokens = new java.util.ArrayList<OcrToken>();
            tokens.add(new OcrToken("E111", 100, 100, 0.95, 40, 30));
            tokens.add(new OcrToken("HALL", 130, 100, 0.95, 36, 30));
            tokens.add(new OcrToken("FÖRRÅD", 102, 52, 0.95, 60, 30));
            tokens.add(new OcrToken("VINDFÅNG", 108, 148, 0.95, 80, 30));
            tokens.add(new OcrToken("101", 400, 200, 0.95, 28, 30));
            tokens.add(new OcrToken("KÖK", 400, 152, 0.95, 36, 30));
            tokens.add(new OcrToken("A.102", 700, 300, 0.95, 36, 12));
            tokens.add(new OcrToken("Kontor", 718, 318, 0.95, 70, 12));
            tokens.add(new OcrToken("E125 WC", 20, 20, 0.98, 50, 10));
            tokens.add(new OcrToken("B110", 50, 500, 0.9, 0, 0));
            tokens.add(new OcrToken("HALL", 50, 514, 0.9, 0, 0));
            tokens.add(new OcrToken("E222", 900, 100, 0.9, 30, 10));
            tokens.add(new OcrToken("NAMN", 900, 180, 0.9, 40, 10));
            tokens.add(new OcrToken("F300", 50, 700, 0.9, 30, 12));
            tokens.add(new OcrToken("RUMMET", 140, 700, 0.9, 50, 12));

            java.util.List<Room> rooms = ProtocolBuilder.roomsFromTokens(tokens);
            assertEquals("VINDFÅNG", roomNamed(rooms, "E111").name);
            assertEquals("KÖK", roomNamed(rooms, "101").name);
            assertEquals("Kontor", roomNamed(rooms, "A.102").name);
            assertEquals("WC", roomNamed(rooms, "E125").name);
            assertEquals("HALL", roomNamed(rooms, "B110").name);
            assertFalse(hasRoom(rooms, "E222"));
            assertFalse(hasRoom(rooms, "F300"));

            java.util.List<ProtocolRow> rows = ProtocolBuilder.pair(rooms, java.util.Collections.<Vent>emptyList());
            File template = new File("../../templates/matprotokoll_grund.xlsx");
            if (!template.isFile()) {
                template = new File("../templates/matprotokoll_grund.xlsx");
            }
            byte[] xlsx = ProtocolXlsx.build(
                    java.nio.file.Files.readAllBytes(template.toPath()),
                    java.util.Collections.singletonList(new ProtocolXlsx.DrawingSheet("plan-a", rows)),
                    true);
            String plan = zipEntryContaining(xlsx, "xl/worksheets/sheet", "Rumsnummer");
            assertTrue(cellWindow(plan, "A2").contains(">E111</t>"));
            assertTrue(cellWindow(plan, "B2").contains(">VINDFÅNG</t>"));
            assertTrue(cellWindow(plan, "A3").contains(">101</t>"));
            assertTrue(cellWindow(plan, "B3").contains(">KÖK</t>"));
            assertTrue(cellWindow(plan, "A4").contains(">A.102</t>"));
            assertTrue(cellWindow(plan, "B4").contains(">Kontor</t>"));
            assertTrue(cellWindow(plan, "A5").contains(">E125</t>"));
            assertTrue(cellWindow(plan, "B5").contains(">WC</t>"));
        } finally {
            RoomFormats.use(java.util.Collections.<RoomFormats.Derived>emptyList());
        }
    }

    @Test
    public void filledRoomFormatReplacesTheBroadScan() throws Exception {
        assertEquals("2 siffror (±1), nummer på en rad + namn under", RoomFormats.derive("12\nWc").summary);
        java.util.List<RoomFormats.Derived> stacked = new java.util.ArrayList<RoomFormats.Derived>();
        stacked.add(RoomFormats.derive("12\nWc"));
        RoomFormats.use(stacked);
        try {
            assertEquals("12", RoomFormats.parse("12\nWc").number);
            assertEquals("Wc", RoomFormats.parse("12\nWc").name);
            assertEquals("Nummer: 5  Namn: Kök", RoomFormats.trial("5\nKök"));
            assertEquals("Nummer: 125  Namn: Keittiö", RoomFormats.trial("125\nKeittiö"));
            assertEquals("Nummer: 12  Namn: Kitchen", RoomFormats.trial("12\nKitchen"));
            assertEquals("Ingen träff.", RoomFormats.trial("1250\nWc"));
            assertEquals("Ingen träff.", RoomFormats.trial("125 WC"));
            assertNull(RoomFormats.parse("102AULA"));
            assertNull(ProtocolBuilder.splitRoom("102AULA"));

            java.util.List<OcrToken> tokens = new java.util.ArrayList<OcrToken>();
            tokens.add(new OcrToken("5", 40, 40, 0.95, 16, 12));
            tokens.add(new OcrToken("Kök", 42, 58, 0.95, 28, 12));
            tokens.add(new OcrToken("12", 160, 40, 0.95, 20, 12));
            tokens.add(new OcrToken("Wc", 158, 58, 0.95, 24, 12));
            tokens.add(new OcrToken("125", 300, 40, 0.95, 28, 12));
            tokens.add(new OcrToken("Keittiö", 310, 60, 0.95, 60, 12));
            tokens.add(new OcrToken("8", 460, 40, 0.95, 16, 12));
            tokens.add(new OcrToken("Bathroom", 470, 58, 0.95, 70, 12));
            tokens.add(new OcrToken("80", 620, 40, 0.95, 20, 12));
            tokens.add(new OcrToken("Förråd", 630, 60, 0.95, 50, 12));
            tokens.add(new OcrToken("1234", 800, 40, 0.95, 36, 12));
            tokens.add(new OcrToken("Kitchen", 800, 58, 0.95, 60, 12));
            tokens.add(new OcrToken("125 WC", 40, 160, 0.95, 50, 12));
            tokens.add(new OcrToken("48", 200, 160, 0.95, 20, 12));
            tokens.add(new OcrToken("WC", 250, 160, 0.95, 24, 12));
            tokens.add(new OcrToken("E125 WC", 400, 160, 0.95, 60, 12));
            tokens.add(new OcrToken("102AULA", 560, 160, 0.95, 60, 12));
            tokens.add(new OcrToken("LOCKZONE", 40, 240, 0.95, 70, 12));

            java.util.List<Room> rooms = ProtocolBuilder.roomsFromTokens(tokens);
            assertEquals("Kök", roomNamed(rooms, "5").name);
            assertEquals("Wc", roomNamed(rooms, "12").name);
            assertEquals("Keittiö", roomNamed(rooms, "125").name);
            assertEquals("Bathroom", roomNamed(rooms, "8").name);
            assertEquals("Förråd", roomNamed(rooms, "80").name);
            assertFalse(hasRoom(rooms, "1234"));
            assertFalse(hasRoom(rooms, "48"));
            assertFalse(hasRoom(rooms, "E125"));
            assertFalse(hasRoom(rooms, "102"));
            assertFalse(hasRoom(rooms, "102AULA"));
            assertEquals(5, rooms.size());
            assertEquals(1, ProtocolBuilder.ventsFromTokens(tokens).size());

            java.util.List<ProtocolRow> rows = ProtocolBuilder.pair(rooms, java.util.Collections.<Vent>emptyList());
            File template = new File("../../templates/matprotokoll_grund.xlsx");
            if (!template.isFile()) {
                template = new File("../templates/matprotokoll_grund.xlsx");
            }
            byte[] xlsx = ProtocolXlsx.build(
                    java.nio.file.Files.readAllBytes(template.toPath()),
                    java.util.Collections.singletonList(new ProtocolXlsx.DrawingSheet("plan-a", rows)),
                    true);
            String plan = zipEntryContaining(xlsx, "xl/worksheets/sheet", "Rumsnummer");
            assertTrue(cellWindow(plan, "A2").contains(">5</t>"));
            assertTrue(cellWindow(plan, "B2").contains(">Kök</t>"));
            assertTrue(cellWindow(plan, "A4").contains(">125</t>"));
            assertTrue(cellWindow(plan, "B4").contains(">Keittiö</t>"));
        } finally {
            RoomFormats.use(java.util.Collections.<RoomFormats.Derived>emptyList());
        }

        RoomFormats.use(java.util.Collections.singletonList(RoomFormats.derive("E111\nVINDFÅNG")));
        try {
            java.util.List<OcrToken> tokens = new java.util.ArrayList<OcrToken>();
            tokens.add(new OcrToken("E11", 100, 100, 0.95, 30, 12));
            tokens.add(new OcrToken("Kök", 100, 118, 0.95, 28, 12));
            tokens.add(new OcrToken("E111", 240, 100, 0.95, 36, 12));
            tokens.add(new OcrToken("VINDFÅNG", 246, 118, 0.95, 70, 12));
            tokens.add(new OcrToken("E1111", 420, 100, 0.95, 42, 12));
            tokens.add(new OcrToken("Bathroom", 430, 118, 0.95, 70, 12));
            tokens.add(new OcrToken("E11111", 640, 100, 0.95, 50, 12));
            tokens.add(new OcrToken("Förråd", 640, 118, 0.95, 50, 12));
            tokens.add(new OcrToken("111", 800, 100, 0.95, 28, 12));
            tokens.add(new OcrToken("Kitchen", 800, 118, 0.95, 60, 12));
            tokens.add(new OcrToken("EE111", 960, 100, 0.95, 40, 12));
            tokens.add(new OcrToken("Hall", 960, 118, 0.95, 30, 12));
            java.util.List<Room> rooms = ProtocolBuilder.roomsFromTokens(tokens);
            assertEquals("Kök", roomNamed(rooms, "E11").name);
            assertEquals("VINDFÅNG", roomNamed(rooms, "E111").name);
            assertEquals("Bathroom", roomNamed(rooms, "E1111").name);
            assertFalse(hasRoom(rooms, "E11111"));
            assertFalse(hasRoom(rooms, "111"));
            assertFalse(hasRoom(rooms, "EE111"));
        } finally {
            RoomFormats.use(java.util.Collections.<RoomFormats.Derived>emptyList());
        }

        RoomFormats.use(java.util.Collections.<RoomFormats.Derived>emptyList());
        java.util.List<OcrToken> open = new java.util.ArrayList<OcrToken>();
        open.add(new OcrToken("E125 WC", 10, 20, 1, 40, 10));
        open.add(new OcrToken("102AULA", 80, 20, 1, 50, 10));
        open.add(new OcrToken("E999", 160, 20, 1, 30, 10));
        java.util.List<Room> broad = ProtocolBuilder.roomsFromTokens(open);
        assertEquals("WC", roomNamed(broad, "E125").name);
        assertEquals("AULA", roomNamed(broad, "102").name);
        assertTrue(hasRoom(broad, "E999"));
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
        assertTrue(plan.contains("<v>35</v>"));
        assertTrue(plan.contains("<v>20</v>"));
        assertTrue(plan.contains("<v>0</v>"));
        String styles = zipText(xlsx, "xl/styles.xml");
        String yellow = fillOf(styles, styleOf(plan, "C1"));
        String red = fillOf(styles, styleOf(plan, "I1"));
        assertEquals("FFFFFFCC", yellow);
        assertEquals("FFFFD9D9", red);
        int totalRow = Integer.parseInt(plan.replaceAll("(?s).*r=\"A(\\d+)\"[^>]*>.*?Kokonais.*", "$1"));
        for (int row = 2; row <= totalRow; row++) {
            for (char col = 'C'; col <= 'H'; col++) {
                String ref = col + Integer.toString(row);
                assertTrue(plan.contains("r=\"" + ref + "\""));
                assertEquals(ref, yellow, fillOf(styles, styleOf(plan, ref)));
            }
            for (char col = 'I'; col <= 'N'; col++) {
                String ref = col + Integer.toString(row);
                assertTrue(plan.contains("r=\"" + ref + "\""));
                assertEquals(ref, red, fillOf(styles, styleOf(plan, ref)));
            }
        }
        assertFalse(plan.contains("r=\"A3\""));
        assertFalse(plan.contains("r=\"B3\""));
        String workbookXml = zipText(xlsx, "xl/workbook.xml");
        assertTrue(workbookXml.contains("fullCalcOnLoad=\"1\""));
        String cover = zipEntryContaining(xlsx, "xl/worksheets/sheet", "Yhteensä Totalt");
        assertTrue(cover.contains("<v>0</v>"));
        assertFalse(cover.contains("<v />"));
        assertFalse(cover.contains("<v/>"));
        assertFalse(cover.contains("SUM(C26:C29)"));
        assertFalse(cover.contains("r=\"C30\""));
        assertTrue(cover.contains("SUM(C9:C13)"));
        assertTrue(fullThinBorder(styles, styleOf(plan, "C2")));
        assertTrue(fullThinBorder(styles, styleOf(plan, "I2")));
        String unsureSheet = zipEntryContaining(xlsx, "xl/worksheets/sheet", "storlek saknas");
        assertTrue(unsureSheet.contains("E102 AULA"));
    }

    private static boolean fullThinBorder(String styles, String styleIndex) {
        java.util.regex.Matcher xfs = java.util.regex.Pattern.compile("(?s)<cellXfs[^>]*>(.*)</cellXfs>").matcher(styles);
        assertTrue(xfs.find());
        java.util.regex.Matcher xf = java.util.regex.Pattern.compile("(?s)<xf\\b[^>]*/>|<xf\\b[^>]*>.*?</xf>").matcher(xfs.group(1));
        int index = Integer.parseInt(styleIndex);
        int at = 0;
        String tag = null;
        while (xf.find()) {
            if (at == index) {
                tag = xf.group();
                break;
            }
            at++;
        }
        assertNotNull(tag);
        java.util.regex.Matcher borderId = java.util.regex.Pattern.compile("borderId=\"(\\d+)\"").matcher(tag);
        assertTrue(borderId.find());
        java.util.regex.Matcher borders = java.util.regex.Pattern.compile("(?s)<borders[^>]*>(.*)</borders>").matcher(styles);
        assertTrue(borders.find());
        java.util.regex.Matcher border = java.util.regex.Pattern.compile("(?s)<border\\b.*?</border>|<border[^>]*/>").matcher(borders.group(1));
        at = 0;
        String body = null;
        while (border.find()) {
            if (at == Integer.parseInt(borderId.group(1))) {
                body = border.group();
                break;
            }
            at++;
        }
        assertNotNull(body);
        for (String side : new String[] {"left", "right", "top", "bottom"}) {
            if (!java.util.regex.Pattern.compile("<" + side + "\\b[^>]*style=\"thin\"").matcher(body).find()) {
                return false;
            }
        }
        return true;
    }

    private static String styleOf(String sheet, String ref) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "r=\"" + ref + "\"[^>]*s=\"(\\d+)\"").matcher(sheet);
        assertTrue(ref, matcher.find());
        return matcher.group(1);
    }

    private static String fillOf(String styles, String styleIndex) {
        java.util.regex.Matcher xfs = java.util.regex.Pattern.compile("(?s)<cellXfs[^>]*>(.*)</cellXfs>").matcher(styles);
        assertTrue(xfs.find());
        java.util.regex.Matcher xf = java.util.regex.Pattern.compile("<xf\\b([^>]*)/?>").matcher(xfs.group(1));
        int index = Integer.parseInt(styleIndex);
        int at = 0;
        String attrs = null;
        while (xf.find()) {
            if (at == index) {
                attrs = xf.group(1);
                break;
            }
            at++;
        }
        assertNotNull(styleIndex);
        java.util.regex.Matcher fillId = java.util.regex.Pattern.compile("fillId=\"(\\d+)\"").matcher(attrs);
        assertTrue(fillId.find());
        java.util.regex.Matcher fills = java.util.regex.Pattern.compile("(?s)<fills[^>]*>(.*)</fills>").matcher(styles);
        assertTrue(fills.find());
        java.util.regex.Matcher fill = java.util.regex.Pattern.compile("(?s)<fill\\b.*?</fill>").matcher(fills.group(1));
        at = 0;
        String body = null;
        while (fill.find()) {
            if (at == Integer.parseInt(fillId.group(1))) {
                body = fill.group(0);
                break;
            }
            at++;
        }
        assertNotNull(body);
        java.util.regex.Matcher rgb = java.util.regex.Pattern.compile("rgb=\"([0-9A-Fa-f]+)\"").matcher(body);
        assertTrue(body, rgb.find());
        return rgb.group(1).toUpperCase();
    }

    private static boolean hasRoom(List<Room> rooms, String number) {
        for (Room room : rooms) {
            if (number.equals(room.number)) {
                return true;
            }
        }
        return false;
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
