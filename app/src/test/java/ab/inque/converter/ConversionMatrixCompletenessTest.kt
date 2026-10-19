package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import ab.inque.converter.preview.ConversionPreviewPlanner
import ab.inque.converter.preview.PreviewStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the complete 25×24 = 600 conversion matrix, path hop validity, preview planner coverage,
 * and [HtmlStructureUtil] structural extraction quality.
 */
class ConversionMatrixCompletenessTest {

    @Test
    fun all600FormatPairs_areReachableWithValidPaths() {
        val formats = FileFormat.entries
        assertEquals("Expected 25 total FileFormat entries", 25, formats.size)

        var totalPairs = 0
        for (from in formats) {
            val reachable = ConversionPlanner.reachableTargets(from, FormatCapabilities.ALL)
            assertEquals(
                "Format $from should reach all ${formats.size - 1} other formats under FormatCapabilities.ALL",
                formats.size - 1,
                reachable.size
            )

            for (to in formats) {
                if (from == to) continue
                totalPairs++

                val path = ConversionPlanner.findPath(from, to, FormatCapabilities.ALL)
                assertNotNull("ConversionPlanner.findPath($from, $to, ALL) returned null", path)
                val nonNullPath = path!!

                assertEquals("Path for $from -> $to must start with $from", from, nonNullPath.first())
                assertEquals("Path for $from -> $to must end with $to", to, nonNullPath.last())
                assertTrue(
                    "Path length for $from -> $to must be in 2..4, got ${nonNullPath.size}: $nonNullPath",
                    nonNullPath.size in 2..4
                )

                for (i in 0 until nonNullPath.size - 1) {
                    val stepFrom = nonNullPath[i]
                    val stepTo = nonNullPath[i + 1]
                    val directNeighbors = ConversionPlanner.directTargets(stepFrom, FormatCapabilities.ALL)
                    assertTrue(
                        "Step $stepFrom -> $stepTo in path $nonNullPath is not a valid direct hop in directTargets($stepFrom)",
                        stepTo in directNeighbors
                    )
                }
            }
        }

        assertEquals("Expected 25 * 24 = 600 directed format pairs", 600, totalPairs)
    }

    @Test
    fun all600FormatPairs_haveAvailablePreviewStrategy() {
        val formats = FileFormat.entries
        val defaultOptions = MediaToolOptions()
        var count = 0

        for (from in formats) {
            for (to in formats) {
                if (from == to) continue
                count++

                val plan = ConversionPreviewPlanner.plan(from, to, defaultOptions)
                assertNotEquals(
                    "Preview strategy for $from -> $to should not be UNAVAILABLE",
                    PreviewStrategy.UNAVAILABLE,
                    plan.strategy
                )
                assertTrue(
                    "Preview subtitle for $from -> $to should not be blank",
                    plan.subtitle.isNotBlank()
                )
            }
        }

        assertEquals(600, count)
    }

    @Test
    fun htmlStructureUtil_extractsComplexHtmlCleanly() {
        val complexHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Ignored Head Title</title>
            </head>
            <body>
                <h1>Main Report &amp; Overview</h1>
                <div class="container">
                    <div class="nested">
                        <h2>Section 1: Summary</h2>
                        <p>Line one with<br/>explicit break &amp; &quot;quoted&quot; &lt;tags&gt;.</p>
                    </div>
                </div>
                <ul>
                    <li>First bullet item</li>
                    <li>Second bullet item</li>
                </ul>
                <table>
                    <thead>
                        <tr><th>Metric</th><th>Value</th></tr>
                    </thead>
                    <tbody>
                        <tr><td>Latency</td><td>12ms</td></tr>
                        <tr><td>Throughput</td><td>980 req/s</td></tr>
                    </tbody>
                </table>
            </body>
            </html>
        """.trimIndent()

        val lines = HtmlStructureUtil.linesFromHtml(complexHtml)
        assertTrue("Expected multiple structural lines, got: $lines", lines.size >= 7)

        val plainText = HtmlStructureUtil.plainTextFromHtml(complexHtml)
        assertTrue("Decoded '&' entity expected", plainText.contains("Main Report & Overview"))
        assertTrue("Section heading expected", plainText.contains("Section 1: Summary"))
        assertTrue("Line before <br> expected", plainText.contains("Line one with"))
        assertTrue("Decoded quotes and angle brackets expected", plainText.contains("explicit break & \"quoted\" <tags>."))
        assertTrue("List items expected", plainText.contains("First bullet item") && plainText.contains("Second bullet item"))
        assertTrue("Table row cells expected", plainText.contains("Latency") && plainText.contains("12ms"))
        assertTrue("Second table row expected", plainText.contains("Throughput") && plainText.contains("980 req/s"))
        assertTrue("Paragraph separation should preserve newlines", plainText.contains("\n\n"))
    }

    @Test
    fun imageExportUtil_bmpAndTiffPixelRoundTrips_preserveExactPixels() {
        val width = 17 // odd width exercises BMP 4-byte row padding
        val height = 11
        val expectedPixels = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            val r = (x * 13 + y * 7) and 0xFF
            val g = (x * 29 + y * 53) and 0xFF
            val b = (x * 41 + y * 19) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val bmpBytes = java.io.ByteArrayOutputStream().use { out ->
            ImageExportUtil.writeBmpFromPixels(
                width = width,
                height = height,
                getRowPixels = { y, rowOut ->
                    System.arraycopy(expectedPixels, y * width, rowOut, 0, width)
                },
                out = out
            )
            out.toByteArray()
        }
        val decodedBmp = ImageExportUtil.readBmpPixels(bmpBytes)
        assertNotNull("BMP pixels should decode cleanly", decodedBmp)
        assertEquals(width, decodedBmp!!.width)
        assertEquals(height, decodedBmp.height)
        assertTrue("BMP pixel array should match exact RGB values", expectedPixels.contentEquals(decodedBmp.argbPixels))

        val tiffBytes = java.io.ByteArrayOutputStream().use { out ->
            ImageExportUtil.writeTiffFromPixels(
                width = width,
                height = height,
                getRowPixels = { y, rowOut ->
                    System.arraycopy(expectedPixels, y * width, rowOut, 0, width)
                },
                out = out
            )
            out.toByteArray()
        }
        val decodedTiff = ImageExportUtil.readTiffPixels(tiffBytes)
        assertNotNull("TIFF pixels should decode cleanly", decodedTiff)
        assertEquals(width, decodedTiff!!.width)
        assertEquals(height, decodedTiff.height)
        assertTrue("TIFF pixel array should match exact RGB values", expectedPixels.contentEquals(decodedTiff.argbPixels))
    }
}
