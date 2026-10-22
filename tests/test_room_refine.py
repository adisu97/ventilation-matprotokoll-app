from matprotokoll.models import Room
from matprotokoll.room_refine import refine_rooms_from_gaps


class _HitEngine:
    def recognize(self, image):
        return [("117", 0.95, (10.0, 10.0, 40.0, 24.0))]


class _Page:
    width = 3000
    height = 1600

    def render_clip(self, clip, zoom):
        from PIL import Image

        return Image.new("RGB", (80, 60), "white")


def test_gap_ocr_fills_117_between_115_and_118():
    rooms = [
        Room("115", "", 2200, 760, source="ocr"),
        Room("116", "", 1950, 646, source="ocr"),
        Room("118", "", 1790, 645, source="ocr"),
        Room("113", "", 2550, 765, source="ocr"),
        Room("112", "", 2460, 647, source="ocr"),
        Room("121", "", 2550, 1110, source="ocr"),
        Room("120", "", 2510, 1220, source="ocr"),
    ]
    out = refine_rooms_from_gaps(_Page(), _HitEngine(), rooms)
    assert any(r.number == "117" for r in out)
