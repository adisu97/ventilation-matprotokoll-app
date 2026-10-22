"""Rum från extraherbart PDF-text (AutoCAD-plot) och från OCR-token."""

from __future__ import annotations

import re
from collections import Counter

from matprotokoll.constants import ROOM_NAME_STOPWORDS, STANDARD_SIZES
from matprotokoll.models import Room

ROOM_NUM_RE = re.compile(r"^[A-ZÅÄÖ]\d{2,3}[A-Za-z]{0,2}$", re.I)
OCR_LETTER_ROOM_RE = re.compile(r"^[EUH]\d{2,3}[A-Za-z]{0,2}$", re.I)
# 101, 111a, 111B
OCR_PLAIN_ROOM_RE = re.compile(r"^(?P<num>\d{3})(?P<sfx>[A-Za-z])?$")
_NEAR_EQUIPMENT_RE = re.compile(
    r"KSO|KTS|EAGLE|ALG|CLIK|DYKH|PELICAN|HFK|PRA|HUK|KTI|STQA|STI|OSK|Φ|Ø|∅",
    re.I,
)
AREA_RE = re.compile(r"m\s*2|m²", re.I)
CODE_RE = re.compile(r"[-_/]E\d|\d+x\d+|EI\d+|Rw\s*\d", re.I)
FLOW_OR_HEIGHT_RE = re.compile(
    r"(?:[+\-−–=]\s*\d)|(?:\d+\s*(?:l\s*/\s*s|1/s|l/s))|(?:h\s*=)|(?:\bø)|(?:\bphi)",
    re.I,
)
PIPE_SIZE_TOKEN_RE = re.compile(r"^(?:p|ø|Φ|∅|o)?(?:0)?(80|100|125|160|200|250|315|400|500)$", re.I)
# 11.4 → 114, 10.8 → 108 (OCR-punkt mitt i rumsnummer)
DOTTED_ROOM_RE = re.compile(r"^(\d{2})\.(\d)$")
# 1U6 → 106, 1B8 → 138 efter substitution
CONFUSABLE_ROOM_RE = re.compile(r"^[IIlL1]\s*[A-Za-z0-9]\s*[A-Za-z0-9][A-Za-z]?$")

NEVER_ROOMS = frozenset({80, 200, 250, 315, 355, 400, 450, 500, 630})
SIZE_COLLISIONS = frozenset({100, 125, 160})

NAME_LABELS = {
    "terrass": "terrass",
    "matsal": "matsal",
    "kök": "kök",
    "kok": "kök",
    "kökvrå": "kökvrå",
    "kokvra": "kökvrå",
    "sovrum": "sovrum",
    "förråd": "förråd",
    "forrad": "förråd",
    "grupprum": "grupprum",
    "entre": "entré",
    "entré": "entré",
    "sand": "sand/vatten",
    "vatten": "sand/vatten",
    "sandvatten": "sand/vatten",
    "idvatten": "sand/vatten",
    "kokvral": "kökvrå",
    "ts": "ts",
    "tm": "tm",
    "kyl": "kyl",
    "ky": "ky",
    "wc": "wc",
    "allrum": "allrum",
    "hall": "hall",
    "dusch": "dusch",
    "kapprum": "kapprum",
    "kontor": "kontor",
    "teknik": "teknik",
}
SHORT_ROOM_RE = re.compile(r"^\d{1,2}$")
AREA_NEAR_RE = re.compile(r"m\s*[2²]|m²", re.I)
ID_TOKEN_RE = re.compile(r"^ID\s*\d{1,3}$", re.I)

_SHORT_SKIP = {"iv", "pl", "sp", "tt", "ak2", "ak7", "vs2", "vs4"}

_DIGIT_SUB = str.maketrans(
    {
        "O": "0",
        "Q": "0",
        "o": "0",
        "I": "1",
        "l": "1",
        "L": "1",
        "i": "1",
        "Z": "2",
        "z": "2",
        "A": "4",
        "S": "5",
        "s": "5",
        "G": "6",
        "B": "8",
        "U": "0",
        "u": "0",
    }
)


def _span_center(bbox: tuple[float, float, float, float]) -> tuple[float, float]:
    return ((bbox[0] + bbox[2]) / 2.0, (bbox[1] + bbox[3]) / 2.0)


def _iter_spans(page):
    if hasattr(page, "text_spans"):
        for span in page.text_spans():
            yield span.text, span.bbox, float(span.size)
        return
    data = page.get_text("dict")
    for block in data.get("blocks", []):
        if block.get("type") != 0:
            continue
        for line in block.get("lines", []):
            for span in line.get("spans", []):
                text = (span.get("text") or "").strip()
                if not text:
                    continue
                bbox = tuple(span["bbox"])
                yield text, bbox, float(span.get("size") or 0.0)


def _is_room_number(text: str, size: float) -> bool:
    if not ROOM_NUM_RE.match(text):
        return False
    digits = sum(ch.isdigit() for ch in text)
    if digits <= 2 and size >= 5.5:
        return False
    return True


def _is_name_candidate(text: str) -> bool:
    t = text.strip()
    if len(t) < 2 or len(t) > 40:
        return False
    if ROOM_NUM_RE.match(t) or OCR_PLAIN_ROOM_RE.match(t):
        return False
    if AREA_RE.search(t):
        return False
    if CODE_RE.search(t):
        return False
    letters = sum(ch.isalpha() for ch in t)
    if letters < 2:
        return False
    low = t.lower()
    if any(sw in low for sw in ROOM_NAME_STOPWORDS):
        return False
    if low in _SHORT_SKIP:
        return False
    if re.search(r"kso|ksq|kti|stq|gvfs|vilp|l/s|1/s|ø|phi|pp\d", low):
        return False
    if not any(ch in "aeiouyåäö" for ch in low) and len(low) <= 5:
        return False
    return True


_COMBINED_ROOM_RE = re.compile(
    r"^((?:[EUH]\d{2,3}[A-Za-z]{0,2})|\d{3}[A-Za-z]?)\s+(.+)$",
    re.I,
)


def _in_title_block(x: float, y: float, page_width: float, page_height: float) -> bool:
    if page_width <= 0 or page_height <= 0:
        return False
    if x >= page_width * 0.82 and y >= page_height * 0.75:
        return True
    if y >= page_height * 0.84:
        return True
    return False


def _near_equipment(x: float, y: float, tokens: list[tuple[str, float, float, float]], max_dist: float = 28.0) -> bool:
    for text, tx, ty, _conf in tokens:
        if not _NEAR_EQUIPMENT_RE.search(text or ""):
            continue
        if ((tx - x) ** 2 + (ty - y) ** 2) ** 0.5 <= max_dist:
            return True
    return False


def _plain_int(num: str) -> int | None:
    digits = "".join(ch for ch in num if ch.isdigit())
    if not digits or len(digits) > 3:
        return None
    try:
        return int(digits)
    except ValueError:
        return None


def _normalize_ocr_number(raw: str) -> list[tuple[str, str]]:
    """Möjliga kanoniska rumsnummer från en OCR-sträng. (nummer, osäkerhet)."""
    t = (raw or "").strip()
    if not t or len(t) > 12:
        return []
    t = t.replace("−", "-").replace("–", "-")
    t = t.strip(".,;:()[]")
    if FLOW_OR_HEIGHT_RE.search(t) and not OCR_PLAIN_ROOM_RE.match(t):
        return []
    if PIPE_SIZE_TOKEN_RE.match(t) and t.upper().startswith(("P", "Ø", "Φ", "O")):
        return []
    out: list[tuple[str, str]] = []

    m_let = OCR_LETTER_ROOM_RE.match(t)
    if m_let:
        digits = sum(ch.isdigit() for ch in t)
        if digits >= 3:
            out.append((t.upper(), "säker"))
        return out

    m_plain = OCR_PLAIN_ROOM_RE.match(t)
    if m_plain:
        num = m_plain.group("num")
        sfx = (m_plain.group("sfx") or "").lower()
        value = int(num)
        if value in NEVER_ROOMS:
            return []
        if sfx:
            out.append((f"{num}{sfx}", "säker"))
        else:
            out.append((num, "säker"))
        return out

    m_dot = DOTTED_ROOM_RE.match(t)
    if m_dot:
        cand = m_dot.group(1) + m_dot.group(2)
        if 101 <= int(cand) <= 199:
            out.append((cand, "trolig"))
        return out

    compact = re.sub(r"\s+", "", t)
    if CONFUSABLE_ROOM_RE.match(compact) and not compact.isdigit():
        translated = compact.translate(_DIGIT_SUB)
        m2 = OCR_PLAIN_ROOM_RE.match(translated)
        if m2:
            num = m2.group("num")
            sfx = (m2.group("sfx") or "").lower()
            value = int(num)
            if value not in NEVER_ROOMS and 101 <= value <= 199:
                key = f"{num}{sfx}" if sfx else num
                out.append((key, "osäker"))
    return out


def _is_ocr_room_number(text: str) -> bool:
    """Bakåtkompatibel predikat för tester: strikt nummer utan OCR-gissning."""
    raw = (text or "").strip()
    if OCR_LETTER_ROOM_RE.match(raw):
        digits = sum(ch.isdigit() for ch in raw)
        return digits >= 3
    m = OCR_PLAIN_ROOM_RE.match(raw)
    if not m:
        return False
    value = int(m.group("num"))
    if value in NEVER_ROOMS:
        return False
    if m.group("sfx"):
        return 101 <= value <= 199
    if value in STANDARD_SIZES and value not in SIZE_COLLISIONS:
        return False
    return 101 <= value <= 199


def _merge_suffixes(items: list[tuple[str, float, float, float, str]]) -> list[tuple[str, float, float, float, str]]:
    """'111' + närliggande 'a'/'b' → 111a."""
    extras = []
    skip = set()
    consumed_bases = set()
    for i, (num, x, y, conf, unc) in enumerate(items):
        if not re.fullmatch(r"\d{3}", num):
            continue
        for j, (other, ox, oy, oconf, _o) in enumerate(items):
            if i == j or j in skip:
                continue
            if other.lower() not in {"a", "b", "c"}:
                continue
            if ((ox - x) ** 2 + (oy - y) ** 2) ** 0.5 <= 18:
                extras.append((num + other.lower(), (x + ox) / 2, (y + oy) / 2, max(conf, oconf), "trolig"))
                skip.add(j)
                consumed_bases.add(i)
    cleaned = [it for i, it in enumerate(items) if i not in skip and i not in consumed_bases]
    return cleaned + extras


def _in_series(num: str, values: list[int]) -> bool:
    n = _plain_int(num)
    if n is None:
        return True
    others = [v for v in values if v != n]
    if len(others) < 4:
        return True
    hundred = [v for v in others if v // 100 == n // 100]
    if len(hundred) < 3:
        return n // 100 == others[0] // 100 or abs(n - min(others, key=lambda v: abs(v - n))) <= 12
    lo, hi = min(hundred), max(hundred)
    nearest = min(abs(n - v) for v in hundred)
    if n < lo - 3 or n > hi + 3:
        return False
    return nearest <= 10


def _name_from_text(text: str) -> str | None:
    raw = (text or "").strip().lower().rstrip(".:")
    if not raw or len(raw) > 24:
        return None
    # Brandtext/legender: "… 6 kg i hall" är inte rumsnamn.
    if len(raw.split()) >= 3:
        return None
    if raw in NAME_LABELS:
        return NAME_LABELS[raw]
    compact = re.sub(r"[^a-zåäö]", "", raw)
    if compact in NAME_LABELS:
        return NAME_LABELS[compact]
    if compact.endswith("vatten") and 6 <= len(compact) <= 14:
        return "sand/vatten"
    for key, val in NAME_LABELS.items():
        if len(key) >= 4 and (raw == key or raw.startswith(key + " ") or raw.endswith(" " + key)):
            return val
    return None


def extract_name_labels(
    tokens: list[tuple[str, float, float, float]],
) -> list[tuple[str, float, float]]:
    """Fristående rumsetiketter (Terrass, Sovrum, TS, TM, Ky) oavsett nummer."""
    found: list[tuple[str, float, float]] = []
    for text, x, y, _conf in tokens:
        namn = _name_from_text(text)
        if namn:
            found.append((namn, x, y))
    # dedupe
    kept: list[tuple[str, float, float]] = []
    for name, x, y in found:
        if any(name == k[0] and ((k[1] - x) ** 2 + (k[2] - y) ** 2) ** 0.5 < 40 for k in kept):
            continue
        kept.append((name, x, y))
    return kept


def extract_rooms_from_tokens(
    tokens: list[tuple[str, float, float, float]],
    max_name_dist: float = 36.0,
    *,
    page_width: float = 0.0,
    page_height: float = 0.0,
) -> list[Room]:
    """Rum från OCR när ritningen saknar markerbar PDF-text (outlined CAD).

    Tre-siffriga nummer (142, 118, 111a) är rum. P125 och 125 intill KSO är
    kanal/don-storlek. Ritningsnummer i stämpeln hoppas över. Nummerserien
    (samma hundratal) används som rimlighetskontroll mot skräp som 186.
    """
    filtered: list[tuple[str, float, float, float]] = []
    for text, x, y, conf in tokens:
        raw = (text or "").strip()
        if not raw:
            continue
        combined = _COMBINED_ROOM_RE.match(raw)
        if combined:
            num, namn = combined.group(1), combined.group(2)
            filtered.append((num, x, y, conf))
            filtered.append((namn, x + 16, y, conf))
            continue
        short_named = re.match(r"^(\d{1,2})\s+([A-Za-zÅÄÖåäö/]{3,})$", raw)
        if short_named:
            filtered.append((short_named.group(1), x, y, conf))
            filtered.append((short_named.group(2), x + 12, y, conf))
            continue
        stuck = re.match(r"^(\d{1,2})([A-Za-zÅÄÖåäö]{3,})$", raw)
        if stuck and _name_from_text(stuck.group(2)):
            filtered.append((stuck.group(1), x, y, conf))
            filtered.append((stuck.group(2), x + 12, y, conf))
            continue
        filtered.append((raw, x, y, conf))

    candidates: list[tuple[str, float, float, float, str]] = []
    names: list[tuple[str, tuple[float, float]]] = []
    lonely_letters: list[tuple[str, float, float, float, str]] = []
    for text, x, y, conf in filtered:
        if _in_title_block(x, y, page_width, page_height):
            continue
        if text.lower() in {"a", "b", "c"} and len(text) == 1:
            lonely_letters.append((text.lower(), x, y, conf, "trolig"))
        parsed = _normalize_ocr_number(text)
        if ID_TOKEN_RE.match(text.replace(" ", "")):
            continue
        m_trail = re.fullmatch(r"(\d{1,2})r", text, re.I)
        if m_trail and not parsed:
            parsed = [(m_trail.group(1), "trolig")]
        if SHORT_ROOM_RE.match(text) or (m_trail and parsed):
            value = int((parsed[0][0] if m_trail and parsed else text))
            if 1 <= value <= 80:
                # Korta nummer (1–16 på skanningar) kräver namn eller m² intill, annars är det mått/ID.
                near_ok = False
                for t2, x2, y2, _c2 in filtered:
                    if ((x2 - x) ** 2 + (y2 - y) ** 2) ** 0.5 > 90:
                        continue
                    if _name_from_text(t2) or AREA_NEAR_RE.search(t2 or ""):
                        near_ok = True
                        break
                if near_ok:
                    parsed = parsed or [(text, "säker")]
        if parsed:
            if _near_equipment(x, y, filtered):
                # Storlekskrock 125/100/160 intill don — hoppa. Bokstavsrum (E125) och
                # 111a behålls om de inte är rena storlekar.
                keep = False
                for num, unc in parsed:
                    if re.search(r"[a-zA-Z]$", num) or num[:1] in "EUH":
                        keep = True
                    elif _plain_int(num) not in SIZE_COLLISIONS:
                        keep = True
                if not keep:
                    continue
            for num, unc in parsed:
                value = _plain_int(num)
                if value is not None and value in NEVER_ROOMS:
                    continue
                if num.isdigit() and num.startswith("0"):
                    continue
                candidates.append((num, x, y, conf, unc))
        elif _is_name_candidate(text):
            if any(ch.isdigit() for ch in text) and not any(k in text.lower() for k in NAME_LABELS):
                continue
            if _NEAR_EQUIPMENT_RE.search(text):
                continue
            if text.lower() in {"ks", "av", "it", "kso", "kts"}:
                continue
            names.append((text, (x, y)))

    candidates.extend(lonely_letters)
    candidates = _merge_suffixes(candidates)

    clean_vals = [
        _plain_int(n)
        for n, _x, _y, _c, unc in candidates
        if unc == "säker" and _plain_int(n) not in SIZE_COLLISIONS and not re.match(r"^[EUH]", n, re.I)
    ]
    clean_vals = [v for v in clean_vals if v is not None]
    series_vals = clean_vals or [
        v for v in (_plain_int(n) for n, *_r in candidates) if v is not None and v not in SIZE_COLLISIONS
    ]
    hundreds = Counter(v // 100 for v in series_vals)
    dominant_h = hundreds.most_common(1)[0][0] if hundreds else None
    letter_n = sum(1 for n, *_r in candidates if re.match(r"^[EUH]", n, re.I))
    plain_n = sum(1 for n, *_r in candidates if _plain_int(n) and not re.match(r"^[EUH]", n, re.I))
    letter_plan = letter_n >= 6 and letter_n > plain_n

    name_labels = extract_name_labels(filtered)
    rooms: list[Room] = []
    for num, x, y, _conf, unc in sorted(candidates, key=lambda z: -z[3]):
        if num.lower() in {"a", "b", "c"}:
            continue
        value = _plain_int(num)
        is_letter = bool(re.match(r"^[EUH]", num, re.I))
        if value == 100 and not is_letter and not re.search(r"[a-zA-Z]$", num):
            continue
        if is_letter and not letter_plan and value in {115, 130, 160}:
            continue
        if not is_letter and letter_plan and value in SIZE_COLLISIONS:
            continue
        if dominant_h is not None and value is not None and value // 100 != dominant_h:
            if not is_letter:
                continue
        if value is not None and value in SIZE_COLLISIONS and not is_letter:
            neighbors = [
                (_plain_int(n), ((xx - x) ** 2 + (yy - y) ** 2) ** 0.5)
                for n, xx, yy, _c, _u in candidates
                if n != num and _plain_int(n) not in (None, *SIZE_COLLISIONS)
            ]
            if not any(vn is not None and abs(vn - value) <= 4 and d < 180 for vn, d in neighbors):
                continue
            unc = "trolig" if unc == "säker" else unc
        elif value is not None and not is_letter and not _in_series(num, [v for v in series_vals if v is not None]):
            continue
        if value is not None and value <= 80 and not is_letter and len(num) <= 2:
            if _looks_like_area_figure(num, x, y, filtered):
                continue
            near_name = None
            best_nd = 90.0
            for namn, nx, ny in name_labels:
                d = ((nx - x) ** 2 + (ny - y) ** 2) ** 0.5
                if d < best_nd:
                    best_nd = d
                    near_name = namn
            near_area = any(
                AREA_NEAR_RE.search(t or "") and ((tx - x) ** 2 + (ty - y) ** 2) ** 0.5 < 90
                for t, tx, ty, _c in filtered
            )
            if not near_name and not near_area:
                continue
            if near_name:
                unc = "säker"
            else:
                unc = "trolig"
        if any(r.number.lower() == num.lower() and ((r.x - x) ** 2 + (r.y - y) ** 2) ** 0.5 < 50 for r in rooms):
            continue
        best = None
        best_d = 90.0 if value is not None and value <= 80 and not is_letter and len(num) <= 2 else max_name_dist
        for namn, nx, ny in name_labels:
            d = ((nx - x) ** 2 + (ny - y) ** 2) ** 0.5
            if d < best_d:
                best_d = d
                best = namn
        if best is None and not (value is not None and value <= 80 and len(num) <= 2):
            for namn, (nx, ny) in names:
                d = ((nx - x) ** 2 + (ny - y) ** 2) ** 0.5
                if d < best_d:
                    best_d = d
                    best = namn
        name = (best or "").strip().lower()
        rooms.append(
            Room(
                number=num if num[:1] in "EUHeuh" else num.lower() if re.search(r"[a-zA-Z]$", num) else num,
                name=name,
                x=x,
                y=y,
                size_pt=8.0,
                uncertainty=unc,
                source="ocr",
            )
        )
    rooms = _drop_split_short_numbers(rooms)
    rooms = _keep_best_duplicate(rooms)
    rooms.sort(key=lambda r: (r.number, r.y, r.x))
    return rooms


def _looks_like_area_figure(
    num: str,
    x: float,
    y: float,
    tokens: list[tuple[str, float, float, float]],
) -> bool:
    """32 intill 32,9 m² eller 8 intill 8,8 m² är yta, inte rumsnummer."""
    for text, tx, ty, _c in tokens:
        raw = (text or "").strip().lower().replace(" ", "")
        if ((tx - x) ** 2 + (ty - y) ** 2) ** 0.5 > 42:
            continue
        if re.match(rf"^{re.escape(num)}[.,]\d", raw):
            return True
        if AREA_NEAR_RE.search(text or "") and re.match(rf"^{re.escape(num)}[.,]?\d*m", raw):
            return True
    return False


def _drop_split_short_numbers(rooms: list[Room]) -> list[Room]:
    """Samma rum läses ofta som 16+6, 13+03 eller 2+20. Behåll en träff."""
    drop: set[int] = set()
    for i, a in enumerate(rooms):
        if i in drop or not a.number.isdigit() or len(a.number) > 2:
            continue
        for j, b in enumerate(rooms):
            if j <= i or j in drop or not b.number.isdigit() or len(b.number) > 2:
                continue
            d = ((b.x - a.x) ** 2 + (b.y - a.y) ** 2) ** 0.5
            same_name = bool(a.name) and a.name == b.name
            if d >= (95 if same_name else 45):
                continue
            worse = _worse_nearby_short(a, b)
            drop.add(i if worse is a else j)
    return [r for i, r in enumerate(rooms) if i not in drop]


def _worse_nearby_short(a: Room, b: Room) -> Room:
    if a.number.startswith("0") != b.number.startswith("0"):
        return a if a.number.startswith("0") else b
    one = two = None
    if len(a.number) == 1 and len(b.number) == 2:
        one, two = a, b
    elif len(b.number) == 1 and len(a.number) == 2:
        one, two = b, a
    if one is not None and two is not None:
        if two.number.startswith(one.number) and two.number[1] == "0":
            return two
        return one
    if bool(a.name) != bool(b.name):
        return a if not a.name else b
    return a if len(a.number) < len(b.number) else b


def _keep_best_duplicate(rooms: list[Room]) -> list[Room]:
    """Ett nummer per plan om dubbletterna inte ligger långt isär med egna grannar."""
    from collections import defaultdict

    groups: dict[str, list[Room]] = defaultdict(list)
    for r in rooms:
        groups[r.number.lower()].append(r)
    kept: list[Room] = []
    for _key, items in groups.items():
        if len(items) == 1:
            kept.extend(items)
            continue
        def score(room: Room) -> float:
            n = _plain_int(room.number) or 0
            s = 0.0
            for o in rooms:
                if o is room:
                    continue
                on = _plain_int(o.number)
                if on is None or on in SIZE_COLLISIONS:
                    continue
                d = ((o.x - room.x) ** 2 + (o.y - room.y) ** 2) ** 0.5
                if d < 280 and abs(on - n) <= 8:
                    s += 1.0
            return s
        ranked = sorted(items, key=score, reverse=True)
        best = ranked[0]
        kept.append(best)
        for extra in ranked[1:]:
            d = ((extra.x - best.x) ** 2 + (extra.y - best.y) ** 2) ** 0.5
            if extra.name and best.name and extra.name != best.name and d > 150:
                kept.append(extra)
            elif d > 420 and score(extra) >= 2:
                kept.append(extra)
    return kept


def merge_rooms(primary: list[Room], extra: list[Room]) -> list[Room]:
    """Slå ihop PDF-text-rum med OCR-rum. OCR fyller luckor."""
    out = list(primary)
    for room in extra:
        if any(
            r.number.lower() == room.number.lower() and ((r.x - room.x) ** 2 + (r.y - room.y) ** 2) ** 0.5 < 50
            for r in out
        ):
            continue
        if any(r.number.lower() == room.number.lower() for r in out) and not re.search(r"[a-z]$", room.number, re.I):
            # Samma nummer långt bort kan vara giltig dubblett (två 118). Behåll om avstånd stort.
            close = [r for r in out if r.number.lower() == room.number.lower()]
            if close and min(((r.x - room.x) ** 2 + (r.y - room.y) ** 2) ** 0.5 for r in close) < 80:
                continue
        out.append(room)
    out.sort(key=lambda r: (r.number, r.y, r.x))
    return out


def extract_rooms(page, max_name_dist: float = 28.0) -> list[Room]:
    """Hämta rumsnummer + närmaste namn. Duplicat på samma nummer slås ihop."""
    numbers: list[tuple[str, tuple[float, float], float]] = []
    names: list[tuple[str, tuple[float, float]]] = []
    for text, bbox, size in _iter_spans(page):
        cx, cy = _span_center(bbox)
        if _is_room_number(text, size):
            numbers.append((text.upper(), (cx, cy), size))
        elif _is_name_candidate(text):
            names.append((text, (cx, cy)))

    rooms: list[Room] = []
    for num, (x, y), size in numbers:
        near_dup = any(
            r.number == num and ((r.x - x) ** 2 + (r.y - y) ** 2) ** 0.5 < 40 for r in rooms
        )
        if near_dup:
            continue
        best = None
        best_d = max_name_dist
        for namn, (nx, ny) in names:
            d = ((nx - x) ** 2 + (ny - y) ** 2) ** 0.5
            if d < best_d:
                best_d = d
                best = namn
        name = (best or "").strip().lower()
        rooms.append(Room(number=num, name=name, x=x, y=y, size_pt=size, source="pdf-text"))
    rooms.sort(key=lambda r: (r.number, r.y, r.x))
    return rooms
