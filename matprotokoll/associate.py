"""Koppla don till rum och para frånluft+tilluft på samma Excel-rad."""

from __future__ import annotations

from collections import defaultdict

from matprotokoll.labels import (
    classify_kind,
    expand_qty,
    is_airflow_token,
    parse_airflow_token,
    parse_label,
)
from matprotokoll.models import ProtocolRow, Room, Vent


def attach_nearby_airflow(
    vents: list[Vent],
    tokens: list[tuple[str, float, float, float]],
    max_dist: float = 18.0,
) -> list[Vent]:
    """Koppla fristående '+56' / '-20' under donnamnet."""
    used: set[int] = set()
    out: list[Vent] = []
    for vent in vents:
        if vent.airflow is not None:
            out.append(vent)
            continue
        best_i = None
        best_d = max_dist
        for i, (text, x, y, _conf) in enumerate(tokens):
            if i in used or not is_airflow_token(text):
                continue
            d = ((x - vent.x) ** 2 + (y - vent.y) ** 2) ** 0.5
            # Luftmängd brukar stå strax under etiketten.
            if d < best_d:
                best_d = d
                best_i = i
        if best_i is not None:
            parsed = parse_airflow_token(tokens[best_i][0])
            used.add(best_i)
            if parsed is not None:
                flow, signed = parsed
                kind = classify_kind(vent.name, flow, signed=signed) or vent.kind
                vent = Vent(
                    name=vent.name,
                    kind=kind,
                    size=vent.size,
                    airflow=abs(flow),
                    qty=vent.qty,
                    x=vent.x,
                    y=vent.y,
                    raw=vent.raw,
                    confidence=vent.confidence,
                    source=vent.source,
                )
        out.append(vent)
    return out


def vents_from_ocr_tokens(tokens: list[tuple[str, float, float, float]]) -> list[Vent]:
    """tokens: (text, x, y, confidence) i PDF-koordinater."""
    vents: list[Vent] = []
    leftover: list[tuple[str, float, float, float]] = []
    for text, x, y, conf in tokens:
        vent = parse_label(text, x=x, y=y, confidence=conf)
        if vent:
            vents.append(vent)
        else:
            leftover.append((text, x, y, conf))
    vents = attach_nearby_airflow(vents, leftover)
    return _dedupe_vents(vents)


def _dedupe_vents(vents: list[Vent], dist: float = 12.0) -> list[Vent]:
    """Slå ihop samma don från överlappande OCR-rutor."""
    kept: list[Vent] = []
    for vent in sorted(vents, key=lambda v: (-v.confidence, v.y, v.x)):
        dup = None
        for i, other in enumerate(kept):
            if other.canonical_name() != vent.canonical_name():
                continue
            if (other.size or "") != (vent.size or ""):
                continue
            if other.qty != vent.qty:
                continue
            d = ((other.x - vent.x) ** 2 + (other.y - vent.y) ** 2) ** 0.5
            if d <= dist:
                dup = i
                break
        if dup is None:
            kept.append(vent)
        else:
            cur = kept[dup]
            if vent.airflow is not None and cur.airflow is None:
                kept[dup] = vent
            elif vent.confidence > cur.confidence:
                kept[dup] = vent
    return kept


def assign_vents_to_rooms(
    vents: list[Vent],
    rooms: list[Room],
    max_dist: float = 220.0,
) -> dict[str, list[Vent]]:
    grouped: dict[str, list[Vent]] = defaultdict(list)
    if not rooms:
        if vents:
            grouped[""] = list(vents)
        return grouped
    for vent in vents:
        best: Room | None = None
        best_d = max_dist
        for room in rooms:
            d = ((room.x - vent.x) ** 2 + (room.y - vent.y) ** 2) ** 0.5
            if d < best_d:
                best_d = d
                best = room
        key = best.label() if best else ""
        grouped[key].append(vent)
    return grouped


def pair_rows_for_room(room_label: str, vents: list[Vent]) -> list[ProtocolRow]:
    expanded: list[Vent] = []
    for vent in vents:
        expanded.extend(expand_qty(vent))
    exhaust = [v for v in expanded if v.kind == "exhaust"]
    supply = [v for v in expanded if v.kind == "supply"]
    n = max(len(exhaust), len(supply))
    if n == 0:
        return []
    rows: list[ProtocolRow] = []
    for i in range(n):
        ex = exhaust[i] if i < len(exhaust) else None
        su = supply[i] if i < len(supply) else None
        rows.append(
            ProtocolRow(
                room=room_label if i == 0 else "",
                exhaust_name=ex.canonical_name() if ex else "",
                exhaust_size=_excel_size(ex),
                exhaust_behov=_excel_flow(ex),
                supply_name=su.canonical_name() if su else "",
                supply_size=_excel_size(su),
                supply_behov=_excel_flow(su),
            )
        )
    return rows


def pair_all(rooms: list[Room], vents: list[Vent]) -> list[ProtocolRow]:
    grouped = assign_vents_to_rooms(vents, rooms)
    # Behåll rumsordning, okända sist.
    order: list[str] = []
    seen: set[str] = set()
    for room in rooms:
        lab = room.label()
        if lab in grouped and lab not in seen:
            order.append(lab)
            seen.add(lab)
    for key in grouped:
        if key not in seen:
            order.append(key)
            seen.add(key)

    rows: list[ProtocolRow] = []
    for key in order:
        label = key if key else "okänt utrymme"
        room_rows = pair_rows_for_room(label, grouped[key])
        rows.extend(room_rows)
    return rows


def _excel_size(vent: Vent | None) -> str | int | None:
    if vent is None or not vent.size:
        return None
    s = str(vent.size).strip()
    if s.isdigit():
        return int(s)
    return s


def _excel_flow(vent: Vent | None) -> float | int | None:
    if vent is None or vent.airflow is None:
        return None
    v = vent.airflow
    if float(v) == int(v):
        return int(v)
    return v
