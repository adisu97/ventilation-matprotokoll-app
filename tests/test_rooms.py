from pathlib import Path

import pymupdf

from matprotokoll.room_eval import score_room_numbers
from matprotokoll.rooms import extract_name_labels, extract_rooms, extract_rooms_from_tokens


def test_extract_rooms_from_synthetic_pdf(tmp_path: Path):
    doc = pymupdf.open()
    page = doc.new_page(width=300, height=200)
    page.insert_text((40, 40), "WC", fontsize=11)
    page.insert_text((40, 55), "E125", fontsize=11)
    page.insert_text((200, 40), "AULA", fontsize=11)
    page.insert_text((200, 55), "E102", fontsize=11)
    page.insert_text((40, 160), "E11", fontsize=18)
    path = tmp_path / "rooms.pdf"
    doc.save(path)
    doc.close()
    opened = pymupdf.open(path)
    rooms = extract_rooms(opened[0])
    opened.close()
    labels = {r.label() for r in rooms}
    assert any(l.lower().startswith("e125") for l in labels)
    assert any(l.lower().startswith("e102") for l in labels)
    assert not any(r.number == "E11" for r in rooms)


def test_rooms_from_ocr_tokens_when_pdf_has_no_text():
    tokens = [
        ("E125", 40.0, 55.0, 0.9),
        ("WC", 40.0, 40.0, 0.9),
        ("E102 aula", 200.0, 50.0, 0.85),
        ("E11", 10.0, 160.0, 0.9),
        ("KSO-125-C", 80.0, 70.0, 0.9),
        ("142", 500.0, 80.0, 0.99),
        ("P125", 90.0, 72.0, 0.9),
        ("125", 95.0, 75.0, 0.99),
        ("160", 400.0, 400.0, 0.99),
        ("301", 3200.0, 2300.0, 0.99),
    ]
    rooms = extract_rooms_from_tokens(tokens, page_width=3370.0, page_height=2385.0)
    numbers = {r.number for r in rooms}
    labels = {r.label().lower() for r in rooms}
    assert any(label.startswith("e125") and "wc" in label for label in labels)
    assert any(label.startswith("e102") and "aula" in label for label in labels)
    assert "142" in numbers
    assert "E11" not in numbers
    assert "P125" not in numbers
    assert "125" not in numbers
    assert "160" not in numbers
    assert "301" not in numbers


def test_ocr_rooms_accept_letter_suffix_and_125_as_room():
    tokens = [
        ("101", 1500.0, 630.0, 0.99),
        ("102", 1410.0, 1030.0, 0.99),
        ("111b", 2870.0, 1190.0, 0.9),
        ("111", 2720.0, 1210.0, 0.85),
        ("a", 2735.0, 1210.0, 0.8),
        ("112", 2460.0, 650.0, 0.99),
        ("11.4", 2200.0, 650.0, 0.9),
        ("123", 2220.0, 1112.0, 0.99),
        ("125", 2043.0, 1116.0, 0.99),
        ("127", 1712.0, 1111.0, 0.99),
        ("KSO-125", 2065.0, 1069.0, 0.95),
        ("186", 100.0, 100.0, 0.7),
        ("Terrass", 200.0, 400.0, 0.9),
        ("TS", 800.0, 780.0, 0.99),
        ("135", 820.0, 780.0, 0.6),
    ]
    rooms = extract_rooms_from_tokens(tokens, page_width=3370.0, page_height=1684.0)
    numbers = {r.number.lower() for r in rooms}
    assert "111a" in numbers
    assert "111b" in numbers
    assert "114" in numbers
    assert "125" in numbers
    assert "186" not in numbers
    assert "112" in numbers
    names = extract_name_labels(tokens)
    kinds = {n[0] for n in names}
    assert "terrass" in kinds
    assert "ts" in kinds


def test_legend_hall_is_not_a_room_name():
    from matprotokoll.rooms import _name_from_text

    assert _name_from_text("BRANDPOST + SLACKARE 6 kg I HALL") is None
    assert _name_from_text("hall") == "hall"
    assert _name_from_text("13SOVRUM") == "sovrum"


def test_short_scan_rooms_need_name_or_area():
    tokens = [
        ("13", 100.0, 100.0, 0.9),
        ("SOVRUM", 110.0, 118.0, 0.9),
        ("36,5 m²", 120.0, 130.0, 0.9),
        ("16", 400.0, 200.0, 0.9),
        ("FÖRRÅD", 410.0, 215.0, 0.9),
        ("ID21", 50.0, 700.0, 0.9),
        ("21", 55.0, 700.0, 0.9),
        ("9", 800.0, 800.0, 0.9),
        ("5", 200.0, 400.0, 0.9),
        ("KÖKVRÅ", 210.0, 415.0, 0.85),
    ]
    rooms = extract_rooms_from_tokens(tokens, page_width=1000.0, page_height=1000.0)
    numbers = {r.number for r in rooms}
    assert "13" in numbers
    assert "16" in numbers
    assert "5" in numbers
    assert "21" not in numbers
    assert "9" not in numbers
    assert any(r.number == "13" and "sovrum" in r.name for r in rooms)


def test_stuck_short_name_and_split_sixteen():
    tokens = [
        ("13SOVRUM", 100.0, 100.0, 0.9),
        ("6", 400.0, 200.0, 0.9),
        ("16", 405.0, 202.0, 0.95),
        ("FÖRRÅD", 410.0, 215.0, 0.9),
        ("8,8 m²", 412.0, 220.0, 0.9),
        ("32", 800.0, 800.0, 0.9),
        ("32,9 m²", 805.0, 802.0, 0.9),
        ("GRUPPRUM", 810.0, 820.0, 0.9),
    ]
    rooms = extract_rooms_from_tokens(tokens, page_width=1000.0, page_height=1000.0)
    numbers = {r.number for r in rooms}
    assert "13" in numbers
    assert "16" in numbers
    assert "6" not in numbers
    assert "32" not in numbers


def test_nearby_short_ocr_duplicates_collapse():
    tokens = [
        ("13", 100.0, 100.0, 0.9),
        ("03", 102.0, 101.0, 0.8),
        ("SOVRUM", 110.0, 118.0, 0.9),
        ("2", 500.0, 500.0, 0.95),
        ("20", 502.0, 501.0, 0.9),
        ("GRUPPRUM", 510.0, 515.0, 0.9),
        ("16", 300.0, 300.0, 0.95),
        ("3", 310.0, 305.0, 0.8),
        ("FÖRRÅD", 320.0, 318.0, 0.9),
    ]
    rooms = extract_rooms_from_tokens(tokens, page_width=1000.0, page_height=1000.0)
    numbers = {r.number for r in rooms}
    assert "13" in numbers
    assert "03" not in numbers
    assert "2" in numbers
    assert "20" not in numbers
    assert "16" in numbers
    assert "3" not in numbers


def test_one_near_sixteen_same_name_dropped():
    tokens = [
        ("16", 971.0, 1738.0, 0.99),
        ("FÖRRÅD", 985.0, 1727.0, 0.99),
        ("1", 994.0, 1800.0, 0.7),
        ("13", 1160.0, 1587.0, 0.99),
        ("SOVRUM", 1173.0, 1576.0, 0.99),
    ]
    rooms = extract_rooms_from_tokens(tokens, page_width=2592.0, page_height=3595.0)
    numbers = {r.number for r in rooms}
    assert "16" in numbers
    assert "13" in numbers
    assert "1" not in numbers


def test_far_apart_short_threes_keep_named_room():
    tokens = [
        ("16", 300.0, 300.0, 0.95),
        ("3", 310.0, 305.0, 0.8),
        ("FÖRRÅD", 320.0, 318.0, 0.9),
        ("3", 800.0, 800.0, 0.99),
        ("GRUPPRUM", 810.0, 815.0, 0.9),
        ("10", 500.0, 500.0, 0.9),
        ("SAND/VATTEN", 520.0, 575.0, 0.9),
        ("11r", 200.0, 200.0, 0.8),
        ("SOVRUM", 210.0, 220.0, 0.9),
    ]
    rooms = extract_rooms_from_tokens(tokens, page_width=1000.0, page_height=1000.0)
    numbers = {r.number for r in rooms}
    assert "16" in numbers
    assert "3" in numbers
    assert any(r.number == "3" and "grupprum" in r.name for r in rooms)
    assert not any(r.number == "3" and "förråd" in r.name for r in rooms)
    assert any(r.number == "10" and "sand" in r.name for r in rooms)
    assert "11" in numbers


def test_score_room_numbers_against_facit():
    expected = ["101", "111a", "125", "142"]
    score = score_room_numbers(["101", "111A", "186"], expected)
    assert score["n_hits"] == 2
    assert "125" in score["misses"] and "142" in score["misses"]
    assert "186" in score["false_positives"]
    assert score["recall"] == 0.5
