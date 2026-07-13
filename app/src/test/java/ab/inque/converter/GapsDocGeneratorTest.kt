package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import org.junit.Test
import java.io.File

/**
 * Generates GAPS.md from live planner analysis + known open items.
 * Run: ./gradlew :app:testDebugUnitTest --tests GapsDocGeneratorTest
 */
class GapsDocGeneratorTest {

    @Test
    fun generateGapsMarkdown() {
        val caps = FormatCapabilities.ALL
        val maxPairs = pairCount(caps)
        val noSttPairs = pairCount(caps.copy(canTranscribe = false))
        val baselinePairs = pairCount(
            FormatCapabilities(
                canEncodeHeic = false,
                canEncodeOgg = false,
                canEncodeFlac = false,
                canTranscribe = false,
                canOcr = true
            )
        )
        val multiHopPairs = countMultiHopPairs(caps)
        val sttPairs = countSttPairs(caps)

        val sb = StringBuilder()
        sb.appendLine("# AnyConvert — Gap Analysis (July 2026)")
        sb.appendLine()
        sb.appendLine("> **Auto-generated** from `ConversionPlanner` + engineering backlog.")
        sb.appendLine("> **Related:** [FEATURES.md](FEATURES.md) · [FEATURES_MATRIX.md](FEATURES_MATRIX.md) · [PLAN.md](PLAN.md)")
        sb.appendLine()
        sb.appendLine("**Regenerate:** `./gradlew :app:testDebugUnitTest --tests GapsDocGeneratorTest`")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Executive summary")
        sb.appendLine()
        sb.appendLine("On a fully capable phone (Android 13+, on-device STT), users reach **$maxPairs conversion paths** across **26 formats**.")
        sb.appendLine("**All UI-offered pairs have shipped handlers** — matrix ⚠️/🎨 labels describe output *fidelity*, not missing code.")
        sb.appendLine()
        sb.appendLine("| Area | Status |")
        sb.appendLine("|------|--------|")
        sb.appendLine("| Core engine + multi-hop planner | ✅ |")
        sb.appendLine("| Phase 4 UX (sniff, mux, frames, mic, mixed-batch warn) | ✅ |")
        sb.appendLine("| MKV + AVI export (FFmpeg remux) | ✅ |")
        sb.appendLine("| Tier 1/2 quick wins (TTS UI, aliases, HTML, waveforms) | ✅ |")
        sb.appendLine("| STT semantic gating (no fake audio→doc multi-hop) | ✅ |")
        sb.appendLine("| Encode-cap gating (HEIC/OGG/FLAC same-category) | ✅ |")
        sb.appendLine("| Cloud STT (API &lt; 33) | ❌ Phase 5.2 |")
        sb.appendLine("| STT/TTS locale picker | ❌ Phase 6 |")
        sb.appendLine("| Perfect Word ↔ PDF / full HTML styling | ❌ Phase 6 |")
        sb.appendLine()
        sb.appendLine("### Capability impact on UI pair count")
        sb.appendLine()
        sb.appendLine("| Profile | UI pairs | Delta vs max |")
        sb.appendLine("|---------|----------|--------------|")
        sb.appendLine("| Max device (API 33+, STT) | **$maxPairs** | — |")
        sb.appendLine("| No on-device STT | **$noSttPairs** | −${maxPairs - noSttPairs} (audio/video text docs) |")
        sb.appendLine("| API 27 baseline | **$baselinePairs** | −${maxPairs - baselinePairs} (encode + STT) |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Conversion coverage (live analysis)")
        sb.appendLine()
        sb.appendLine(categoryCrossMatrixSection(caps))
        sb.appendLine()
        sb.appendLine("### Per-format target counts (max device)")
        sb.appendLine()
        sb.appendLine("| Source format | Category | Targets |")
        sb.appendLine("|---------------|----------|---------|")
        for (from in FileFormat.entries) {
            val n = ConversionPlanner.reachableTargets(from, caps).size
            sb.appendLine("| ${from.displayName} (`.${from.extension}`) | ${from.category.name.lowercase()} | $n |")
        }
        sb.appendLine()
        sb.appendLine("### Route mechanisms (pair counts)")
        sb.appendLine()
        sb.appendLine(mechanismCountsSection(caps))
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|--------|-------|")
        sb.appendLine("| Single-hop pairs | ${maxPairs - multiHopPairs} |")
        sb.appendLine("| Multi-hop pairs (3+ nodes in path) | $multiHopPairs |")
        sb.appendLine("| Pairs using on-device STT | $sttPairs |")
        sb.appendLine("| Implementation gaps in UI set | **0** |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Gap categories")
        sb.appendLine()
        sb.appendLine("### A — Product & UX gaps")
        sb.appendLine()
        sb.appendLine("| ID | Gap | Severity | Status |")
        sb.appendLine("|----|-----|----------|--------|")
        sb.appendLine("| A-01 | Unknown extension before sniff | Medium | ✅ `FormatResolver` + URI sniff |")
        sb.appendLine("| A-02 | Mixed batch uses first file only | Medium | ✅ Warn toast; per-file routing = Phase 6 |")
        sb.appendLine("| A-03 | Video → single thumbnail only | Medium | ✅ Optional all-frames ZIP |")
        sb.appendLine("| A-04 | Audio → video silent | High | ✅ Muxed audio track |")
        sb.appendLine("| A-05 | No TTS speech-rate / voice UI | Low | ✅ SeekBar + voice spinner |")
        sb.appendLine("| A-06 | Mic permission for STT routes | High | ✅ Scoped to audio/video → text docs (not video→PDF) |")
        sb.appendLine("| A-07 | STT on Android 12 and below | High | **Open** — cloud STT backend (Phase 5.2) |")
        sb.appendLine("| A-08 | Cannot export MKV | Medium | ✅ FFmpeg remux |")
        sb.appendLine("| A-09 | No STT/TTS **language** picker | Low | **Open** (voice picker done) |")
        sb.appendLine("| A-10 | Fake audio→doc via waveform multi-hop | High | ✅ `semanticTargetAllowed` in planner |")
        sb.appendLine()
        sb.appendLine("### B — Conversion fidelity gaps (by design or incremental)")
        sb.appendLine()
        sb.appendLine("| ID | Gap | Pairs affected | Status |")
        sb.appendLine("|----|-----|----------------|--------|")
        sb.appendLine("| B-01 | HTML → Word/EPUB loses layout | HTML → DOCX/EPUB/TXT | **Improved** — HTML→PDF→DOCX chain |")
        sb.appendLine("| B-02 | Doc → HTML plain wrapper | * → HTML | ✅ `<p>` per line |")
        sb.appendLine("| B-03 | PDF → Word best-effort | PDF → DOCX/RTF | Open (incremental) |")
        sb.appendLine("| B-04 | Photo → Word = OCR text | Image → DOCX/PDF/… | By design 🎨 |")
        sb.appendLine("| B-05 | Video → PDF = frame images | Video → PDF | By design 🎨 |")
        sb.appendLine("| B-06 | AVI remux quality | Video → AVI | ✅ FFmpeg (simplified fidelity) |")
        sb.appendLine("| B-07 | Handwriting OCR accuracy | Image → TXT | ML Kit limits |")
        sb.appendLine("| B-08 | TTS read-aloud voice quality | Doc → audio | System TTS limits |")
        sb.appendLine("| B-09 | STT accent/noise accuracy | Audio/video → text | On-device STT limits |")
        sb.appendLine()
        sb.appendLine("### E — Geometry / fidelity gaps (Phase 6.6)")
        sb.appendLine()
        sb.appendLine("| ID | Gap | Severity | Status |")
        sb.appendLine("|----|-----|----------|--------|")
        sb.appendLine("| G-01 | Video thumbnail / frame ZIP ignores rotation | High | ✅ `orientVideoFrame` |")
        sb.appendLine("| G-02 | Double rotation (pixels + orientation hint) | High | ✅ muxer hint cleared when tools rotate |")
        sb.appendLine("| G-03 | Slideshow stretches mixed aspect ratios | High | ✅ letterbox + first-image canvas |")
        sb.appendLine("| G-04 | Trim starts at previous keyframe | Medium | ✅ UI hint (`trim_keyframe_hint`) |")
        sb.appendLine("| G-05 | Remux drops orientation metadata | Medium | ✅ `remuxWithTools` orientation hint |")
        sb.appendLine("| G-06 | 180° + flip compound logic | Low | ✅ explicit 180° NV12 rotate |")
        sb.appendLine("| G-08 | WEBM may drop AAC audio | Medium | ✅ FFmpeg Opus mux fallback |")
        sb.appendLine()
        sb.appendLine("Detail: [GEOMETRY_FIX_PLAN.md](GEOMETRY_FIX_PLAN.md) · Tests: [GEOMETRY_TEST_PLAN.md](GEOMETRY_TEST_PLAN.md)")
        sb.appendLine()
        sb.appendLine("### C — Engineering gaps")
        sb.appendLine()
        sb.appendLine("| ID | Gap | Status |")
        sb.appendLine("|----|-----|--------|")
        sb.appendLine("| C-01 | Planner ↔ handler drift | ✅ `PlannerHandlerSyncTest` |")
        sb.appendLine("| C-02 | Full 600-pair E2E in CI | Open — unit + P0 smoke only |")
        sb.appendLine("| C-03 | Instrumented tests need device | Ongoing |")
        sb.appendLine("| C-04 | JitPack TAndroidLame flakes | Open — vendor AAR (Phase 5.3) |")
        sb.appendLine("| C-05 | Monolithic `UniversalConverter` (~3.8k lines) | Phase 6 split |")
        sb.appendLine("| C-06 | No `conversion_graph.json` | Phase 6 |")
        sb.appendLine("| C-07 | Image→doc via PDF when OCR off | Low — multi-hop may still appear |")
        sb.appendLine()
        sb.appendLine("### D — Policy (closed)")
        sb.appendLine()
        sb.appendLine("SAF-only I/O, no `MANAGE_EXTERNAL_STORAGE`, Play-appropriate permissions.")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## What is **not** a gap")
        sb.appendLine()
        sb.appendLine("| User expectation | Reality |")
        sb.appendLine("|------------------|---------|")
        sb.appendLine("| ⚠️ Simplified = broken | Valid output, reduced fidelity |")
        sb.appendLine("| 🎨 Creative = fake | Intentional transform (OCR, TTS, waveform, slideshow) |")
        sb.appendLine("| MP3 → DOCX needs transcribe | ✅ `convertAudioToDocument` (STT) |")
        sb.appendLine("| DOCX → MP3 needs read-aloud | ✅ `convertDocumentToAudio` (TTS) |")
        sb.appendLine("| Video → PDF needs STT | **No** — frame capture only |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Lessons learned")
        sb.appendLine()
        sb.appendLine("1. **Planner vs handler:** Audio→BMP/TIFF/HEIC waveform was planner-only; handler already worked via PNG hop.")
        sb.appendLine("2. **STT gating:** `canTranscribe=false` must block audio→*all* documents via `semanticTargetAllowed`, not only direct edges.")
        sb.appendLine("3. **Encode caps:** Same-category transcode must respect `canEncodeHeic/Ogg/Flac` or API 27 shows impossible targets.")
        sb.appendLine("4. **FFmpeg scope:** Containers MediaMuxer lacks (MKV, AVI) — MP4 intermediate + stream copy.")
        sb.appendLine("5. **HTML→DOCX:** Prefer HTML→PDF→DOCX over plain-text POI when iText html2pdf is available.")
        sb.appendLine("6. **Docs hygiene:** Fidelity tiers ≠ missing features; keep FEATURES.md (product) separate from FEATURES_MATRIX.md.")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Definition of “gap closed”")
        sb.appendLine()
        sb.appendLine("1. Works on reference device (Pixel API 33+)")
        sb.appendLine("2. Documented in FEATURES.md / FEATURES_MATRIX.md with correct tier")
        sb.appendLine("3. Test in TEST_PLAN (unit, instrumented, or manual ID)")
        sb.appendLine("4. Planner offers target iff handler exists and semantic rules allow it")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Document history")
        sb.appendLine()
        sb.appendLine("| Version | Date | Notes |")
        sb.appendLine("|---------|------|-------|")
        sb.appendLine("| 2.3 | Jul 2026 | Auto-generator; STT semantic gating; capability pair counts |")
        sb.appendLine("| 2.2 | Jul 2026 | Tier 1/2 closed; TTS UI; AVI FFmpeg |")
        sb.appendLine("| 2.1 | Jul 2026 | MKV export; 600 UI pairs |")

        val content = sb.toString()
        for (path in gapsOutputPaths()) {
            path.parentFile?.mkdirs()
            path.writeText(content)
        }
    }

    private fun pairCount(caps: FormatCapabilities): Int =
        FileFormat.entries.sumOf { from -> ConversionPlanner.reachableTargets(from, caps).size }

    private fun countMultiHopPairs(caps: FormatCapabilities): Int {
        var n = 0
        for (from in FileFormat.entries) {
            for (to in ConversionPlanner.reachableTargets(from, caps)) {
                val path = ConversionPlanner.findPath(from, to, caps)
                if (path != null && path.size > 2) n++
            }
        }
        return n
    }

    private fun countSttPairs(caps: FormatCapabilities): Int {
        var n = 0
        for (from in FileFormat.entries) {
            if (from.category != FormatCategory.AUDIO && from.category != FormatCategory.VIDEO) continue
            for (to in ConversionPlanner.reachableTargets(from, caps)) {
                if (to.category != FormatCategory.DOCUMENT) continue
                if (from.category == FormatCategory.VIDEO && to == FileFormat.PDF) continue
                n++
            }
        }
        return n
    }

    private fun categoryCrossMatrixSection(caps: FormatCapabilities): String {
        val cats = FormatCategory.entries
        val counts = mutableMapOf<Pair<FormatCategory, FormatCategory>, Int>()
        for (from in FileFormat.entries) {
            for (to in ConversionPlanner.reachableTargets(from, caps)) {
                val key = from.category to to.category
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val sb = StringBuilder()
        sb.appendLine("### Category cross-matrix (source → target)")
        sb.appendLine()
        sb.appendLine("| From ↓ / To → | ${cats.joinToString(" | ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }} | **Total** |")
        sb.appendLine("|${"---|".repeat(cats.size + 2)}")
        for (fromCat in cats) {
            val row = cats.map { toCat -> counts[fromCat to toCat] ?: 0 }
            val total = row.sum()
            sb.appendLine("| **${fromCat.name.lowercase()}** | ${row.joinToString(" | ")} | **$total** |")
        }
        val colTotals = cats.map { toCat -> cats.sumOf { fromCat -> counts[fromCat to toCat] ?: 0 } }
        sb.appendLine("| **Total** | ${colTotals.joinToString(" | ") { "**$it**" }} | **${colTotals.sum()}** |")
        return sb.toString().trimEnd()
    }

    private fun mechanismCountsSection(caps: FormatCapabilities): String {
        val buckets = linkedMapOf<String, Int>()
        for (from in FileFormat.entries) {
            for (to in ConversionPlanner.reachableTargets(from, caps)) {
                val path = ConversionPlanner.findPath(from, to, caps)
                val key = mechanismLabel(from, to, path)
                buckets[key] = (buckets[key] ?: 0) + 1
            }
        }
        val sb = StringBuilder()
        sb.appendLine("| Mechanism | Pairs |")
        sb.appendLine("|-----------|-------|")
        for ((label, count) in buckets.entries.sortedByDescending { it.value }) {
            sb.appendLine("| $label | $count |")
        }
        return sb.toString().trimEnd()
    }

    private fun mechanismLabel(from: FileFormat, to: FileFormat, path: List<FileFormat>?): String {
        if (path != null && path.size > 2) return "Multi-hop (${path.size - 1} steps)"
        if (from.category == to.category) {
            return when {
                from.category == FormatCategory.VIDEO && to == FileFormat.AVI -> "Video transcode + FFmpeg AVI remux"
                from.category == FormatCategory.VIDEO && to == FileFormat.MKV -> "Video transcode + FFmpeg MKV remux"
                from.category == FormatCategory.VIDEO && to == FileFormat.GIF -> "Video → GIF frames"
                else -> "Same-category transcode"
            }
        }
        return when {
            from.category == FormatCategory.AUDIO && to.category == FormatCategory.DOCUMENT ->
                "STT → document (${to.extension.uppercase()})"
            from.category == FormatCategory.VIDEO && to == FileFormat.PDF -> "Video frame capture → PDF"
            from.category == FormatCategory.VIDEO && to.category == FormatCategory.DOCUMENT ->
                "Extract audio → STT → document"
            from.category == FormatCategory.VIDEO && to.category == FormatCategory.AUDIO -> "Audio extract"
            from.category == FormatCategory.VIDEO && isRaster(to) -> "Video frame extract"
            from.category == FormatCategory.AUDIO && isRaster(to) -> "Waveform image"
            from.category == FormatCategory.AUDIO && to.category == FormatCategory.VIDEO ->
                "Waveform slideshow + muxed audio"
            from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.AUDIO -> "TTS → audio"
            from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.VIDEO ->
                "Document slideshow video"
            from.category == FormatCategory.IMAGE && to == FileFormat.PDF -> "Image → PDF"
            from.category == FormatCategory.IMAGE && to.category == FormatCategory.DOCUMENT -> "OCR → document"
            from.category == FormatCategory.IMAGE && to.category == FormatCategory.AUDIO -> "OCR + TTS"
            from.category == FormatCategory.IMAGE && to.category == FormatCategory.VIDEO -> "Image slideshow"
            from == FileFormat.GIF && to.category == FormatCategory.IMAGE -> "GIF first frame"
            from.category == FormatCategory.DOCUMENT && isRaster(to) -> "Document render → image"
            else -> "Document / media converter"
        }
    }

    private fun isRaster(f: FileFormat) =
        f == FileFormat.JPG || f == FileFormat.JPEG || f == FileFormat.PNG ||
            f == FileFormat.WEBP || f == FileFormat.BMP || f == FileFormat.TIFF || f == FileFormat.HEIC

    private fun gapsOutputPaths(): List<File> {
        val skillsDir = skillsDir()
        return listOf(File(skillsDir, "GAPS.md"))
    }

    private fun skillsDir(): File {
        val fromEnv = System.getenv("UNIMEDIACONV_SKILLS_DIR")
        if (!fromEnv.isNullOrBlank()) return File(fromEnv)
        return File("C:/Users/inque/OneDrive/Desktop/Work/skills/unimediaconv")
    }
}
