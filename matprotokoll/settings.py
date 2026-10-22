"""Miljövariabler som styr OCR, PDF-backend och minnesåtgång.

Skrivbord (standard, noggrann): zoom 3, rutor 900 px, överlapp 240, rotation 0/90/270.
Snabb: samma RapidOCR/PyMuPDF, färre rutor, ingen rotation. Telefon/surfplatta defaultar till snabb.
"""

from __future__ import annotations

import os


def _env(name: str, default: str) -> str:
    value = os.environ.get(name)
    if value is None or not str(value).strip():
        return default
    return str(value).strip()


def ocr_preference() -> str:
    """auto | rapidocr | tesseract."""
    value = _env("MATPROTOKOLL_OCR", "auto").lower()
    if value in {"tess", "tesseract"}:
        return "tesseract"
    if value in {"rapid", "rapidocr"}:
        return "rapidocr"
    return "auto"


def pdf_preference() -> str:
    """auto | pymupdf | poppler."""
    value = _env("MATPROTOKOLL_PDF", "auto").lower()
    if value in {"mupdf", "pymupdf"}:
        return "pymupdf"
    if value == "poppler":
        return "poppler"
    return "auto"


def low_memory() -> bool:
    return _env("MATPROTOKOLL_LOWMEM", "").lower() in {"1", "true", "yes", "ja"}


def quality_mode() -> str:
    """fast | accurate. Telefon (LOWMEM) defaultar till snabb."""
    value = _env("MATPROTOKOLL_QUALITY", "").lower()
    if value in {"fast", "snabb", "quick"}:
        return "fast"
    if value in {"accurate", "noggrann", "fine"}:
        return "accurate"
    return "fast" if low_memory() else "accurate"


def _quality_defaults() -> dict[str, float | int | tuple[int, ...]]:
    if quality_mode() == "fast":
        if low_memory():
            return {"zoom": 2.0, "tile": 800, "overlap": 140, "rotations": (0,)}
        return {"zoom": 2.8, "tile": 1100, "overlap": 220, "rotations": (0,)}
    if low_memory():
        return {"zoom": 2.2, "tile": 800, "overlap": 160, "rotations": (0, 90)}
    return {"zoom": 3.0, "tile": 900, "overlap": 240, "rotations": (0, 90, 270)}


def zoom() -> float:
    defaults = _quality_defaults()
    raw = _env("MATPROTOKOLL_ZOOM", "")
    if not raw:
        return float(defaults["zoom"])
    try:
        value = float(raw)
    except ValueError:
        return float(defaults["zoom"])
    return min(4.0, max(1.0, value))


def tile_px() -> int:
    defaults = _quality_defaults()
    raw = _env("MATPROTOKOLL_TILE", "")
    if not raw:
        return int(defaults["tile"])
    try:
        value = int(float(raw))
    except ValueError:
        return int(defaults["tile"])
    return min(2400, max(400, value))


def overlap_px() -> int:
    defaults = _quality_defaults()
    raw = _env("MATPROTOKOLL_OVERLAP", "")
    if not raw:
        value = int(defaults["overlap"])
    else:
        try:
            value = int(float(raw))
        except ValueError:
            value = int(defaults["overlap"])
    tile = tile_px()
    return min(tile // 2, max(40, value))


def ocr_rotations() -> tuple[int, ...]:
    raw = _env("MATPROTOKOLL_ROTATIONS", "")
    if raw.strip():
        parts = []
        for piece in raw.replace(";", ",").split(","):
            piece = piece.strip()
            if piece in {"0", "90", "270", "180"}:
                parts.append(int(piece))
        if parts:
            return tuple(parts)
    return tuple(int(x) for x in _quality_defaults()["rotations"])  # type: ignore[arg-type]
