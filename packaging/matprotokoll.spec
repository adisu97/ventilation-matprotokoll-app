# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller onedir — Windows portabel ZIP utan installation."""

import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_all

sys.setrecursionlimit(sys.getrecursionlimit() * 5)

ROOT = Path(SPECPATH).resolve().parent
datas = [(str(ROOT / "templates" / "matprotokoll_grund.xlsx"), "templates")]
binaries = []
hiddenimports = ["pymupdf", "rapidocr_onnxruntime", "onnxruntime", "cv2"]

for pkg in ("rapidocr_onnxruntime", "onnxruntime", "cv2", "pymupdf", "PIL"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

a = Analysis(
    [str(ROOT / "app.py")],
    pathex=[str(ROOT)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["streamlit", "pandas", "pytesseract", "matplotlib", "tkinter"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="matprotokoll",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="matprotokoll",
)
