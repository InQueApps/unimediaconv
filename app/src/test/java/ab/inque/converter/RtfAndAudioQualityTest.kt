package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPipeline
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class RtfAndAudioQualityTest {

    private class StubJvmContext(private val cacheAndFilesDir: File) : ContextWrapper(null) {
        override fun getCacheDir(): File = cacheAndFilesDir
        override fun getFilesDir(): File = cacheAndFilesDir
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun rtfUtil_escapeAndStripRoundTrip_preservesUnicodeSpecialCharsAndParagraphs() {
        val original = "Café — résumé • 東京\nSecond paragraph with {braces} and \\backslash\\\nThird line"
        val escaped = RtfUtil.escapeRtfText(original)
        val wrappedRtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0 Arial;}}\\f0\\fs24 $escaped}"

        val stripped = RtfUtil.stripRtf(wrappedRtf)
        assertEquals(original, stripped)
    }

    @Test
    fun rtfUtil_stripRtf_skipsNestedDestinationsAndDecodesWindows1252HexEscapes() {
        val rtf = buildString {
            append("{\\rtf1\\ansi\\deff0")
            append("{\\fonttbl{\\f0\\froman Times New Roman;}{\\f1\\fswiss Helvetica;}}")
            append("{\\colortbl;\\red255\\green0\\blue0;\\red0\\green128\\blue0;}")
            append("{\\*\\generator CustomRtfEngine 2.0;}")
            append("{\\stylesheet{\\s0 Normal;}}")
            append("\\f0\\fs24 ")
            append("Caf\\'e9 \\'93quoted\\'94 text\\par ")
            append("Second paragraph\\line next line")
            append("}")
        }

        val plain = RtfUtil.stripRtf(rtf)
        assertFalse("Font table must be stripped", plain.contains("Times New Roman"))
        assertFalse("Generator destination must be stripped", plain.contains("CustomRtfEngine"))
        assertFalse("Stylesheet must be stripped", plain.contains("Normal"))
        assertTrue("Expected decoded Windows-1252 hex escapes, got: $plain", plain.contains("Café \u201Cquoted\u201D text"))
        assertTrue("Expected multi-paragraph line breaks, got: $plain", plain.contains("Café \u201Cquoted\u201D text\nSecond paragraph\nnext line"))
    }

    @Test
    fun imageExportUtil_readTiffPixels_supportsLittleEndianAndBigEndian() {
        val width = 5
        val height = 4
        val expectedPixels = IntArray(width * height) { idx ->
            val r = (idx * 37 + 15) and 0xFF
            val g = (idx * 59 + 80) and 0xFF
            val b = (idx * 97 + 140) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // 1. Little-Endian ('II') via writeTiffFromPixels
        val leBytes = ByteArrayOutputStream().use { out ->
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
        assertEquals('I'.code.toByte(), leBytes[0])
        assertEquals('I'.code.toByte(), leBytes[1])
        val leDecoded = ImageExportUtil.readTiffPixels(leBytes)
        assertNotNull("Little-Endian TIFF should decode cleanly", leDecoded)
        assertEquals(width, leDecoded!!.width)
        assertEquals(height, leDecoded.height)
        assertTrue("Little-Endian TIFF pixels must match", expectedPixels.contentEquals(leDecoded.argbPixels))

        // 2. Big-Endian ('MM') synthetic uncompressed RGB TIFF
        val mmBytes = buildBigEndianTiff(width, height, expectedPixels)
        assertEquals('M'.code.toByte(), mmBytes[0])
        assertEquals('M'.code.toByte(), mmBytes[1])
        val mmDecoded = ImageExportUtil.readTiffPixels(mmBytes)
        assertNotNull("Big-Endian TIFF should decode cleanly", mmDecoded)
        assertEquals(width, mmDecoded!!.width)
        assertEquals(height, mmDecoded.height)
        assertTrue("Big-Endian TIFF pixels must match", expectedPixels.contentEquals(mmDecoded.argbPixels))
    }

    @Test
    fun imageExportUtil_readBmpPixels_supportsBottomUpAndTopDown() {
        val width = 7 // exercises 4-byte row padding
        val height = 5
        val expectedPixels = IntArray(width * height) { idx ->
            val r = (idx * 43 + 10) and 0xFF
            val g = (idx * 71 + 20) and 0xFF
            val b = (idx * 19 + 200) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // 1. Bottom-up BMP (positive height)
        val bottomUpBytes = ByteArrayOutputStream().use { out ->
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
        val bottomUpDecoded = ImageExportUtil.readBmpPixels(bottomUpBytes)
        assertNotNull("Bottom-up BMP should decode cleanly", bottomUpDecoded)
        assertEquals(width, bottomUpDecoded!!.width)
        assertEquals(height, bottomUpDecoded.height)
        assertTrue("Bottom-up BMP pixels must match", expectedPixels.contentEquals(bottomUpDecoded.argbPixels))

        // 2. Top-down BMP (negative height in DIB header)
        val topDownBytes = buildTopDownBmp(width, height, expectedPixels)
        val topDownDecoded = ImageExportUtil.readBmpPixels(topDownBytes)
        assertNotNull("Top-down BMP should decode cleanly", topDownDecoded)
        assertEquals(width, topDownDecoded!!.width)
        assertEquals(height, topDownDecoded.height)
        assertTrue("Top-down BMP pixels must match", expectedPixels.contentEquals(topDownDecoded.argbPixels))
    }

    @Test
    fun conversionPipeline_executeTwoHopHtmlToPdfToTxt_cleansUpIntermediatePdfInCacheDir() {
        val tempRoot = Files.createTempDirectory("pipeline_hop_cleanup_").toFile()
        try {
            val cacheDir = File(tempRoot, "cache").apply { mkdirs() }
            val stubContext = StubJvmContext(cacheDir)

            val sourceHtml = File(tempRoot, "input_source.html")
            sourceHtml.writeText(
                """<!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"/><title>Pipeline Cleanup Test</title></head>
                <body>
                  <h1>Multi-Hop Pipeline Header</h1>
                  <p>Unique marker phrase for intermediate cleanup verification.</p>
                </body>
                </html>""".trimIndent(),
                Charsets.UTF_8
            )

            val progressValues = mutableListOf<Float>()
            val finalTxt = ConversionPipeline.execute(
                converter = UniversalConverter(),
                sourceFile = sourceHtml,
                path = listOf(FileFormat.HTML, FileFormat.PDF, FileFormat.TXT),
                context = stubContext,
                progressCallback = { p -> progressValues.add(p) },
                scale = null,
                quality = QualityPreset.MEDIUM,
                toolOptions = MediaToolOptions()
            )

            assertTrue("Final TXT output must exist", finalTxt.exists() && finalTxt.length() > 0L)
            assertEquals("txt", finalTxt.extension.lowercase())
            assertTrue(
                "Final TXT output should preserve source text",
                finalTxt.readText().contains("intermediate cleanup verification", ignoreCase = true)
            )
            assertTrue("Progress callback must complete at 1.0f", progressValues.isNotEmpty() && progressValues.last() == 1f)

            val leftoverPdfs = cacheDir.listFiles()?.filter { it.extension.equals("pdf", ignoreCase = true) }.orEmpty()
            assertTrue(
                "Intermediate .pdf file must be deleted from cacheDir after multi-hop execution, found: $leftoverPdfs",
                leftoverPdfs.isEmpty()
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun buildBigEndianTiff(width: Int, height: Int, argbPixels: IntArray): ByteArray {
        val samplesPerPixel = 3
        val imageDataSize = width * height * samplesPerPixel
        val numTags = 9
        val ifdOffset = 8
        val ifdSize = 2 + numTags * 12 + 4
        val bpsOffset = ifdOffset + ifdSize
        val stripOffset = bpsOffset + 6
        val totalSize = stripOffset + imageDataSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.put('M'.code.toByte())
        buf.put('M'.code.toByte())
        buf.putShort(42.toShort())
        buf.putInt(ifdOffset)

        buf.putShort(numTags.toShort())
        fun putTagLong(tag: Int, count: Int, value: Int) {
            buf.putShort(tag.toShort())
            buf.putShort(4.toShort()) // LONG (4 bytes)
            buf.putInt(count)
            buf.putInt(value)
        }
        putTagLong(256, 1, width)
        putTagLong(257, 1, height)
        // Tag 258: BitsPerSample (3 SHORT values at bpsOffset)
        buf.putShort(258.toShort())
        buf.putShort(3.toShort()) // SHORT
        buf.putInt(3)
        buf.putInt(bpsOffset)
        putTagLong(259, 1, 1) // Compression = Uncompressed
        putTagLong(262, 1, 2) // PhotometricInterpretation = RGB
        putTagLong(273, 1, stripOffset) // StripOffsets
        putTagLong(277, 1, samplesPerPixel) // SamplesPerPixel
        putTagLong(278, 1, height) // RowsPerStrip
        putTagLong(279, 1, imageDataSize) // StripByteCounts
        buf.putInt(0) // next IFD offset

        // BitsPerSample values (8, 8, 8)
        buf.putShort(8.toShort())
        buf.putShort(8.toShort())
        buf.putShort(8.toShort())

        // Pixel RGB data
        for (p in argbPixels) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
        }
        return buf.array()
    }

    private fun buildTopDownBmp(width: Int, height: Int, argbPixels: IntArray): ByteArray {
        val rowBytes = width * 3
        val rowPadding = (4 - (rowBytes % 4)) % 4
        val paddedRowBytes = rowBytes + rowPadding
        val imageSize = paddedRowBytes * height
        val fileSize = 54 + imageSize

        val buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte())
        buf.put('M'.code.toByte())
        buf.putInt(fileSize)
        buf.putInt(0)
        buf.putInt(54)

        buf.putInt(40) // DIB header size
        buf.putInt(width)
        buf.putInt(-height) // Negative height indicates top-down DIB
        buf.putShort(1.toShort())
        buf.putShort(24.toShort())
        buf.putInt(0) // BI_RGB
        buf.putInt(imageSize)
        buf.putInt(2835)
        buf.putInt(2835)
        buf.putInt(0)
        buf.putInt(0)

        val pad = ByteArray(rowPadding)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argbPixels[y * width + x]
                buf.put((p and 0xFF).toByte()) // B
                buf.put(((p shr 8) and 0xFF).toByte()) // G
                buf.put(((p shr 16) and 0xFF).toByte()) // R
            }
            if (rowPadding > 0) buf.put(pad)
        }
        return buf.array()
    }
}
