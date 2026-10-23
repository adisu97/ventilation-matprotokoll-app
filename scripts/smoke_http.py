#!/usr/bin/env python3
"""Rök: starta inte servern — prata med en redan lyssnande Flask (Windows-exe eller python)."""

from __future__ import annotations

import argparse
import io
import sys
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8501")
    parser.add_argument("--pdf", required=True)
    parser.add_argument("--xlsx-out", required=True)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    pdf = Path(args.pdf)
    data = pdf.read_bytes()
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            opener.open(args.base + "/", timeout=5)
            break
        except Exception:
            time.sleep(1)
    else:
        print("servern svarade inte", file=sys.stderr)
        return 2

    boundary = "----matprotokoll"
    body = (
        (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="excel_name"\r\n\r\n'
            "smoke.xlsx\r\n"
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="quality"\r\n\r\n'
            "fast\r\n"
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="mode_0"\r\n\r\n'
            "sheet\r\n"
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="pdfs"; filename="{pdf.name}"\r\n'
            "Content-Type: application/pdf\r\n\r\n"
        ).encode("utf-8")
        + data
        + f"\r\n--{boundary}--\r\n".encode("utf-8")
    )
    req = urllib.request.Request(
        args.base + "/extract",
        data=body,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        opener.open(req, timeout=60)
    except urllib.error.HTTPError as exc:
        if exc.code not in {302, 303, 301}:
            print("extract", exc, file=sys.stderr)
            return 2

    page = ""
    t0 = time.time()
    while time.time() - t0 < args.timeout:
        page = opener.open(args.base + "/status", timeout=30).read().decode("utf-8", "replace")
        if "Förhandsgranska" in page:
            break
        if "Något gick fel" in page:
            print(page, file=sys.stderr)
            return 2
        time.sleep(2)
    else:
        print("timeout status", file=sys.stderr)
        return 2

    build = (
        b"excel_name=smoke.xlsx&nrows_0=1&s0r0c0=E125&s0r0c1=kso&s0r0c2=125"
        if "s0r0c0" not in page
        else None
    )
    # Skicka tillbaka de fält som finns i sidan.
    import re

    fields = re.findall(r'name="([^"]+)"[^>]*value="([^"]*)"', page)
    from urllib.parse import urlencode

    payload = {k: v.replace("&amp;", "&") for k, v in fields}
    payload.setdefault("excel_name", "smoke.xlsx")
    body2 = urlencode(payload).encode("utf-8")
    req2 = urllib.request.Request(
        args.base + "/build",
        data=body2,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        resp = opener.open(req2, timeout=60)
        blob = resp.read()
    except urllib.error.HTTPError as exc:
        blob = exc.read()
    if blob[:2] != b"PK":
        print("inte en xlsx", blob[:80], file=sys.stderr)
        return 2
    Path(args.xlsx_out).write_bytes(blob)
    print("xlsx", args.xlsx_out, len(blob))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
