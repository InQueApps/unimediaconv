# Universal Media Converter

Convert documents, images, video, and audio entirely on your Android device — no uploads, no accounts, no cloud.

![Banner](media/banner.png)

---

## Supported Conversions

Every conversion listed below maps to a working code path in `UniversalConverter.kt`. Formats that appear in the UI but are not yet fully implemented are omitted here.

### Documents

| From | To |
|------|----|
| EPUB | PDF, DOCX, TXT, RTF |
| PDF | EPUB, DOCX, RTF, TXT |
| PDF | JPG or PNG (one image per page, delivered as a ZIP) |
| DOCX | PDF, RTF, TXT, EPUB |
| TXT | PDF |
| RTF | PDF |
| Any image | PDF (one image per page) |
| Multiple images | Single multi-page PDF |

Document engine: [iText 7](https://itextpdf.com/) + [Apache POI](https://poi.apache.org/) + [Jsoup](https://jsoup.org/).

---

### Images

**Supported inputs:** JPG, JPEG, PNG, WEBP, BMP, TIFF, HEIC / HEIF

**Supported outputs:** JPG, PNG, WEBP

| Feature | Details |
|---------|---------|
| Format conversion | Any supported input → JPG, PNG, or WEBP |
| Image → PDF | Single image or a batch of images into one PDF |
| Resize | Set a target width or height in pixels; aspect ratio is preserved |
| Quality | Low / Medium / High preset (JPEG: 60 / 82 / 95 — WebP: 65 / 82 / 95) |

> **HEIC note:** Full decode requires Android 9 (API 28) or later. On Android 8.1 a best-effort decode is attempted via `BitmapFactory`.

---

### Video

**Supported inputs:** MP4, MOV, AVI, MKV, WEBM

**Video → Video outputs:** MP4, MOV, WEBM
Full decode + re-encode via Android `MediaCodec`. Requires hardware codec support on the device.

**Video → Audio outputs:**

| Output | Method |
|--------|--------|
| M4A | Lossless remux — copies the audio track byte-for-byte into an M4A container with no re-encoding. Fast; quality is identical to the source. |
| WAV | Decodes the audio track to raw 16-bit PCM and writes a standard WAV file. |

**Quality presets (video → video only):** Low = 1.5 Mbps · Medium = 4 Mbps · High = 8 Mbps

---

### Audio

**Supported inputs:** MP3, M4A, WAV

| From | To | Method |
|------|----|--------|
| MP3 / M4A / WAV | WAV | Decode via `MediaCodec`, write 16-bit PCM WAV |
| WAV / MP3 | M4A | Two-pass: decode to PCM → encode to AAC-LC via `MediaCodec` |

**Quality presets (WAV / MP3 → M4A):** Low = 64 kbps · Medium = 128 kbps · High = 256 kbps

> MP3 *encoding* is not available — Android has no built-in MP3 encoder. WAV and M4A cover the same use cases with full quality.

---

## App Features

### Batch conversion
Select multiple files of the same format in a single operation. Each file is converted to the chosen output format. Output can be delivered as:
- **Individual files** — view, share, or save each one separately
- **ZIP archive** — all outputs bundled into a single file

Special case: selecting multiple images and targeting PDF merges them into one multi-page PDF instead of separate files.

### Progress & control
- Real-time progress bar (0 – 100 %)
- Status text: "Starting", "Processing", "Converting", "Finalizing"
- Batch mode shows **"File N of M"** while working through a queue
- **Cancel** button stops the conversion at any point

### Background conversion
A foreground service with a persistent notification keeps the conversion alive when you switch apps or lock the screen. The notification shows live progress and tapping it returns you to the app.

### Output options (per result file)
- **View** — opens the file in a compatible app
- **Share** — sends the file via the Android share sheet
- **Save** — opens the system file picker to save to any folder

### Image resize
When a single image is selected, an optional resize panel appears. Enter a target width or height in pixels; the other dimension is calculated automatically to preserve the aspect ratio. A live preview shows the resulting dimensions before conversion.

### Quality presets
For image and video conversions a **Low / Medium / High** quality selector is shown. Choosing a lower preset reduces file size at the cost of some fidelity. PNG output is always lossless and is unaffected by the preset.

### Privacy
All conversions run entirely on the device using Android system APIs (`MediaCodec`, `MediaMuxer`, `MediaExtractor`) and open-source libraries. No file is ever uploaded to a server.

---

## Points & Monetisation

| Action | Points |
|--------|--------|
| New install | 10 free points |
| Each file converted | −1 point |
| Watch a rewarded video ad | +3 points |
| One-time Premium unlock | Unlimited conversions forever |

Premium is a single in-app purchase that removes the point limit permanently. Watching ads is optional and only required to replenish points on the free tier.

---

## Requirements

| | |
|-|-|
| **Minimum Android** | 8.1 Oreo (API 27) |
| **Target Android** | 15 (API 35) |
| **Version** | 0.14 |
| **Permissions** | Storage read (for file picker), Internet (ads), Foreground service (background conversion) |

---

## Architecture

```
LandingActivity
└── MainActivity
    ├── ConverterViewModel          — state holder, coroutine orchestration
    │   └── UniversalConverter      — all format conversion logic
    │       ├── EpubConverter       — EPUB ↔ PDF / DOCX / TXT / RTF
    │       ├── PdfConverter        — PDF ↔ EPUB / DOCX / RTF / TXT / images
    │       └── DocxConverter       — DOCX ↔ PDF / RTF / TXT / EPUB
    ├── ConversionForegroundService — foreground notification during conversion
    ├── ConversionPrefs             — points balance & premium unlock (SharedPreferences)
    └── PurchaseActivity            — Google Play Billing for Premium
```

All heavy work runs on `Dispatchers.IO`. The ViewModel exposes a single `StateFlow<ConversionState>` that the Activity observes. Temp files are written to `cacheDir` and deleted automatically when the conversion job finishes.
