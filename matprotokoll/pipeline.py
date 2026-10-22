"""Hel kedja: PDF → rum + OCR-don → parrade rader → Excel."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Callable

from matprotokoll.associate import pair_all, vents_from_ocr_tokens
from matprotokoll.excel_io import (
    add_protocol_sheet,
    drop_unused_prototype_sheets,
    load_workbook_from_bytes_or_path,
    new_workbook_from_template,
    save_workbook,
)
from matprotokoll.labels import parse_label
from matprotokoll.models import ProtocolRow, SheetResult, Vent
from matprotokoll.ocr import OcrEngine, load_ocr_engine, ocr_page_tokens
from matprotokoll.pdfdoc import PdfPage, open_document
from matprotokoll.room_refine import refine_rooms_from_gaps
from matprotokoll.rooms import extract_rooms, extract_rooms_from_tokens, merge_rooms
from matprotokoll.scans import ocr_scan_page, page_looks_like_scan
from matprotokoll.settings import tile_px, zoom as default_zoom
from matprotokoll.sheet_names import sheet_name_from_pdf

logger = logging.getLogger(__name__)
ProgressCb = Callable[[float, str], None]


def _vents_from_pdf_text(page: PdfPage) -> list[Vent]:
    """Komplettera med don som faktiskt finns som PDF-text (ovanligt men billigt)."""
    found: list[Vent] = []
    for x0, y0, x1, y1, text in page.words():
        vent = parse_label(text, x=(x0 + x1) / 2.0, y=(y0 + y1) / 2.0, confidence=1.0)
        if vent:
            vent.source = "pdf-text"
            found.append(vent)
    return found


def extract_sheet_from_pdf(
    pdf_path: str | Path,
    *,
    pdf_name: str | None = None,
    zoom: float | None = None,
    tile_px_value: int | None = None,
    engine: OcrEngine | None = None,
    progress: ProgressCb | None = None,
    existing_sheet_names: list[str] | None = None,
) -> SheetResult:
    pdf_path = Path(pdf_path)
    name = pdf_name or pdf_path.name
    zoom = default_zoom() if zoom is None else zoom
    tile = tile_px() if tile_px_value is None else tile_px_value
    with open_document(pdf_path) as doc:
        rooms = []
        tokens: list[tuple[str, float, float, float]] = []
        text_vents: list[Vent] = []
        n = max(1, doc.page_count)
        shared = engine or load_ocr_engine()
        for i, page in enumerate(doc.pages()):
            page_rooms = extract_rooms(page)
            text_vents.extend(_vents_from_pdf_text(page))

            def _cb(frac: float, msg: str, page_i=i) -> None:
                if progress:
                    progress((page_i + frac) / n, f"{name}: sida {page_i + 1}/{n}: {msg}")

            if page_looks_like_scan(page):
                timed = ocr_scan_page(page, shared, progress=_cb if progress else None)
                page_tokens = timed.tokens
                logger.info(
                    "Skannad sida: render %.2fs förbehandling %.2fs OCR %.2fs (%s rutor)",
                    timed.render_s,
                    timed.preprocess_s,
                    timed.ocr_s,
                    timed.n_tiles,
                )
            else:
                page_tokens = ocr_page_tokens(
                    page,
                    zoom=zoom,
                    tile_px_value=tile,
                    engine=shared,
                    progress=_cb if progress else None,
                )
            ocr_rooms = extract_rooms_from_tokens(
                page_tokens,
                page_width=page.width,
                page_height=page.height,
            )
            page_rooms = merge_rooms(page_rooms, ocr_rooms)
            if not page_looks_like_scan(page):
                try:
                    page_rooms = refine_rooms_from_gaps(page, shared, page_rooms)
                except Exception:
                    logger.exception("Luck-OCR för rum misslyckades")
            rooms.extend(page_rooms)
            tokens.extend(page_tokens)

    ocr_vents = vents_from_ocr_tokens(tokens)
    vents = ocr_vents + [v for v in text_vents if v.source == "pdf-text"]
    rows = pair_all(rooms, ocr_vents if ocr_vents else vents)
    warnings: list[str] = []
    if not ocr_vents:
        warnings.append(
            "Inga kända don hittades via OCR. Kontrollera att ritningen innehåller "
            "KSO/KTS/EAGLE/PELICAN m.fl. och att texten inte är för suddig."
        )
    if not rooms:
        warnings.append(
            "Inga rumsnummer kunde läsas (varken PDF-text eller OCR). "
            "Don läggs under okänt utrymme."
        )
    sheet = sheet_name_from_pdf(name, existing_sheet_names)
    return SheetResult(
        pdf_name=name,
        sheet_name=sheet,
        rows=rows,
        rooms=rooms,
        vents=ocr_vents,
        warnings=warnings,
    )


def rows_from_preview(dicts: list[dict]) -> list[ProtocolRow]:
    return [ProtocolRow.from_preview_dict(d) for d in dicts]


def build_workbooks(
    results: list[SheetResult],
    *,
    existing_xlsx: bytes | None = None,
    template_path: Path | str | None = None,
) -> dict[str, bytes]:
    """Bygg en eller flera arbetsböcker.

    result.new_workbook=True → egen fil från mallen (med Koneet Maskiner).
    Övriga PDF:er hamnar som nya blad i bas-arbetsboken.
    """
    files: dict[str, bytes] = {}
    base = None
    if existing_xlsx:
        base = load_workbook_from_bytes_or_path(existing_xlsx)
    else:
        need_base = any(not r.new_workbook for r in results) or not results
        if need_base:
            base = new_workbook_from_template(template_path)

    existing_names = list(base.sheetnames) if base is not None else []
    for result in results:
        if result.new_workbook:
            wb = new_workbook_from_template(template_path)
            add_protocol_sheet(wb, result.sheet_name, result.rows)
            drop_unused_prototype_sheets(wb)
            fname = f"{result.sheet_name}.xlsx"
            files[fname] = save_workbook(wb)
        else:
            assert base is not None
            add_protocol_sheet(base, result.sheet_name, result.rows)
            existing_names.append(result.sheet_name)

    if base is not None:
        drop_unused_prototype_sheets(base)
        files["_base"] = save_workbook(base)
    return files
