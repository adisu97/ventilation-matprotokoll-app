package fi.nyja.ventapp.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Pair vents into protocol rows and collect uncertain rows. */
public final class ProtocolBuilder {
    private static final Pattern ROOM_NUM = Pattern.compile("^[A-ZÅÄÖ][0-9]{2,4}[A-Za-z]?$");
    private static final Pattern PLAIN_ROOM = Pattern.compile("^\\d{3}[A-Za-z]?$");
    private static final Pattern ROOM_INSIDE = Pattern.compile("(?<![A-ZÅÄÖ0-9])([A-ZÅÄÖ][0-9]{2,4})(?![A-ZÅÄÖ0-9])");
    private static final Pattern SPLIT_LETTER_SPACE = Pattern.compile("^([A-ZÅÄÖa-zåäö]\\d{2,4})\\s+([A-ZÅÄÖa-zåäö]{2,24})$");
    private static final Pattern SPLIT_DIGIT_SPACE = Pattern.compile("^(\\d{2,4})\\s+([A-ZÅÄÖa-zåäö]{2,24})$");
    private static final Pattern SPLIT_LETTER_GLUE = Pattern.compile("^([A-ZÅÄÖa-zåäö]\\d{2,4})([A-ZÅÄÖa-zåäö]{2,16})$");
    private static final Pattern SPLIT_DIGIT_GLUE = Pattern.compile("^(\\d{3})([A-ZÅÄÖa-zåäö]{2,16})$");
    private static final double AIR_DIST = 36.0;
    private static final double VENT_DEDUPE = 22.0;
    private static final double ROOM_DIST = 420.0;
    private static final double ROOM_SURE = 220.0;
    private static final double UNSURE_CONFIDENCE = 0.6;

    private ProtocolBuilder() {
    }

    public static List<Room> roomsFromTokens(List<OcrToken> tokens) {
        List<Room> rooms = new ArrayList<Room>();
        for (int i = 0; i < tokens.size(); i++) {
            OcrToken token = tokens.get(i);
            String text = LatinScript.clean(token.text.trim());
            RoomText parts = splitRoom(text);
            if (parts == null) {
                continue;
            }
            if (parts.name.isEmpty() && parts.number.equalsIgnoreCase(text)
                    && LabelParser.parse(text, token.x, token.y, token.confidence) != null) {
                continue;
            }
            if (LabelParser.looksLikeDuctSize(parts.number) && !LatinScript.isKnownRoomName(parts.name)
                    && !hasRoomNameNearby(tokens, token)) {
                continue;
            }
            String name = parts.name.isEmpty() ? nearbyName(tokens, token) : parts.name;
            name = LatinScript.correctRoomName(name);
            rooms.add(new Room(parts.number, name, token.x, token.y, token.confidence));
        }
        return mergeCloseRooms(rooms);
    }

    public static List<Vent> ventsFromTokens(List<OcrToken> tokens) {
        List<OcrToken> labels = new ArrayList<OcrToken>();
        List<List<Vent>> parsed = new ArrayList<List<Vent>>();
        List<OcrToken> leftover = new ArrayList<OcrToken>();
        for (OcrToken token : tokens) {
            List<Vent> found = LabelParser.parseAll(token.text, token.x, token.y, token.confidence);
            if (found.isEmpty()) {
                leftover.add(token);
            } else {
                labels.add(token);
                parsed.add(found);
            }
        }
        boolean[] used = new boolean[leftover.size()];
        List<Vent> vents = new ArrayList<Vent>();
        for (int n = 0; n < labels.size(); n++) {
            OcrToken token = labels.get(n);
            int best = -1;
            double bestD = AIR_DIST;
            for (int i = 0; i < leftover.size(); i++) {
                if (used[i] || !LabelParser.isAirflowToken(leftover.get(i).text)) {
                    continue;
                }
                double d = distToLabel(token, leftover.get(i));
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            LabelParser.Airflow air = null;
            if (best >= 0) {
                used[best] = true;
                air = LabelParser.parseAirflowToken(leftover.get(best).text);
            }
            for (Vent vent : parsed.get(n)) {
                if (vent.airflow == null && air != null) {
                    String kind = LabelParser.classify(vent.name, air.value, air.signed);
                    if (kind == null) {
                        kind = vent.kind;
                    }
                    vent = new Vent(vent.name, kind, vent.size, Math.abs(air.value), vent.qty,
                            vent.x, vent.y, vent.raw, vent.confidence);
                }
                vents.add(vent);
            }
        }
        return dedupe(vents);
    }

    private static double distToLabel(OcrToken label, OcrToken other) {
        double left = label.w > 0 ? label.x - label.w / 2.0 : label.x;
        double right = label.w > 0 ? label.x + label.w / 2.0 : label.x;
        double dx = 0;
        if (other.x < left) {
            dx = left - other.x;
        } else if (other.x > right) {
            dx = other.x - right;
        }
        double dy = Math.abs(label.y - other.y);
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static List<ProtocolRow> pair(List<Room> rooms, List<Vent> vents) {
        Map<String, List<Vent>> grouped = new LinkedHashMap<String, List<Vent>>();
        Map<String, Double> confidence = new LinkedHashMap<String, Double>();
        Map<String, String> distanceNote = new LinkedHashMap<String, String>();
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
                    double conf = bestD > ROOM_SURE ? Math.min(best.confidence, 0.5) : best.confidence;
                    Double previous = confidence.get(key);
                    confidence.put(key, previous == null ? conf : Math.min(previous, conf));
                    if (bestD > ROOM_SURE) {
                        distanceNote.put(key, "don långt från rumsnumret");
                    }
                }
            }
            for (Room room : rooms) {
                String key = room.label();
                if (!grouped.containsKey(key)) {
                    grouped.put(key, new ArrayList<Vent>());
                    confidence.put(key, Math.min(room.confidence, 0.5));
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
            rows.addAll(rowsForRoom(label, grouped.get(key), roomConf, key.isEmpty(), distanceNote.get(key)));
        }
        return rows;
    }

    private static List<ProtocolRow> rowsForRoom(String label, List<Vent> vents, double roomConf, boolean unknown,
            String distanceNote) {
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
        if (n == 0) {
            rows.add(new ProtocolRow(label, "", null, null, "", null, null, true, "inget don i rummet", roomConf));
            return rows;
        }
        for (int i = 0; i < n; i++) {
            Vent ex = i < exhaust.size() ? exhaust.get(i) : null;
            Vent su = i < supply.size() ? supply.get(i) : null;
            String reason = unsureReason(ex, su, unknown, roomConf, distanceNote);
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

    private static String unsureReason(Vent ex, Vent su, boolean unknown, double roomConf, String distanceNote) {
        StringBuilder reason = new StringBuilder();
        if (unknown) {
            reason.append("inget rum ");
        } else if (distanceNote != null && !distanceNote.isEmpty()) {
            reason.append(distanceNote).append(' ');
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
                if (!other.canonicalName().equals(vent.canonicalName()) || other.qty != vent.qty) {
                    continue;
                }
                boolean sameSize = eq(other.size, vent.size);
                boolean oneMissing = other.size == null || other.size.isEmpty() || vent.size == null || vent.size.isEmpty();
                if (!sameSize && !oneMissing) {
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
                kept.set(dup, richer(kept.get(dup), vent));
            }
        }
        return kept;
    }

    private static Vent richer(Vent current, Vent extra) {
        int currentScore = (current.airflow != null ? 2 : 0) + (current.size != null && !current.size.isEmpty() ? 1 : 0);
        int extraScore = (extra.airflow != null ? 2 : 0) + (extra.size != null && !extra.size.isEmpty() ? 1 : 0);
        if (extraScore > currentScore) {
            return extra;
        }
        if (extraScore == currentScore && extra.confidence > current.confidence) {
            return extra;
        }
        return current;
    }

    private static boolean eq(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        return left.equals(right);
    }

    public static final class RoomText {
        public final String number;
        public final String name;

        public RoomText(String number, String name) {
            this.number = number == null ? "" : number;
            this.name = name == null ? "" : name;
        }
    }

    /** Number and name from one string, for example E125 WC, 125WC or 102AULA. */
    public static RoomText splitRoom(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.isEmpty() || raw.length() > 40) {
            return null;
        }
        java.util.regex.Matcher spaced = SPLIT_LETTER_SPACE.matcher(raw);
        if (!spaced.matches()) {
            spaced = SPLIT_DIGIT_SPACE.matcher(raw);
        }
        if (spaced.matches()) {
            String name = roomName(spaced.group(2));
            if (name == null) {
                return new RoomText(spaced.group(1).toUpperCase(Locale.ROOT), "");
            }
            return new RoomText(spaced.group(1).toUpperCase(Locale.ROOT), name);
        }
        java.util.regex.Matcher glued = SPLIT_LETTER_GLUE.matcher(raw);
        if (!glued.matches()) {
            glued = SPLIT_DIGIT_GLUE.matcher(raw);
        }
        if (glued.matches()) {
            String name = roomName(glued.group(2));
            if (name != null) {
                return new RoomText(glued.group(1).toUpperCase(Locale.ROOT), name);
            }
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (ROOM_NUM.matcher(upper).matches() || PLAIN_ROOM.matcher(upper).matches()) {
            return new RoomText(upper, "");
        }
        java.util.regex.Matcher inside = ROOM_INSIDE.matcher(upper);
        if (inside.find()) {
            return new RoomText(inside.group(1), "");
        }
        return null;
    }

    static String roomNumber(String text) {
        RoomText parts = splitRoom(text);
        if (parts == null || parts.number.isEmpty()) {
            return null;
        }
        return parts.number;
    }

    private static String roomName(String text) {
        if (text == null) {
            return null;
        }
        String name = text.trim();
        if (name.length() < 2 || name.length() > 24) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isLetter(name.charAt(i))) {
                return null;
            }
        }
        if (LabelParser.parse(name, 0, 0, 1) != null) {
            return null;
        }
        return name;
    }

    private static List<Room> mergeCloseRooms(List<Room> rooms) {
        List<Room> kept = new ArrayList<Room>();
        for (Room room : rooms) {
            int hit = -1;
            for (int i = 0; i < kept.size(); i++) {
                Room other = kept.get(i);
                if (other.number.equalsIgnoreCase(room.number) && dist(other.x, other.y, room.x, room.y) < 30) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                kept.add(room);
            } else if (kept.get(hit).name.isEmpty() && !room.name.isEmpty()) {
                kept.set(hit, room);
            }
        }
        return kept;
    }

    private static String nearbyName(List<OcrToken> tokens, OcrToken room) {
        String best = "";
        double bestD = 42;
        for (OcrToken token : tokens) {
            if (token == room) {
                continue;
            }
            String text = token.text.trim();
            String name = roomName(LatinScript.correctRoomName(text));
            if (name == null) {
                continue;
            }
            double d = dist(room.x, room.y, token.x, token.y);
            if (d < bestD) {
                bestD = d;
                best = name;
            }
        }
        return best;
    }

    private static boolean hasRoomNameNearby(List<OcrToken> tokens, OcrToken room) {
        for (OcrToken token : tokens) {
            if (token == room) {
                continue;
            }
            if (!LatinScript.isKnownRoomName(token.text)) {
                continue;
            }
            if (dist(room.x, room.y, token.x, token.y) < 42) {
                return true;
            }
        }
        return false;
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
