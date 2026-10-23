# Mätprotokoll för ventilation

Samma program på Windows, Linux, macOS och Android: Flask i webbläsaren, RapidOCR/onnxruntime, PyMuPDF, Pillow och openpyxl. Låsta versioner i `requirements.txt`.

**Gränssnittsspråk: svenska.** Publikt repo: https://github.com/adisu97/ventilation-matprotokoll-app

## Vad programmet gör

1. Du namnger Excel-filen.
2. Du laddar upp en eller flera ritnings-PDF:er.
3. Per PDF väljer du **nytt blad i aktuell arbetsbok** eller **ny Excel-fil**.
4. Varje PDF blir ett eget blad, namngivet efter filnamnet (kortas till Excels 31-teckengräns).
5. Ny arbetsbok får förstabladet **Koneet Maskiner** från mallen.
6. Extraherade rader visas i en redigerbar tabell innan du laddar ner `.xlsx`.

Kolumner per blad:

| A | B | C | D | E–G | H | I | J | K–M |
|---|---|---|---|-----|---|---|---|-----|
| Huonetila / Utrymme | Frånluftsdon | Storlek | Behov (l/s) | tomma (mätta värden) | Tilluftsdon | Storlek | Behov | tomma |

Frånluft och tilluft i **samma rum** hamnar på samma rad. **Extra don** i rummet ger nya rader med **tom kolumn A**.

Kända don (lagras lowercase: `kso`, `dykh`, `clik`, …):

- **Tilluft (H):** DYKH, CLIK, KTS, EAGLE, PELICAN, HFK
- **Frånluft (B):** KSO, DYKH, CLIK, ALG, HFK, PRA, HUK / HU

Tecken på luftmängd i etiketten (`+40` tilluft, `-20` frånluft) väger tyngre än listan när produkten kan vara båda.

## Windows-dator (ingen installation, inget admin-lösenord)

1. Öppna **Releases**: https://github.com/adisu97/ventilation-matprotokoll-app/releases
2. Ladda ner `matprotokoll-windows.zip`.
3. Packa upp **hela mappen** (t.ex. på Skrivbordet eller i Nedladdningar).
4. Dubbelklicka på `matprotokoll.exe`. Webbläsaren öppnas på http://127.0.0.1:8501

**SmartScreen** kan visa "Windows skyddade din dator" (osignerad fil). Välj **Mer information → Kör ändå**. Inget administratörslösenord behövs. Programmet lägger inget i Program Files.

Andra enheter på samma wifi (Android-surfplatta, **iPad**): stäng programmet, öppna Kommandotolken i mappen och kör `matprotokoll.exe --lan`. Adressen till datorn skrivs ut, t.ex. `http://192.168.1.12:8501`. iPad kan inte köra programmet lokalt — bara öppna den adressen i Safari.

## Linux/macOS med Python (utveckling)

Python 3.12. Samma `requirements.txt` som i ZIP:en.

```bash
git clone https://github.com/adisu97/ventilation-matprotokoll-app.git
cd ventilation-matprotokoll-app
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python -m matprotokoll.webapp
```

`--lan` lyssnar på hela wifit. `--quality fast` är samma RapidOCR med färre rutor.

## Android-telefon och Android-surfplatta (Termux)

Samma UI och samma RapidOCR/PyMuPDF som på datorn. Ingen Play Butik, ingen root, ingen inloggning. Samsung A52 och andra 64-bitarstelefoner använder APK:en nedan.

### 1. Ladda ner Termux och installera

Ladda ner APK:en direkt härifrån:

https://github.com/adisu97/ventilation-matprotokoll-app/releases/latest/download/termux-arm64-v8a.apk

Öppna filen i webbläsaren. Tillåt **Installera okända appar** för webbläsaren och installera. Öppna sedan Termux. **Blanda inte med Termux från Play Butik** — den har en annan signatur.

Äldre telefon med annan processor: [termux-universal.apk](https://github.com/adisu97/ventilation-matprotokoll-app/releases/latest/download/termux-universal.apk) på samma release. Python-paketen finns färdiga för 64-bitars ARM. En ren 32-bitarstelefon kan installera Termux, men installationen av själva programmet stannar troligen när pip inte hittar hjul.

APK:erna är oförändrade officiella Termux **v0.118.3** (`github-debug`). Licens **GPLv3**, omfördelning är tillåten när källkoden följer med: https://github.com/termux/termux-app/tree/v0.118.3 och https://github.com/termux/termux-app/blob/v0.118.3/LICENSE.md (GPLv3, med undantag under Apache 2.0 och MIT enligt den filen). Kontrollsummor: [SHA256SUMS.txt](https://github.com/adisu97/ventilation-matprotokoll-app/releases/latest/download/SHA256SUMS.txt).

Valfritt alternativ: samma Termux-version finns på F-Droid, men med en annan signatur. Använd inte båda installationerna.

### 2. Klistra in den här raden i Termux

```bash
curl -fsSL https://github.com/adisu97/ventilation-matprotokoll-app/releases/latest/download/bootstrap.sh | bash
```

Första gången tar flera minuter. Du ska inte välja någon Linux-distribution — skriptet gör det.

### 3. Starta nästa gång

```bash
bash ~/matprotokoll/android/start.sh
```

Öppna http://127.0.0.1:8501 i Chrome på **samma** enhet. `http`, inte `https`.

**Surfplatta med Android:** samma Termux-väg. **iPad:** kör programmet på datorn med `--lan` och öppna datorns adress i Safari.

Snabbare på telefon (samma verktyg): `start.sh` använder redan `--quality fast`. Noggrann: `MATPROTOKOLL_QUALITY=accurate bash android/start.sh`.

### Felsökning

- `pkg: command not found` — du är inte i Termux, eller Play Store-Termux är installerad.
- Sidan öppnas inte — `start.sh` måste köra, adressen är `http://127.0.0.1:8501`.
- Telefonen stänger appen mitt i OCR — håll `MATPROTOKOLL_QUALITY=fast` (standard).

På skrivbordet är standard **noggrann** rumssökning.

## Tester

En syntetisk mini-ritning finns i `tests/fixtures/mini_ritning.pdf` (inga kunddata). Mallen för nya Excel-filer är `templates/matprotokoll_grund.xlsx`.

```bash
python -m pytest -q
```

```bash
python -m matprotokoll.webapp --extract tests/fixtures/mini_ritning.pdf --xlsx-out /tmp/mini.xlsx --quality fast
```

## OCR-begränsningar (viktigt)

Donetiketter på AutoCAD-plot är **ofta inte** markerbar PDF-text utan outlined/vektortext. Programmet:

1. Läser **rumsnummer och rumsnamn** ur PDF-textlagret när det finns (t.ex. `E125` + `WC`).
2. Renderar sidan **ruta för ruta** och kör **RapidOCR** (samma onnxruntime på dator, Windows-ZIP och Android).
3. Godkänner **bara** träffar mot listan ovan. Inga don hittas på – inga rader hittas på.

Det som ofta fungerar: `KSO-125-C`, `KTS-160-0-C`, `2xKTS-125-0-C`, `2xEAGLE CRa 200-600` plus luftmängd `+56` / `-40` under strecket.

Det som kan bli fel:

- Suddiga, mycket små eller kraftigt roterade etiketter
- OCR-förväxling `O`/`0` (hanteras för KSO/KTS, men inte alltid)
- Fel rum om donet ligger långt från rumsnumret
- Kanaltext (`SP1-Ø315`, `CIRCO`, `YLÖS`) som **inte** ska bli don – filtreras bort
- Behov (l/s) lämnas tomt om etiketten inte visar ett tydligt flöde

Granska alltid förhandsvisningen innan du sparar.

## Projektstruktur

```
app.py                         Startar Flask (samma UI överallt)
matprotokoll/webapp.py         Webbgränssnitt
android/bootstrap.sh           En rad att klistra in i Termux
android/install.sh             Automatisk installation
android/start.sh               http://127.0.0.1:8501
packaging/matprotokoll.spec    PyInstaller onedir (Windows)
requirements.txt               Låsta versioner (alla plattformar)
templates/matprotokoll_grund.xlsx
tests/fixtures/mini_ritning.pdf
.github/workflows/ci.yml       Tester + Windows-ZIP
```
