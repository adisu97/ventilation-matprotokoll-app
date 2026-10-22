from matprotokoll.scans import map_rotated_xy, page_looks_like_scan, preprocess_scan
from PIL import Image


class _FakeInner:
    def get_drawings(self):
        return []

    def get_images(self):
        return [(1, 0)]


class _FakePage:
    def __init__(self):
        self._page = _FakeInner()

    def words(self):
        return []


def test_page_looks_like_scan_without_text_or_drawings():
    assert page_looks_like_scan(_FakePage()) is True


def test_map_rotated_xy_90_and_270():
    # Original (10, 0) on 40×20: 90° CCW → (0, 29); 270° CCW → (19, 10).
    x, y = map_rotated_xy(0, 29, 40, 20, 90)
    assert abs(x - 10) < 1e-6 and abs(y - 0) < 1e-6
    x, y = map_rotated_xy(19, 10, 40, 20, 270)
    assert abs(x - 10) < 1e-6 and abs(y - 0) < 1e-6


def test_preprocess_scan_shrinks_huge_gray():
    img = Image.new("RGB", (2000, 1000), "white")
    out = preprocess_scan(img, max_side=800)
    assert max(out.size) <= 800
    assert out.mode == "RGB"
