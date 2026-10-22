"""PDF-läsning med PyMuPDF (samma pip-hjul på Windows, Linux och Android-proot).

Poppler är bara reserv om PyMuPDF saknas.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from matprotokoll.settings import pdf_preference

logger = logging.getLogger(__name__)


@dataclass
class TextSpan:
    text: str
    bbox: tuple[float, float, float, float]
    size: float


class PdfPage:
    index: int
    width: float
    height: float

    def text_spans(self) -> list[TextSpan]:
        raise NotImplementedError

    def words(self) -> list[tuple[float, float, float, float, str]]:
        raise NotImplementedError

    def render_clip(self, clip: tuple[float, float, float, float], zoom: float) -> Image.Image:
        raise NotImplementedError


class PdfDocument:
    def __enter__(self) -> PdfDocument:
        return self

    def __exit__(self, *args) -> None:
        self.close()

    def close(self) -> None:
        return None

    @property
    def page_count(self) -> int:
        raise NotImplementedError

    def pages(self) -> list[PdfPage]:
        raise NotImplementedError


def _local(tag: str) -> str:
    if "}" in tag:
        return tag.rsplit("}", 1)[-1]
    return tag


class PopplerPage(PdfPage):
    def __init__(self, doc: PopplerDocument, index: int, width: float, height: float, spans: list[TextSpan]):
        self._doc = doc
        self.index = index
        self.width = width
        self.height = height
        self._spans = spans

    def text_spans(self) -> list[TextSpan]:
        return list(self._spans)

    def words(self) -> list[tuple[float, float, float, float, str]]:
        return [(s.bbox[0], s.bbox[1], s.bbox[2], s.bbox[3], s.text) for s in self._spans]

    def render_clip(self, clip: tuple[float, float, float, float], zoom: float) -> Image.Image:
        if not shutil.which("pdftoppm"):
            raise RuntimeError("pdftoppm saknas. I Termux: pkg install poppler")
        dpi = max(72, int(round(zoom * 72)))
        scale = dpi / 72.0
        x0, y0, x1, y1 = clip
        x = max(0, int(x0 * scale))
        y = max(0, int(y0 * scale))
        w = max(1, int((x1 - x0) * scale))
        h = max(1, int((y1 - y0) * scale))
        page_no = self.index + 1
        with tempfile.TemporaryDirectory(prefix="matprotokoll-tile-") as tmp:
            out = Path(tmp) / "tile"
            cmd = [
                "pdftoppm",
                "-f",
                str(page_no),
                "-l",
                str(page_no),
                "-r",
                str(dpi),
                "-x",
                str(x),
                "-y",
                str(y),
                "-W",
                str(w),
                "-H",
                str(h),
                "-png",
                "-singlefile",
                str(self._doc.path),
                str(out),
            ]
            proc = subprocess.run(cmd, capture_output=True, check=False)
            png = out.with_suffix(".png")
            if proc.returncode != 0 or not png.exists():
                err = (proc.stderr or b"").decode("utf-8", "replace")[-500:]
                raise RuntimeError(f"pdftoppm misslyckades: {err}")
            image = Image.open(png)
            image.load()
            return image.copy()


class PopplerDocument(PdfDocument):
    def __init__(self, path: Path):
        if not shutil.which("pdftotext") or not shutil.which("pdfinfo"):
            raise RuntimeError("Poppler saknas (pdftotext/pdfinfo). I Termux: pkg install poppler")
        self.path = path
        self._pages = _poppler_pages(self)

    @property
    def page_count(self) -> int:
        return len(self._pages)

    def pages(self) -> list[PdfPage]:
        return list(self._pages)


def _pdf_page_count(path: Path) -> int:
    proc = subprocess.run(["pdfinfo", str(path)], capture_output=True, check=False, text=True)
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or "pdfinfo misslyckades")[-400:])
    for line in proc.stdout.splitlines():
        if line.lower().startswith("pages:"):
            return max(1, int(line.split(":", 1)[1].strip()))
    return 1


def _poppler_pages(doc: PopplerDocument) -> list[PopplerPage]:
    n = _pdf_page_count(doc.path)
    pages: list[PopplerPage] = []
    for i in range(n):
        proc = subprocess.run(
            ["pdftotext", "-bbox", "-f", str(i + 1), "-l", str(i + 1), str(doc.path), "-"],
            capture_output=True,
            check=False,
        )
        if proc.returncode != 0:
            err = (proc.stderr or b"").decode("utf-8", "replace")[-400:]
            raise RuntimeError(f"pdftotext misslyckades: {err}")
        width, height, spans = _parse_bbox(proc.stdout)
        pages.append(PopplerPage(doc, i, width, height, spans))
    return pages


def _parse_bbox(xml_bytes: bytes) -> tuple[float, float, list[TextSpan]]:
    """pdftotext -bbox använder origo uppe till vänster, y nedåt (samma som PyMuPDF)."""
    root = ET.fromstring(xml_bytes)
    width = 0.0
    height = 0.0
    spans: list[TextSpan] = []
    for el in root.iter():
        if _local(el.tag) != "page":
            continue
        width = float(el.attrib.get("width", "0") or 0)
        height = float(el.attrib.get("height", "0") or 0)
        for word in el.iter():
            if _local(word.tag) != "word":
                continue
            text = (word.text or "").strip()
            if not text:
                continue
            try:
                x0 = float(word.attrib["xMin"])
                y0 = float(word.attrib["yMin"])
                x1 = float(word.attrib["xMax"])
                y1 = float(word.attrib["yMax"])
            except (KeyError, ValueError):
                continue
            spans.append(TextSpan(text=text, bbox=(x0, y0, x1, y1), size=max(0.0, y1 - y0)))
    return width, height, spans


class MuPage(PdfPage):
    def __init__(self, page, index: int):
        self._page = page
        self.index = index
        rect = page.rect
        self.width = float(rect.width)
        self.height = float(rect.height)

    def text_spans(self) -> list[TextSpan]:
        spans: list[TextSpan] = []
        data = self._page.get_text("dict")
        for block in data.get("blocks", []):
            if block.get("type") != 0:
                continue
            for line in block.get("lines", []):
                for span in line.get("spans", []):
                    text = (span.get("text") or "").strip()
                    if not text:
                        continue
                    bbox = tuple(float(v) for v in span["bbox"])
                    spans.append(TextSpan(text=text, bbox=bbox, size=float(span.get("size") or 0.0)))
        return spans

    def words(self) -> list[tuple[float, float, float, float, str]]:
        out = []
        for item in self._page.get_text("words"):
            x0, y0, x1, y1, text = item[:5]
            if str(text).strip():
                out.append((float(x0), float(y0), float(x1), float(y1), str(text)))
        return out

    def render_clip(self, clip: tuple[float, float, float, float], zoom: float) -> Image.Image:
        import pymupdf

        rect = pymupdf.Rect(*clip)
        matrix = pymupdf.Matrix(zoom, zoom)
        pix = self._page.get_pixmap(matrix=matrix, clip=rect, alpha=False)
        return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)


class MuDocument(PdfDocument):
    def __init__(self, path: Path):
        import pymupdf

        self._doc = pymupdf.open(path)

    def close(self) -> None:
        self._doc.close()

    @property
    def page_count(self) -> int:
        return self._doc.page_count

    def pages(self) -> list[PdfPage]:
        return [MuPage(self._doc[i], i) for i in range(self._doc.page_count)]


def open_document(path: str | Path) -> PdfDocument:
    pdf_path = Path(path)
    choice = pdf_preference()
    if choice == "poppler":
        logger.info("PDF-backend: Poppler")
        return PopplerDocument(pdf_path)
    if choice == "pymupdf":
        logger.info("PDF-backend: PyMuPDF")
        return MuDocument(pdf_path)
    try:
        doc = MuDocument(pdf_path)
        logger.info("PDF-backend: PyMuPDF")
        return doc
    except Exception as exc:
        logger.warning("PyMuPDF kunde inte användas (%s). Faller tillbaka på Poppler.", exc)
        return PopplerDocument(pdf_path)
