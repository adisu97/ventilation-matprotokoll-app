"""Rendera PDF-sida till bildrutor och läs don-etiketter med OCR.

Ventilationsdon på AutoCAD-plot är ofta outlined/vektortext och syns inte i
PDF-textlagret. Pipeline: rendera en ruta i taget → RapidOCR om det finns,
annars Tesseract. Hela sidan ritas aldrig upp, så den stora exempelritningen
inte tar slut på minnet i telefonen.
"""

from __future__ import annotations

import gc
import logging
import os
from pathlib import Path
from typing import Callable

from PIL import Image

from matprotokoll.pdfdoc import PdfPage, open_document
from matprotokoll.settings import (
    low_memory,
    ocr_preference,
    ocr_rotations,
    overlap_px,
    tile_px,
    zoom as default_zoom,
)

logger = logging.getLogger(__name__)

ProgressCb = Callable[[float, str], None]


class OcrEngine:
    name = "ocr"

    def recognize(self, image: Image.Image) -> list[tuple[str, float, tuple[float, float, float, float]]]:
        """Returnera (text, conf 0-1, bbox x0,y0,x1,y1 i bildpixlar)."""
        raise NotImplementedError


class RapidOcrEngine(OcrEngine):
    name = "RapidOCR"

    def __init__(self) -> None:
        from rapidocr_onnxruntime import RapidOCR

        self._ocr = RapidOCR()

    def recognize(self, image: Image.Image) -> list[tuple[str, float, tuple[float, float, float, float]]]:
        import numpy as np

        arr = np.array(image.convert("RGB"))
        result, _elapse = self._ocr(arr)
        out: list[tuple[str, float, tuple[float, float, float, float]]] = []
        if not result:
            return out
        for item in result:
            box, text, conf = item[0], item[1], float(item[2])
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            bbox = (min(xs), min(ys), max(xs), max(ys))
            if text and str(text).strip():
                out.append((str(text).strip(), conf, bbox))
        return out


def group_tesseract_lines(data: dict) -> list[tuple[str, float, tuple[float, float, float, float]]]:
    """Slå ihop ord på samma textrad. `KSO-125-C` och `2xEAGLE CRa 200-600` ska hålla ihop."""
    n = len(data.get("text") or [])
    groups: dict[tuple, list[tuple]] = {}
    order: list[tuple] = []
    for i in range(n):
        text = (data["text"][i] or "").strip()
        if not text:
            continue
        key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
        try:
            conf = float(data["conf"][i]) / 100.0
        except (TypeError, ValueError, KeyError):
            conf = 0.0
        if conf < 0:
            conf = 0.0
        x, y, w, h = data["left"][i], data["top"][i], data["width"][i], data["height"][i]
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append((text, conf, x, y, x + w, y + h))
    out: list[tuple[str, float, tuple[float, float, float, float]]] = []
    for key in order:
        parts = groups[key]
        text = " ".join(part[0] for part in parts).strip()
        if not text:
            continue
        conf = sum(part[1] for part in parts) / len(parts)
        x0 = min(part[2] for part in parts)
        y0 = min(part[3] for part in parts)
        x1 = max(part[4] for part in parts)
        y1 = max(part[5] for part in parts)
        out.append((text, conf, (x0, y0, x1, y1)))
    return out


class TesseractEngine(OcrEngine):
    name = "Tesseract"

    def recognize(self, image: Image.Image) -> list[tuple[str, float, tuple[float, float, float, float]]]:
        import pytesseract

        lang = os.environ.get("MATPROTOKOLL_TESS_LANG", "eng")
        gray = image.convert("L")
        # psm 11: gles text, som don-etiketter utspridda på en ritning.
        config = "--oem 3 --psm 11"
        data = pytesseract.image_to_data(
            gray,
            lang=lang,
            config=config,
            output_type=pytesseract.Output.DICT,
        )
        return group_tesseract_lines(data)


def load_ocr_engine() -> OcrEngine:
    """Samma motor överallt: RapidOCR. Tesseract bara om MATPROTOKOLL_OCR=tesseract."""
    choice = ocr_preference()
    if choice == "tesseract":
        logger.info("OCR-motor: Tesseract")
        return TesseractEngine()
    try:
        engine = RapidOcrEngine()
    except Exception as exc:
        raise RuntimeError(
            "RapidOCR/onnxruntime kunde inte laddas. "
            "Installera samma requirements.txt på alla plattformar (även Android via proot)."
        ) from exc
    logger.info("OCR-motor: RapidOCR")
    return engine


def preprocess_tile(image: Image.Image) -> Image.Image:
    """Kontrast på outlined CAD-text. Billigt och hjälper både RapidOCR och Tesseract."""
    from PIL import ImageOps

    gray = image.convert("L")
    return ImageOps.autocontrast(gray, cutoff=1).convert("RGB")


def map_xy_from_rotation(cx: float, cy: float, rot: int, w: int, h: int) -> tuple[float, float]:
    """Återför punkt i roterad PIL-bild (CCW, expand) till originalruta."""
    if rot == 0:
        return cx, cy
    if rot == 90:
        return float(w - 1 - cy), float(cx)
    if rot == 180:
        return float(w - 1 - cx), float(h - 1 - cy)
    if rot == 270:
        return float(cy), float(h - 1 - cx)
    return cx, cy


def _tile_is_blank(image: Image.Image, mean_min: float = 248.0, std_max: float = 10.0) -> bool:
    """Tom ruta utan numpy (numpy behövs inte i telefonvägen)."""
    hist = image.convert("L").histogram()
    total = sum(hist) or 1
    mean = sum(i * hist[i] for i in range(256)) / total
    var = sum(hist[i] * (i - mean) ** 2 for i in range(256)) / total
    return mean >= mean_min and var ** 0.5 <= std_max


def ocr_page_tokens(
    page: PdfPage,
    *,
    zoom: float | None = None,
    tile_px_value: int | None = None,
    overlap: int | None = None,
    engine: OcrEngine | None = None,
    progress: ProgressCb | None = None,
    rotations: tuple[int, ...] | None = None,
    preprocess: bool | None = None,
) -> list[tuple[str, float, float, float]]:
    """OCR en PDF-sida. Returnerar (text, pdf_x, pdf_y, conf)."""
    engine = engine or load_ocr_engine()
    zoom = default_zoom() if zoom is None else zoom
    tile = tile_px() if tile_px_value is None else tile_px_value
    overlap = overlap_px() if overlap is None else overlap
    rotations = ocr_rotations() if rotations is None else rotations
    if preprocess is None:
        preprocess = engine.name.lower().startswith("tesseract")
    full_w = max(1, int(page.width * zoom))
    full_h = max(1, int(page.height * zoom))
    step = max(1, tile - overlap)

    jobs: list[tuple[int, int, int, int]] = []
    y = 0
    while y < full_h:
        x = 0
        y1 = min(full_h, y + tile)
        while x < full_w:
            x1 = min(full_w, x + tile)
            jobs.append((x, y, x1, y1))
            if x1 >= full_w:
                break
            x += step
        if y1 >= full_h:
            break
        y += step

    tokens: list[tuple[str, float, float, float]] = []
    total = max(1, len(jobs) * max(1, len(rotations)))
    n = 0
    for x0, y0, x1, y1 in jobs:
        clip = (x0 / zoom, y0 / zoom, x1 / zoom, y1 / zoom)
        try:
            image = page.render_clip(clip, zoom)
        except Exception as exc:
            logger.warning("Kunde inte rita ruta: %s", exc)
            n += len(rotations)
            continue
        try:
            if _tile_is_blank(image):
                n += len(rotations)
                continue
            work = preprocess_tile(image) if preprocess else image
            tw, th = work.size
            for rot in rotations:
                n += 1
                if progress:
                    progress(n / total, f"OCR-ruta {n}/{total}")
                if rot == 0:
                    img = work
                else:
                    img = work.rotate(rot, expand=True)
                try:
                    hits = engine.recognize(img)
                except Exception as exc:
                    logger.warning("OCR misslyckades för ruta: %s", exc)
                    continue
                for text, conf, bbox in hits:
                    cx = (bbox[0] + bbox[2]) / 2.0
                    cy = (bbox[1] + bbox[3]) / 2.0
                    lx, ly = map_xy_from_rotation(cx, cy, rot, tw, th)
                    pdf_x = clip[0] + lx / zoom
                    pdf_y = clip[1] + ly / zoom
                    tokens.append((text, float(pdf_x), float(pdf_y), float(conf)))
        finally:
            del image
            if low_memory() and n % 8 == 0:
                gc.collect()
    return tokens


def ocr_pdf_file(
    path: str | Path,
    *,
    zoom: float | None = None,
    engine: OcrEngine | None = None,
    progress: ProgressCb | None = None,
) -> list[list[tuple[str, float, float, float]]]:
    with open_document(path) as doc:
        pages = []
        n = doc.page_count
        shared = engine or load_ocr_engine()
        for i, page in enumerate(doc.pages()):

            def _cb(frac: float, msg: str, page_i=i, n_pages=n) -> None:
                if progress:
                    progress((page_i + frac) / n_pages, f"Sida {page_i + 1}/{n_pages}: {msg}")

            pages.append(
                ocr_page_tokens(page, zoom=zoom, engine=shared, progress=_cb if progress else None)
            )
        return pages
