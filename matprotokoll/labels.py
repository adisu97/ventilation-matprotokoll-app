"""Tolka don-etiketter från OCR eller PDF-text. Hitta inte på don utan evidens."""

from __future__ import annotations

import re
from dataclasses import replace

from matprotokoll.constants import (
    ALL_PRODUCTS,
    AMBIGUOUS,
    FRANLUFT_ONLY,
    OCR_PRODUCT_FIXES,
    STANDARD_SIZES,
    TILLUFT_ONLY,
)
from matprotokoll.models import Vent

_AIRFLOW_RE = re.compile(
    r"(?<![A-Z0-9])(?P<sign>[+-])\s*(?P<val>\d{1,4})(?:\s*(?:l\s*/\s*s|l/s|ls))?\b",
    re.I,
)
_SIZE_RANGE_RE = re.compile(r"(?<!\d)(\d{2,4})\s*[-/]\s*(\d{2,4})(?!\d)")
_MULT_SIZE_RE = re.compile(r"(?<!\d)(\d{1,2})\s*[xX×*]\s*(\d{2,4})(?!\d)")
_STD_SIZE_RE = re.compile(
    r"(?<!\d)(" + "|".join(str(s) for s in sorted(STANDARD_SIZES, reverse=True)) + r")(?!\d)"
)
_NOISE_RE = re.compile(
    r"\b(SP1|YL[OÖ]S|CIRCO|DOUBLE|PP\d+|AV\d+|IVL|KANAV|TUULET)\b",
    re.I,
)

# Produktnamn längst först så att KTS-360 och PELICAN vinner över HU.
# HU matchas bara tillsammans med storlek (annars för många OCR-träffar).
_PRODUCT_RE = re.compile(
    r"(?:(?P<qty>\d{1,2})\s*[xX×*]\s*)?(?P<name>"
    r"PELICAN|EAGLE|DYKH|CLIK|KTS-360|KTS|KSO|HFK|ALG|PRA|HUK|"
    r"HU(?=[-/\s]\d)"
    r")\b",
    re.I,
)


def normalize_ocr_text(text: str) -> str:
    """Rätta vanliga OCR-fel utan att skapa nya produktnamn."""
    if not text:
        return ""
    s = (
        text.replace("–", "-")
        .replace("—", "-")
        .replace("−", "-")
        .replace("=", "-")
        .replace("×", "x")
        .replace("’", "")
        .replace("`", "")
        .replace("´", "")
    )
    s = re.sub(r"\s+", " ", s).strip()
    upper = s.upper()
    # Noll/O i KSO/KTS
    upper = re.sub(r"KS[O0]", "KSO", upper)
    upper = re.sub(r"KT[S5]", "KTS", upper)
    for bad, good in OCR_PRODUCT_FIXES:
        if bad != good:
            upper = upper.replace(bad, good)
    return upper


def classify_kind(
    name: str,
    airflow: float | None = None,
    *,
    signed: bool = False,
) -> str | None:
    """Bestäm frånluft/tilluft.

    Produkter som bara finns i en lista behåller den sidan även om OCR tappar minustecken.
    För tvetydiga don (dykh/clik/hfk/pelican) vinner ett *signerat* luftflöde.
    """
    n = (name or "").strip().lower()
    if n == "kts-360":
        n = "kts"
    if n in FRANLUFT_ONLY:
        return "exhaust"
    if n in TILLUFT_ONLY:
        return "supply"
    if signed and airflow is not None:
        if airflow < 0:
            return "exhaust"
        if airflow > 0:
            return "supply"
    if n in AMBIGUOUS:
        # I ifyllda exempel: dykh/clik/pelican som tilluft om inget annat anges.
        if n == "hfk":
            return "exhaust"
        return "supply"
    if n in ALL_PRODUCTS:
        return "supply" if n in TILLUFT_ONLY else "exhaust"
    return None


def parse_airflow_token(text: str) -> tuple[float, bool] | None:
    """Tolka luftflöde. Returnerar (värde med tecken, signed)."""
    if not text:
        return None
    raw = str(text).strip()
    s = normalize_ocr_text(raw).replace(" ", "")
    has_unit = bool(re.search(r"L\s*/\s*S|LS", s))
    s = re.sub(r"L\s*/\s*S|LS", "", s)
    m = re.fullmatch(r"([+-])?(\d{1,4})", s)
    if not m:
        return None
    val = float(m.group(2))
    signed = m.group(1) is not None
    if m.group(1) == "-":
        val = -val
    if abs(val) < 2:
        return None
    # Osingerade 100/125/160 är oftast donstorlek, inte l/s.
    if not signed and not has_unit and int(abs(val)) in STANDARD_SIZES:
        return None
    return val, signed


def _size_from_rest(rest: str, name: str) -> str | None:
    if not rest:
        return None
    # HFK-stil 2*315
    if name.lower() in {"hfk", "alg", "pra", "eagle", "pelican"}:
        m = _MULT_SIZE_RE.search(rest)
        if m and int(m.group(2)) in STANDARD_SIZES:
            return f"{int(m.group(1))}*{int(m.group(2))}"
    m = _SIZE_RANGE_RE.search(rest)
    if m:
        a, b = int(m.group(1)), int(m.group(2))
        # CRa 200-600 etc.
        if a >= 80 and b >= a:
            return f"{a}-{b}"
    m = _STD_SIZE_RE.search(rest)
    if m:
        return str(int(m.group(1)))
    return None


def parse_label(text: str, *, x: float = 0.0, y: float = 0.0, confidence: float = 1.0) -> Vent | None:
    """Tolka en etikettrad. Returnerar None om ingen känd don-typ finns."""
    if not text or not str(text).strip():
        return None
    raw = str(text).strip()
    if _NOISE_RE.search(raw) and not any(p in raw.upper() for p in ("KSO", "KTS", "EAGLE", "PELICAN", "ALG")):
        return None

    norm = normalize_ocr_text(raw)
    match = _PRODUCT_RE.search(norm)
    if not match:
        return None

    name = match.group("name").lower()
    if name == "hu" and re.search(r"[A-Z]HU|HU[A-Z]", norm):
        return None
    qty = int(match.group("qty") or 1)
    if qty < 1 or qty > 12:
        qty = 1

    rest = norm[match.end() :]
    size = _size_from_rest(rest, name)
    if size is None and name == "kts-360":
        size = _size_from_rest(rest, "kts")

    airflow = None
    signed_flow = False
    am = _AIRFLOW_RE.search(norm)
    if am:
        airflow = float(am.group("val"))
        signed_flow = True
        if am.group("sign") == "-":
            airflow = -airflow
        if abs(airflow) < 2:
            airflow = None
            signed_flow = False

    kind = classify_kind(name, airflow, signed=signed_flow)
    if kind is None:
        return None

    store_name = name
    if store_name == "huk":
        store_name = "hu"

    abs_flow = abs(airflow) if airflow is not None else None
    return Vent(
        name=store_name,
        kind=kind,
        size=size,
        airflow=abs_flow,
        qty=qty,
        x=x,
        y=y,
        raw=raw,
        confidence=confidence,
        source="ocr",
    )


def expand_qty(vent: Vent) -> list[Vent]:
    """2xKTS → två rader. Luftmängd delas lika när den finns."""
    n = max(1, int(vent.qty or 1))
    if n == 1:
        return [replace(vent, qty=1)]
    flow = None
    if vent.airflow is not None:
        split = vent.airflow / n
        flow = int(split) if split == int(split) else round(split, 1)
    return [replace(vent, qty=1, airflow=flow) for _ in range(n)]


def is_airflow_token(text: str) -> bool:
    return parse_airflow_token(text) is not None
