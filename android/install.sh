#!/usr/bin/env bash
# Installerar samma program som på datorn, i Termux. Användaren behöver inte
# känna till Linux: skriptet sköter bakgrundsmiljön själv.
set -euo pipefail

if ! command -v pkg >/dev/null 2>&1 || [[ -z "${PREFIX:-}" ]]; then
  echo "Öppna Termux (APK från F-Droid eller GitHub, inte Play Butik) och kör skriptet där."
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f "$ROOT/requirements.txt" || ! -d "$ROOT/matprotokoll" ]]; then
  echo "Hittar inte programfilerna i ${ROOT}."
  echo "Packa upp hela ZIP-filen så att mapparna android/ och matprotokoll/ syns."
  exit 1
fi

DISTRO="${MATPROTOKOLL_DISTRO:-matprotokoll}"
echo "Installerar programmet i ${ROOT} …"

pkg update -y
pkg install -y proot-distro git curl unzip

ROOTFS="${PREFIX}/var/lib/proot-distro/installed-rootfs"
if [[ ! -d "${ROOTFS}/${DISTRO}" ]]; then
  echo "Förbereder körningen (första gången tar flera minuter) …"
  if proot-distro install --help 2>/dev/null | grep -q override-alias; then
    proot-distro install --override-alias "$DISTRO" ubuntu
  elif [[ -d "${ROOTFS}/ubuntu" ]]; then
    DISTRO=ubuntu
  else
    proot-distro install ubuntu
    DISTRO=ubuntu
  fi
fi

echo "$DISTRO" > "$ROOT/.android-distro"

proot-distro login "$DISTRO" --shared-tmp --bind "${ROOT}:/opt/matprotokoll" -- bash -lc '
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3 python3-venv python3-pip python3-dev \
  libgl1 libglib2.0-0 libgomp1 ca-certificates
cd /opt/matprotokoll
if [[ ! -d .venv ]]; then
  python3 -m venv .venv
fi
. .venv/bin/activate
python -m pip install -U pip
python -m pip install -r requirements.txt
python - <<PY
import flask, openpyxl, pymupdf, PIL, numpy, onnxruntime, cv2, rapidocr_onnxruntime
print("RapidOCR/onnxruntime, PyMuPDF, Pillow, openpyxl: ok")
print("pymupdf", pymupdf.__version__)
print("onnxruntime", onnxruntime.__version__)
PY
'

echo
echo "Klart. Starta med:"
echo "  bash android/start.sh"
echo "Öppna sedan http://127.0.0.1:8501 i telefonens webbläsare (http, inte https)."
