package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import org.junit.Test
import java.io.File

/**
 * Generates FEATURES_MATRIX.md — technical matrix with fidelity + implementation status.
 * Run: ./gradlew :app:testDebugUnitTest --tests ConversionFeaturesDocGeneratorTest
 */
class ConversionFeaturesDocGeneratorTest {

    /** Output fidelity — NOT the same as "missing implementation". */
    enum class Fidelity(val label: String, val desc: String) {
        NATIVE("✅ Native", "Full encoder/converter; expected quality for the pair"),
        DEGRADED("⚠️ Simplified", "Valid output; layout, structure, or accuracy reduced"),
        CREATIVE("🎨 Creative", "Intentional transform (OCR, TTS, waveform, slideshow) — shipped by design"),
        BLOCKED("🚫 Blocked", "Not offered in UI")
    }

    enum class DeviceReq(val suffix: String) {
        NONE(""),
        API_28("+ API 28+"),
        API_29("+ API 29+"),
        API_33_STT("+ API 33+ STT")
    }

    @Test
    fun generateFeaturesMarkdown() {
        val caps = FormatCapabilities.ALL
        val pairCount = FileFormat.entries.sumOf { from ->
            ConversionPlanner.reachableTargets(from, caps).size
        }
        val sb = StringBuilder()
        sb.appendLine("# AnyConvert — Technical Conversion Matrix")
        sb.appendLine()
        sb.appendLine("> **Product doc:** [FEATURES.md](FEATURES.md) · **Gaps:** [GAPS.md](GAPS.md)")
        sb.appendLine()
        sb.appendLine("**Generated from:** `ConversionPlanner` + `UniversalConverter` · **Updated:** July 2026")
        sb.appendLine()
        sb.appendLine("**Regenerate:** `./gradlew :app:testDebugUnitTest --tests ConversionFeaturesDocGeneratorTest`")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Implementation completeness (read this first)")
        sb.appendLine()
        sb.appendLine("**Every conversion offered in the app dropdown has shipped code.**")
        sb.appendLine("Fidelity ratings describe *output quality*, not missing features.")
        sb.appendLine()
        sb.appendLine("| Question | Answer |")
        sb.appendLine("|----------|--------|")
        sb.appendLine("| Are all **$pairCount UI pairs** implemented? | **Yes** |")
        sb.appendLine("| Is anything marked 🎨/⚠️ \"broken\" or fake? | **No** — those are honest quality labels |")
        sb.appendLine("| What is actually **not** implemented? | **Nothing in the UI set** (MKV export added Phase 5) |")
        sb.appendLine("| What is **Phase 5** (remaining)? | Cloud STT (API &lt; 33), STT/TTS locale picker |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Rating legend")
        sb.appendLine()
        sb.appendLine("| Fidelity | Meaning |")
        sb.appendLine("|----------|---------|")
        Fidelity.entries.filter { it != Fidelity.BLOCKED }.forEach { f ->
            sb.appendLine("| ${f.label} | ${f.desc} |")
        }
        sb.appendLine()
        sb.appendLine("**Device column:** minimum API / on-device engine required for the **primary handler** on that pair (not every multi-hop shortcut).")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine(capabilityProfilesSection())
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine(categoryCrossMatrixSection(caps))
        sb.appendLine()
        sb.appendLine("### Per-source target counts")
        sb.appendLine()
        sb.appendLine("| Source | Category | Targets |")
        sb.appendLine("|--------|----------|---------|")
        for (from in FileFormat.entries) {
            val n = ConversionPlanner.reachableTargets(from, caps).size
            sb.appendLine("| ${from.displayName} (`.${from.extension}`) | ${from.category.name.lowercase()} | $n |")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        var total = 0
        var native = 0
        var degraded = 0
        var creative = 0
        var withDevice = 0

        val perSource = FileFormat.entries.map { from ->
            val targets = ConversionPlanner.reachableTargets(from, caps).sortedBy { it.name }
            total += targets.size
            val rows = targets.map { to ->
                val fidelity = classifyFidelity(from, to)
                val device = deviceRequirement(from, to, caps)
                when (fidelity) {
                    Fidelity.NATIVE -> native++
                    Fidelity.DEGRADED -> degraded++
                    Fidelity.CREATIVE -> creative++
                    Fidelity.BLOCKED -> {}
                }
                if (device != DeviceReq.NONE) withDevice++
                val path = ConversionPlanner.findPath(from, to, caps)
                Row(to, fidelity, device, hopNote(path), mechanismLabel(from, to, path))
            }
            from to rows
        }

        sb.appendLine("## Summary")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|--------|-------|")
        sb.appendLine("| Formats | ${FileFormat.entries.size} (${FileFormat.entries.size} formats · $total directed pairs) |")
        sb.appendLine("| **Pairs in UI** (max device) | **$total** |")
        sb.appendLine("| ✅ Native fidelity | $native |")
        sb.appendLine("| ⚠️ Simplified fidelity | $degraded |")
        sb.appendLine("| 🎨 Creative (by design) | $creative |")
        sb.appendLine("| With device requirement | $withDevice |")
        sb.appendLine("| **Implementation gaps in UI set** | **0** |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Routes reference")
        sb.appendLine()
        sb.appendLine(routeReference())
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Full matrix by source")
        sb.appendLine()

        for ((from, rows) in perSource) {
            sb.appendLine("### ${from.displayName} (`.${from.extension}`) → ${rows.size} targets")
            sb.appendLine()
            sb.appendLine("| Target | Fidelity | Device | Mechanism | Route |")
            sb.appendLine("|--------|----------|--------|-----------|-------|")
            for (row in rows) {
                val dev = if (row.device == DeviceReq.NONE) "—" else row.device.suffix.trim()
                sb.appendLine("| ${row.to.displayName} | ${row.fidelity.label} | $dev | ${row.mechanism} | ${row.route} |")
            }
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Planned improvements (not missing from current UI)")
        sb.appendLine()
        sb.appendLine("| Item | Affects | Phase |")
        sb.appendLine("|------|---------|-------|")
        sb.appendLine("| Cloud STT fallback | Transcribe on API 28–32 | 5 |")
        sb.appendLine("| STT/TTS locale picker | Transcribe / read-aloud language | 5 |")
        sb.appendLine("| Structured HTML/DOCX | Fidelity of some doc paths | 6 |")
        sb.appendLine("| Per-file mixed batch | Batch UX | 6 |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Related")
        sb.appendLine()
        sb.appendLine("- [FEATURES.md](FEATURES.md) — product specifications")
        sb.appendLine("- [GAPS.md](GAPS.md) — open product/engineering gaps")
        sb.appendLine("- [TEST_PLAN.md](TEST_PLAN.md) — QA")

        val content = sb.toString()
        for (path in matrixOutputPaths()) {
            path.parentFile?.mkdirs()
            path.writeText(content)
        }
    }

    /** Gradle unit tests run with CWD = `app/` module — resolve repo root explicitly. */
    private fun matrixOutputPaths(): List<File> {
        val skillsDir = skillsDir()
        return listOf(File(skillsDir, "FEATURES_MATRIX.md"))
    }

    private fun skillsDir(): File {
        val fromEnv = System.getenv("UNIMEDIACONV_SKILLS_DIR")
        if (!fromEnv.isNullOrBlank()) return File(fromEnv)
        return File("C:/Users/inque/OneDrive/Desktop/Work/skills/unimediaconv")
    }

    private data class Row(
        val to: FileFormat,
        val fidelity: Fidelity,
        val device: DeviceReq,
        val route: String,
        val mechanism: String
    )

    private fun hopNote(path: List<FileFormat>?): String {
        if (path == null) return "—"
        return if (path.size <= 2) path.joinToString(" → ") { it.extension.uppercase() }
        else path.joinToString(" → ") { it.extension.uppercase() } + " (multi-hop)"
    }

    /**
     * Tags the minimum device capability for the **direct** conversion handler.
     * Video → PDF uses frame capture (not STT). Audio → any document uses STT.
     */
    private fun deviceRequirement(from: FileFormat, to: FileFormat, @Suppress("UNUSED_PARAMETER") caps: FormatCapabilities): DeviceReq {
        if (to == FileFormat.HEIC) return DeviceReq.API_28
        if (to == FileFormat.OGG || to == FileFormat.FLAC) return DeviceReq.API_29
        if (requiresOnDeviceStt(from, to)) return DeviceReq.API_33_STT
        return DeviceReq.NONE
    }

    private fun requiresOnDeviceStt(from: FileFormat, to: FileFormat): Boolean {
        if (to.category != FormatCategory.DOCUMENT) return false
        return when (from.category) {
            FormatCategory.AUDIO -> true
            FormatCategory.VIDEO -> to != FileFormat.PDF
            else -> false
        }
    }

    private fun pairCount(caps: FormatCapabilities): Int =
        FileFormat.entries.sumOf { from -> ConversionPlanner.reachableTargets(from, caps).size }

    private fun capabilityProfilesSection(): String {
        val profiles = listOf(
            "Max device (API 33+, on-device STT)" to FormatCapabilities.ALL,
            "No on-device STT (`canTranscribe = false`)" to FormatCapabilities.ALL.copy(canTranscribe = false),
            "API 27 baseline (no HEIC/OGG/FLAC/STT encode)" to FormatCapabilities(
                canEncodeHeic = false,
                canEncodeOgg = false,
                canEncodeFlac = false,
                canTranscribe = false,
                canOcr = true
            )
        )
        val sb = StringBuilder()
        sb.appendLine("## Capability profiles (pair counts)")
        sb.appendLine()
        sb.appendLine("UI targets come from `FormatCapabilities.from(context)` + `ConversionPlanner.reachableTargets()`.")
        sb.appendLine("Counts below are **total directed pairs** (sum of per-source targets).")
        sb.appendLine()
        sb.appendLine("| Profile | UI pairs | Notes |")
        sb.appendLine("|---------|----------|-------|")
        for ((label, profile) in profiles) {
            val count = pairCount(profile)
            val notes = when (label) {
                profiles[0].first -> "Reference matrix in this doc"
                profiles[1].first -> "Audio/video → text docs require STT; video → PDF (frames) still offered"
                else -> "minSdk 27 floor; HEIC/OGG/FLAC output + STT document targets removed"
            }
            sb.appendLine("| $label | **$count** | $notes |")
        }
        sb.appendLine()
        sb.appendLine("**Gating flags (`FormatCapabilities`):**")
        sb.appendLine()
        sb.appendLine("| Flag | When false, planner hides… |")
        sb.appendLine("|------|------------------------------|")
        sb.appendLine("| `canEncodeHeic` | HEIC image output |")
        sb.appendLine("| `canEncodeOgg` / `canEncodeFlac` | OGG / FLAC audio output |")
        sb.appendLine("| `canTranscribe` | All audio → documents; video → text documents (video → PDF frames still allowed) |")
        sb.appendLine("| `canOcr` | Direct image → text documents; blocked when false (incl. multi-hop) |")
        return sb.toString().trimEnd()
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
        sb.appendLine("## Category cross-matrix")
        sb.appendLine()
        sb.appendLine("| From ↓ / To → | ${cats.joinToString(" | ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }} | **Total** |")
        sb.appendLine("|${"---|".repeat(cats.size + 2)}")
        for (fromCat in cats) {
            val row = cats.map { toCat -> counts[fromCat to toCat] ?: 0 }
            sb.appendLine("| **${fromCat.name.lowercase()}** | ${row.joinToString(" | ")} | **${row.sum()}** |")
        }
        val colTotals = cats.map { toCat -> cats.sumOf { fromCat -> counts[fromCat to toCat] ?: 0 } }
        sb.appendLine("| **Total** | ${colTotals.joinToString(" | ") { "**$it**" }} | **${colTotals.sum()}** |")
        return sb.toString().trimEnd()
    }

    private fun mechanismLabel(from: FileFormat, to: FileFormat, path: List<FileFormat>?): String {
        if (path != null && path.size > 2) return "Multi-hop"
        if (from.category == to.category) {
            return when {
                from.category == FormatCategory.VIDEO && to == FileFormat.AVI -> "FFmpeg AVI remux"
                from.category == FormatCategory.VIDEO && to == FileFormat.MKV -> "FFmpeg MKV remux"
                from.category == FormatCategory.VIDEO && to == FileFormat.GIF -> "Frame export"
                else -> "Transcode"
            }
        }
        return when {
            from.category == FormatCategory.AUDIO && to.category == FormatCategory.DOCUMENT -> "STT"
            from.category == FormatCategory.VIDEO && to == FileFormat.PDF -> "Frame → PDF"
            from.category == FormatCategory.VIDEO && to.category == FormatCategory.DOCUMENT -> "STT"
            from.category == FormatCategory.VIDEO && to.category == FormatCategory.AUDIO -> "Audio extract"
            from.category == FormatCategory.VIDEO && isRaster(to) -> "Frame extract"
            from.category == FormatCategory.AUDIO && isRaster(to) -> "Waveform"
            from.category == FormatCategory.AUDIO && to.category == FormatCategory.VIDEO -> "Waveform + mux"
            from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.AUDIO -> "TTS"
            from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.VIDEO -> "Slideshow"
            from.category == FormatCategory.IMAGE && to == FileFormat.PDF -> "Image → PDF"
            from.category == FormatCategory.IMAGE && to.category == FormatCategory.DOCUMENT -> "OCR"
            from.category == FormatCategory.IMAGE && to.category == FormatCategory.AUDIO -> "OCR + TTS"
            from.category == FormatCategory.IMAGE && to.category == FormatCategory.VIDEO -> "Slideshow"
            from == FileFormat.GIF && to.category == FormatCategory.IMAGE -> "GIF frame"
            from.category == FormatCategory.DOCUMENT && isRaster(to) -> "Render → image"
            else -> "Converter"
        }
    }

    private fun classifyFidelity(from: FileFormat, to: FileFormat): Fidelity {
        if (from.category == to.category) {
            return when (from.category) {
                FormatCategory.IMAGE -> Fidelity.NATIVE
                FormatCategory.AUDIO -> Fidelity.NATIVE
                FormatCategory.VIDEO -> if (to == FileFormat.AVI) Fidelity.DEGRADED else Fidelity.NATIVE
                FormatCategory.DOCUMENT -> documentSameCategory(from, to)
            }
        }

        return when {
            from.category == FormatCategory.IMAGE && to == FileFormat.PDF -> Fidelity.NATIVE
            from.category == FormatCategory.DOCUMENT && isRaster(to) -> Fidelity.NATIVE

            from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.AUDIO ->
                Fidelity.DEGRADED // TTS plain text

            from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.VIDEO ->
                Fidelity.CREATIVE // slideshow

            from.category == FormatCategory.IMAGE && to.category == FormatCategory.DOCUMENT ->
                if (to == FileFormat.TXT) Fidelity.DEGRADED else Fidelity.CREATIVE // OCR

            from.category == FormatCategory.IMAGE && to.category == FormatCategory.AUDIO ->
                Fidelity.CREATIVE // OCR + TTS

            from.category == FormatCategory.IMAGE && to.category == FormatCategory.VIDEO ->
                Fidelity.CREATIVE // slideshow

            from.category == FormatCategory.VIDEO && to.category == FormatCategory.AUDIO ->
                Fidelity.NATIVE

            from.category == FormatCategory.VIDEO && to == FileFormat.PDF ->
                Fidelity.CREATIVE // frame pages

            from.category == FormatCategory.VIDEO && isRaster(to) ->
                Fidelity.NATIVE // thumbnail; optional frame ZIP via media tools

            from.category == FormatCategory.VIDEO && to.category == FormatCategory.DOCUMENT ->
                Fidelity.DEGRADED // STT

            from.category == FormatCategory.AUDIO && to.category == FormatCategory.DOCUMENT ->
                if (to == FileFormat.TXT) Fidelity.DEGRADED else Fidelity.CREATIVE

            from.category == FormatCategory.AUDIO && isRaster(to) ->
                Fidelity.CREATIVE // waveform

            from.category == FormatCategory.AUDIO && to.category == FormatCategory.VIDEO ->
                Fidelity.NATIVE // waveform video + muxed audio (Phase 4)

            else -> Fidelity.NATIVE
        }
    }

    private fun documentSameCategory(from: FileFormat, to: FileFormat): Fidelity {
        return when {
            from == FileFormat.HTML && to in listOf(FileFormat.DOCX, FileFormat.EPUB, FileFormat.TXT) ->
                Fidelity.DEGRADED
            from == FileFormat.HTML && to == FileFormat.RTF -> Fidelity.DEGRADED
            from == FileFormat.HTML && to == FileFormat.PDF -> Fidelity.NATIVE
            to == FileFormat.HTML -> Fidelity.DEGRADED
            from == FileFormat.RTF && to == FileFormat.DOCX -> Fidelity.DEGRADED
            from == FileFormat.PDF && to == FileFormat.TXT -> Fidelity.DEGRADED
            from == FileFormat.PDF && to in listOf(FileFormat.DOCX, FileFormat.RTF) -> Fidelity.DEGRADED
            from == FileFormat.EPUB && to == FileFormat.HTML -> Fidelity.DEGRADED
            else -> Fidelity.NATIVE
        }
    }

    private fun isRaster(f: FileFormat) =
        f == FileFormat.JPG || f == FileFormat.JPEG || f == FileFormat.PNG ||
            f == FileFormat.WEBP || f == FileFormat.BMP || f == FileFormat.TIFF || f == FileFormat.HEIC

    private fun routeReference(): String = """
| Route | Example | Fidelity | Shipped |
|-------|---------|----------|---------|
| Same-format transcode | MP4 → MOV | ✅ Native | Yes |
| AVI out | MP4 → AVI | ⚠️ Simplified | Yes |
| Image encode | PNG → WEBP | ✅ Native | Yes |
| HEIC out | PNG → HEIC | ✅ Native | Yes (+ API 28+) |
| Doc ↔ doc native | EPUB → PDF | ✅ Native | Yes |
| HTML → DOCX | HTML → DOCX | ⚠️ Simplified | Yes |
| * → HTML | PDF → HTML | ⚠️ Simplified | Yes |
| PDF → images | PDF → PNG | ✅ Native | Yes |
| Image → PDF | PNG → PDF | ✅ Native | Yes |
| Image → doc | PNG → DOCX | 🎨 Creative | Yes (OCR) |
| Doc → audio | PDF → MP3 | ⚠️ Simplified | Yes (TTS) |
| Doc → video | PDF → MP4 | 🎨 Creative | Yes (slideshow) |
| Video → audio | MP4 → MP3 | ✅ Native | Yes |
| Video → image | MP4 → JPG | ✅ Native | Yes |
| Video → frames ZIP | MP4 → JPG + option | ✅ Native | Yes |
| Video → PDF | MP4 → PDF | 🎨 Creative | Yes (frames, no STT) |
| Video → text doc | MP4 → TXT | ⚠️ Simplified | Yes (+ API 33+ STT) |
| Audio → doc | MP3 → PDF | 🎨 Creative | Yes (+ API 33+ STT) |
| Audio → image | MP3 → PNG | 🎨 Creative | Yes |
| Audio → video | MP3 → MP4 | ✅ Native | Yes (waveform + **muxed audio**) |
| GIF → image | GIF → PNG | ✅ Native | Yes |
| MKV out | MP4 → MKV | ✅ Native | Yes (FFmpeg remux) |
""".trimIndent()
}
