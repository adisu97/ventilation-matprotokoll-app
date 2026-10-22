"""Skannade ritningar: raster-PDF utan vektorer/text."""

from __future__ import annotations

import io
import logging
import time
from dataclasses import dataclass, field

from PIL import Image, ImageOps

logger = logging.getLogger(__name__)

# A0-skanningar på 300 dpi blir >100 Mpx.
Image.MAX_IMAGE_PIXELS = 250_000_000


@dataclass
class TimedTokens:
    tokens: list[tuple[str, float, float, float]]
    render_s: float
    preprocess_s: float
    ocr_s: float
    width: float
    height: float
    work_size: tuple[int, int] = (0, 0)
    n_tiles: int = 0
    extras: dict = field(default_factory=dict)


def page_looks_like_scan(page) -> bool:
    """True när sidan är en inskannad bild (ingen text, inga CAD-streck)."""
    try:
        if page.words():
            return False
    except Exception:
        pass
    inner = getattr(page, "_page", None)
    if inner is None:
        return False
    try:
        if inner.get_drawings():
            return False
        images = inner.get_images()
        return bool(images)
    except Exception:
        return False


def extract_scan_image(page) -> Image.Image | None:
    """Plocka ut den inbäddade rasterbilden (undvik att rita om PDF:en)."""
    inner = getattr(page, "_page", None)
    if inner is None:
        return None
    try:
        import pymupdf
    except Exception:
        return None
    images = inner.get_images(full=True)
    if not images:
        return None
    xref = images[0][0]
    doc = inner.parent
    info = doc.extract_image(xref)
    blob = info.get("image")
    if not blob:
        return None
    return Image.open(io.BytesIO(blob)).convert("RGB")


def preprocess_scan(image: Image.Image, *, max_side: int = 4200) -> Image.Image:
    """Skala ner, gråskala, kontrast. Ingen siddeskew — planen kan vara ritad snett."""
    w, h = image.size
    scale = min(1.0, max_side / max(w, h))
    if scale < 0.999:
        image = image.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.Resampling.BILINEAR)
    gray = ImageOps.autocontrast(image.convert("L"), cutoff=1)
    # Ingen median: den suddar 1–2-siffriga rumsnummer på 300 dpi-skanningar.
    return gray.convert("RGB")


def ocr_bitmap_tokens(
    image: Image.Image,
    *,
    page_width: float,
    page_height: float,
    engine,
    tile: int = 1200,
    overlap: int = 160,
    progress=None,
) -> tuple[list[tuple[str, float, float, float]], float, int]:
    """OCR en redan förbehandlad bild. Koordinater i PDF-punkter."""
    from matprotokoll.ocr import _tile_is_blank

    t0 = time.perf_counter()
    iw, ih = image.size
    step = max(1, tile - overlap)
    jobs = []
    y = 0
    while y < ih:
        y1 = min(ih, y + tile)
        x = 0
        while x < iw:
            x1 = min(iw, x + tile)
            jobs.append((x, y, x1, y1))
            if x1 >= iw:
                break
            x += step
        if y1 >= ih:
            break
        y += step
    tokens: list[tuple[str, float, float, float]] = []
    used = 0
    for i, (x0, y0, x1, y1) in enumerate(jobs, start=1):
        if progress:
            progress(i / max(1, len(jobs)), f"Skannings-OCR {i}/{len(jobs)}")
        tile_im = image.crop((x0, y0, x1, y1))
        # Tunna ritningsstreck på vit bakgrund har hög medelgrå — hoppa bara
        # över nästan helt tomma rutor.
        if _tile_is_blank(tile_im, mean_min=253.0, std_max=3.5):
            continue
        used += 1
        try:
            hits = engine.recognize(tile_im)
        except Exception as exc:
            logger.warning("Skannings-OCR misslyckades: %s", exc)
            continue
        for text, conf, bbox in hits:
            cx = (bbox[0] + bbox[2]) / 2.0
            cy = (bbox[1] + bbox[3]) / 2.0
            pdf_x = (x0 + cx) / iw * page_width
            pdf_y = (y0 + cy) / ih * page_height
            tokens.append((text, float(pdf_x), float(pdf_y), float(conf)))
    return tokens, time.perf_counter() - t0, used


def ocr_scan_page(page, engine, *, max_side: int = 5000, tile: int = 1300, overlap: int = 180, progress=None) -> TimedTokens:
    t_all = time.perf_counter()
    t0 = time.perf_counter()
    raw = extract_scan_image(page)
    if raw is None:
        # Poppler/Termux: rita sidan i begränsad upplösning.
        zoom = min(2.2, max_side / max(page.width, page.height))
        raw = page.render_clip((0, 0, page.width, page.height), zoom)
        render_s = time.perf_counter() - t0
    else:
        render_s = time.perf_counter() - t0
    t1 = time.perf_counter()
    work = preprocess_scan(raw, max_side=max_side)
    preprocess_s = time.perf_counter() - t1
    del raw
    tokens, ocr_s, n_tiles = ocr_bitmap_tokens(
        work,
        page_width=page.width,
        page_height=page.height,
        engine=engine,
        tile=tile,
        overlap=overlap,
        progress=progress,
    )
    extra_t0 = time.perf_counter()
    extra, n_crops = _ocr_around_names(
        work, tokens, engine, page.width, page.height, angles=(90, 270)
    )
    tokens.extend(extra)
    ocr_s += time.perf_counter() - extra_t0
    n_tiles += n_crops
    return TimedTokens(
        tokens=tokens,
        render_s=render_s,
        preprocess_s=preprocess_s,
        ocr_s=ocr_s,
        width=page.width,
        height=page.height,
        work_size=work.size,
        n_tiles=n_tiles,
        extras={"total_s": time.perf_counter() - t_all, "name_crops": n_crops},
    )


def map_rotated_xy(x: float, y: float, width: int, height: int, angle: int) -> tuple[float, float]:
    """Pil Image.rotate(angle, expand=True) är moturs. Åter till originalkoordinater."""
    if angle % 360 == 0:
        return x, y
    if angle % 360 == 90:
        return width - 1 - y, x
    if angle % 360 == 270:
        return y, height - 1 - x
    if angle % 360 == 180:
        return width - 1 - x, height - 1 - y
    return x, y


def _ocr_around_names(
    work: Image.Image,
    tokens,
    engine,
    page_w: float,
    page_h: float,
    *,
    angles: tuple[int, ...] = (0, 90, 270),
):
    """Hög zoom + 90/270° runt rumsnamn och m² — skannade planer är ofta ritade snett."""
    from matprotokoll.rooms import AREA_NEAR_RE, SHORT_ROOM_RE, _name_from_text

    iw, ih = work.size
    extra = []
    n = 0
    seen = set()
    anchors = []
    for text, x, y, _c in tokens:
        if _name_from_text(text) or AREA_NEAR_RE.search(text or ""):
            anchors.append((x, y))
    for x, y in anchors:
        key = (round(x / 45), round(y / 45))
        if key in seen:
            continue
        seen.add(key)
        px = x / page_w * iw
        py = y / page_h * ih
        box = (max(0, px - 180), max(0, py - 180), min(iw, px + 180), min(ih, py + 180))
        crop = work.crop(tuple(int(v) for v in box))
        if crop.width < 20 or crop.height < 20:
            continue
        crop = ImageOps.autocontrast(crop.convert("L"), cutoff=1).convert("RGB")
        crop = crop.resize((crop.width * 2, crop.height * 2), Image.Resampling.BICUBIC)
        cw, ch = crop.size
        for angle in angles:
            n += 1
            try:
                view = crop if angle % 360 == 0 else crop.rotate(angle, expand=True, fillcolor=(255, 255, 255))
                hits = engine.recognize(view)
            except Exception:
                continue
            for t2, conf, bbox in hits:
                cx = (bbox[0] + bbox[2]) / 2.0
                cy = (bbox[1] + bbox[3]) / 2.0
                ox, oy = map_rotated_xy(cx, cy, cw, ch, angle)
                ox /= 2.0
                oy /= 2.0
                pdf_x = (box[0] + ox) / iw * page_w
                pdf_y = (box[1] + oy) / ih * page_h
                extra.append((t2, float(pdf_x), float(pdf_y), float(conf)))
                raw = (t2 or "").strip()
                if SHORT_ROOM_RE.match(raw):
                    extra.append((raw, float(pdf_x), float(pdf_y), float(conf)))
    return extra, n
