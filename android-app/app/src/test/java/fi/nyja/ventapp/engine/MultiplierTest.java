package fi.nyja.ventapp.engine;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 2x, 3x and the rest become one row per vent. */
public class MultiplierTest {
    @Test
    public void countsFromTwoThroughNineAndPastTen() {
        for (int n = 2; n <= 9; n++) {
            assertCopies("" + n + "xKTS-160 +40", n, "supply", "kts", "160", 40.0);
            assertCopies("" + n + "xKSO-125 -20", n, "exhaust", "kso", "125", 20.0);
        }
        assertCopies("10xLOCKZONE-200 +15", 10, "supply", "lockzone", "200", 15.0);
        assertCopies("12 x EXCA-100 -18", 12, "exhaust", "exca", "100", 18.0);
        assertCopies("15X KSO 160 -22", 15, "exhaust", "kso", "160", 22.0);
        assertCopies("2×EAGLE-125 +27", 2, "supply", "eagle", "125", 27.0);
        assertCopies("2xEAGLE-125 +54", 2, "supply", "eagle", "125", 54.0);
        assertCopies("3xKTS-160 +50", 3, "supply", "kts", "160", 50.0);
        assertEquals(27.0, ProtocolBuilder.sharePerRow(54, 2), 0.01);
        assertEquals(16.7, ProtocolBuilder.sharePerRow(50, 3), 0.01);
        assertEquals(50.1, 16.7 * 3, 0.01);
    }

    @Test
    public void spacingCaseAndCountBeforeTheSize() {
        assertCopies("2 x KTS-160 +30", 2, "supply", "kts", "160", 30.0);
        assertCopies("2X KSO-125 -12", 2, "exhaust", "kso", "125", 12.0);
        assertCopies("3× EXCA-100 -9", 3, "exhaust", "exca", "100", 9.0);
        assertCopies("KTS 4x160 +40", 4, "supply", "kts", "160", 40.0);
        assertCopies("KSO 5 x 125 -20", 5, "exhaust", "kso", "125", 20.0);
        assertCopies("EXCA 6×100 -11", 6, "exhaust", "exca", "100", 11.0);
        Vent rectangle = LabelParser.parse("LOCKZONE 200x100 +25", 0, 0, 1);
        assertEquals(1, rectangle.qty);
        assertEquals("200x100", rectangle.size);
        assertEquals(25.0, rectangle.airflow, 0.01);
    }

    @Test
    public void splitTokensAndATotalAreExpanded() {
        List<OcrToken> supply = TokenCleanup.prepare(Arrays.asList(
                new OcrToken("2", 10, 20, 1, 8, 8),
                new OcrToken("x", 18, 20, 1, 8, 8),
                new OcrToken("EAGLE", 40, 20, 1, 30, 8),
                new OcrToken("125", 80, 20, 1, 20, 8),
                new OcrToken("+27", 110, 20, 1, 16, 8)));
        Vent eagle = ProtocolBuilder.ventsFromTokens(supply).get(0);
        assertEquals(2, eagle.qty);
        assertEquals("eagle", eagle.canonicalName());
        assertEquals("125", eagle.size);
        List<ProtocolRow> rows = ProtocolBuilder.pair(
                Collections.singletonList(new Room("E116", "Trappa", 10, 20, 1)),
                Collections.singletonList(eagle));
        assertEquals(2, rows.size());
        assertEquals("E116 Trappa", rows.get(0).room);
        assertEquals("", rows.get(1).room);
        assertEquals(13.5, rows.get(0).supplyBehov, 0.01);
        assertEquals(13.5, rows.get(1).supplyBehov, 0.01);

        List<OcrToken> exhaust = TokenCleanup.prepare(Arrays.asList(
                new OcrToken("KSO", 10, 40, 1, 24, 8),
                new OcrToken("3", 40, 40, 1, 8, 8),
                new OcrToken("×", 50, 40, 1, 8, 8),
                new OcrToken("160", 70, 40, 1, 20, 8),
                new OcrToken("-18 tot", 100, 40, 1, 30, 8)));
        Vent kso = ProtocolBuilder.ventsFromTokens(exhaust).get(0);
        assertEquals(3, kso.qty);
        assertEquals("exhaust", kso.kind);
        assertEquals(18.0, kso.airflow, 0.01);
        List<ProtocolRow> total = ProtocolBuilder.pair(
                Collections.singletonList(new Room("E125", "WC", 10, 40, 1)),
                Collections.singletonList(kso));
        assertEquals(3, total.size());
        assertEquals("E125 WC", total.get(0).room);
        assertEquals("", total.get(1).room);
        assertEquals("", total.get(2).room);
        assertEquals(6.0, total.get(0).exhaustBehov, 0.01);
        assertEquals(6.0, total.get(1).exhaustBehov, 0.01);
        assertEquals(6.0, total.get(2).exhaustBehov, 0.01);
        assertEquals(18.0, total.get(0).exhaustBehov + total.get(1).exhaustBehov + total.get(2).exhaustBehov, 0.01);
    }

    @Test
    public void sizePromptLearnedRuleAndCorrectionExpand() {
        Vent messy = LabelParser.parse("4x LOCKZONE Ca 250-600", 0, 0, 1);
        assertEquals(4, messy.qty);
        assertTrue(!LabelParser.sizeIsClear(messy.raw, messy.size));
        List<ProtocolRow> confirmed = ProtocolBuilder.rowsForConfirmedSize(
                Collections.<ProtocolRow>emptyList(),
                new PendingSize(0, "E10 Rum", messy),
                "250");
        assertEquals(4, confirmed.size());
        assertEquals("E10 Rum", confirmed.get(0).room);
        assertEquals("", confirmed.get(3).room);
        assertEquals("250", confirmed.get(2).supplySize);

        List<ProtocolRow> typed = ProtocolBuilder.rowsForConfirmedSize(
                Collections.<ProtocolRow>emptyList(),
                new PendingSize(0, "E11", new Vent("kso", "exhaust", null, 20.0, 1, 0, 0, "KSO", 1)),
                "3x125");
        assertEquals(3, typed.size());
        assertEquals("125", typed.get(0).exhaustSize);
        assertEquals(6.7, typed.get(1).exhaustBehov, 0.01);
        assertEquals("", typed.get(2).room);

        LearnedRules rules = new LearnedRules();
        rules.put(LearnedRules.NAME, "KSO", "7x KSO");
        rules.put(LearnedRules.BEHOV, "EAGLE", "28 tot");
        LearnedRules.install(rules);
        try {
            Vent named = rules.applyVent(new Vent("kso", "exhaust", "125", 10.0, 1, 0, 0, "KSO", 1));
            assertEquals(7, named.qty);
            assertEquals("KSO", named.name);
            List<ProtocolRow> seven = ProtocolBuilder.pair(
                    Collections.singletonList(new Room("E1", "", 0, 0, 1)),
                    Collections.singletonList(new Vent("kso", "exhaust", "125", 10.0, 1, 0, 0, "KSO", 1)));
            assertEquals(7, seven.size());
            assertEquals("E1", seven.get(0).room);
            assertEquals("", seven.get(6).room);
            assertEquals(1.4, seven.get(3).exhaustBehov, 0.01);

            Vent totaled = rules.applyVent(new Vent("eagle", "supply", "125", null, 2, 0, 0, "EAGLE", 1));
            List<ProtocolRow> halved = ProtocolBuilder.pair(
                    Collections.singletonList(new Room("E2", "", 0, 0, 1)),
                    Collections.singletonList(totaled));
            assertEquals(2, halved.size());
            assertEquals(14.0, halved.get(0).supplyBehov, 0.01);
            assertEquals(14.0, halved.get(1).supplyBehov, 0.01);
        } finally {
            LearnedRules.install(new LearnedRules());
        }

        List<ProtocolRow> added = ProtocolBuilder.rowsFromCorrection(
                "E20 Hall", "8x EXCA", "100", "16", "franluft", "8x EXCA");
        assertEquals(8, added.size());
        assertEquals("E20 Hall", added.get(0).room);
        assertEquals("", added.get(7).room);
        assertEquals("EXCA", added.get(4).exhaustName);
        assertEquals("100", added.get(4).exhaustSize);
        assertEquals(2.0, added.get(4).exhaustBehov, 0.01);
        List<ProtocolRow> sized = ProtocolBuilder.rowsFromCorrection(
                "E21", "KTS", "9x160", "18", "tilluft", "KTS");
        assertEquals(9, sized.size());
        assertEquals("KTS", sized.get(0).supplyName);
        assertEquals("160", sized.get(8).supplySize);
        assertEquals(2.0, sized.get(0).supplyBehov, 0.01);
        assertEquals("", sized.get(1).room);
        List<ProtocolRow> summed = ProtocolBuilder.rowsFromCorrection(
                "E22", "2x KSO", "125", "30 total", "exhaust", "2x KSO");
        assertEquals(2, summed.size());
        assertEquals(15.0, summed.get(0).exhaustBehov, 0.01);
        assertEquals(15.0, summed.get(1).exhaustBehov, 0.01);
        assertNull(summed.get(1).supplyBehov);
    }

    private static void assertCopies(String label, int copies, String kind, String name, String size, double flow) {
        Vent vent = LabelParser.parse(label, 0, 0, 1);
        assertTrue(label, vent != null);
        assertEquals(label, copies, vent.qty);
        assertEquals(label, kind, vent.kind);
        assertEquals(label, name, vent.canonicalName());
        assertEquals(label, size, vent.size);
        assertEquals(label, flow, vent.airflow, 0.01);
        List<ProtocolRow> rows = ProtocolBuilder.pair(
                Collections.singletonList(new Room("E100", "Rum", 0, 0, 1)),
                Collections.singletonList(vent));
        assertEquals(label, copies, rows.size());
        assertEquals("E100 Rum", rows.get(0).room);
        for (int i = 0; i < rows.size(); i++) {
            ProtocolRow row = rows.get(i);
            if (i > 0) {
                assertEquals(label, "", row.room);
            }
            double share = ProtocolBuilder.sharePerRow(flow, copies);
            if ("supply".equals(kind)) {
                assertEquals(label, name, row.supplyName);
                assertEquals(label, size, row.supplySize);
                assertEquals(label, share, row.supplyBehov, 0.01);
            } else {
                assertEquals(label, name, row.exhaustName);
                assertEquals(label, size, row.exhaustSize);
                assertEquals(label, share, row.exhaustBehov, 0.01);
            }
        }
    }
}
