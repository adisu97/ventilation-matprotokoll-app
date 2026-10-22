from matprotokoll.associate import pair_rows_for_room
from matprotokoll.labels import classify_kind, normalize_ocr_text, parse_airflow_token, parse_label
from matprotokoll.models import Vent


def test_kso_125_c():
    v = parse_label("KSO-125-C")
    assert v is not None
    assert v.name == "kso"
    assert v.kind == "exhaust"
    assert v.size == 125 or v.size == "125"
    assert v.qty == 1


def test_kso_space_variant():
    v = parse_label("KSO 100-1-C")
    assert v is not None
    assert v.name == "kso"
    assert str(v.size) == "100"


def test_kts_hyphen():
    v = parse_label("KTS-100-1-C")
    assert v is not None
    assert v.name == "kts"
    assert v.kind == "supply"
    assert str(v.size) == "100"


def test_kts_160_0_c():
    v = parse_label("KTS-160-0-C")
    assert v is not None
    assert v.name == "kts"
    assert str(v.size) == "160"


def test_qty_two_kts():
    v = parse_label("2xKTS-125-0-C")
    assert v is not None
    assert v.qty == 2
    assert v.name == "kts"
    assert str(v.size) == "125"


def test_eagle_cra():
    v = parse_label("2xEAGLE CRa 200-600")
    assert v is not None
    assert v.name == "eagle"
    assert v.kind == "supply"
    assert v.qty == 2
    assert v.size == "200-600"


def test_five_eagle_accessory():
    v = parse_label("5xEAGLE CRa 400-600-RO+ALSd 315-400")
    assert v is not None
    assert v.name == "eagle"
    assert v.qty == 5
    assert v.size == "400-600"


def test_pelican_and_alg():
    p = parse_label("PELICAN 125")
    assert p is not None and p.name == "pelican" and p.kind == "supply"
    a = parse_label("ALG 200")
    assert a is not None and a.name == "alg" and a.kind == "exhaust" and str(a.size) == "200"


def test_ocr_zero_for_o():
    v = parse_label("KS0-160-C")
    assert v is not None
    assert v.name == "kso"
    assert str(v.size) == "160"


def test_ocr_kls_is_kts():
    v = parse_label("KLS-100-1-C")
    assert v is not None
    assert v.name == "kts"
    assert v.kind == "supply"


def test_dykh_clik_lowercase_stored():
    d = parse_label("dykh 160")
    assert d is not None and d.name == "dykh" and d.kind == "supply"
    c = parse_label("CLIK 125")
    assert c is not None and c.name == "clik" and str(c.size) == "125"


def test_hfk_multi_size():
    v = parse_label("HFK 2*315")
    assert v is not None
    assert v.name == "hfk"
    assert v.size == "2*315"


def test_huk_hu():
    v = parse_label("HUK 250")
    assert v is not None and v.name == "hu" and v.kind == "exhaust"
    v2 = parse_label("HU 250")
    assert v2 is not None and v2.name == "hu"


def test_airflow_sign_overrides_kind():
    v = parse_label("DYKH-160")
    assert v is not None
    v.airflow = 40
    # parse with sign in string
    signed = parse_label("KSO-125-C -20")
    assert signed is not None
    assert signed.kind == "exhaust"
    assert signed.airflow == 20
    plus = parse_label("KTS-160-0-C +40")
    assert plus is not None and plus.kind == "supply" and plus.airflow == 40


def test_parse_airflow_token():
    assert parse_airflow_token("+56") == (56.0, True)
    assert parse_airflow_token("-20") == (-20.0, True)
    assert parse_airflow_token("20 l/s") == (20.0, False)
    assert parse_airflow_token("abc") is None
    assert parse_airflow_token("125") is None
    assert parse_airflow_token("1") is None
    assert parse_airflow_token("20") == (20.0, False)


def test_ignore_duct_and_noise():
    assert parse_label("SP1-Ø315") is None
    assert parse_label("SP1-0315") is None
    assert parse_label("CIRCO 125 DOUBLE") is None
    assert parse_label("YLOS T306") is None
    assert parse_label("AULA E102") is None
    assert parse_label("random text") is None


def test_kts_360_variant():
    v = parse_label("KTS-360 100")
    assert v is not None
    assert v.name == "kts-360"
    assert v.kind == "supply"
    assert str(v.size) == "100"


def test_normalize_ocr():
    assert "KSO" in normalize_ocr_text("KS0-125-C")
    assert "KTS" in normalize_ocr_text("KT5-160")


def test_classify_kind():
    assert classify_kind("kso") == "exhaust"
    assert classify_kind("kts") == "supply"
    assert classify_kind("eagle") == "supply"
    assert classify_kind("pelican") == "supply"
    assert classify_kind("kso", airflow=20, signed=True) == "exhaust"
    assert classify_kind("hfk", airflow=-10, signed=True) == "exhaust"
    assert classify_kind("hfk", airflow=10, signed=True) == "supply"


def test_kso_stays_exhaust_even_if_plus_sign():
    v = parse_label("KSO-125-C +20")
    assert v is not None
    assert v.kind == "exhaust"
    assert v.airflow == 20


def test_bare_hu_ignored_but_hu_with_size_ok():
    assert parse_label("HU") is None
    assert parse_label("THE HUGE ROOM") is None
    v = parse_label("HU 250")
    assert v is not None and v.name == "hu"


def test_four_alg_no_size():
    v = parse_label("4xALG")
    assert v is not None
    assert v.name == "alg"
    assert v.qty == 4
    assert v.size is None
