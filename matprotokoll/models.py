from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class Vent:
    """Ett don som hittats på ritningen (OCR eller PDF-text)."""

    name: str
    kind: str  # "exhaust" | "supply"
    size: str | None = None
    airflow: float | None = None
    qty: int = 1
    x: float = 0.0
    y: float = 0.0
    raw: str = ""
    confidence: float = 1.0
    source: str = "ocr"

    def canonical_name(self) -> str:
        return (self.name or "").strip().lower()


@dataclass
class Room:
    number: str
    name: str
    x: float
    y: float
    size_pt: float = 0.0
    uncertainty: str = "säker"
    source: str = "pdf-text"

    def label(self) -> str:
        num = (self.number or "").strip()
        namn = (self.name or "").strip()
        if num and namn:
            return f"{num} {namn}"
        return num or namn


@dataclass
class ProtocolRow:
    room: str = ""
    exhaust_name: str = ""
    exhaust_size: str | int | float | None = None
    exhaust_behov: float | int | None = None
    supply_name: str = ""
    supply_size: str | int | float | None = None
    supply_behov: float | int | None = None
    note: str = ""

    def to_preview_dict(self) -> dict[str, Any]:
        return {
            "Utrymme": self.room,
            "Frånluftsdon": self.exhaust_name,
            "Frånluft storlek": _empty_if_none(self.exhaust_size),
            "Frånluft behov (l/s)": _empty_if_none(self.exhaust_behov),
            "Tilluftsdon": self.supply_name,
            "Tilluft storlek": _empty_if_none(self.supply_size),
            "Tilluft behov (l/s)": _empty_if_none(self.supply_behov),
            "Kommentar": self.note,
        }

    @classmethod
    def from_preview_dict(cls, data: dict[str, Any]) -> ProtocolRow:
        return cls(
            room=_as_str(data.get("Utrymme")),
            exhaust_name=_as_str(data.get("Frånluftsdon")).lower(),
            exhaust_size=_size_or_none(data.get("Frånluft storlek")),
            exhaust_behov=_num_or_none(data.get("Frånluft behov (l/s)")),
            supply_name=_as_str(data.get("Tilluftsdon")).lower(),
            supply_size=_size_or_none(data.get("Tilluft storlek")),
            supply_behov=_num_or_none(data.get("Tilluft behov (l/s)")),
            note=_as_str(data.get("Kommentar")),
        )


def _empty_if_none(value: Any) -> Any:
    if value is None:
        return ""
    return value


def _as_str(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _num_or_none(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        n = float(value)
    except (TypeError, ValueError):
        return None
    if n == int(n):
        return int(n)
    return n


def _size_or_none(value: Any) -> str | int | None:
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if float(value) == int(value):
            return int(value)
        return value
    text = str(value).strip()
    if not text:
        return None
    if text.isdigit():
        return int(text)
    return text


@dataclass
class SheetResult:
    pdf_name: str
    sheet_name: str
    rows: list[ProtocolRow] = field(default_factory=list)
    rooms: list[Room] = field(default_factory=list)
    vents: list[Vent] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    new_workbook: bool = False
