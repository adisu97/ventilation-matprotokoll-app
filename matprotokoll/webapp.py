"""Ett webbgränssnitt (Flask) för dator, Android och den portabla Windows-ZIP:en."""

from __future__ import annotations

import argparse
import html
import json
import logging
import os
import socket
import sys
import threading
import uuid
import webbrowser
from pathlib import Path

from flask import Flask, redirect, request, send_file, session, url_for
from werkzeug.utils import secure_filename

from matprotokoll.models import SheetResult
from matprotokoll.ocr import load_ocr_engine
from matprotokoll.pipeline import build_workbooks, extract_sheet_from_pdf, rows_from_preview
from matprotokoll.preview import PREVIEW_COLUMNS
from matprotokoll.settings import ocr_preference, pdf_preference, quality_mode, tile_px, zoom
from matprotokoll.sheet_names import ensure_workbook_filename

logger = logging.getLogger(__name__)

STORE = Path.home() / ".matprotokoll-sessions"
_LOCK = threading.Lock()


def create_app() -> Flask:
    app = Flask(__name__)
    app.secret_key = "matprotokoll-lokal"
    app.config["MAX_CONTENT_LENGTH"] = 80 * 1024 * 1024

    @app.get("/")
    def index():
        return _page(_upload_form())

    @app.post("/extract")
    def extract():
        excel_name = (request.form.get("excel_name") or "").strip()
        quality = (request.form.get("quality") or "").strip().lower()
        if quality in {"fast", "accurate"}:
            os.environ["MATPROTOKOLL_QUALITY"] = quality
        uploads = [f for f in request.files.getlist("pdfs") if f and f.filename]
        if not excel_name:
            return _page(_upload_form(error="Ange Excel-filens namn först.")), 400
        if not uploads:
            return _page(_upload_form(excel_name=excel_name, error="Ladda upp minst en PDF.")), 400

        sid = uuid.uuid4().hex
        session["sid"] = sid
        folder = _folder(sid)
        folder.mkdir(parents=True, exist_ok=True)
        pdfs: list[dict] = []
        for i, upload in enumerate(uploads):
            raw_name = Path(upload.filename or f"ritning-{i}.pdf").name
            safe = secure_filename(raw_name) or f"ritning-{i}.pdf"
            if not safe.lower().endswith(".pdf"):
                safe += ".pdf"
            path = folder / f"{i:02d}-{safe}"
            upload.save(path)
            mode = request.form.get(f"mode_{i}", "sheet")
            pdfs.append(
                {
                    "path": str(path),
                    "pdf_name": raw_name,
                    "new_workbook": mode == "file",
                }
            )
        existing = request.files.get("existing")
        existing_path = None
        if existing and existing.filename:
            existing_path = folder / "existing.xlsx"
            existing.save(existing_path)
        state = {
            "status": "running",
            "progress": 0.0,
            "message": "Startar…",
            "error": "",
            "excel_name": ensure_workbook_filename(excel_name),
            "existing": str(existing_path) if existing_path else "",
            "sheets": [],
            "engine": "",
        }
        _write_state(sid, state)
        thread = threading.Thread(target=_run_extract, args=(sid, pdfs), daemon=True)
        thread.start()
        return redirect(url_for("status"))

    @app.get("/status")
    def status():
        sid = session.get("sid")
        state = _read_state(sid) if sid else None
        if not state:
            return redirect(url_for("index"))
        if state.get("status") == "running":
            pct = int(float(state.get("progress") or 0) * 100)
            body = (
                "<h1>Läser ritningen</h1>"
                f"<p>{html.escape(state.get('message') or '')}</p>"
                f"<progress max='100' value='{pct}'></progress>"
                "<p class='muted'>Stäng inte fliken. En stor ritning kan ta flera minuter på telefonen.</p>"
                "<meta http-equiv='refresh' content='2'>"
            )
            return _page(body)
        if state.get("status") == "error":
            return _page(
                "<h1>Något gick fel</h1>"
                f"<p class='error'>{html.escape(state.get('error') or 'Okänt fel')}</p>"
                "<p><a href='/'>Börja om</a></p>"
            )
        return _page(_preview_form(state))

    @app.post("/build")
    def build():
        sid = session.get("sid")
        state = _read_state(sid) if sid else None
        if not state or state.get("status") != "done":
            return redirect(url_for("index"))
        excel_name = ensure_workbook_filename(request.form.get("excel_name") or state["excel_name"])
        results: list[SheetResult] = []
        for si, sheet in enumerate(state["sheets"]):
            records = _records_from_form(si)
            result = SheetResult(
                pdf_name=sheet["pdf_name"],
                sheet_name=sheet["sheet_name"],
                rows=rows_from_preview(records),
                new_workbook=bool(sheet.get("new_workbook")),
            )
            results.append(result)
        existing = state.get("existing") or None
        existing_bytes = Path(existing).read_bytes() if existing and Path(existing).is_file() else None
        files = build_workbooks(results, existing_xlsx=existing_bytes)
        base = files.pop("_base", None)
        folder = _folder(sid)
        if base is not None and not files:
            out = folder / excel_name
            out.write_bytes(base)
            download_name = excel_name
        elif base is None and len(files) == 1:
            fname, data = next(iter(files.items()))
            download_name = fname if str(fname).lower().endswith(".xlsx") else excel_name
            out = folder / Path(download_name).name
            out.write_bytes(data)
        else:
            import io
            from zipfile import ZipFile

            buf = io.BytesIO()
            with ZipFile(buf, "w") as zf:
                if base is not None:
                    zf.writestr(excel_name, base)
                for fname, data in files.items():
                    zf.writestr(fname, data)
            download_name = Path(excel_name).stem + ".zip"
            out = folder / download_name
            out.write_bytes(buf.getvalue())
        state["download_path"] = str(out)
        state["download_name"] = download_name
        _write_state(sid, state)
        return redirect(url_for("download"))

    @app.get("/download")
    def download():
        sid = session.get("sid")
        state = _read_state(sid) if sid else None
        if not state or not state.get("download_path"):
            return redirect(url_for("index"))
        path = Path(state["download_path"])
        return send_file(
            path,
            as_attachment=True,
            download_name=state.get("download_name") or path.name,
        )

    @app.errorhandler(413)
    def too_large(_err):
        return _page("<h1>Filen är för stor</h1><p>Gränsen är 80 MB.</p>"), 413

    return app


def _run_extract(sid: str, pdfs: list[dict]) -> None:
    try:
        engine = load_ocr_engine()
        state = _read_state(sid)
        state["engine"] = getattr(engine, "name", "")
        _write_state(sid, state)
        sheets = []
        n = len(pdfs)
        for i, item in enumerate(pdfs):

            def _cb(frac: float, msg: str, idx=i) -> None:
                cur = _read_state(sid)
                cur["progress"] = (idx + frac) / n
                cur["message"] = msg
                _write_state(sid, cur)

            existing_names = [s["sheet_name"] for s in sheets]
            result = extract_sheet_from_pdf(
                item["path"],
                pdf_name=item["pdf_name"],
                engine=engine,
                progress=_cb,
                existing_sheet_names=existing_names,
            )
            result.new_workbook = bool(item["new_workbook"])
            sheets.append(
                {
                    "pdf_name": result.pdf_name,
                    "sheet_name": result.sheet_name,
                    "new_workbook": result.new_workbook,
                    "warnings": result.warnings,
                    "n_vents": len(result.vents),
                    "n_rooms": len(result.rooms),
                    "rows": [row.to_preview_dict() for row in result.rows],
                }
            )
        state = _read_state(sid)
        state["status"] = "done"
        state["progress"] = 1.0
        state["message"] = "Klar"
        state["sheets"] = sheets
        _write_state(sid, state)
    except Exception as exc:
        logger.exception("Extraktion misslyckades")
        state = _read_state(sid)
        state["status"] = "error"
        state["error"] = str(exc)
        _write_state(sid, state)


def _records_from_form(sheet_index: int) -> list[dict]:
    count = int(request.form.get(f"nrows_{sheet_index}", "0") or 0)
    records = []
    for ri in range(count):
        record = {}
        empty = True
        for ci, column in enumerate(PREVIEW_COLUMNS):
            value = request.form.get(f"s{sheet_index}r{ri}c{ci}", "")
            if isinstance(value, str):
                value = value.strip()
            record[column] = value
            if value:
                empty = False
        if not empty:
            records.append(record)
    return records


def _folder(sid: str) -> Path:
    return STORE / sid


def _write_state(sid: str, state: dict) -> None:
    folder = _folder(sid)
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / "state.json"
    text = json.dumps(state, ensure_ascii=False)
    with _LOCK:
        path.write_text(text, encoding="utf-8")


def _read_state(sid: str | None) -> dict:
    if not sid:
        return {}
    path = _folder(sid) / "state.json"
    if not path.is_file():
        return {}
    with _LOCK:
        return json.loads(path.read_text(encoding="utf-8"))


def _upload_form(excel_name: str = "matprotokoll.xlsx", error: str = "") -> str:
    err = f"<p class='error'>{html.escape(error)}</p>" if error else ""
    fast = quality_mode() == "fast"
    acc_sel = "" if fast else " selected"
    fast_sel = " selected" if fast else ""
    return f"""
    <h1>Mätprotokoll för ventilation</h1>
    <p>Namnge Excel-filen, ladda upp ritningar och granska raderna innan du sparar.</p>
    <p class='muted'>OCR: {html.escape(ocr_preference())} · PDF: {html.escape(pdf_preference())}
    · zoom {zoom():g} · ruta {tile_px()} px</p>
    {err}
    <form method="post" action="/extract" enctype="multipart/form-data">
      <p><label>Rumssökning
        <select name="quality">
          <option value="accurate"{acc_sel}>Noggrann (samma RapidOCR, fler rutor)</option>
          <option value="fast"{fast_sel}>Snabb (samma RapidOCR, färre rutor)</option>
        </select>
      </label></p>
      <h2>1. Namn på Excel-fil</h2>
      <label>Filnamn
        <input type="text" name="excel_name" required value="{html.escape(excel_name)}">
      </label>
      <label>Befintlig Excel att utöka (valfritt)
        <input type="file" name="existing" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet">
      </label>
      <h2>2. Ladda upp ritningar</h2>
      <label>PDF-filer
        <input id="pdfs" type="file" name="pdfs" accept=".pdf,application/pdf" multiple required>
      </label>
      <h2>3. Per ritning</h2>
      <p class="muted">Varje PDF blir ett eget blad, namngivet efter filnamnet.</p>
      <div id="modes"></div>
      <button type="submit">Extrahera don och rum</button>
    </form>
    <script>
    const input = document.getElementById('pdfs');
    const box = document.getElementById('modes');
    function renderModes() {{
      box.innerHTML = '';
      Array.from(input.files || []).forEach((file, i) => {{
        const row = document.createElement('fieldset');
        row.innerHTML = '<legend>' + file.name.replace(/</g,'') + '</legend>'
          + '<label><input type="radio" name="mode_' + i + '" value="sheet" checked> Nytt blad i aktuell arbetsbok</label>'
          + '<label><input type="radio" name="mode_' + i + '" value="file"> Ny Excel-fil</label>';
        box.appendChild(row);
      }});
    }}
    input.addEventListener('change', renderModes);
    </script>
    """


def _preview_form(state: dict) -> str:
    parts = [
        "<h1>4. Förhandsgranska och redigera</h1>",
        f"<p class='muted'>OCR-motor: {html.escape(state.get('engine') or '')}. "
        "Extra don i samma rum har tom kolumn Utrymme. Tomma rader sparas inte.</p>",
        "<form method='post' action='/build'>",
        "<label>Excel-fil <input name='excel_name' required value='"
        + html.escape(state.get("excel_name") or "matprotokoll.xlsx")
        + "'></label>",
    ]
    for si, sheet in enumerate(state.get("sheets") or []):
        target = "ny Excel-fil" if sheet.get("new_workbook") else "blad i arbetsboken"
        parts.append(f"<h2>{html.escape(sheet.get('sheet_name') or '')}</h2>")
        parts.append(
            "<p class='muted'>"
            + html.escape(
                f"{sheet.get('pdf_name')} → {target}. {sheet.get('n_vents')} don, {sheet.get('n_rooms')} rum."
            )
            + "</p>"
        )
        for warning in sheet.get("warnings") or []:
            parts.append(f"<p class='error'>{html.escape(warning)}</p>")
        rows = list(sheet.get("rows") or [])
        rows.append({col: "" for col in PREVIEW_COLUMNS})
        parts.append("<div class='tablewrap'><table>")
        parts.append("<tr>" + "".join(f"<th>{html.escape(col)}</th>" for col in PREVIEW_COLUMNS) + "</tr>")
        for ri, row in enumerate(rows):
            cells = []
            for ci, col in enumerate(PREVIEW_COLUMNS):
                value = "" if row.get(col) is None else str(row.get(col))
                cells.append(
                    f"<td><input name='s{si}r{ri}c{ci}' value='{html.escape(value, quote=True)}'></td>"
                )
            parts.append("<tr>" + "".join(cells) + "</tr>")
        parts.append("</table></div>")
        parts.append(f"<input type='hidden' name='nrows_{si}' value='{len(rows)}'>")
    parts.append("<h2>5. Ladda ner</h2>")
    parts.append("<button type='submit'>Skapa Excel</button>")
    parts.append("</form>")
    parts.append("<p class='muted'><a href='/'>Börja om</a></p>")
    return "".join(parts)


def _page(body: str) -> str:
    return f"""<!doctype html>
<html lang="sv">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Mätprotokoll ventilation</title>
  <style>
    body {{ font-family: sans-serif; margin: 0; padding: 16px; line-height: 1.4; }}
    h1 {{ font-size: 1.4rem; }}
    label {{ display: block; margin: 12px 0; }}
    input, button {{ font-size: 16px; }}
    input[type="text"], input:not([type]) {{ width: 100%; box-sizing: border-box; padding: 8px; }}
    button {{ background: #b42318; color: white; border: 0; padding: 12px 16px; margin-top: 12px; }}
    fieldset {{ margin: 8px 0; }}
    .muted {{ color: #555; }}
    .error {{ color: #8a1c13; }}
    progress {{ width: 100%; height: 1.2rem; }}
    .tablewrap {{ overflow-x: auto; }}
    table {{ border-collapse: collapse; min-width: 720px; }}
    th, td {{ border: 1px solid #ccc; padding: 4px; vertical-align: top; }}
    td input {{ width: 14rem; box-sizing: border-box; }}
  </style>
</head>
<body>
{body}
</body>
</html>"""


def lan_ips() -> list[str]:
    found: list[str] = []
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        found.append(sock.getsockname()[0])
        sock.close()
    except Exception:
        pass
    try:
        found.append(socket.gethostbyname(socket.gethostname()))
    except Exception:
        pass
    out: list[str] = []
    for ip in found:
        if ip and not ip.startswith("127.") and ip not in out:
            out.append(ip)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="Mätprotokoll i webbläsaren")
    parser.add_argument("--host", default="")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8501")))
    parser.add_argument("--lan", action="store_true", help="Lyssna på alla nätverkskort (surfplatta/iPad på samma wifi)")
    parser.add_argument("--open-browser", dest="open_browser", action="store_true", default=None)
    parser.add_argument("--no-browser", dest="open_browser", action="store_false")
    parser.add_argument("--extract", metavar="PDF", help="Kör rum/don-extraktion utan webb (CI och Windows-rök)")
    parser.add_argument("--json-out", default="")
    parser.add_argument("--xlsx-out", default="")
    parser.add_argument("--quality", choices=("fast", "accurate"), default="")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    from matprotokoll.paths import exe_dir, is_frozen

    if is_frozen():
        os.chdir(exe_dir())
    os.environ.setdefault("MATPROTOKOLL_OCR", "rapidocr")
    os.environ.setdefault("MATPROTOKOLL_PDF", "pymupdf")
    if args.quality:
        os.environ["MATPROTOKOLL_QUALITY"] = args.quality
    elif os.environ.get("MATPROTOKOLL_LAN", "").lower() in {"1", "true", "yes", "ja"}:
        args.lan = True

    if args.extract:
        from matprotokoll.extract_cli import dump_json, extract_payload, write_xlsx

        payload = extract_payload(args.extract, quality=args.quality or None)
        text = json.dumps(payload, ensure_ascii=False, indent=2)
        if args.json_out:
            dump_json(payload, args.json_out)
            print(args.json_out, flush=True)
        else:
            print(text, flush=True)
        if args.xlsx_out:
            write_xlsx(args.extract, args.xlsx_out, quality=args.quality or None)
            print(args.xlsx_out, flush=True)
        return

    host = args.host or ("0.0.0.0" if args.lan else "127.0.0.1")
    open_browser = args.open_browser
    if open_browser is None:
        open_browser = bool(getattr(sys, "frozen", False))
    local = f"http://127.0.0.1:{args.port}"
    print(f"Öppna i webbläsaren: {local}", flush=True)
    if host in {"0.0.0.0", "::"}:
        for ip in lan_ips():
            print(f"Andra enheter på samma wifi: http://{ip}:{args.port}", flush=True)
        print("iPad kan inte köra programmet lokalt — använd adressen ovan i Safari.", flush=True)
    print("Använd http, inte https. Låt fönstret vara igång.", flush=True)
    if open_browser:
        threading.Timer(1.0, lambda: webbrowser.open(local)).start()
    create_app().run(host=host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()
