package fi.nyja.ventapp.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Drops duplicate tile hits and joins fragments of one label. */
public final class TokenCleanup {
    private TokenCleanup() {
    }

    public static List<OcrToken> prepare(List<OcrToken> tokens) {
        List<OcrToken> unique = new ArrayList<OcrToken>();
        for (OcrToken token : tokens) {
            String trimmed = token.text.trim();
            if (trimmed.length() < 2 && !isFragment(trimmed)) {
                continue;
            }
            boolean duplicate = false;
            for (OcrToken kept : unique) {
                if (kept.text.equalsIgnoreCase(token.text) && dist(kept, token) < 20) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(token);
            }
        }
        Collections.sort(unique, new Comparator<OcrToken>() {
            @Override
            public int compare(OcrToken a, OcrToken b) {
                int y = Double.compare(a.y, b.y);
                return y != 0 ? y : Double.compare(a.x, b.x);
            }
        });
        List<OcrToken> merged = new ArrayList<OcrToken>();
        for (OcrToken token : unique) {
            if (!merged.isEmpty()) {
                OcrToken prev = merged.get(merged.size() - 1);
                if (shouldJoin(prev, token)) {
                    merged.set(merged.size() - 1, join(prev, token));
                    continue;
                }
            }
            merged.add(token);
        }
        List<OcrToken> kept = new ArrayList<OcrToken>();
        for (OcrToken token : merged) {
            if (token.text.trim().length() >= 2) {
                kept.add(token);
            }
        }
        return kept;
    }

    private static boolean isFragment(String text) {
        return text.matches("(?i)[0-9]|[x×*]|[+-]");
    }

    private static boolean shouldJoin(OcrToken left, OcrToken right) {
        if (!sameLine(left, right)) {
            return false;
        }
        String l = left.text.trim();
        String r = right.text.trim();
        boolean leftVent = LabelParser.parse(l, left.x, left.y, left.confidence) != null;
        boolean rightVent = LabelParser.parse(r, right.x, right.y, right.confidence) != null;
        if (leftVent && rightVent) {
            return false;
        }
        ProtocolBuilder.RoomText room = ProtocolBuilder.splitRoom(l);
        if (room != null && room.name.isEmpty() && room.number.equalsIgnoreCase(l)) {
            return false;
        }
        if (r.matches("^[+\\-].*") || r.matches("(?i)^cra\\b.*") || r.matches("^\\d.*")) {
            return true;
        }
        if (l.matches("(?i)^\\d+\\s*[x×*]$") || l.matches("(?i).*\\d\\s*[x×*]$")) {
            return true;
        }
        return l.equalsIgnoreCase("cra") || r.equalsIgnoreCase("cra");
    }

    private static boolean sameLine(OcrToken left, OcrToken right) {
        double height = Math.max(4, Math.max(left.h, right.h));
        if (Math.abs(left.y - right.y) > height * 0.8) {
            return false;
        }
        double leftEdge = left.w > 0 ? left.x + left.w / 2.0 : left.x;
        double gap = right.x - leftEdge;
        return gap >= -2 && gap < Math.max(36, height * 2.5);
    }

    private static OcrToken join(OcrToken left, OcrToken right) {
        String glue = right.text.startsWith("-") ? "" : " ";
        double x0 = left.w > 0 ? left.x - left.w / 2.0 : left.x;
        double x1 = right.w > 0 ? right.x + right.w / 2.0 : right.x;
        return new OcrToken(left.text + glue + right.text, (x0 + x1) / 2.0, (left.y + right.y) / 2.0,
                Math.min(left.confidence, right.confidence), Math.max(1, x1 - x0), Math.max(left.h, right.h));
    }

    private static boolean startsWithDigit(String text) {
        return text.length() > 0 && Character.isDigit(text.charAt(0));
    }

    private static double dist(OcrToken a, OcrToken b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
