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
import static org.junit.Assert.assertTrue;

public class ProtocolLogicTest {
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
    public void secondVentInSameRoomLeavesRoomBlank() {
        Room room = new Room("E125", "wc", 0, 0, 1);
        Vent first = new Vent("kso", "exhaust", "125", 20.0, 1, 1, 1, "KSO", 0.9);
        Vent second = new Vent("kso", "exhaust", "100", 15.0, 1, 2, 2, "KSO", 0.9);
        List<ProtocolRow> rows = ProtocolBuilder.pair(Collections.singletonList(room), Arrays.asList(first, second));
        assertEquals(2, rows.size());
        assertEquals("E125 wc", rows.get(0).room);
        assertEquals("", rows.get(1).room);
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
        ProtocolRow sure = new ProtocolRow("E125 wc", "kso", "125", 20.0, "kts", "125", 20.0, false, "", 0.95);
        ProtocolRow unsure = new ProtocolRow("E102", "dykh", null, null, "", null, null, true, "storlek saknas frånluft", 0.4);
        byte[] xlsx = ProtocolXlsx.build(
                Files.readAllBytes(template.toPath()),
                Collections.singletonList(new ProtocolXlsx.DrawingSheet("plan-a", Arrays.asList(sure, unsure))),
                true);
        String workbook = zipText(xlsx, "xl/workbook.xml");
        assertTrue(workbook.contains("Koneet Maskiner"));
        assertTrue(workbook.contains("plan-a"));
        assertTrue(workbook.contains("Osäkra"));
        assertFalse(workbook.contains("name=\"Sheet1\""));
        String allSheets = zipTextMatching(xlsx, "xl/worksheets/sheet");
        assertTrue(allSheets.contains("E125 wc"));
        assertTrue(allSheets.contains("storlek saknas"));
        assertTrue(allSheets.contains("Kokonaisilmamäärät/Totalt"));
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
