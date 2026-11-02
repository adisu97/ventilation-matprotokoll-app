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

import fi.nyja.ventapp.engine.OcrToken;
import fi.nyja.ventapp.engine.ProtocolBuilder;
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

        public Outcome(byte[] xlsx, List<String> notes) {
            this.xlsx = xlsx;
            this.notes = notes;
        }
    }

    private DrawingExtractor() {
    }

    public static Outcome extract(Context context, List<DrawingEntry> drawings, String existingUri, Progress progress)
            throws Exception {
        byte[] base = existingUri == null ? readAsset(context, "matprotokoll_grund.xlsx") : readUri(context, Uri.parse(existingUri));
        boolean fromTemplate = existingUri == null;
        PpOcr ocr = openOcr(context);
        List<ProtocolXlsx.DrawingSheet> sheets = new ArrayList<ProtocolXlsx.DrawingSheet>();
        List<String> notes = new ArrayList<String>();
        List<String> usedNames = new ArrayList<String>();
        int count = drawings.size();
        try {
            for (int i = 0; i < count; i++) {
                DrawingEntry entry = drawings.get(i);
                final int index = i;
                try {
                    List<OcrToken> tokens = readDrawing(context, ocr, entry, new TileProgress() {
                        @Override
                        public void onTile(float fraction) {
                            if (progress != null) {
                                progress.onProgress(index, count, fraction, entry.name);
                            }
                        }
                    });
                    List<Room> rooms = ProtocolBuilder.roomsFromTokens(tokens);
                    List<Vent> vents = ProtocolBuilder.ventsFromTokens(tokens);
                    List<ProtocolRow> rows = ProtocolBuilder.pair(rooms, vents);
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
        return new Outcome(ProtocolXlsx.build(base, sheets, fromTemplate), notes);
    }

    private static PpOcr openOcr(Context context) throws Exception {
        byte[] det = readAsset(context, "ocr/det.onnx");
        byte[] rec = readAsset(context, "ocr/rec.onnx");
        return new PpOcr(det, rec, context.getAssets().open("ocr/ppocr_keys.txt"));
    }

    private static List<OcrToken> readDrawing(Context context, PpOcr ocr, DrawingEntry entry, TileProgress progress)
            throws Exception {
        if (DrawingEntry.KIND_PHOTO.equals(entry.kind) && entry.photoPath != null) {
            return readBitmapFile(ocr, entry.photoPath, progress);
        }
        Uri uri = Uri.parse(entry.uri);
        String name = entry.name == null ? "" : entry.name.toLowerCase();
        if (DrawingEntry.KIND_PDF.equals(entry.kind) || name.endsWith(".pdf")) {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) {
                throw new IllegalStateException("Kunde inte öppna PDF:en.");
            }
            try {
                return readPdf(ocr, fd, progress);
            } finally {
                fd.close();
            }
        }
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
            return ocr.read(bitmap, 0, 0, 1f);
        } finally {
            bitmap.recycle();
        }
    }

    private static List<OcrToken> readBitmapFile(PpOcr ocr, String path, TileProgress progress) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2200) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
        if (bitmap == null) {
            throw new IllegalStateException("Fotot gick inte att läsa.");
        }
        if (progress != null) {
            progress.onTile(0.2f);
        }
        try {
            return ocr.read(bitmap, 0, 0, sample);
        } finally {
            bitmap.recycle();
            if (progress != null) {
                progress.onTile(1f);
            }
        }
    }

    private static List<OcrToken> readPdf(PpOcr ocr, ParcelFileDescriptor fd, TileProgress progress) throws Exception {
        PdfRenderer renderer = new PdfRenderer(fd);
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        try {
            int pages = renderer.getPageCount();
            for (int p = 0; p < pages; p++) {
                PdfRenderer.Page page = renderer.openPage(p);
                try {
                    float scale = 1.5f;
                    int fullW = Math.max(1, Math.round(page.getWidth() * scale));
                    int fullH = Math.max(1, Math.round(page.getHeight() * scale));
                    int maxSide = 3600;
                    if (Math.max(fullW, fullH) > maxSide) {
                        float shrink = maxSide / (float) Math.max(fullW, fullH);
                        scale *= shrink;
                        fullW = Math.max(1, Math.round(page.getWidth() * scale));
                        fullH = Math.max(1, Math.round(page.getHeight() * scale));
                    }
                    int tile = 640;
                    int tilesX = Math.max(1, (fullW + tile - 1) / tile);
                    int tilesY = Math.max(1, (fullH + tile - 1) / tile);
                    int total = Math.max(1, pages * tilesX * tilesY);
                    int done = p * tilesX * tilesY;
                    float yShift = p * (page.getHeight() + 40);
                    for (int ty = 0; ty < tilesY; ty++) {
                        for (int tx = 0; tx < tilesX; tx++) {
                            int ox = tx * tile;
                            int oy = ty * tile;
                            int tw = Math.min(tile, fullW - ox);
                            int th = Math.min(tile, fullH - oy);
                            if (tw < 8 || th < 8) {
                                continue;
                            }
                            Bitmap bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                            Matrix matrix = new Matrix();
                            matrix.setScale(scale, scale);
                            matrix.postTranslate(-ox, -oy);
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            tokens.addAll(ocr.read(bitmap, ox, oy + yShift * scale, scale));
                            bitmap.recycle();
                            done++;
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
        return tokens;
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
