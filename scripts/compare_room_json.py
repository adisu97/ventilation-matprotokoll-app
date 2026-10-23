#!/usr/bin/env python3
"""Jämför rumsnummer mellan två extract-JSON (t.ex. Linux vs Windows-exe)."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def numbers(payload: dict) -> set[str]:
    out = set()
    for room in payload.get("rooms") or []:
        nr = str(room.get("nr") or "").strip().lower()
        if nr:
            out.add(nr)
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("--min-rooms", type=int, default=8)
    parser.add_argument("--require-engine", default="RapidOCR")
    args = parser.parse_args()
    a = json.loads(Path(args.left).read_text(encoding="utf-8"))
    b = json.loads(Path(args.right).read_text(encoding="utf-8"))
    for payload, name in ((a, args.left), (b, args.right)):
        engine = payload.get("engine") or ""
        if args.require_engine and args.require_engine.lower() not in engine.lower():
            print(f"{name}: fel OCR-motor {engine!r}, förväntade {args.require_engine}", file=sys.stderr)
            return 2
        if payload.get("n_rooms", 0) < args.min_rooms:
            print(f"{name}: för få rum ({payload.get('n_rooms')})", file=sys.stderr)
            return 2
    na, nb = numbers(a), numbers(b)
    only_a, only_b = sorted(na - nb), sorted(nb - na)
    print(f"{Path(args.left).name}: {len(na)} rum, motor {a.get('engine')}")
    print(f"{Path(args.right).name}: {len(nb)} rum, motor {b.get('engine')}")
    if only_a or only_b:
        print("bara vänster:", ", ".join(only_a) or "—")
        print("bara höger:", ", ".join(only_b) or "—")
        return 1
    print("samma rumsnummer")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
