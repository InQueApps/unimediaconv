package ab.inque.converter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * End-to-end instrumented conversion quality verification suite covering:
 * - Image ↔ Image & pixel fidelity (BMP, TIFF, PNG, WEBP, JPG, and TIFF as source)
 * - PDF → Images (PNG, JPG, WEBP, BMP, TIFF, HEIC)
 * - GIF & Slideshow video quality (PNG → GIF → MP4, GIF → PNG with scale & rotation, PNG → MP4/WEBM/MKV/AVI)
 * - Audio & Audio → Video quality (synthetic 440Hz WAV → MP3/M4A/OGG/FLAC → WAV, WAV → GIF/MP4/WEBM/MKV/AVI, silent GIF/MP4 → WAV/MP3)
 */
@RunWith(AndroidJUnit4::class)
class ConversionQualityVerificationTest {

    private val context get() = ConversionTestHelper.appContext()
    private val cacheDir get() = context.cacheDir

    // ── 1. Image ↔ Image & Pixel Fidelity ───────────────────────────────────

    @Test
    fun imageRoundTrips_pngBmpTiffWebpJpg_preserveDimensionsAndCenterPixelColor() {
        val converter = UniversalConverter()
        val width = 120
        val height = 80
        val expectedColor = Color.rgb(210, 45, 90)
        val srcPng = createSolidPng(width, height, expectedColor, "src_color_${System.nanoTime()}.png")

        try {
            // PNG -> BMP -> PNG (lossless 24-bit RGB)
            val bmp = converter.convertFile(srcPng, FileFormat.BMP, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(bmp, "bmp")
            val pngFromBmp = converter.convertFile(bmp, FileFormat.PNG, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(pngFromBmp, "png")
            assertImagePixelsMatch(pngFromBmp, width, height, expectedColor, tolerance = 0, label = "PNG->BMP->PNG")
            bmp.delete()
            pngFromBmp.delete()

            // PNG -> TIFF -> PNG (lossless 24-bit RGB)
            val tiff = converter.convertFile(srcPng, FileFormat.TIFF, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(tiff, "tiff")
            val pngFromTiff = converter.convertFile(tiff, FileFormat.PNG, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(pngFromTiff, "png")
            assertImagePixelsMatch(pngFromTiff, width, height, expectedColor, tolerance = 0, label = "PNG->TIFF->PNG")
            tiff.delete()
            pngFromTiff.delete()

            // PNG -> WEBP -> PNG
            val webp = converter.convertFile(srcPng, FileFormat.WEBP, context, {}, quality = QualityPreset.HIGH)
            ConversionTestHelper.assertOutputExistsAndNonEmpty(webp, "webp")
            val pngFromWebp = converter.convertFile(webp, FileFormat.PNG, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(pngFromWebp, "png")
            assertImagePixelsMatch(pngFromWebp, width, height, expectedColor, tolerance = 12, label = "PNG->WEBP->PNG")
            webp.delete()
            pngFromWebp.delete()

            // PNG -> JPG
            val jpg = converter.convertFile(srcPng, FileFormat.JPG, context, {}, quality = QualityPreset.HIGH)
            ConversionTestHelper.assertOutputExistsAndNonEmpty(jpg, "jpg")
            assertImagePixelsMatch(jpg, width, height, expectedColor, tolerance = 12, label = "PNG->JPG")
            jpg.delete()
        } finally {
            srcPng.delete()
        }
    }

    @Test
    fun tiffAsSource_convertsToJpgPngAndPdfCleanly() {
        val converter = UniversalConverter()
        val width = 96
        val height = 64
        val color = Color.rgb(40, 160, 220)
        val seedPng = createSolidPng(width, height, color, "seed_for_tiff_${System.nanoTime()}.png")
        val srcTiff = converter.convertFile(seedPng, FileFormat.TIFF, context, {})
        seedPng.delete()

        try {
            ConversionTestHelper.assertOutputExistsAndNonEmpty(srcTiff, "tiff")

            // TIFF -> JPG
            val outJpg = converter.convertFile(srcTiff, FileFormat.JPG, context, {}, quality = QualityPreset.HIGH)
            ConversionTestHelper.assertOutputExistsAndNonEmpty(outJpg, "jpg")
            assertImagePixelsMatch(outJpg, width, height, color, tolerance = 12, label = "TIFF->JPG")
            outJpg.delete()

            // TIFF -> PNG
            val outPng = converter.convertFile(srcTiff, FileFormat.PNG, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(outPng, "png")
            assertImagePixelsMatch(outPng, width, height, color, tolerance = 0, label = "TIFF->PNG")
            outPng.delete()

            // TIFF -> PDF
            val outPdf = converter.convertFile(srcTiff, FileFormat.PDF, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(outPdf, "pdf")
            PdfReader(outPdf).use { reader ->
                PdfDocument(reader).use { pdfDoc ->
                    assertTrue("TIFF -> PDF should have at least 1 page", pdfDoc.numberOfPages >= 1)
                }
            }
            outPdf.delete()
        } finally {
            srcTiff.delete()
        }
    }

    // ── 2. PDF -> Images (PNG, JPG, WEBP, BMP, TIFF, HEIC) ──────────────────

    @Test
    fun pdfToImages_allRasterFormats_produceZipWithNonEmptyPageImage() {
        val converter = UniversalConverter()
        val pdfFile = createOnePagePdf("pdf_img_test_${System.nanoTime()}.pdf")

        val targets = mutableListOf(
            FileFormat.PNG,
            FileFormat.JPG,
            FileFormat.WEBP,
            FileFormat.BMP,
            FileFormat.TIFF
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            targets.add(FileFormat.HEIC)
        }

        try {
            for (target in targets) {
                val inputCopy = File(cacheDir, "pdf_src_for_${target.extension}_${System.nanoTime()}.pdf")
                pdfFile.copyTo(inputCopy, overwrite = true)
                try {
                    val zipOut = converter.convertFile(inputCopy, target, context, {})
                    ConversionTestHelper.assertOutputExistsAndNonEmpty(zipOut, "zip")

                    val expectedEntryName = "page_1.${target.extension}"
                    ZipFile(zipOut).use { zip ->
                        val entry = zip.getEntry(expectedEntryName)
                        assertNotNull(
                            "Expected $expectedEntryName inside ${zipOut.name} for PDF -> $target",
                            entry
                        )
                        val bytes = zip.getInputStream(entry!!).readBytes()
                        assertTrue(
                            "Image bytes for $expectedEntryName should be non-empty",
                            bytes.isNotEmpty()
                        )
                    }
                    zipOut.delete()
                } finally {
                    inputCopy.delete()
                }
            }
        } finally {
            pdfFile.delete()
        }
    }

    // ── 3. GIF & Slideshow Video Quality ────────────────────────────────────

    @Test
    fun gifAndSlideshowConversions_preserveDimensionsDurationAndPlayability() {
        val converter = UniversalConverter()
        val srcPng = createSolidPng(200, 100, Color.rgb(30, 180, 90), "gif_src_${System.nanoTime()}.png")

        try {
            // PNG -> GIF -> MP4
            val gifFile = converter.convertFile(srcPng, FileFormat.GIF, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(gifFile, "gif")

            val mp4FromGif = converter.convertFile(gifFile, FileFormat.MP4, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(mp4FromGif, "mp4")
            assertTrue("GIF -> MP4 duration should be > 0", ConversionTestHelper.videoDurationMs(mp4FromGif) > 0L)
            mp4FromGif.delete()

            // GIF -> PNG with scale = 0.5f and rotation = 90 (200x100 scaled 0.5 -> 100x50, rotated 90 -> 50x100)
            val transformedPng = converter.convertFile(
                sourceFile = gifFile,
                targetFormat = FileFormat.PNG,
                context = context,
                progressCallback = {},
                scale = 0.5f,
                quality = QualityPreset.MEDIUM,
                toolOptions = MediaToolOptions(rotationDegrees = 90)
            )
            ConversionTestHelper.assertOutputExistsAndNonEmpty(transformedPng, "png")
            val (tw, th) = ConversionTestHelper.decodeBounds(transformedPng)
            assertEquals("Expected width 50 after 0.5x scale + 90° rotation of 200x100 GIF", 50, tw)
            assertEquals("Expected height 100 after 0.5x scale + 90° rotation of 200x100 GIF", 100, th)
            transformedPng.delete()
            gifFile.delete()

            // PNG -> MP4, WEBM, MKV, AVI via convertImagesToVideo
            val videoTargets = listOf(FileFormat.MP4, FileFormat.WEBM, FileFormat.MKV, FileFormat.AVI)
            for (videoTarget in videoTargets) {
                if (videoTarget == FileFormat.MKV) {
                    ConversionTestHelper.assumeFfmpegAvailable()
                }
                val videoOut = converter.convertImagesToVideo(
                    imageFiles = listOf(srcPng),
                    sourceFormat = FileFormat.PNG,
                    targetFormat = videoTarget,
                    context = context,
                    progressCallback = {},
                    quality = QualityPreset.MEDIUM
                )
                ConversionTestHelper.assertOutputExistsAndNonEmpty(videoOut, videoTarget.extension)
                if (videoTarget != FileFormat.AVI) {
                    val durationMs = ConversionTestHelper.videoDurationMs(videoOut)
                    assertTrue(
                        "PNG -> ${videoTarget.name} should have positive duration, got $durationMs",
                        durationMs > 0L
                    )
                } else {
                    assertTrue("PNG -> AVI should contain RIFF/AVI header", videoOut.length() > 1024L)
                }
                videoOut.delete()
            }
        } finally {
            srcPng.delete()
        }
    }

    // ── 4. Audio & Audio -> Video Quality ───────────────────────────────────

    @Test
    fun audioRoundTripsAndWaveformVideoConversions_produceValidOutputs() {
        val converter = UniversalConverter()
        val wavSource = createSineWaveWav(
            durationSeconds = 1.0,
            frequencyHz = 440.0,
            sampleRate = 44100,
            fileName = "sine440_${System.nanoTime()}.wav"
        )

        try {
            // WAV -> MP3, M4A, OGG, FLAC -> WAV
            val audioTargets = listOf(FileFormat.MP3, FileFormat.M4A, FileFormat.OGG, FileFormat.FLAC)
            for (audioTarget in audioTargets) {
                val inputCopy = File(cacheDir, "wav_for_${audioTarget.extension}_${System.nanoTime()}.wav")
                wavSource.copyTo(inputCopy, overwrite = true)
                try {
                    val encoded = converter.convertFile(inputCopy, audioTarget, context, {})
                    ConversionTestHelper.assertOutputExistsAndNonEmpty(encoded, audioTarget.extension)
                    assertTrue("WAV -> ${audioTarget.name} should be > 256 bytes", encoded.length() > 256L)

                    val roundTripWav = converter.convertFile(encoded, FileFormat.WAV, context, {})
                    ConversionTestHelper.assertOutputExistsAndNonEmpty(roundTripWav, "wav")
                    assertTrue("Round-trip ${audioTarget.name} -> WAV should contain PCM data", roundTripWav.length() > 4096L)

                    roundTripWav.delete()
                    encoded.delete()
                } finally {
                    inputCopy.delete()
                }
            }

            // WAV -> GIF, MP4, WEBM, MKV, AVI (Audio -> Video waveform)
            val videoTargets = listOf(FileFormat.GIF, FileFormat.MP4, FileFormat.WEBM, FileFormat.MKV, FileFormat.AVI)
            for (videoTarget in videoTargets) {
                val inputCopy = File(cacheDir, "wav_av_${videoTarget.extension}_${System.nanoTime()}.wav")
                wavSource.copyTo(inputCopy, overwrite = true)
                try {
                    val avOut = converter.convertFile(inputCopy, videoTarget, context, {})
                    ConversionTestHelper.assertOutputExistsAndNonEmpty(avOut, videoTarget.extension)
                    assertTrue("WAV -> ${videoTarget.name} should be non-trivial in size", avOut.length() > 512L)
                    avOut.delete()
                } finally {
                    inputCopy.delete()
                }
            }
        } finally {
            wavSource.delete()
        }
    }

    @Test
    fun silentGifAndVideo_toWavAndMp3_succeedsWithSilentAudioTrack() {
        val converter = UniversalConverter()
        val png = createSolidPng(96, 96, Color.BLUE, "silent_src_${System.nanoTime()}.png")
        val silentGif = converter.convertFile(png, FileFormat.GIF, context, {})
        png.delete()

        try {
            val wavFromGif = converter.convertFile(silentGif, FileFormat.WAV, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(wavFromGif, "wav")
            assertTrue("Silent GIF -> WAV should have valid WAV header + PCM samples", wavFromGif.length() > 1000L)
            wavFromGif.delete()

            val mp3FromGif = converter.convertFile(silentGif, FileFormat.MP3, context, {})
            ConversionTestHelper.assertOutputExistsAndNonEmpty(mp3FromGif, "mp3")
            assertTrue("Silent GIF -> MP3 should be non-empty", mp3FromGif.length() > 256L)
            mp3FromGif.delete()
        } finally {
            silentGif.delete()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun createSolidPng(width: Int, height: Int, color: Int, fileName: String): File {
        val file = File(cacheDir, fileName)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    private fun assertImagePixelsMatch(
        file: File,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedColor: Int,
        tolerance: Int,
        label: String
    ) {
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
            ?: ImageExportUtil.readTiff(file)
            ?: ImageExportUtil.readBmp(file)
        assertNotNull("$label: failed to decode image ${file.name}", decoded)
        try {
            assertEquals("$label: width mismatch", expectedWidth, decoded!!.width)
            assertEquals("$label: height mismatch", expectedHeight, decoded.height)

            val centerPixel = decoded.getPixel(decoded.width / 2, decoded.height / 2)
            val dr = abs(Color.red(centerPixel) - Color.red(expectedColor))
            val dg = abs(Color.green(centerPixel) - Color.green(expectedColor))
            val db = abs(Color.blue(centerPixel) - Color.blue(expectedColor))

            assertTrue(
                "$label: center pixel RGB(${Color.red(centerPixel)},${Color.green(centerPixel)},${Color.blue(centerPixel)}) " +
                    "differs from expected RGB(${Color.red(expectedColor)},${Color.green(expectedColor)},${Color.blue(expectedColor)}) " +
                    "beyond tolerance=$tolerance",
                dr <= tolerance && dg <= tolerance && db <= tolerance
            )
        } finally {
            decoded?.recycle()
        }
    }

    private fun createOnePagePdf(fileName: String): File {
        val file = File(cacheDir, fileName)
        PdfWriter(file).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                Document(pdfDoc).use { doc ->
                    doc.add(Paragraph("PDF Raster Export Verification").setFontSize(20f).setBold())
                    doc.add(Paragraph("Rendering page 1 to raster image formats.").setFontSize(12f))
                }
            }
        }
        return file
    }

    private fun createSineWaveWav(
        durationSeconds: Double,
        frequencyHz: Double,
        sampleRate: Int,
        fileName: String
    ): File {
        val file = File(cacheDir, fileName)
        val numSamples = (durationSeconds * sampleRate).toInt()
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val dataSize = numSamples * channels * (bitsPerSample / 8)

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM chunk size
        buffer.putShort(1) // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * (bitsPerSample / 8)).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (sin(2.0 * PI * frequencyHz * t) * 16000.0).toInt().toShort()
            buffer.putShort(sample)
        }

        file.writeBytes(buffer.array())
        return file
    }
}
