"""Sökvägar i källkod och i PyInstaller-onedir (Windows-ZIP)."""

from __future__ import annotations

import sys
from pathlib import Path


def is_frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def bundle_root() -> Path:
    if is_frozen():
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parent.parent


def exe_dir() -> Path:
    if is_frozen():
        return Path(sys.executable).resolve().parent
    return bundle_root()


def template_xlsx() -> Path:
    return bundle_root() / "templates" / "matprotokoll_grund.xlsx"
