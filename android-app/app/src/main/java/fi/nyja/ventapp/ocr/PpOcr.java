package fi.nyja.ventapp.ocr;

import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import fi.nyja.ventapp.engine.OcrToken;

/** PP-OCRv4 det + rec. Models are the Apache-2.0 PaddleOCR weights shipped in assets. */
public final class PpOcr implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession det;
    private final OrtSession rec;
    private final List<String> keys;

    public PpOcr(byte[] detModel, byte[] recModel, InputStream charset) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);
        det = env.createSession(detModel, options);
        rec = env.createSession(recModel, options);
        keys = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(charset, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isEmpty()) {
                keys.add(line);
            }
        }
        reader.close();
    }

    public List<OcrToken> read(Bitmap bitmap, float originX, float originY, float pixelsPerPoint) throws Exception {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] px = new int[width * height];
        bitmap.getPixels(px, 0, width, 0, 0, width, height);
        contrast(px);
        int rw = width;
        int rh = height;
        float scale = 1f;
        int maxSide = 960;
        if (Math.max(width, height) > maxSide) {
            scale = maxSide / (float) Math.max(width, height);
            rw = Math.max(32, Math.round(width * scale / 32f) * 32);
            rh = Math.max(32, Math.round(height * scale / 32f) * 32);
        } else {
            rw = Math.max(32, Math.round(width / 32f) * 32);
            rh = Math.max(32, Math.round(height / 32f) * 32);
        }
        float[] input = chw(px, width, height, rw, rh);
        float[] map;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, rh, rw});
             OrtSession.Result result = det.run(java.util.Collections.singletonMap("x", tensor))) {
            map = flatten((OnnxTensor) result.get(0));
        }
        List<int[]> boxes = components(map, rw, rh);
        List<OcrToken> tokens = new ArrayList<OcrToken>();
        float backX = width / (float) rw;
        float backY = height / (float) rh;
        int limit = Math.min(boxes.size(), 80);
        for (int i = 0; i < limit; i++) {
            int[] box = boxes.get(i);
            int x0 = clamp((int) (box[0] * backX) - 2, 0, width - 1);
            int y0 = clamp((int) (box[1] * backY) - 2, 0, height - 1);
            int x1 = clamp((int) (box[2] * backX) + 2, x0 + 1, width);
            int y1 = clamp((int) (box[3] * backY) + 2, y0 + 1, height);
            String text = recognize(px, width, x0, y0, x1, y1);
            if (text.length() < 2) {
                continue;
            }
            double cx = (originX + (x0 + x1) / 2f) / pixelsPerPoint;
            double cy = (originY + (y0 + y1) / 2f) / pixelsPerPoint;
            tokens.add(new OcrToken(text, cx, cy, 0.75, (x1 - x0) / pixelsPerPoint, (y1 - y0) / pixelsPerPoint));
        }
        return tokens;
    }

    private String recognize(int[] px, int stride, int x0, int y0, int x1, int y1) throws Exception {
        int cropW = x1 - x0;
        int cropH = y1 - y0;
        int targetH = 48;
        int targetW = Math.max(8, Math.min(320, Math.round(targetH * (cropW / (float) cropH))));
        float[] chw = new float[3 * targetH * targetW];
        for (int y = 0; y < targetH; y++) {
            int sy = y0 + Math.min(cropH - 1, y * cropH / targetH);
            for (int x = 0; x < targetW; x++) {
                int sx = x0 + Math.min(cropW - 1, x * cropW / targetW);
                int color = px[sy * stride + sx];
                float r = (((color >> 16) & 255) / 255f - 0.5f) / 0.5f;
                float g = (((color >> 8) & 255) / 255f - 0.5f) / 0.5f;
                float b = ((color & 255) / 255f - 0.5f) / 0.5f;
                int base = y * targetW + x;
                chw[base] = r;
                chw[targetH * targetW + base] = g;
                chw[2 * targetH * targetW + base] = b;
            }
        }
        float[] logits;
        long[] shape;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, targetH, targetW});
             OrtSession.Result result = rec.run(java.util.Collections.singletonMap("x", tensor))) {
            OnnxTensor out = (OnnxTensor) result.get(0);
            logits = flatten(out);
            shape = ((ai.onnxruntime.TensorInfo) out.getInfo()).getShape();
        }
        int classes = 6625;
        int steps = logits.length / classes;
        if (shape.length >= 2) {
            classes = (int) shape[shape.length - 1];
            steps = logits.length / classes;
        }
        return ctc(logits, steps, classes);
    }

    private String ctc(float[] logits, int steps, int classes) {
        StringBuilder text = new StringBuilder();
        int prev = -1;
        double score = 0;
        int count = 0;
        for (int t = 0; t < steps; t++) {
            int best = 0;
            float bestV = -1e9f;
            int offset = t * classes;
            for (int c = 0; c < classes; c++) {
                float v = logits[offset + c];
                if (v > bestV) {
                    bestV = v;
                    best = c;
                }
            }
            if (best != 0 && best != prev && best - 1 < keys.size()) {
                text.append(keys.get(best - 1));
                score += bestV;
                count++;
            }
            prev = best;
        }
        return text.toString().trim();
    }

    static List<int[]> components(float[] map, int width, int height) {
        if (map.length < width * height) {
            return new ArrayList<int[]>();
        }
        boolean[] seen = new boolean[width * height];
        List<int[]> boxes = new ArrayList<int[]>();
        int[] stack = new int[width * height];
        for (int i = 0; i < map.length; i++) {
            if (seen[i] || map[i] < 0.3f) {
                continue;
            }
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            int minX = i % width;
            int maxX = minX;
            int minY = i / width;
            int maxY = minY;
            int count = 0;
            float sum = 0;
            while (sp > 0) {
                int cur = stack[--sp];
                int x = cur % width;
                int y = cur / width;
                count++;
                sum += map[cur];
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (count > 12000) {
                    break;
                }
                sp = consider(stack, seen, map, width, height, sp, x + 1, y);
                sp = consider(stack, seen, map, width, height, sp, x - 1, y);
                sp = consider(stack, seen, map, width, height, sp, x, y + 1);
                sp = consider(stack, seen, map, width, height, sp, x, y - 1);
            }
            if (count > 12 && sum / count >= 0.5f && maxX - minX > 6 && maxY - minY > 4) {
                boxes.add(new int[]{minX, minY, maxX + 1, maxY + 1});
            }
            if (boxes.size() >= 80) {
                break;
            }
        }
        return boxes;
    }

    private static int consider(int[] stack, boolean[] seen, float[] map, int width, int height, int sp, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return sp;
        }
        int idx = y * width + x;
        if (seen[idx] || map[idx] < 0.3f) {
            return sp;
        }
        seen[idx] = true;
        stack[sp] = idx;
        return sp + 1;
    }

    private static float[] chw(int[] px, int sw, int sh, int dw, int dh) {
        float[] out = new float[3 * dw * dh];
        int plane = dw * dh;
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, y * sh / dh);
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, x * sw / dw);
                int color = px[sy * sw + sx];
                int i = y * dw + x;
                out[i] = norm((color >> 16) & 255);
                out[plane + i] = norm((color >> 8) & 255);
                out[2 * plane + i] = norm(color & 255);
            }
        }
        return out;
    }

    private static void contrast(int[] px) {
        int min = 255;
        int max = 0;
        for (int i = 0; i < px.length; i += 8) {
            int c = px[i];
            int g = (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10;
            if (g < min) min = g;
            if (g > max) max = g;
        }
        if (max - min < 24) {
            return;
        }
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = stretch((c >> 16) & 255, min, max);
            int g = stretch((c >> 8) & 255, min, max);
            int b = stretch(c & 255, min, max);
            px[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static int stretch(int v, int min, int max) {
        int s = (v - min) * 255 / (max - min);
        if (s < 0) return 0;
        if (s > 255) return 255;
        return s;
    }

    private static float norm(int v) {
        return (v / 255f - 0.5f) / 0.5f;
    }

    private static float[] flatten(OnnxTensor tensor) throws Exception {
        Object value = tensor.getValue();
        if (value instanceof float[]) {
            return (float[]) value;
        }
        if (value instanceof float[][]) {
            float[][] two = (float[][]) value;
            int n = 0;
            for (float[] row : two) n += row.length;
            float[] out = new float[n];
            int p = 0;
            for (float[] row : two) {
                System.arraycopy(row, 0, out, p, row.length);
                p += row.length;
            }
            return out;
        }
        if (value instanceof float[][][]) {
            float[][][] three = (float[][][]) value;
            int n = 0;
            for (float[][] a : three) for (float[] b : a) n += b.length;
            float[] out = new float[n];
            int p = 0;
            for (float[][] a : three) {
                for (float[] b : a) {
                    System.arraycopy(b, 0, out, p, b.length);
                    p += b.length;
                }
            }
            return out;
        }
        if (value instanceof float[][][][]) {
            float[][][][] four = (float[][][][]) value;
            int n = 0;
            for (float[][][] a : four) for (float[][] b : a) for (float[] c : b) n += c.length;
            float[] out = new float[n];
            int p = 0;
            for (float[][][] a : four) {
                for (float[][] b : a) {
                    for (float[] c : b) {
                        System.arraycopy(c, 0, out, p, c.length);
                        p += c.length;
                    }
                }
            }
            return out;
        }
        throw new IllegalStateException("Oväntad OCR-utdata.");
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    @Override
    public void close() {
        try {
            det.close();
        } catch (Exception ignored) {
        }
        try {
            rec.close();
        } catch (Exception ignored) {
        }
    }
}
