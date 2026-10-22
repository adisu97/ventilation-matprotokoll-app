from pathlib import Path

from matprotokoll.extract_cli import extract_payload
from matprotokoll.models import ProtocolRow, Room, SheetResult
from matprotokoll.paths import bundle_root, is_frozen, template_xlsx
from matprotokoll.webapp import lan_ips


def test_not_frozen_in_tests():
    assert is_frozen() is False
    assert template_xlsx().is_file()
    assert (bundle_root() / "matprotokoll").is_dir()


def test_extract_payload_uses_rapidocr(monkeypatch, tmp_path):
    def fake_extract(path, **kwargs):
        return SheetResult(
            pdf_name=Path(path).name,
            sheet_name="x",
            rooms=[Room(number="101", name="", x=1, y=1, size_pt=8)],
            rows=[ProtocolRow(room="101")],
            warnings=[],
        )

    class Dummy:
        name = "RapidOCR"

    monkeypatch.setattr("matprotokoll.extract_cli.extract_sheet_from_pdf", fake_extract)
    monkeypatch.setattr("matprotokoll.extract_cli.load_ocr_engine", lambda: Dummy())
    payload = extract_payload(tmp_path / "a.pdf", quality="fast")
    assert payload["engine"] == "RapidOCR"
    assert payload["rooms"][0]["nr"] == "101"


def test_lan_ips_returns_list():
    assert isinstance(lan_ips(), list)
