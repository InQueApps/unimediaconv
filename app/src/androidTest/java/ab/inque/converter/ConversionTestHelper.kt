package ab.inque.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assume
import org.junit.Assert.assertTrue
import java.io.File

object ConversionTestHelper {

    const val TEST_MP4_ASSET = "conversions/test.mp4"

    fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    /** Context for files bundled in androidTest/assets (test APK, not the app under test). */
    private fun testAssetContext(): Context =
        InstrumentationRegistry.getInstrumentation().context

    fun copyAssetToCache(assetName: String, destName: String): File {
        val out = File(appContext().cacheDir, destName)
        testAssetContext().assets.open(assetName).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    /** Bundled H.264/AAC ~1s landscape clip from androidTest assets. */
    fun copyTestMp4(): File =
        copyAssetToCache(TEST_MP4_ASSET, "test_${System.currentTimeMillis()}.mp4")

    /** Skip when FFmpeg-Kit is not loadable (some instrumented runtimes). */
    fun assumeFfmpegAvailable() {
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-version"))
            Assume.assumeTrue(
                "FFmpeg not available in this runtime",
                ReturnCode.isSuccess(session.returnCode)
            )
        } catch (e: Throwable) {
            Assume.assumeTrue("FFmpeg not available: ${e.message}", false)
        }
    }

    /** Minimal 1×1 PNG (red pixel) for image conversion smoke tests. */
    fun writeMinimalPng(cacheFile: File) {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44,
            0x41, 0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(),
            0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00, 0x03, 0x01,
            0x01, 0x00, 0x18, 0xDD.toByte(), 0x8D.toByte(), 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        cacheFile.writeBytes(png)
    }

    /** Solid-color square PNG for geometry round-trip tests. */
    fun writeSquarePng(file: File, size: Int, fillColor: Int = Color.RED, borderColor: Int = Color.GREEN) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(fillColor)
        if (size >= 4) {
            val paint = Paint().apply {
                color = borderColor
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRect(1f, 1f, size - 2f, size - 2f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Landscape or portrait labeled PNG for aspect-ratio slideshow tests. */
    fun writeAspectPng(file: File, width: Int, height: Int, label: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (minOf(width, height) / 8f).coerceAtLeast(12f)
        }
        canvas.drawText(label, 16f, height / 2f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    fun convert(
        source: File,
        target: FileFormat,
        quality: QualityPreset = QualityPreset.MEDIUM,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        val converter = UniversalConverter()
        return converter.convertFile(
            sourceFile = source,
            targetFormat = target,
            context = appContext(),
            progressCallback = {},
            quality = quality,
            toolOptions = toolOptions
        )
    }

    fun decodeBounds(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    fun hasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return true
            }
        } finally {
            extractor.release()
        }
        return false
    }

    fun videoRotationDegrees(file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    return format.getInteger(MediaFormat.KEY_ROTATION)
                }
            }
        } finally {
            extractor.release()
        }
        return 0
    }

    fun videoDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    fun assertAspectRatioEquals(
        w1: Int, h1: Int,
        w2: Int, h2: Int,
        tolerance: Float = 0.05f,
        message: String = "aspect ratio mismatch: $w1×$h1 vs $w2×$h2"
    ) {
        assertTrue(message, BitmapGeometryUtil.aspectRatiosClose(w1, h1, w2, h2, tolerance))
    }

    fun convertImagesToSlideshow(
        imageFiles: List<File>,
        target: FileFormat,
        quality: QualityPreset = QualityPreset.MEDIUM
    ): File {
        val sourceFormat = FileFormat.fromExtension(imageFiles.first().extension) ?: FileFormat.PNG
        return UniversalConverter().convertImagesToVideo(
            imageFiles, sourceFormat, target, appContext(), {}, quality
        )
    }

    fun assertOutputExistsAndNonEmpty(output: File, expectedExtension: String) {
        check(output.exists()) { "Output missing: ${output.absolutePath}" }
        check(output.length() > 0L) { "Output empty: ${output.absolutePath}" }
        check(output.extension.equals(expectedExtension, ignoreCase = true)) {
            "Expected .$expectedExtension, got .${output.extension}"
        }
    }
}
