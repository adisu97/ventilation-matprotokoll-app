#!/usr/bin/env bash
# Startar samma Flask-app som på datorn och öppnar webbläsaren.
set -euo pipefail

if ! command -v pkg >/dev/null 2>&1 || [[ -z "${PREFIX:-}" ]]; then
  echo "Kör det här i Termux."
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
DISTRO="${MATPROTOKOLL_DISTRO:-}"
if [[ -z "$DISTRO" && -f "$ROOT/.android-distro" ]]; then
  DISTRO="$(tr -d '[:space:]' < "$ROOT/.android-distro")"
fi
DISTRO="${DISTRO:-matprotokoll}"

if [[ ! -f "$ROOT/.venv/bin/activate" ]]; then
  echo "Programmet är inte installerat. Kör först EN gång:"
  echo "  bash android/install.sh"
  exit 1
fi

export MATPROTOKOLL_OCR="${MATPROTOKOLL_OCR:-rapidocr}"
export MATPROTOKOLL_PDF="${MATPROTOKOLL_PDF:-pymupdf}"
export MATPROTOKOLL_QUALITY="${MATPROTOKOLL_QUALITY:-fast}"
export MATPROTOKOLL_LOWMEM="${MATPROTOKOLL_LOWMEM:-1}"
export OMP_THREAD_LIMIT="${OMP_THREAD_LIMIT:-2}"
PORT="${PORT:-8501}"

echo "Öppna i webbläsaren: http://127.0.0.1:${PORT}"
echo "Använd http, inte https. Låt Termux vara igång."

( sleep 4
  if command -v termux-open-url >/dev/null 2>&1; then
    termux-open-url "http://127.0.0.1:${PORT}" || true
  else
    am start -a android.intent.action.VIEW -d "http://127.0.0.1:${PORT}" >/dev/null 2>&1 || true
  fi
) &

exec proot-distro login "$DISTRO" --shared-tmp --bind "${ROOT}:/opt/matprotokoll" -- bash -lc "
set -euo pipefail
cd /opt/matprotokoll
. .venv/bin/activate
export MATPROTOKOLL_OCR='${MATPROTOKOLL_OCR}'
export MATPROTOKOLL_PDF='${MATPROTOKOLL_PDF}'
export MATPROTOKOLL_QUALITY='${MATPROTOKOLL_QUALITY}'
export MATPROTOKOLL_LOWMEM='${MATPROTOKOLL_LOWMEM}'
export OMP_THREAD_LIMIT='${OMP_THREAD_LIMIT}'
export PORT='${PORT}'
exec python -m matprotokoll.webapp --host 127.0.0.1 --port '${PORT}' --no-browser
"
