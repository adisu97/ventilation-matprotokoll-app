package fi.nyja.ventapp.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Writes protocol sheets into the bundled template, or appends sheets to an existing workbook.
 * Existing sheets and their XML are left unchanged when appending.
 */
public final class ProtocolXlsx {
    private static final Pattern SHEET = Pattern.compile(
            "<sheet\\s+name=\"([^\"]*)\"\\s+sheetId=\"(\\d+)\"[^>]*r:id=\"([^\"]+)\"");
    private static final Pattern REL = Pattern.compile(
            "<Relationship\\s+Type=\"([^\"]*)\"\\s+Target=\"([^\"]*)\"\\s+Id=\"([^\"]+)\"");
    private static final String SHEET_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";

    private ProtocolXlsx() {
    }

    public static byte[] build(byte[] templateOrExisting, List<DrawingSheet> drawings, boolean fromTemplate)
            throws IOException {
        Map<String, byte[]> zip = readZip(templateOrExisting);
        stripStrayC30(zip);
        if (zip.containsKey("xl/styles.xml")) {
            zip.put("xl/styles.xml", gridBorders(zip.get("xl/styles.xml")));
        }
        String workbook = text(zip, "xl/workbook.xml");
        String rels = text(zip, "xl/_rels/workbook.xml.rels");
        String contentTypes = text(zip, "[Content_Types].xml");
        List<SheetRef> sheets = parseSheets(workbook);
        Map<String, String> relTargets = parseRels(rels);
        String protoXml = null;
        if (fromTemplate) {
            protoXml = prototypeXml(zip, sheets, relTargets);
        }
        List<String> names = new ArrayList<String>();
        for (SheetRef sheet : sheets) {
            names.add(sheet.name);
        }
        int sheetId = 1;
        int fileNum = 1;
        for (SheetRef sheet : sheets) {
            sheetId = Math.max(sheetId, sheet.sheetId + 1);
            String target = normalize(relTargets.get(sheet.rid));
            Matcher num = Pattern.compile("sheet(\\d+)\\.xml").matcher(target);
            if (num.find()) {
                fileNum = Math.max(fileNum, Integer.parseInt(num.group(1)) + 1);
            }
        }
        while (zip.containsKey("xl/worksheets/sheet" + fileNum + ".xml")) {
            fileNum++;
        }
        int relNum = 1;
        Matcher relMax = Pattern.compile("Id=\"rId(\\d+)\"").matcher(rels);
        while (relMax.find()) {
            relNum = Math.max(relNum, Integer.parseInt(relMax.group(1)) + 1);
        }
        List<ProtocolRow> unsure = new ArrayList<ProtocolRow>();
        List<String> unsureSource = new ArrayList<String>();
        for (DrawingSheet drawing : drawings) {
            String sheetName = SheetNames.unique(drawing.sheetName, names);
            names.add(sheetName);
            String xml = sheetXml(protoXml, sheetName, drawing.rows);
            String part = "xl/worksheets/sheet" + fileNum + ".xml";
            zip.put(part, xml.getBytes(StandardCharsets.UTF_8));
            String rid = "rId" + relNum;
            workbook = insertSheet(workbook, sheetName, sheetId, rid);
            rels = insertRel(rels, rid, "/xl/worksheets/sheet" + fileNum + ".xml");
            contentTypes = insertOverride(contentTypes, "/xl/worksheets/sheet" + fileNum + ".xml");
            for (ProtocolRow row : drawing.rows) {
                if (row.unsure) {
                    unsure.add(row);
                    unsureSource.add(sheetName);
                }
            }
            sheetId++;
            fileNum++;
            relNum++;
        }
        if (!unsure.isEmpty()) {
            String sheetName = SheetNames.unique("Osäkra", names);
            String xml = unsureXml(unsure, unsureSource);
            String part = "xl/worksheets/sheet" + fileNum + ".xml";
            zip.put(part, xml.getBytes(StandardCharsets.UTF_8));
            String rid = "rId" + relNum;
            workbook = insertSheet(workbook, sheetName, sheetId, rid);
            rels = insertRel(rels, rid, "/xl/worksheets/sheet" + fileNum + ".xml");
            contentTypes = insertOverride(contentTypes, "/xl/worksheets/sheet" + fileNum + ".xml");
        }
        if (fromTemplate) {
            workbook = dropPrototypeSheets(workbook);
            rels = rels.replaceAll("<Relationship[^>]*Target=\"/xl/worksheets/sheet[234]\\.xml\"[^>]*/>", "");
        }
        zip.put("xl/workbook.xml", ensureFullCalc(workbook).getBytes(StandardCharsets.UTF_8));
        zip.put("xl/_rels/workbook.xml.rels", rels.getBytes(StandardCharsets.UTF_8));
        zip.put("[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
        cacheFormulaResults(zip);
        return writeZip(zip);
    }

    private static String prototypeXml(Map<String, byte[]> zip, List<SheetRef> sheets, Map<String, String> relTargets) {
        for (String candidate : new String[] {"Sheet1", "Sheet2", "Sheet3"}) {
            for (SheetRef sheet : sheets) {
                if (candidate.equals(sheet.name)) {
                    String target = normalize(relTargets.get(sheet.rid));
                    byte[] data = zip.get(target);
                    if (data != null) {
                        return new String(data, StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return null;
    }

    private static String sheetXml(String prototype, String name, List<ProtocolRow> rows) {
        String prepared = prototype == null ? null : addNameColumn(prototype);
        String header = headerRow(prepared);
        String styles = withNameStyle(dataStyles(prototype));
        StringBuilder data = new StringBuilder();
        int r = 2;
        for (ProtocolRow row : rows) {
            data.append(dataRow(r, row, styles));
            r++;
        }
        int dataLast = rows.isEmpty() ? 2 : rows.size() + 1;
        int total = rows.isEmpty() ? 3 : rows.size() + 3;
        data.append(bandRow(total - 1, styles));
        data.append(totalRow(total, 2, dataLast, styles, sumBehov(rows, true), sumBehov(rows, false)));
        String sheetData = "<sheetData>" + header + data + "</sheetData>";
        if (prepared != null && prepared.contains("<sheetData>") && prepared.contains("</sheetData>")) {
            int start = prepared.indexOf("<sheetData>");
            int end = prepared.indexOf("</sheetData>") + "</sheetData>".length();
            String prefix = prepared.substring(0, start);
            String suffix = prepared.substring(end);
            prefix = prefix.replaceFirst("dimension ref=\"[^\"]*\"", "dimension ref=\"A1:N" + total + "\"");
            return prefix + sheetData + suffix;
        }
        return fallbackSheet(sheetData, total);
    }

    /** Grund has one Utrymme column. The written sheet adds Rumsnamn and shifts the don columns. */
    private static String addNameColumn(String xml) {
        if (xml.contains("Rumsnamn")) {
            return xml;
        }
        String shifted = xml;
        for (int n = 40; n >= 2; n--) {
            shifted = shifted.replace("min=\"" + n + "\"", "min=\"#" + (n + 1) + "\"");
            shifted = shifted.replace("max=\"" + n + "\"", "max=\"#" + (n + 1) + "\"");
        }
        shifted = shifted.replace("#", "");
        int colEnd = shifted.indexOf("min=\"1\" max=\"1\" />");
        if (colEnd >= 0) {
            colEnd += "min=\"1\" max=\"1\" />".length();
            String nameWidth = "<col width=\"18\" bestFit=\"1\" customWidth=\"1\" style=\"75\" min=\"2\" max=\"2\" />";
            shifted = shifted.substring(0, colEnd) + nameWidth + shifted.substring(colEnd);
        }
        int start = shifted.indexOf("<row r=\"1\"");
        int end = start < 0 ? -1 : shifted.indexOf("</row>", start);
        if (start < 0 || end < 0) {
            return shifted;
        }
        String row = shifted.substring(start, end);
        for (int col = 20; col >= 2; col--) {
            row = row.replace("r=\"" + cellRef(col, 1) + "\"", "r=\"" + cellRef(col + 1, 1) + "\"");
        }
        row = row.replaceFirst("(?s)<t[^>]*>.*?</t>", "<t>Rumsnummer\nHuonenro</t>");
        int close = row.indexOf("</c>");
        if (close >= 0) {
            String nameCell = "<c r=\"B1\" s=\"18\" t=\"inlineStr\"><is><t>Rumsnamn\nUtrymme</t></is></c>";
            row = row.substring(0, close + 4) + nameCell + row.substring(close + 4);
        }
        return shifted.substring(0, start) + row + shifted.substring(end);
    }

    private static String withNameStyle(String styles) {
        String[] parts = styles.split(",");
        if (parts.length == 0) {
            return styles;
        }
        StringBuilder out = new StringBuilder();
        out.append(parts[0]).append(',').append(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(',').append(parts[i]);
        }
        return out.toString();
    }

    private static String headerRow(String prototype) {
        if (prototype == null) {
            return fallbackHeader();
        }
        int start = prototype.indexOf("<row r=\"1\"");
        if (start < 0) {
            start = prototype.indexOf("<row r='1'");
        }
        if (start < 0) {
            return fallbackHeader();
        }
        int end = prototype.indexOf("</row>", start);
        if (end < 0) {
            return fallbackHeader();
        }
        return prototype.substring(start, end + "</row>".length());
    }

    private static String dataStyles(String prototype) {
        if (prototype == null) {
            return "0,0,0,0,0,0,0,0,0,0,0,0,0";
        }
        int start = prototype.indexOf("<row r=\"2\"");
        if (start < 0) {
            return "0,0,0,0,0,0,0,0,0,0,0,0,0";
        }
        int end = prototype.indexOf("</row>", start);
        String row = prototype.substring(start, end);
        StringBuilder styles = new StringBuilder();
        for (int col = 1; col <= 13; col++) {
            String cell = cellRef(col, 2);
            int c = row.indexOf("r=\"" + cell + "\"");
            String style = "0";
            if (c >= 0) {
                String window = row.substring(c, Math.min(row.length(), c + 80));
                Matcher styleMatch = Pattern.compile("s=\"(\\d+)\"").matcher(window);
                if (styleMatch.find()) {
                    style = styleMatch.group(1);
                }
            }
            if (styles.length() > 0) {
                styles.append(',');
            }
            styles.append(style);
        }
        return styles.toString();
    }

    private static String dataRow(int row, ProtocolRow data, String styles) {
        String[] style = styles.split(",");
        StringBuilder xml = new StringBuilder();
        xml.append("<row r=\"").append(row).append("\">");
        ProtocolBuilder.RoomText room = ProtocolBuilder.splitRoom(data.room);
        if (room == null) {
            cell(xml, 1, row, data.room, style);
        } else {
            cell(xml, 1, row, room.number, style);
            cell(xml, 2, row, room.name, style);
        }
        if (!data.exhaustName.isEmpty()) {
            cell(xml, 3, row, data.exhaustName, style);
            valueOrBand(xml, 4, row, data.exhaustSize, style);
            valueOrBand(xml, 5, row, num(data.exhaustBehov), style, true);
        } else {
            band(xml, row, style, 3, 5);
        }
        band(xml, row, style, 6, 8);
        if (!data.supplyName.isEmpty()) {
            cell(xml, 9, row, data.supplyName, style);
            valueOrBand(xml, 10, row, data.supplySize, style);
            valueOrBand(xml, 11, row, num(data.supplyBehov), style, true);
        } else {
            band(xml, row, style, 9, 11);
        }
        band(xml, row, style, 12, 14);
        xml.append("</row>");
        return xml.toString();
    }

    /** Empty frånluft C–H and tilluft I–N cells, using the template data style so the fill stays. */
    private static String bandRow(int row, String styles) {
        String[] style = styles.split(",");
        StringBuilder xml = new StringBuilder();
        xml.append("<row r=\"").append(row).append("\">");
        band(xml, row, style, 3, 14);
        xml.append("</row>");
        return xml.toString();
    }

    private static String totalRow(int row, int from, int to, String styles, double exhaustBehov, double supplyBehov) {
        String[] style = styles.split(",");
        StringBuilder xml = new StringBuilder();
        xml.append("<row r=\"").append(row).append("\">");
        cell(xml, 1, row, "Kokonaisilmamäärät/Totalt", style);
        band(xml, row, style, 3, 4);
        formula(xml, 5, row, "SUM(E" + from + ":E" + to + ")", style, exhaustBehov);
        formula(xml, 6, row, "SUM(F" + from + ":F" + to + ")", style, 0);
        band(xml, row, style, 7, 10);
        formula(xml, 11, row, "SUM(K" + from + ":K" + to + ")", style, supplyBehov);
        formula(xml, 12, row, "SUM(L" + from + ":L" + to + ")", style, 0);
        band(xml, row, style, 13, 14);
        xml.append("</row>");
        return xml.toString();
    }

    private static double sumBehov(List<ProtocolRow> rows, boolean exhaust) {
        double sum = 0;
        for (ProtocolRow row : rows) {
            Double value = exhaust ? row.exhaustBehov : row.supplyBehov;
            if (value != null) {
                sum += value;
            }
        }
        return sum;
    }

    private static void band(StringBuilder xml, int row, String[] style, int from, int to) {
        for (int col = from; col <= to; col++) {
            emptyStyled(xml, col, row, style);
        }
    }

    private static void valueOrBand(StringBuilder xml, int col, int row, String value, String[] style) {
        valueOrBand(xml, col, row, value, style, false);
    }

    private static void valueOrBand(StringBuilder xml, int col, int row, String value, String[] style, boolean number) {
        if (value == null || value.isEmpty()) {
            emptyStyled(xml, col, row, style);
        } else if (number) {
            numberCell(xml, col, row, value, style);
        } else {
            cell(xml, col, row, value, style);
        }
    }

    private static void emptyStyled(StringBuilder xml, int col, int row, String[] style) {
        String s = style.length >= col ? style[col - 1] : "0";
        xml.append("<c r=\"").append(cellRef(col, row)).append("\" s=\"").append(s).append("\"/>");
    }

    private static void cell(StringBuilder xml, int col, int row, String value, String[] style) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String s = style.length >= col ? style[col - 1] : "0";
        xml.append("<c r=\"").append(cellRef(col, row)).append("\" s=\"").append(s)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(esc(value)).append("</t></is></c>");
    }

    private static void numberCell(StringBuilder xml, int col, int row, String value, String[] style) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String s = style.length >= col ? style[col - 1] : "0";
        xml.append("<c r=\"").append(cellRef(col, row)).append("\" s=\"").append(s)
                .append("\"><v>").append(esc(value)).append("</v></c>");
    }

    private static void formula(StringBuilder xml, int col, int row, String formula, String[] style, double cached) {
        String s = style.length >= col ? style[col - 1] : "0";
        xml.append("<c r=\"").append(cellRef(col, row)).append("\" s=\"").append(s)
                .append("\"><f>").append(esc(formula)).append("</f><v>")
                .append(num(cached)).append("</v></c>");
    }

    private static String ensureFullCalc(String workbook) {
        if (workbook.contains("fullCalcOnLoad=\"1\"")) {
            return workbook;
        }
        if (workbook.contains("fullCalcOnLoad=\"0\"")) {
            return workbook.replace("fullCalcOnLoad=\"0\"", "fullCalcOnLoad=\"1\"");
        }
        if (workbook.contains("<calcPr")) {
            return workbook.replace("<calcPr", "<calcPr fullCalcOnLoad=\"1\" ");
        }
        return workbook.replace("</workbook>", "<calcPr fullCalcOnLoad=\"1\"/></workbook>");
    }

    /** The cover sheet has a lone SUM in C30, outside the real total row. */
    private static void stripStrayC30(Map<String, byte[]> zip) {
        for (Map.Entry<String, byte[]> entry : new ArrayList<Map.Entry<String, byte[]>>(zip.entrySet())) {
            if (!entry.getKey().startsWith("xl/worksheets/") || !entry.getKey().endsWith(".xml")) {
                continue;
            }
            String xml = new String(entry.getValue(), StandardCharsets.UTF_8);
            if (!xml.contains("SUM(C26:C29)")) {
                continue;
            }
            String next = xml.replaceAll(
                    "<c r=\"C30\"[^>]*>\\s*<f>SUM\\(C26:C29\\)</f>\\s*(?:<v[^>]*>.*?</v>|<v\\s*/>)?\\s*</c>",
                    "");
            next = next.replaceAll("<row r=\"30\"[^>]*>\\s*</row>", "");
            zip.put(entry.getKey(), next.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Data styles 25–31 used a border with no top edge. Give them a full thin box. */
    private static byte[] gridBorders(byte[] styles) {
        String xml = new String(styles, StandardCharsets.UTF_8);
        int start = xml.indexOf("<cellXfs");
        int open = start < 0 ? -1 : xml.indexOf('>', start);
        int end = xml.indexOf("</cellXfs>");
        if (open < 0 || end < open) {
            return styles;
        }
        Matcher xfs = Pattern.compile("(?s)<xf\\b[^>]*/>|<xf\\b[^>]*>.*?</xf>").matcher(xml.substring(open + 1, end));
        StringBuffer body = new StringBuffer();
        int index = 0;
        while (xfs.find()) {
            String tag = xfs.group();
            if (index >= 25 && index <= 31) {
                tag = tag.replaceFirst("borderId=\"\\d+\"", "borderId=\"1\"");
            }
            xfs.appendReplacement(body, Matcher.quoteReplacement(tag));
            index++;
        }
        xfs.appendTail(body);
        String next = xml.substring(0, open + 1) + body + xml.substring(end);
        return next.getBytes(StandardCharsets.UTF_8);
    }

    /** Viewers that skip calculation still show the cached number in <v>. */
    private static void cacheFormulaResults(Map<String, byte[]> zip) {
        for (Map.Entry<String, byte[]> entry : new ArrayList<Map.Entry<String, byte[]>>(zip.entrySet())) {
            String name = entry.getKey();
            if (!name.startsWith("xl/worksheets/") || !name.endsWith(".xml")) {
                continue;
            }
            String xml = new String(entry.getValue(), StandardCharsets.UTF_8);
            if (!xml.contains("<f>")) {
                continue;
            }
            zip.put(name, fillEmptyFormulaValues(xml).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String fillEmptyFormulaValues(String xml) {
        Matcher cells = Pattern.compile("(?s)<c r=\"([A-Z]+)(\\d+)\"([^>]*)>(.*?)</c>").matcher(xml);
        Map<String, Double> numbers = new LinkedHashMap<String, Double>();
        while (cells.find()) {
            String body = cells.group(4);
            if (body.contains("<f>")) {
                continue;
            }
            Matcher value = Pattern.compile("<v>(-?\\d+(?:\\.\\d+)?)</v>").matcher(body);
            if (value.find()) {
                numbers.put(cells.group(1) + cells.group(2), Double.valueOf(value.group(1)));
            }
        }
        Matcher formulas = Pattern.compile(
                "(?s)<f>(SUM\\(([A-Z]+)(\\d+):([A-Z]+)(\\d+)\\))</f>(<v\\s*/>|<v>\\s*</v>|<v>-?\\d+(?:\\.\\d+)?</v>)?")
                .matcher(xml);
        StringBuffer out = new StringBuffer();
        while (formulas.find()) {
            String cached = formulas.group(6);
            if (cached != null && cached.matches("<v>-?\\d+(?:\\.\\d+)?</v>")) {
                formulas.appendReplacement(out, Matcher.quoteReplacement(formulas.group(0)));
                continue;
            }
            double sum = sumRange(numbers, formulas.group(2), Integer.parseInt(formulas.group(3)),
                    formulas.group(4), Integer.parseInt(formulas.group(5)));
            String replacement = "<f>" + formulas.group(1) + "</f><v>" + num(sum) + "</v>";
            formulas.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        formulas.appendTail(out);
        return out.toString();
    }

    private static double sumRange(Map<String, Double> numbers, String colFrom, int rowFrom, String colTo, int rowTo) {
        int startCol = columnIndex(colFrom);
        int endCol = columnIndex(colTo);
        double sum = 0;
        for (Map.Entry<String, Double> entry : numbers.entrySet()) {
            Matcher ref = Pattern.compile("([A-Z]+)(\\d+)").matcher(entry.getKey());
            if (!ref.matches()) {
                continue;
            }
            int col = columnIndex(ref.group(1));
            int row = Integer.parseInt(ref.group(2));
            if (col >= startCol && col <= endCol && row >= rowFrom && row <= rowTo) {
                sum += entry.getValue();
            }
        }
        return sum;
    }

    private static int columnIndex(String letters) {
        int n = 0;
        for (int i = 0; i < letters.length(); i++) {
            n = n * 26 + (letters.charAt(i) - 'A' + 1);
        }
        return n;
    }

    private static String unsureXml(List<ProtocolRow> rows, List<String> sources) {
        StringBuilder data = new StringBuilder();
        data.append("<row r=\"1\">");
        plain(data, 1, 1, "Källa");
        plain(data, 2, 1, "Utrymme");
        plain(data, 3, 1, "Frånluft");
        plain(data, 4, 1, "Tilluft");
        plain(data, 5, 1, "Konfidens");
        plain(data, 6, 1, "Orsak");
        data.append("</row>");
        for (int i = 0; i < rows.size(); i++) {
            ProtocolRow row = rows.get(i);
            int r = i + 2;
            data.append("<row r=\"").append(r).append("\">");
            plain(data, 1, r, sources.get(i));
            plain(data, 2, r, row.room);
            plain(data, 3, r, row.exhaustName);
            plain(data, 4, r, row.supplyName);
            plain(data, 5, r, String.format(Locale.US, "%.2f", row.confidence));
            plain(data, 6, r, row.unsureReason);
            data.append("</row>");
        }
        return fallbackSheet("<sheetData>" + data + "</sheetData>", rows.size() + 1);
    }

    private static void plain(StringBuilder xml, int col, int row, String value) {
        xml.append("<c r=\"").append(cellRef(col, row))
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(esc(value)).append("</t></is></c>");
    }

    private static String fallbackHeader() {
        String[] headers = {
                "Rumsnummer\nHuonenro", "Rumsnamn\nUtrymme", "Poistoilmaelin Frånluftsdon", "Koko Storlek",
                "Tarve Behov (l/s)", "Mitattu Uppmätt (l/s)", "Paine Tryck (Pa)", "Asento Justering",
                "Tuloilmaelin Tilluftsdon", "Koko Storlek", "Tarve Behov (l/s)",
                "Mitattu Uppmätt (l/s)", "Paine Tryck (Pa)", "Asento Justering"
        };
        StringBuilder row = new StringBuilder("<row r=\"1\">");
        for (int i = 0; i < headers.length; i++) {
            plain(row, i + 1, 1, headers[i]);
        }
        row.append("</row>");
        return row.toString();
    }

    private static String fallbackSheet(String sheetData, int lastRow) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<dimension ref=\"A1:N" + lastRow + "\"/>"
                + sheetData
                + "<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>"
                + "</worksheet>";
    }

    private static String dropPrototypeSheets(String workbook) {
        return workbook.replaceAll("<sheet name=\"Sheet[123]\"[^>]*/>", "");
    }

    private static String insertSheet(String workbook, String name, int sheetId, String rid) {
        String tag = "<sheet name=\"" + escAttr(name) + "\" sheetId=\"" + sheetId
                + "\" state=\"visible\" r:id=\"" + rid + "\"/>";
        return workbook.replace("</sheets>", tag + "</sheets>");
    }

    private static String insertRel(String rels, String rid, String target) {
        String tag = "<Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\""
                + target + "\" Id=\"" + rid + "\"/>";
        return rels.replace("</Relationships>", tag + "</Relationships>");
    }

    private static String insertOverride(String types, String part) {
        String tag = "<Override PartName=\"" + part + "\" ContentType=\"" + SHEET_TYPE + "\"/>";
        return types.replace("</Types>", tag + "</Types>");
    }

    private static List<SheetRef> parseSheets(String workbook) {
        List<SheetRef> sheets = new ArrayList<SheetRef>();
        Matcher matcher = SHEET.matcher(workbook);
        while (matcher.find()) {
            sheets.add(new SheetRef(matcher.group(1), Integer.parseInt(matcher.group(2)), matcher.group(3), ""));
        }
        return sheets;
    }

    private static Map<String, String> parseRels(String rels) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        Matcher matcher = REL.matcher(rels);
        while (matcher.find()) {
            map.put(matcher.group(3), matcher.group(2));
        }
        for (SheetRef ignored : new ArrayList<SheetRef>()) {
            // targets filled by caller via map
        }
        return map;
    }

    private static String normalize(String target) {
        if (target == null) {
            return "";
        }
        String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        return path;
    }

    private static String text(Map<String, byte[]> zip, String name) throws IOException {
        byte[] data = zip.get(name);
        if (data == null) {
            throw new IOException("Saknar " + name + " i arbetsboken.");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> readZip(byte[] bytes) throws IOException {
        Map<String, byte[]> zip = new LinkedHashMap<String, byte[]>();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes));
        ZipEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            zip.put(entry.getName(), buf.toByteArray());
        }
        in.close();
        return zip;
    }

    private static byte[] writeZip(Map<String, byte[]> zip) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(buf);
        for (Map.Entry<String, byte[]> entry : zip.entrySet()) {
            out.putNextEntry(new ZipEntry(entry.getKey()));
            out.write(entry.getValue());
            out.closeEntry();
        }
        out.close();
        return buf.toByteArray();
    }

    private static String cellRef(int col, int row) {
        StringBuilder ref = new StringBuilder();
        int n = col;
        while (n > 0) {
            n--;
            ref.insert(0, (char) ('A' + (n % 26)));
            n /= 26;
        }
        ref.append(row);
        return ref.toString();
    }

    private static String num(Double value) {
        if (value == null) {
            return "";
        }
        if (value == Math.rint(value)) {
            return Integer.toString(value.intValue());
        }
        return String.format(Locale.US, "%s", value);
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String value) {
        return esc(value).replace("\"", "&quot;");
    }

    private static final class SheetRef {
        final String name;
        final int sheetId;
        final String rid;
        final String target;

        SheetRef(String name, int sheetId, String rid, String target) {
            this.name = name;
            this.sheetId = sheetId;
            this.rid = rid;
            this.target = target;
        }
    }

    public static final class DrawingSheet {
        public final String sheetName;
        public final List<ProtocolRow> rows;

        public DrawingSheet(String sheetName, List<ProtocolRow> rows) {
            this.sheetName = sheetName;
            this.rows = rows;
        }
    }
}
