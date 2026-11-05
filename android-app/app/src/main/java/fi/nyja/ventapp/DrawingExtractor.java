package fi.nyja.ventapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import fi.nyja.ventapp.engine.OcrGeometry;
import fi.nyja.ventapp.engine.OcrToken;
import fi.nyja.ventapp.engine.ProtocolBuilder;
import fi.nyja.ventapp.engine.TokenCleanup;
import fi.nyja.ventapp.engine.ProtocolRow;
import fi.nyja.ventapp.engine.ProtocolXlsx;
import fi.nyja.ventapp.engine.Room;
import fi.nyja.ventapp.engine.SheetNames;
import fi.nyja.ventapp.engine.Vent;
import fi.nyja.ventapp.ocr.PpOcr;

/** Renders each drawing on the device, runs OCR, and writes the Excel file. */
public final class DrawingExtractor {
    public interface Progress {
        void onProgress(int fileIndex, int fileCount, float fraction, String message);
    }

    public static final class Outcome {
        public final byte[] xlsx;
        public final List<String> notes;
        public final String debug;

        public Outcome(byte[] xlsx, List<String> notes, String debug) {
            this.xlsx = xlsx;
            this.notes = notes;
            this.debug = debug == null ? "" : debug;
        }
    }

    private DrawingExtractor() {
    }

    public static Outcome extract(Context context, List<DrawingEntry> drawings, String existingUri, Progress progress)
            throws Exception {
        VentNameStore.load(context);
        RoomFormatStore.load(context);
        byte[] base = existingUri == null ? readAsset(context, "matprotokoll_grund.xlsx") : readUri(context, Uri.parse(existingUri));
        boolean fromTemplate = existingUri == null;
        PpOcr ocr = openOcr(context);
        List<ProtocolXlsx.DrawingSheet> sheets = new ArrayList<ProtocolXlsx.DrawingSheet>();
        List<String> notes = new ArrayList<String>();
        List<String> usedNames = new ArrayList<String>();
        StringBuilder debug = new StringBuilder();
        int count = drawings.size();
        try {
            for (int i = 0; i < count; i++) {
                DrawingEntry entry = drawings.get(i);
                final int index = i;
                try {
                    int mark = debug.length();
                    ocr.resetCounts();
                    List<OcrToken> tokens = readDrawing(context, ocr, entry, new TileProgress() {
                        @Override
                        public void onTile(float fraction) {
                            if (progress != null) {
                                progress.onProgress(index, count, fraction, entry.name);
                            }
                        }
                    }, debug, notes);
                    List<Room> rooms = ProtocolBuilder.roomsFromTokens(tokens);
                    List<Vent> vents = ProtocolBuilder.ventsFromTokens(tokens);
                    List<ProtocolRow> rows = ProtocolBuilder.pair(rooms, vents);
                    int unsure = 0;
                    for (ProtocolRow row : rows) {
                        if (row.unsure) {
                            unsure++;
                        }
                    }
                    String summary = "Sammanfattning " + (entry.name == null ? "" : entry.name) + "\n"
                            + "OCR-rutor: " + ocr.boxes + "\n"
                            + "OCR-texter: " + ocr.recognized + "\n"
                            + "OCR-kvar: " + ocr.kept + "\n"
                            + "rum: " + rooms.size() + "\n"
                            + "don: " + vents.size() + "\n"
                            + "osäkra: " + unsure + "\n\n";
                    debug.insert(mark, summary);
                    debug.append("rum: ").append(rooms.size()).append("\n");
                    debug.append("don: ").append(vents.size()).append("\n");
                    debug.append("osäkra: ").append(unsure).append("\n\n");
                    if (rooms.isEmpty() && vents.isEmpty()) {
                        if (ocr.boxes == 0) {
                            notes.add(entry.name + ": OCR hittade 0 rutor. Ingen text att tolka.");
                        } else {
                            notes.add(entry.name + ": OCR " + ocr.boxes + " rutor, " + ocr.recognized
                                    + " texter, " + ocr.kept + " kvar efter filter. Inget rum eller don tolkades.");
                        }
                    }
                    String sheet = SheetNames.fromFileName(entry.name, usedNames);
                    usedNames.add(sheet);
                    sheets.add(new ProtocolXlsx.DrawingSheet(sheet, rows));
                    if (vents.isEmpty()) {
                        notes.add(entry.name + ": inga kända don hittades.");
                    }
                    if (rooms.isEmpty()) {
                        notes.add(entry.name + ": inga rumsnummer hittades.");
                    }
                } catch (Exception ex) {
                    notes.add(entry.name + ": " + message(ex));
                }
                if (progress != null) {
                    progress.onProgress(i, count, 1f, entry.name);
                }
            }
        } finally {
            ocr.close();
        }
        if (sheets.isEmpty()) {
            throw new IllegalStateException("Ingen ritning kunde läsas.");
        }
        return new Outcome(ProtocolXlsx.build(base, sheets, fromTemplate), notes, debug.toString());
    }

    private static PpOcr openOcr(Context context) throws Exception {
        byte[] det = readAsset(context, "ocr/det.onnx");
        byte[] rec = readAsset(context, "ocr/rec.onnx");
        return new PpOcr(det, rec, context.getAssets().open("ocr/ppocr_keys.txt"));
    }

    private static List<OcrToken> readDrawing(Context context, PpOcr ocr, DrawingEntry entry, TileProgress progress,
            StringBuilder debug, List<String> notes) throws Exception {
        debug.append("== ").append(entry.name == null ? "ritning" : entry.name).append("\n");
        List<OcrToken> tokens;
        if (DrawingEntry.KIND_PHOTO.equals(entry.kind) && entry.photoPath != null) {
            tokens = readBitmapFile(ocr, entry.photoPath, progress, debug);
        } else {
            Uri uri = Uri.parse(entry.uri);
            String name = entry.name == null ? "" : entry.name.toLowerCase();
            if (DrawingEntry.KIND_PDF.equals(entry.kind) || name.endsWith(".pdf")) {
                ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
                if (fd == null) {
                    throw new IllegalStateException("Kunde inte öppna PDF:en.");
                }
                try {
                    tokens = readPdf(context, ocr, fd, progress, debug, notes, entry.name);
                } finally {
                    fd.close();
                }
            } else {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IllegalStateException("Kunde inte öppna bilden.");
                }
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                in.close();
                if (bitmap == null) {
                    throw new IllegalStateException("Bilden gick inte att läsa.");
                }
                try {
                    tokens = readBitmap(ocr, bitmap, progress, debug);
                } finally {
                    bitmap.recycle();
                }
            }
        }
        List<OcrToken> prepared = TokenCleanup.prepare(tokens);
        debug.append("rutor=").append(tokens.size()).append(" efter sammanslagning=").append(prepared.size()).append("\n");
        for (OcrToken token : prepared) {
            debug.append(token.text).append("\n");
        }
        return prepared;
    }

    private static List<OcrToken> readBitmapFile(PpOcr ocr, String path, TileProgress progress, StringBuilder debug)
            throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 4500) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
        if (bitmap == null) {
            throw new IllegalStateException("Fotot gick inte att läsa.");
        }
        debug.append("foto ").append(bitmap.getWidth()).append("x").append(bitmap.getHeight())
                .append(" sample=").append(sample).append("\n");
        try {
            return readBitmap(ocr, bitmap, progress, debug);
        } finally {
            bitmap.recycle();
        }
    }

    private static List<OcrToken> readBitmap(PpOcr ocr, Bitmap bitmap, TileProgress progress, StringBuilder debug)
            throws Exception {
        float pointsPerPixel = 1000f / Math.max(bitmap.getWidth(), bitmap.getHeight());
        return readTiled(ocr, bitmap, pointsPerPixel, 0, progress, debug);
    }

    private static List<OcrToken> readPdf(Context context, PpOcr ocr, ParcelFileDescriptor fd, TileProgress progress,
            StringBuilder debug, List<String> notes, String drawingName) throws Exception {
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        String label = drawingName == null ? "PDF" : drawingName;
        try {
            ParcelFileDescriptor copy = fd.dup();
            byte[] pdf;
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(copy.getFileDescriptor());
                try {
                    pdf = readAll(in);
                } finally {
                    in.close();
                }
            } finally {
                copy.close();
            }
            PdfTextLayer.Result text = PdfTextLayer.read(context, pdf);
            if (text.error != null) {
                String message = label + ": textlagret kunde inte läsas: " + text.error;
                notes.add(message);
                debug.append(message).append("\n");
                debug.append("textlager: 0 ord\n");
            } else {
                tokens.addAll(text.tokens);
                debug.append("textlager: ").append(text.tokens.size()).append(" ord\n");
                if (text.tokens.isEmpty()) {
                    debug.append("textlager tomt, OCR används\n");
                }
            }
        } catch (Exception ex) {
            String message = label + ": textlagret kunde inte läsas: " + message(ex);
            notes.add(message);
            debug.append(message).append("\n");
            debug.append("textlager: 0 ord\n");
        }
        int beforeOcr = tokens.size();
        try {
            PdfRenderer renderer = new PdfRenderer(fd);
            try {
            int pages = renderer.getPageCount();
            for (int p = 0; p < pages; p++) {
                PdfRenderer.Page page = renderer.openPage(p);
                try {
                    // 300 dpi: an 8 pt label is about 33 px tall, inside the 20–40 px band.
                    float scale = 300f / 72f;
                    int fullW = Math.max(1, Math.round(page.getWidth() * scale));
                    int fullH = Math.max(1, Math.round(page.getHeight() * scale));
                    debug.append("pdf sida ").append(p + 1).append(" skala=").append(scale)
                            .append(" px=").append(fullW).append("x").append(fullH)
                            .append(" text8pt=").append(Math.round(8f * scale)).append("px\n");
                    int tile = 960;
                    int overlap = 280;
                    int step = Math.max(1, tile - overlap);
                    int tilesX = Math.max(1, (fullW + step - 1) / step);
                    int tilesY = Math.max(1, (fullH + step - 1) / step);
                    int total = Math.max(1, pages * tilesX * tilesY);
                    int done = p * tilesX * tilesY;
                    float yShift = p * (page.getHeight() + 40);
                    for (int ty = 0; ty < tilesY; ty++) {
                        for (int tx = 0; tx < tilesX; tx++) {
                            int ox = tx * step;
                            int oy = ty * step;
                            int tw = Math.min(tile, fullW - ox);
                            int th = Math.min(tile, fullH - oy);
                            done++;
                            if (tw < 32 || th < 32) {
                                continue;
                            }
                            Bitmap bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                            Matrix matrix = new Matrix();
                            matrix.setScale(scale, scale);
                            matrix.postTranslate(-ox, -oy);
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            try {
                                if (!mostlyBlank(bitmap)) {
                                    tokens.addAll(readAngles(ocr, bitmap, ox / scale, oy / scale + yShift, scale));
                                }
                            } catch (Exception ex) {
                                String message = label + ": OCR-rutan misslyckades: " + message(ex);
                                notes.add(message);
                                debug.append(message).append("\n");
                            }
                            bitmap.recycle();
                            if (progress != null) {
                                progress.onTile(Math.min(1f, done / (float) total));
                            }
                        }
                    }
                } finally {
                    page.close();
                }
            }
            } finally {
                renderer.close();
            }
            debug.append("OCR-rutor: ").append(tokens.size() - beforeOcr).append("\n");
        } catch (Exception ex) {
            String message = label + ": sidan kunde inte ritas: " + message(ex);
            notes.add(message);
            debug.append(message).append("\n");
            debug.append("OCR-rutor: ").append(tokens.size() - beforeOcr).append("\n");
        }
        return tokens;
    }

    private static List<OcrToken> readTiled(PpOcr ocr, Bitmap bitmap, float pointsPerPixel, float yShift,
            TileProgress progress, StringBuilder debug) throws Exception {
        int tile = 960;
        int overlap = 240;
        int step = tile - overlap;
        int tilesX = Math.max(1, (bitmap.getWidth() + step - 1) / step);
        int tilesY = Math.max(1, (bitmap.getHeight() + step - 1) / step);
        debug.append("bildrutor ").append(tilesX).append("x").append(tilesY).append("\n");
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        int total = Math.max(1, tilesX * tilesY);
        int done = 0;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int ox = tx * step;
                int oy = ty * step;
                int tw = Math.min(tile, bitmap.getWidth() - ox);
                int th = Math.min(tile, bitmap.getHeight() - oy);
                done++;
                if (tw < 32 || th < 32) {
                    continue;
                }
                Bitmap part = Bitmap.createBitmap(bitmap, ox, oy, tw, th);
                if (!mostlyBlank(part)) {
                    tokens.addAll(readAngles(ocr, part, ox * pointsPerPixel, oy * pointsPerPixel + yShift, 1f / pointsPerPixel));
                }
                part.recycle();
                if (progress != null) {
                    progress.onTile(Math.min(1f, done / (float) total));
                }
            }
        }
        return tokens;
    }

    private static List<OcrToken> readAngles(PpOcr ocr, Bitmap tile, double originX, double originY, float pixelsPerPoint)
            throws Exception {
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        int[] angles = new int[]{0, 90, 270};
        for (int angle : angles) {
            Bitmap view = angle == 0 ? tile : rotate(tile, angle);
            try {
                List<OcrToken> found = ocr.read(view, 0, 0, 1f);
                for (OcrToken token : found) {
                    double[] point = OcrGeometry.unmapClockwise(token.x, token.y, tile.getWidth(), tile.getHeight(), angle);
                    tokens.add(new OcrToken(
                            token.text,
                            originX + point[0] / pixelsPerPoint,
                            originY + point[1] / pixelsPerPoint,
                            token.confidence,
                            token.w / pixelsPerPoint,
                            token.h / pixelsPerPoint));
                }
            } finally {
                if (view != tile) {
                    view.recycle();
                }
            }
        }
        return tokens;
    }

    private static Bitmap rotate(Bitmap source, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static boolean mostlyBlank(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(2, Math.min(width, height) / 120);
        int dark = 0;
        int count = 0;
        long sum = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int color = bitmap.getPixel(x, y);
                int gray = ((color >> 16) & 255);
                sum += gray;
                count++;
                if (gray < 210) {
                    dark++;
                }
            }
        }
        if (count == 0) {
            return true;
        }
        return dark < 2 && sum / (double) count > 248;
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        InputStream in = context.getAssets().open(name);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readUri(Context context, Uri uri) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("Kunde inte öppna Excel-filen.");
        }
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static String message(Exception ex) {
        String text = ex.getMessage();
        if (text == null || text.trim().isEmpty()) {
            return "okänt fel";
        }
        return text;
    }

    private interface TileProgress {
        void onTile(float fraction);
    }
}
