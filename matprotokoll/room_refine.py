"""Andra pass: sök saknade rumsnummer i luckor i nummerserien."""

from __future__ import annotations

import re

from matprotokoll.models import Room
from matprotokoll.rooms import _normalize_ocr_number, _plain_int


def _boxes_for_gap(n: int, lower: Room, upper: Room) -> list[tuple[float, float, float, float]]:
    x = (lower.x + upper.x) / 2.0
    y = (lower.y + upper.y) / 2.0
    # WC-raden på 301 ligger ~110 pt från allrumsraden.
    centers = [(x, y), (x, y + 115), (x, y - 115), (lower.x, y + 115), (upper.x, y + 115)]
    boxes = []
    for cx, cy in centers:
        boxes.append((cx - 55, cy - 40, cx + 55, cy + 40))
    return boxes


def refine_rooms_from_gaps(page, engine, rooms: list[Room], *, zoom: float = 5.0) -> list[Room]:
    """OCR:a små rutor där ett nummer i serien saknas. Lägger bara till vid träff."""
    numbered = [( _plain_int(r.number), r) for r in rooms if _plain_int(r.number)]
    numbered = [(n, r) for n, r in numbered if n is not None]
    if len(numbered) < 6:
        return rooms
    numbered.sort(key=lambda t: t[0])
    present = {n for n, _r in numbered}
    lo, hi = numbered[0][0], numbered[-1][0]
    missing = [n for n in range(lo, hi + 1) if n not in present]
    # hoppa över stora tomma hål (103/105/109/110/139)
    extra: list[Room] = []
    by_n = {n: r for n, r in numbered}
    for n in missing:
        lower = max((r for k, r in numbered if k < n), key=lambda r: _plain_int(r.number) or 0, default=None)
        upper = min((r for k, r in numbered if k > n), key=lambda r: _plain_int(r.number) or 99, default=None)
        if lower is None or upper is None:
            continue
        gap = (_plain_int(upper.number) or 0) - (_plain_int(lower.number) or 0)
        if gap > 5:
            continue
        for x0, y0, x1, y1 in _boxes_for_gap(n, lower, upper):
            x0 = max(0, x0)
            y0 = max(0, y0)
            try:
                img = page.render_clip((x0, y0, x1, y1), zoom)
            except Exception:
                continue
            try:
                hits = engine.recognize(img)
            except Exception:
                continue
            for text, conf, bbox in hits:
                for cand, unc in _normalize_ocr_number(text):
                    val = _plain_int(cand)
                    if val == n or cand.lower() in {f"{n}a", f"{n}b"}:
                        cx = x0 + ((bbox[0] + bbox[2]) / 2.0) / zoom
                        cy = y0 + ((bbox[1] + bbox[3]) / 2.0) / zoom
                        extra.append(
                            Room(
                                number=str(n) if val == n else cand.lower(),
                                name="",
                                x=cx,
                                y=cy,
                                size_pt=8.0,
                                uncertainty="trolig",
                                source="ocr-lucka",
                            )
                        )
        # 111a/111b: om 111 saknas, acceptera suffix
        if n == 111:
            for x0, y0, x1, y1 in _boxes_for_gap(n, lower, upper):
                try:
                    img = page.render_clip((max(0, x0), max(0, y0), x1, y1), zoom)
                    hits = engine.recognize(img)
                except Exception:
                    continue
                blob = " ".join(h[0] for h in hits)
                if re.search(r"111\s*[aA]", blob) or re.search(r"111a", blob, re.I):
                    extra.append(Room("111a", "", (x0 + x1) / 2, (y0 + y1) / 2, 8.0, "trolig", "ocr-lucka"))
                if re.search(r"111\s*[bB]", blob) or re.search(r"111b", blob, re.I):
                    extra.append(Room("111b", "", (x0 + x1) / 2, (y0 + y1) / 2, 8.0, "trolig", "ocr-lucka"))
    # dedupe extra against existing
    out = list(rooms)
    for r in extra:
        if any(o.number.lower() == r.number.lower() for o in out):
            continue
        out.append(r)
    out.sort(key=lambda r: (r.number, r.y, r.x))
    return out
