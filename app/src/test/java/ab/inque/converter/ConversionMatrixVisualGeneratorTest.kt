package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import org.junit.Test
import java.io.File

/**
 * Generates scripts/conversion_matrix.html — interactive visual conversion grid.
 * Run: ./gradlew :app:testDebugUnitTest --tests ConversionMatrixVisualGeneratorTest
 */
class ConversionMatrixVisualGeneratorTest {

  private enum class Fidelity(val code: String, val css: String, val label: String) {
    NATIVE("N", "cell-native", "Native"),
    DEGRADED("D", "cell-degraded", "Simplified"),
    CREATIVE("C", "cell-creative", "Creative"),
    NONE("-", "cell-none", "Not offered")
  }

  /** Pairs covered by instrumented E2E smokes (subset). */
  private val e2eTestedPairs = setOf(
    FileFormat.TXT to FileFormat.PDF,
    FileFormat.HTML to FileFormat.PDF,
    FileFormat.HTML to FileFormat.RTF,
    FileFormat.PNG to FileFormat.PDF,
    FileFormat.TXT to FileFormat.DOCX,
    FileFormat.RTF to FileFormat.PDF,
    FileFormat.MP4 to FileFormat.MP3,
    FileFormat.MP4 to FileFormat.JPG,
    FileFormat.PNG to FileFormat.JPG,
    FileFormat.MP4 to FileFormat.WAV,
    FileFormat.TXT to FileFormat.HTML,
    FileFormat.HTML to FileFormat.TXT,
    FileFormat.HTML to FileFormat.DOCX,
    FileFormat.TXT to FileFormat.MP3,
    FileFormat.PNG to FileFormat.TXT,
    FileFormat.MP4 to FileFormat.PDF,
    FileFormat.MP4 to FileFormat.TXT
  )

  @Test
  fun generateVisualMatrixHtml() {
    val caps = FormatCapabilities.ALL
    val formats = FileFormat.entries.toList()
    val sb = StringBuilder()
    sb.appendLine("<!DOCTYPE html>")
    sb.appendLine("<html lang=\"en\"><head>")
    sb.appendLine("<meta charset=\"UTF-8\"/>")
    sb.appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
    sb.appendLine("<title>AnyConvert — Conversion Matrix (600 pairs)</title>")
    sb.appendLine("<style>")
    sb.append(css())
    sb.appendLine("</style></head><body>")
    sb.appendLine("<header>")
    sb.appendLine("<h1>AnyConvert conversion matrix</h1>")
    sb.appendLine("<p class=\"sub\">26 formats · <strong>600</strong> directed pairs (max device, API 33+ STT) · generated from <code>ConversionPlanner</code></p>")
    sb.appendLine(legendHtml())
    sb.appendLine(categoryHeatmapHtml(caps))
    sb.appendLine("</header>")
    sb.appendLine("<main>")
    sb.appendLine("<p class=\"hint\">Scroll the grid. Hover a cell for route detail. Blue ring = E2E smoke tested on device.</p>")
    sb.appendLine("<div class=\"grid-wrap\"><table class=\"matrix\">")
    sb.appendLine("<thead><tr><th class=\"corner\">From \\ To</th>")
    for (to in formats) {
      sb.appendLine("<th class=\"col-hdr\" title=\"${to.displayName}\">${to.extension.uppercase()}</th>")
    }
    sb.appendLine("</tr></thead><tbody>")
    for (from in formats) {
      sb.appendLine("<tr>")
      sb.appendLine("<th class=\"row-hdr\" title=\"${from.displayName}\">${from.extension.uppercase()}</th>")
      for (to in formats) {
        if (from == to) {
          sb.appendLine("<td class=\"cell-diag\">—</td>")
          continue
        }
        val path = ConversionPlanner.findPath(from, to, caps)
        if (path == null) {
          sb.appendLine("<td class=\"cell-none\" title=\"Not offered\">·</td>")
          continue
        }
        val fid = classifyFidelity(from, to)
        val tested = (from to to) in e2eTestedPairs
        val route = path.joinToString(" → ") { it.extension.uppercase() }
        val mech = mechanismShort(from, to, path)
        val cls = buildString {
          append(fid.css)
          if (tested) append(" cell-tested")
        }
        val title = "${from.displayName} → ${to.displayName}\\n${fid.label} · $mech\\n$route"
        sb.appendLine("<td class=\"$cls\" title=\"$title\">${fid.code}</td>")
      }
      sb.appendLine("</tr>")
    }
    sb.appendLine("</tbody></table></div>")
    sb.appendLine(testCoverageSection())
    sb.appendLine("</main>")
    sb.appendLine("<footer><p>Regenerate: <code>.\\gradlew :app:testDebugUnitTest --tests ConversionMatrixVisualGeneratorTest</code></p></footer>")
    sb.appendLine("</body></html>")

    val out = outputFile()
    out.parentFile.mkdirs()
    out.writeText(sb.toString())
  }

  private fun outputFile(): File {
    System.getenv("UNIMEDIACONV_SCRIPTS_DIR")?.takeIf { it.isNotBlank() }?.let {
      return File(it, "conversion_matrix.html")
    }
    val fromApp = File("../scripts/conversion_matrix.html")
    if (fromApp.parentFile?.isDirectory == true) return fromApp
    return File("C:/Users/inque/OneDrive/Desktop/Work/code/appdev/android/unimediaconv/scripts/conversion_matrix.html")
  }

  private fun legendHtml(): String = """
    <div class="legend">
      <span><i class="swatch cell-native"></i> Native</span>
      <span><i class="swatch cell-degraded"></i> Simplified</span>
      <span><i class="swatch cell-creative"></i> Creative</span>
      <span><i class="swatch cell-tested"></i> E2E smoke tested</span>
      <span class="codes">Cell codes: N / D / C</span>
    </div>
  """.trimIndent()

  private fun categoryHeatmapHtml(caps: FormatCapabilities): String {
    val cats = FormatCategory.entries
    val counts = mutableMapOf<Pair<FormatCategory, FormatCategory>, Int>()
    for (from in FileFormat.entries) {
      for (to in ConversionPlanner.reachableTargets(from, caps)) {
        val key = from.category to to.category
        counts[key] = (counts[key] ?: 0) + 1
      }
    }
    val max = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val sb = StringBuilder()
    sb.appendLine("<section class=\"cat-matrix\"><h2>Category cross-matrix (pair counts)</h2>")
    sb.appendLine("<table class=\"cat-table\"><thead><tr><th></th>")
    for (c in cats) sb.appendLine("<th>${c.name.lowercase()}</th>")
    sb.appendLine("<th>Σ</th></tr></thead><tbody>")
    for (fromCat in cats) {
      sb.appendLine("<tr><th>${fromCat.name.lowercase()}</th>")
      var rowSum = 0
      for (toCat in cats) {
        val n = counts[fromCat to toCat] ?: 0
        rowSum += n
        val intensity = (n.toFloat() / max * 100).toInt()
        sb.appendLine("<td class=\"heat\" style=\"background:color-mix(in srgb, #2d6a4f $intensity%, #1a1a2e)\">$n</td>")
      }
      sb.appendLine("<td class=\"sum\">$rowSum</td></tr>")
    }
    sb.appendLine("</tbody></table></section>")
    return sb.toString()
  }

  private fun testCoverageSection(): String {
    val jvm = 58
    val device = 49
    val e2e = e2eTestedPairs.size
    return """
      <section class="tests">
        <h2>Automated test coverage</h2>
        <table class="info">
          <tr><td>Planner sync (all 600 paths)</td><td><strong>✅</strong> PlannerHandlerSyncTest</td></tr>
          <tr><td>JVM unit tests</td><td><strong>$jvm</strong></td></tr>
          <tr><td>Instrumented tests</td><td><strong>~$device</strong> (device required)</td></tr>
          <tr><td>E2E conversion smokes (pairs)</td><td><strong>$e2e</strong> of 600 (ring on grid)</td></tr>
          <tr><td>CI on PR</td><td>unit-tests + release-16kb</td></tr>
        </table>
        <p>Full inventory: <code>Work/skills/unimediaconv/TEST_COVERAGE.md</code></p>
      </section>
    """.trimIndent()
  }

  private fun css(): String = """
    :root { --bg:#0f0f1a; --fg:#e8e8f0; --muted:#8888aa; --accent:#6c9eff; }
    * { box-sizing:border-box; }
    body { font-family:system-ui,sans-serif; background:var(--bg); color:var(--fg); margin:0; padding:1rem 1.5rem 2rem; }
    h1 { margin:0 0 .25rem; font-size:1.5rem; }
    .sub { color:var(--muted); margin:0 0 1rem; }
    .legend { display:flex; flex-wrap:wrap; gap:1rem; margin:1rem 0; font-size:.85rem; align-items:center; }
    .swatch { display:inline-block; width:14px; height:14px; border-radius:3px; vertical-align:middle; margin-right:4px; }
    .codes { color:var(--muted); margin-left:auto; }
    .hint { color:var(--muted); font-size:.9rem; }
    .grid-wrap { overflow:auto; max-height:75vh; border:1px solid #333; border-radius:8px; margin:1rem 0; }
    table.matrix { border-collapse:collapse; font-size:11px; }
    table.matrix th, table.matrix td { border:1px solid #2a2a3a; padding:3px 4px; text-align:center; min-width:28px; }
    .corner { position:sticky; left:0; top:0; z-index:3; background:#1a1a2e; }
    .col-hdr { position:sticky; top:0; z-index:2; background:#1a1a2e; font-weight:600; writing-mode:vertical-rl; transform:rotate(180deg); height:72px; }
    .row-hdr { position:sticky; left:0; z-index:1; background:#1a1a2e; font-weight:600; text-align:right; padding-right:6px; }
    .cell-native { background:#2d6a4f; color:#fff; font-weight:600; cursor:help; }
    .cell-degraded { background:#e9c46a; color:#1a1a2e; font-weight:600; cursor:help; }
    .cell-creative { background:#e76f51; color:#fff; font-weight:600; cursor:help; }
    .cell-none { background:#1e1e2e; color:#444; }
    .cell-diag { background:#252535; color:#555; }
    .cell-tested { box-shadow:inset 0 0 0 2px var(--accent); }
    .cat-matrix { margin:1.5rem 0; }
    .cat-table th, .cat-table td { border:1px solid #333; padding:6px 10px; text-align:center; }
    .cat-table .heat { color:#fff; font-weight:600; min-width:48px; }
    .cat-table .sum { font-weight:700; background:#1a1a2e; }
    .tests { margin-top:2rem; }
    .info td { padding:6px 12px; border-bottom:1px solid #333; }
    footer { margin-top:2rem; color:var(--muted); font-size:.8rem; }
  """.trimIndent()

  private fun mechanismShort(from: FileFormat, to: FileFormat, path: List<FileFormat>?): String {
    if (path != null && path.size > 2) return "Multi-hop"
    if (from.category == to.category) return "Transcode"
    return when {
      from.category == FormatCategory.AUDIO && to.category == FormatCategory.DOCUMENT -> "STT"
      from.category == FormatCategory.VIDEO && to == FileFormat.PDF -> "Frames"
      from.category == FormatCategory.VIDEO && to.category == FormatCategory.DOCUMENT -> "STT/OCR"
      from.category == FormatCategory.IMAGE && to.category == FormatCategory.DOCUMENT -> "OCR"
      from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.AUDIO -> "TTS"
      else -> "Bridge"
    }
  }

  private fun classifyFidelity(from: FileFormat, to: FileFormat): Fidelity {
    if (from.category == to.category) {
      return when (from.category) {
        FormatCategory.VIDEO -> if (to == FileFormat.AVI) Fidelity.DEGRADED else Fidelity.NATIVE
        FormatCategory.DOCUMENT -> Fidelity.DEGRADED
        else -> Fidelity.NATIVE
      }
    }
    return when {
      from.category == FormatCategory.IMAGE && to.category == FormatCategory.DOCUMENT -> Fidelity.CREATIVE
      from.category == FormatCategory.IMAGE && to.category == FormatCategory.AUDIO -> Fidelity.CREATIVE
      from.category == FormatCategory.IMAGE && to.category == FormatCategory.VIDEO -> Fidelity.CREATIVE
      from.category == FormatCategory.VIDEO && to == FileFormat.PDF -> Fidelity.CREATIVE
      from.category == FormatCategory.VIDEO && to.category == FormatCategory.DOCUMENT -> Fidelity.DEGRADED
      from.category == FormatCategory.AUDIO && to.category == FormatCategory.DOCUMENT -> Fidelity.CREATIVE
      from.category == FormatCategory.AUDIO && isRaster(to) -> Fidelity.CREATIVE
      from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.AUDIO -> Fidelity.DEGRADED
      from.category == FormatCategory.DOCUMENT && to.category == FormatCategory.VIDEO -> Fidelity.CREATIVE
      else -> Fidelity.NATIVE
    }
  }

  private fun isRaster(f: FileFormat) =
    f == FileFormat.JPG || f == FileFormat.JPEG || f == FileFormat.PNG ||
      f == FileFormat.WEBP || f == FileFormat.BMP || f == FileFormat.TIFF || f == FileFormat.HEIC
}
