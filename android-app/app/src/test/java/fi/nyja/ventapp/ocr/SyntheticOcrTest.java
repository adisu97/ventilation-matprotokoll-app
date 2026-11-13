package fi.nyja.ventapp.ocr;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import fi.nyja.ventapp.engine.OcrToken;

import static org.junit.Assert.assertTrue;

/**
 * Runs the shipped det.onnx and rec.onnx on a drawn image
 * (E125 WC, KSO-125 20 l/s, LOCKZONE, and EXCA turned 90 degrees).
 */
public class SyntheticOcrTest {
    @Test
    public void onnxReadsDrawnRoomAndVentText() throws Exception {
        File det = model("det.onnx");
        File rec = model("rec.onnx");
        File keys = model("ppocr_keys.txt");
        int[] image = loadPpm(fixture());
        int width = image[0];
        int height = image[1];
        int[] px = new int[width * height];
        System.arraycopy(image, 2, px, 0, px.length);
        PpOcr ocr;
        try {
            ocr = new PpOcr(read(det), read(rec), new FileInputStream(keys));
        } catch (Throwable error) {
            throw new AssertionError("ONNX runtime did not start: " + error, error);
        }
        try {
            ocr.resetCounts();
            List<OcrToken> tokens = ocr.readPixels(px, width, height, 0, 0, 1f);
            StringBuilder seen = new StringBuilder();
            for (OcrToken token : tokens) {
                seen.append(token.text).append('\n');
            }
            String all = seen.toString().toUpperCase();
            assertTrue("boxes=" + ocr.boxes + " recognised=" + ocr.recognized + " kept=" + ocr.kept + "\n" + seen,
                    ocr.boxes > 0 && (all.contains("E125") || all.contains("KSO") || all.contains("LOCK")
                            || all.contains("EXCA")));
        } finally {
            ocr.close();
        }
    }

    private static File fixture() {
        File[] files = {
                new File("src/test/resources/ocr_labels.ppm"),
                new File("app/src/test/resources/ocr_labels.ppm")
        };
        for (File file : files) {
            if (file.isFile()) {
                return file;
            }
        }
        throw new IllegalStateException("ocr_labels.ppm");
    }

    private static File model(String name) {
        File[] roots = {
                new File("src/main/assets/ocr/" + name),
                new File("app/src/main/assets/ocr/" + name)
        };
        for (File file : roots) {
            if (file.isFile()) {
                return file;
            }
        }
        throw new IllegalStateException(name);
    }

    private static int[] loadPpm(File file) throws Exception {
        byte[] data = read(file);
        int i = 0;
        if (data[0] != 'P' || data[1] != '6') {
            throw new IllegalStateException("not P6");
        }
        i = 2;
        int width = nextInt(data, i);
        i = skipToken(data, i);
        int height = nextInt(data, i);
        i = skipToken(data, i);
        int max = nextInt(data, i);
        i = skipToken(data, i);
        if (data[i] == '\n') {
            i++;
        }
        if (max != 255) {
            throw new IllegalStateException("max " + max);
        }
        int[] out = new int[2 + width * height];
        out[0] = width;
        out[1] = height;
        for (int p = 0; p < width * height; p++) {
            int r = data[i++] & 255;
            int g = data[i++] & 255;
            int b = data[i++] & 255;
            out[2 + p] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    private static int nextInt(byte[] data, int i) {
        while (i < data.length && (data[i] == ' ' || data[i] == '\n' || data[i] == '\r' || data[i] == '\t')) {
            i++;
        }
        int n = 0;
        while (i < data.length && data[i] >= '0' && data[i] <= '9') {
            n = n * 10 + (data[i] - '0');
            i++;
        }
        return n;
    }

    private static int skipToken(byte[] data, int i) {
        while (i < data.length && (data[i] == ' ' || data[i] == '\n' || data[i] == '\r' || data[i] == '\t')) {
            i++;
        }
        while (i < data.length && data[i] > ' ') {
            i++;
        }
        return i;
    }

    private static byte[] read(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            return in.readAllBytes();
        } finally {
            in.close();
        }
    }
}
