"""Excel-bladnamn: max 31 tecken, inga ogiltiga tecken."""

from __future__ import annotations

from pathlib import Path

from matprotokoll.constants import EXCEL_INVALID_SHEET_CHARS, EXCEL_MAX_SHEET_LEN


def sheet_name_from_pdf(filename: str, existing: list[str] | None = None) -> str:
    stem = Path(filename).stem
    return uniquify_sheet_name(sanitize_sheet_name(stem), existing or [])


def sanitize_sheet_name(name: str) -> str:
    cleaned = "".join("_" if ch in EXCEL_INVALID_SHEET_CHARS else ch for ch in (name or ""))
    cleaned = cleaned.strip(" '")
    cleaned = " ".join(cleaned.split())
    if not cleaned:
        cleaned = "Blad"
    return cleaned[:EXCEL_MAX_SHEET_LEN]


def ensure_workbook_filename(name: str) -> str:
    """Filnamn för nedladdning. Lägger till .xlsx och tar bort sökvägar."""
    raw = Path(name or "").name.strip() or "matprotokoll"
    raw = raw.replace("/", "_").replace("\\", "_")
    if not raw.lower().endswith(".xlsx"):
        raw += ".xlsx"
    return raw


def uniquify_sheet_name(name: str, existing: list[str]) -> str:
    taken = {n.lower() for n in existing}
    base = sanitize_sheet_name(name)
    if base.lower() not in taken:
        return base
    for i in range(2, 100):
        suffix = f"_{i}"
        trimmed = sanitize_sheet_name(base[: EXCEL_MAX_SHEET_LEN - len(suffix)] + suffix)
        if trimmed.lower() not in taken:
            return trimmed
    return (base[: EXCEL_MAX_SHEET_LEN - 2] + "_x")[:EXCEL_MAX_SHEET_LEN]
