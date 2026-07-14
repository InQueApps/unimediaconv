# Scripts — UniMediaConv

## Visual conversion matrix

**File:** [`conversion_matrix.html`](conversion_matrix.html)

Open in any browser. Shows:

- **Category heatmap** — how many pairs connect document / image / video / audio (600 total on max device)
- **26×26 grid** — every format → every target
  - **Green** = native fidelity
  - **Yellow** = simplified
  - **Orange** = creative (OCR, TTS, waveform, frames)
  - **Blue ring** = covered by an instrumented E2E smoke test
- Hover a cell for route (e.g. `EPUB → PDF → JPG`)

### Regenerate

From repo root:

```powershell
.\gradlew :app:testDebugUnitTest --tests ConversionMatrixVisualGeneratorTest
```

Override output directory:

```powershell
$env:UNIMEDIACONV_SCRIPTS_DIR = "C:\path\to\unimediaconv\scripts"
.\gradlew :app:testDebugUnitTest --tests ConversionMatrixVisualGeneratorTest
```

## Other scripts

| Script | Purpose |
|--------|---------|
| `run_all_tests.ps1` | Unit + connected Android tests |
| `check_16kb_alignment.ps1` | Verify release AAB arm64 ELF alignment |
| `test_link_conversion.py` | Dev helper for link import |

Skills docs (features, gaps, test map): `Work/skills/unimediaconv/`
