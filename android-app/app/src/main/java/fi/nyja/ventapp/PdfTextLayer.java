package fi.nyja.ventapp;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import fi.nyja.ventapp.engine.OcrToken;

/**
 * Reads the PDF text layer on every page, including text drawn sideways.
 * PdfRenderer cannot do that before Android 15.
 * Coordinates use the same top-left point space as the OCR tiles.
 */
public final class PdfTextLayer {
    public static final class Result {
        public final List<OcrToken> tokens;
        public final String error;

        Result(List<OcrToken> tokens, String error) {
            this.tokens = tokens;
            this.error = error;
        }
    }

    private PdfTextLayer() {
    }

    public static Result read(Context context, byte[] pdf) {
        try {
            if (context != null) {
                PDFBoxResourceLoader.init(context.getApplicationContext());
            }
        } catch (Throwable ignored) {
            // Standard fonts still extract without the Android resource loader.
        }
        return read(pdf);
    }

    public static Result read(byte[] pdf) {
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        PDDocument document = null;
        try {
            document = PDDocument.load(pdf);
            WordStripper stripper = new WordStripper();
            int pages = document.getNumberOfPages();
            for (int page = 0; page < pages; page++) {
                float height = document.getPage(page).getMediaBox().getHeight();
                stripper.yShift = page * (height + 40f);
                stripper.glyphs.clear();
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                stripper.getText(document);
                tokens.addAll(words(stripper.glyphs));
            }
            return new Result(tokens, null);
        } catch (Throwable error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            Throwable cause = error.getCause();
            if (cause == null && error instanceof ExceptionInInitializerError) {
                cause = ((ExceptionInInitializerError) error).getException();
            }
            if (cause != null) {
                String detail = cause.getMessage();
                message = message + " (" + cause.getClass().getSimpleName()
                        + (detail == null ? "" : ": " + detail) + ")";
            }
            return new Result(tokens, message);
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (Exception ignored) {
                    // The tokens already collected stay usable.
                }
            }
        }
    }

    static List<OcrToken> words(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<Glyph>(glyphs);
        Collections.sort(sorted, new Comparator<Glyph>() {
            @Override
            public int compare(Glyph a, Glyph b) {
                int dir = a.dir - b.dir;
                if (dir != 0) {
                    return dir;
                }
                int across = Float.compare(a.across, b.across);
                return across != 0 ? across : Float.compare(a.along, b.along);
            }
        });
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        List<Glyph> word = new ArrayList<Glyph>();
        for (Glyph glyph : sorted) {
            if (glyph.ch == null || glyph.ch.trim().isEmpty()) {
                flush(tokens, word);
                continue;
            }
            if (!word.isEmpty() && !continues(word.get(word.size() - 1), glyph)) {
                flush(tokens, word);
            }
            word.add(glyph);
        }
        flush(tokens, word);
        return tokens;
    }

    private static void flush(List<OcrToken> tokens, List<Glyph> word) {
        if (word.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        float x0 = word.get(0).x;
        float x1 = word.get(0).x;
        float y = 0;
        float h = 0;
        for (Glyph glyph : word) {
            text.append(glyph.ch);
            x0 = Math.min(x0, glyph.x);
            x1 = Math.max(x1, glyph.x + Math.max(glyph.w, 0.5f));
            y += glyph.y;
            h = Math.max(h, glyph.h);
        }
        String value = text.toString().trim();
        if (value.length() >= 1) {
            tokens.add(new OcrToken(value, (x0 + x1) / 2f, y / word.size(), 0.98, Math.max(1f, x1 - x0), Math.max(1f, h)));
        }
        word.clear();
    }

    private static boolean continues(Glyph previous, Glyph next) {
        if (previous.dir != next.dir) {
            return false;
        }
        float height = Math.max(previous.h, next.h);
        if (Math.abs(previous.across - next.across) > Math.max(2f, height * 0.8f)) {
            return false;
        }
        float gap = next.along - (previous.along + Math.max(previous.w, 0.4f));
        return gap >= -previous.w && gap <= Math.max(1.6f, height * 0.45f);
    }

    static final class Glyph {
        final String ch;
        final float x;
        final float y;
        final float w;
        final float h;
        final float along;
        final float across;
        final int dir;

        Glyph(String ch, float x, float y, float w, float h, int dir) {
            this.ch = ch;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.dir = dir;
            // XDirAdj/YDirAdj are already rotated into a top-left line, for every direction.
            this.along = x;
            this.across = y;
        }
    }

    private static final class WordStripper extends PDFTextStripper {
        private final List<Glyph> glyphs = new ArrayList<Glyph>();
        private float yShift;

        private WordStripper() throws Exception {
            super();
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(false);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            String value = text.getUnicode();
            if (value == null || value.isEmpty()) {
                return;
            }
            int dir = Math.round(text.getDir());
            float x = text.getXDirAdj();
            float y = text.getYDirAdj() + yShift;
            float w = Math.max(0.4f, text.getWidthDirAdj());
            float h = Math.max(0.4f, text.getHeightDir());
            glyphs.add(new Glyph(value, x, y, w, h, dir));
        }
    }
}
