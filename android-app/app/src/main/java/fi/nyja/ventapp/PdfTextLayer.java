package fi.nyja.ventapp;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.List;

import fi.nyja.ventapp.engine.OcrToken;

/**
 * Reads the PDF text layer. PdfRenderer cannot do that before Android 15.
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
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        PDDocument document = null;
        try {
            PDFBoxResourceLoader.init(context.getApplicationContext());
            document = PDDocument.load(pdf);
            WordStripper stripper = new WordStripper(tokens);
            int pages = document.getNumberOfPages();
            for (int page = 0; page < pages; page++) {
                float height = document.getPage(page).getMediaBox().getHeight();
                stripper.yShift = page * (height + 40f);
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                stripper.getText(document);
            }
            return new Result(tokens, null);
        } catch (Throwable error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = error.getClass().getSimpleName();
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

    private static final class WordStripper extends PDFTextStripper {
        private final List<OcrToken> tokens;
        private float yShift;

        private WordStripper(List<OcrToken> tokens) throws Exception {
            super();
            this.tokens = tokens;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || positions == null || positions.isEmpty()) {
                return;
            }
            String word = text.trim();
            if (word.length() < 2) {
                return;
            }
            TextPosition first = positions.get(0);
            TextPosition last = positions.get(positions.size() - 1);
            float x0 = first.getXDirAdj();
            float x1 = last.getXDirAdj() + last.getWidthDirAdj();
            float y = first.getYDirAdj() + yShift;
            tokens.add(new OcrToken(word, (x0 + x1) / 2f, y, 0.98, Math.max(1f, x1 - x0), first.getHeightDir()));
        }
    }
}
