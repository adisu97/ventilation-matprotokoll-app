"""Kända don-typer och Excel-konstanter."""

from __future__ import annotations

# Tilluft (kolumn H). Case-insensitive vid matchning; lagras lowercase.
TILLUFT_NAMES = ("dykh", "clik", "kts", "eagle", "pelican", "hfk")

# Frånluft (kolumn B). HUK / HU räknas som samma familj.
FRANLUFT_NAMES = ("kso", "dykh", "clik", "alg", "hfk", "pra", "huk", "hu")

# Produkter som bara hör till ena sidan.
TILLUFT_ONLY = frozenset({"kts", "eagle", "pelican"})
FRANLUFT_ONLY = frozenset({"kso", "alg", "pra", "huk", "hu"})
AMBIGUOUS = frozenset({"dykh", "clik", "hfk"})

ALL_PRODUCTS = tuple(dict.fromkeys((*TILLUFT_NAMES, *FRANLUFT_NAMES)))

# Vanliga don-diametrar (mm) – används för att välja storlek ur OCR-sträng.
STANDARD_SIZES = (80, 100, 125, 160, 200, 250, 315, 355, 400, 450, 500, 630)

# OCR-förväxlingar i produktnamn (längre först).
OCR_PRODUCT_FIXES: tuple[tuple[str, str], ...] = (
    ("PELICAN", "PELICAN"),
    ("PEL1CAN", "PELICAN"),
    ("PEL1CAN", "PELICAN"),
    ("PELT CAN", "PELICAN"),
    ("EAGLE", "EAGLE"),
    ("EAG1E", "EAGLE"),
    ("EAGIE", "EAGLE"),
    ("EACLE", "EAGLE"),
    ("DYKH", "DYKH"),
    ("DYK H", "DYKH"),
    ("CLIK", "CLIK"),
    ("CL1K", "CLIK"),
    ("KTS-360", "KTS-360"),
    ("KT5-360", "KTS-360"),
    ("KTS", "KTS"),
    ("KT5", "KTS"),
    ("KLS", "KTS"),
    ("K1S", "KTS"),
    ("KSO", "KSO"),
    ("KS0", "KSO"),
    ("K5O", "KSO"),
    ("HFK", "HFK"),
    ("ALG", "ALG"),
    ("A1G", "ALG"),
    ("PRA", "PRA"),
    ("HUK", "HUK"),
)

# Ord i ritningstext som inte är rumsnamn.
ROOM_NAME_STOPWORDS = frozenset(
    {
        "kerrosala",
        "rrosala",
        "vss",
        "platser",
        "pers",
        "hlö",
        "hlo",
        "m2",
        "ivl",
        "aukko",
        "ritilä",
        "ritila",
        "handledare",
        "pos",
    }
)

HEADER_FILL_EXHAUST = "FFFFFFCC"
HEADER_FILL_SUPPLY = "FFFFD9D9"

COVER_SHEET_NAME = "Koneet Maskiner"
PROTO_SHEET_CANDIDATES = ("Sheet1", "Sheet2", "Sheet3")

EXCEL_INVALID_SHEET_CHARS = set(r"[]:*?/\\")
EXCEL_MAX_SHEET_LEN = 31
