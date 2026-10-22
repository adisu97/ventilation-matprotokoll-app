"""Gemensam extraktion till JSON/Excel — samma kedja som webben."""

from __future__ import annotations

import json
import os
from pathlib import Path

from matprotokoll.ocr import load_ocr_engine
from matprotokoll.pipeline import build_workbooks, extract_sheet_from_pdf


def extract_payload(pdf_path: str | Path, *, quality: str | None = None) -> dict:
    pdf_path = Path(pdf_path)
    if quality:
        os.environ["MATPROTOKOLL_QUALITY"] = quality
    os.environ.setdefault("MATPROTOKOLL_OCR", "rapidocr")
    os.environ.setdefault("MATPROTOKOLL_PDF", "pymupdf")
    engine = load_ocr_engine()
    result = extract_sheet_from_pdf(pdf_path, pdf_name=pdf_path.name, engine=engine)
    rooms = [
        {"nr": r.number, "namn": r.name or "", "osakerhet": getattr(r, "uncertainty", "") or ""}
        for r in result.rooms
    ]
    return {
        "pdf": pdf_path.name,
        "engine": engine.name,
        "quality": os.environ.get("MATPROTOKOLL_QUALITY", ""),
        "n_rooms": len(rooms),
        "n_vents": len(result.vents),
        "n_rows": len(result.rows),
        "rooms": rooms,
        "sheet_name": result.sheet_name,
        "warnings": result.warnings,
    }


def write_xlsx(pdf_path: str | Path, xlsx_path: str | Path, *, quality: str | None = None) -> Path:
    pdf_path = Path(pdf_path)
    xlsx_path = Path(xlsx_path)
    if quality:
        os.environ["MATPROTOKOLL_QUALITY"] = quality
    os.environ.setdefault("MATPROTOKOLL_OCR", "rapidocr")
    engine = load_ocr_engine()
    result = extract_sheet_from_pdf(pdf_path, pdf_name=pdf_path.name, engine=engine)
    files = build_workbooks([result])
    data = files.get("_base") or next(iter(files.values()))
    xlsx_path.write_bytes(data)
    return xlsx_path


def dump_json(payload: dict, path: str | Path) -> None:
    Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
