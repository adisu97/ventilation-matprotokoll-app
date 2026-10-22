#!/usr/bin/env bash
# Bakåtkompatibelt namn. Samma sak som android/install.sh.
exec bash "$(cd "$(dirname "$0")" && pwd)/install.sh" "$@"
