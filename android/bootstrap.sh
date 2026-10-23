#!/usr/bin/env bash
# En rad i Termux: curl -fsSL …/bootstrap.sh | bash
# Hämtar det publika programmet. Ingen inloggning, ingen token.
set -euo pipefail

if ! command -v pkg >/dev/null 2>&1 || [[ -z "${PREFIX:-}" ]]; then
  echo "Öppna Termux och kör skriptet där."
  echo "APK: https://github.com/adisu97/ventilation-matprotokoll-app/releases/latest/download/termux-arm64-v8a.apk"
  echo "F-Droid är bara ett valfritt alternativ och ska inte blandas med den här APK:en."
  exit 1
fi

REPO="${MATPROTOKOLL_REPO:-https://github.com/adisu97/ventilation-matprotokoll-app.git}"
RELEASE_BASE="${MATPROTOKOLL_RELEASE:-https://github.com/adisu97/ventilation-matprotokoll-app/releases/latest/download}"
DEST="${MATPROTOKOLL_HOME:-$HOME/matprotokoll}"

pkg update -y
pkg install -y git curl unzip proot-distro

here="$(cd "$(dirname "$0")" 2>/dev/null && pwd || true)"
if [[ -n "${here}" && -f "$here/install.sh" && -d "$here/../matprotokoll" ]]; then
  echo "Hittade programmet i ${here}/.."
  cd "$here/.."
  bash android/install.sh
  echo
  echo "Nästa gång: bash $(pwd)/android/start.sh"
  exit 0
fi

if [[ -f "$DEST/android/install.sh" ]]; then
  cd "$DEST"
  bash android/install.sh
  echo
  echo "Nästa gång: bash ${DEST}/android/start.sh"
  exit 0
fi

mkdir -p "$DEST"
fetch_ok=0
echo "Hämtar programmet …"
if git clone --depth 1 "$REPO" "$DEST" 2>/tmp/matprotokoll-git.err; then
  fetch_ok=1
else
  tmpzip="${TMPDIR:-/tmp}/matprotokoll-src.zip"
  if curl -fsSL -o "$tmpzip" "${RELEASE_BASE}/source.zip"; then
    unpack="${TMPDIR:-/tmp}/matprotokoll-unpack-$$"
    rm -rf "$unpack"
    mkdir -p "$unpack"
    unzip -o -q "$tmpzip" -d "$unpack"
    inner="$(find "$unpack" -maxdepth 3 -type d -name android 2>/dev/null | head -1)"
    if [[ -n "$inner" && -f "$inner/install.sh" ]]; then
      src="$(cd "$inner/.." && pwd)"
      cp -a "$src"/. "$DEST"/
      fetch_ok=1
    fi
  fi
fi

if [[ "$fetch_ok" -eq 0 ]]; then
  echo "Kunde inte hämta källkoden. Kontrollera nätet och adressen:"
  echo "  $REPO"
  echo "  ${RELEASE_BASE}/source.zip"
  exit 1
fi

cd "$DEST"
bash android/install.sh
echo
echo "Nästa gång räcker det med:"
echo "  bash ${DEST}/android/start.sh"
