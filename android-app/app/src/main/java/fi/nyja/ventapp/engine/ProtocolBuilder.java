package fi.nyja.ventapp.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Pair vents into protocol rows and collect uncertain rows. */
public final class ProtocolBuilder {
    private static final Pattern ROOM_NUM = Pattern.compile("^[A-ZÅÄÖ][0-9]{2,4}[A-Za-z]{0,2}$");
    private static final Pattern PLAIN_ROOM = Pattern.compile("^\\d{3}[A-Za-z]?$");
    private static final Pattern ROOM_INSIDE = Pattern.compile("(?<![A-ZÅÄÖ0-9])([A-ZÅÄÖ][0-9]{2,4}[A-Za-z]{0,2})(?![A-ZÅÄÖ0-9])");
    private static final double AIR_DIST = 18.0;
    private static final double VENT_DEDUPE = 12.0;
    private static final double ROOM_DIST = 220.0;
    private static final double UNSURE_CONFIDENCE = 0.6;

    private ProtocolBuilder() {
    }

    public static List<Room> roomsFromTokens(List<OcrToken> tokens) {
        List<Room> rooms = new ArrayList<Room>();
        for (int i = 0; i < tokens.size(); i++) {
            OcrToken token = tokens.get(i);
            String text = token.text.trim();
            String roomNum = roomNumber(text);
            if (roomNum == null) {
                continue;
            }
            if (roomNum.equalsIgnoreCase(text)
                    && LabelParser.parse(text, token.x, token.y, token.confidence) != null) {
                continue;
            }
            String name = nearbyName(tokens, token);
            rooms.add(new Room(roomNum, name, token.x, token.y, token.confidence));
        }
        return rooms;
    }

    public static List<Vent> ventsFromTokens(List<OcrToken> tokens) {
        List<Vent> vents = new ArrayList<Vent>();
        List<OcrToken> leftover = new ArrayList<OcrToken>();
        for (OcrToken token : tokens) {
            Vent vent = LabelParser.parse(token.text, token.x, token.y, token.confidence);
            if (vent != null) {
                vents.add(vent);
            } else {
                leftover.add(token);
            }
        }
        List<Vent> withAir = new ArrayList<Vent>();
        boolean[] used = new boolean[leftover.size()];
        for (Vent vent : vents) {
            if (vent.airflow != null) {
                withAir.add(vent);
                continue;
            }
            int best = -1;
            double bestD = AIR_DIST;
            for (int i = 0; i < leftover.size(); i++) {
                if (used[i] || !LabelParser.isAirflowToken(leftover.get(i).text)) {
                    continue;
                }
                double d = dist(vent.x, vent.y, leftover.get(i).x, leftover.get(i).y);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                LabelParser.Airflow air = LabelParser.parseAirflowToken(leftover.get(best).text);
                if (air != null) {
                    String kind = LabelParser.classify(vent.name, air.value, air.signed);
                    if (kind == null) {
                        kind = vent.kind;
                    }
                    vent = new Vent(vent.name, kind, vent.size, Math.abs(air.value), vent.qty,
                            vent.x, vent.y, vent.raw, vent.confidence);
                }
            }
            withAir.add(vent);
        }
        return dedupe(withAir);
    }

    public static List<ProtocolRow> pair(List<Room> rooms, List<Vent> vents) {
        Map<String, List<Vent>> grouped = new LinkedHashMap<String, List<Vent>>();
        Map<String, Double> confidence = new LinkedHashMap<String, Double>();
        if (rooms == null || rooms.isEmpty()) {
            if (!vents.isEmpty()) {
                grouped.put("", new ArrayList<Vent>(vents));
                confidence.put("", 0.4);
            }
        } else {
            for (Vent vent : vents) {
                Room best = null;
                double bestD = ROOM_DIST;
                for (Room room : rooms) {
                    double d = dist(room.x, room.y, vent.x, vent.y);
                    if (d < bestD) {
                        bestD = d;
                        best = room;
                    }
                }
                String key = best == null ? "" : best.label();
                if (!grouped.containsKey(key)) {
                    grouped.put(key, new ArrayList<Vent>());
                }
                grouped.get(key).add(vent);
                if (best != null) {
                    confidence.put(key, best.confidence);
                }
            }
        }
        List<String> order = new ArrayList<String>();
        if (rooms != null) {
            for (Room room : rooms) {
                String lab = room.label();
                if (grouped.containsKey(lab) && !order.contains(lab)) {
                    order.add(lab);
                }
            }
        }
        for (String key : grouped.keySet()) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }
        List<ProtocolRow> rows = new ArrayList<ProtocolRow>();
        for (String key : order) {
            String label = key.isEmpty() ? "okänt utrymme" : key;
            double roomConf = confidence.containsKey(key) ? confidence.get(key) : 0.4;
            rows.addAll(rowsForRoom(label, grouped.get(key), roomConf, key.isEmpty()));
        }
        return rows;
    }

    private static List<ProtocolRow> rowsForRoom(String label, List<Vent> vents, double roomConf, boolean unknown) {
        List<Vent> expanded = new ArrayList<Vent>();
        for (Vent vent : vents) {
            expanded.addAll(expand(vent));
        }
        List<Vent> exhaust = new ArrayList<Vent>();
        List<Vent> supply = new ArrayList<Vent>();
        for (Vent vent : expanded) {
            if ("exhaust".equals(vent.kind)) {
                exhaust.add(vent);
            } else {
                supply.add(vent);
            }
        }
        int n = Math.max(exhaust.size(), supply.size());
        List<ProtocolRow> rows = new ArrayList<ProtocolRow>();
        for (int i = 0; i < n; i++) {
            Vent ex = i < exhaust.size() ? exhaust.get(i) : null;
            Vent su = i < supply.size() ? supply.get(i) : null;
            String reason = unsureReason(ex, su, unknown, roomConf);
            double conf = Math.min(roomConf, Math.min(conf(ex), conf(su)));
            rows.add(new ProtocolRow(
                    i == 0 ? label : "",
                    ex == null ? "" : ex.canonicalName(),
                    ex == null ? null : ex.size,
                    ex == null ? null : ex.airflow,
                    su == null ? "" : su.canonicalName(),
                    su == null ? null : su.size,
                    su == null ? null : su.airflow,
                    reason != null,
                    reason,
                    conf));
        }
        return rows;
    }

    private static String unsureReason(Vent ex, Vent su, boolean unknown, double roomConf) {
        StringBuilder reason = new StringBuilder();
        if (unknown) {
            reason.append("inget rum ");
        } else if (roomConf < UNSURE_CONFIDENCE) {
            reason.append("osäkert rum ");
        }
        noteVent(reason, ex, "frånluft");
        noteVent(reason, su, "tilluft");
        String text = reason.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static void noteVent(StringBuilder reason, Vent vent, String side) {
        if (vent == null) {
            return;
        }
        if (vent.confidence < UNSURE_CONFIDENCE) {
            reason.append("låg OCR-säkerhet ").append(side).append(' ');
        }
        if (vent.size == null || vent.size.isEmpty()) {
            reason.append("storlek saknas ").append(side).append(' ');
        }
    }

    private static double conf(Vent vent) {
        return vent == null ? 1.0 : vent.confidence;
    }

    private static List<Vent> expand(Vent vent) {
        int n = Math.max(1, vent.qty);
        List<Vent> out = new ArrayList<Vent>();
        if (n == 1) {
            out.add(vent);
            return out;
        }
        Double flow = null;
        if (vent.airflow != null) {
            double split = vent.airflow / n;
            flow = split == Math.rint(split) ? (double) (int) split : Math.round(split * 10.0) / 10.0;
        }
        for (int i = 0; i < n; i++) {
            out.add(new Vent(vent.name, vent.kind, vent.size, flow, 1, vent.x, vent.y, vent.raw, vent.confidence));
        }
        return out;
    }

    private static List<Vent> dedupe(List<Vent> vents) {
        List<Vent> kept = new ArrayList<Vent>();
        List<Vent> sorted = new ArrayList<Vent>(vents);
        sorted.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        for (Vent vent : sorted) {
            int dup = -1;
            for (int i = 0; i < kept.size(); i++) {
                Vent other = kept.get(i);
                if (!other.canonicalName().equals(vent.canonicalName())) {
                    continue;
                }
                if (!eq(other.size, vent.size) || other.qty != vent.qty) {
                    continue;
                }
                if (dist(other.x, other.y, vent.x, vent.y) <= VENT_DEDUPE) {
                    dup = i;
                    break;
                }
            }
            if (dup < 0) {
                kept.add(vent);
            } else {
                Vent cur = kept.get(dup);
                if (vent.airflow != null && cur.airflow == null) {
                    kept.set(dup, vent);
                } else if (vent.confidence > cur.confidence) {
                    kept.set(dup, vent);
                }
            }
        }
        return kept;
    }

    private static boolean eq(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        return left.equals(right);
    }

    static String roomNumber(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim().toUpperCase(Locale.ROOT);
        if (ROOM_NUM.matcher(trimmed).matches() || PLAIN_ROOM.matcher(trimmed).matches()) {
            return trimmed;
        }
        java.util.regex.Matcher inside = ROOM_INSIDE.matcher(trimmed);
        if (inside.find()) {
            return inside.group(1);
        }
        return null;
    }

    private static String nearbyName(List<OcrToken> tokens, OcrToken room) {
        String best = "";
        double bestD = 36;
        for (OcrToken token : tokens) {
            if (token == room) {
                continue;
            }
            String text = token.text.trim();
            if (text.length() < 2 || text.length() > 24) {
                continue;
            }
            if (ROOM_NUM.matcher(text).matches() || PLAIN_ROOM.matcher(text).matches()) {
                continue;
            }
            if (LabelParser.parse(text, token.x, token.y, token.confidence) != null) {
                continue;
            }
            int letters = 0;
            for (int i = 0; i < text.length(); i++) {
                if (Character.isLetter(text.charAt(i))) {
                    letters++;
                }
            }
            if (letters < 2) {
                continue;
            }
            double d = dist(room.x, room.y, token.x, token.y);
            if (token.y >= room.y - 4 && d < bestD) {
                bestD = d;
                best = text.toLowerCase(Locale.ROOT);
            }
        }
        return best;
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
