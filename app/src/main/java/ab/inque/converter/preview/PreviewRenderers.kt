package ab.inque.converter.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import ab.inque.converter.BitmapGeometryUtil
import ab.inque.converter.DocxConverter
import ab.inque.converter.EpubConverter
import ab.inque.converter.FileFormat
import ab.inque.converter.MediaToolOptions
import ab.inque.converter.PdfConverter
import ab.inque.converter.pipeline.OcrHelper
import org.jsoup.Jsoup
import java.io.File

object PreviewImageRenderer {

  private const val MAX_PREVIEW_EDGE = 2560

    fun renderImage(
        context: Context,
        uri: Uri,
        fileName: String,
        sourceFormat: FileFormat,
        toolOptions: MediaToolOptions,
        scale: Float?
    ): Bitmap {
        val file = PreviewUriHelper.materializeIfNeeded(context, uri, fileName)
        var bitmap = decodeBitmap(file, sourceFormat)
        val oriented = applyExif(bitmap, file, sourceFormat)
        if (oriented !== bitmap) bitmap.recycle()
        bitmap = oriented
        if (toolOptions.hasRotation) {
            val rotated = BitmapGeometryUtil.rotateBitmap(bitmap, toolOptions.rotationDegrees)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        if (toolOptions.hasFlip) {
            val flipped = BitmapGeometryUtil.flipBitmap(
                bitmap, toolOptions.flipHorizontal, toolOptions.flipVertical
            )
            if (flipped !== bitmap) bitmap.recycle()
            bitmap = flipped
        }
        if (scale != null && scale > 0f && scale != 1f) {
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return capSize(bitmap)
    }

    fun renderVideoFrame(
        context: Context,
        uri: Uri,
        fileName: String,
        toolOptions: MediaToolOptions,
        durationMs: Long
    ): Bitmap {
        val file = PreviewUriHelper.materializeIfNeeded(context, uri, fileName)
        val (start, end) = ConversionPreviewPlanner.mediaWindow(toolOptions, durationMs)
        val sampleMs = when {
            toolOptions.hasExtractFrame -> toolOptions.extractFrameMs.coerceIn(start, end.coerceAtMost(durationMs))
            end > start -> (start + end) / 2
            durationMs > 0 -> durationMs / 2
            else -> 0L
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(
                sampleMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: throw java.io.IOException("No frame at selected time")
            var result = frame
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()?.takeIf { it in listOf(0, 90, 180, 270) } ?: 0
            if (rotation != 0) {
                val rotated = BitmapGeometryUtil.rotateBitmap(result, rotation)
                if (rotated !== result) result.recycle()
                result = rotated
            }
            if (toolOptions.hasRotation) {
                val userRot = BitmapGeometryUtil.rotateBitmap(result, toolOptions.rotationDegrees)
                if (userRot !== result) result.recycle()
                result = userRot
            }
            if (toolOptions.hasFlip) {
                val flipped = BitmapGeometryUtil.flipBitmap(
                    result, toolOptions.flipHorizontal, toolOptions.flipVertical
                )
                if (flipped !== result) result.recycle()
                result = flipped
            }
            return capSize(result)
        } finally {
            retriever.release()
        }
    }

    fun renderWaveform(context: Context, uri: Uri, fileName: String): Bitmap {
        val file = PreviewUriHelper.materializeIfNeeded(context, uri, fileName)
        val width = 1200
        val height = 320
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#121A24"))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#5C9BD5")
            strokeWidth = 2f
        }
        // Lightweight pseudo-waveform from file byte envelope (no full decode)
        val bytes = file.inputStream().use { it.readBytes().take(65536) }
        if (bytes.isNotEmpty()) {
            val mid = height / 2f
            val step = (bytes.size / width.toFloat()).coerceAtLeast(1f)
            var x = 0f
            var i = 0f
            while (x < width && i < bytes.size) {
                val b = bytes[i.toInt()].toInt() and 0xFF
                val amp = (b - 128) / 128f * (height * 0.4f)
                canvas.drawLine(x, mid - amp, x, mid + amp, paint)
                x += 1f
                i += step
            }
        }
        return bitmap
    }

    private fun decodeBitmap(file: File, format: FileFormat): Bitmap {
        if (format == FileFormat.HEIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(source)
        }
        return BitmapFactory.decodeFile(file.absolutePath)
            ?: throw java.io.IOException("Could not decode image")
    }

    private fun applyExif(bitmap: Bitmap, file: File, format: FileFormat): Bitmap {
        if (format == FileFormat.HEIC) return bitmap
        val matrix = BitmapGeometryUtil.exifOrientationMatrix(
            BitmapGeometryUtil.readExifOrientation(file)
        ) ?: return bitmap
        return BitmapGeometryUtil.transformBitmap(bitmap, matrix)
    }

    private fun capSize(bitmap: Bitmap): Bitmap {
        val max = MAX_PREVIEW_EDGE
        if (bitmap.width <= max && bitmap.height <= max) return bitmap
        val s = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
        val w = (bitmap.width * s).toInt().coerceAtLeast(1)
        val h = (bitmap.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}

object PreviewDocumentSampler {

    private const val TEXT_CHAR_LIMIT = 12_000
    private const val PDF_PREVIEW_DPI = 200

    fun sampleText(context: Context, file: File, format: FileFormat): String {
        val full = when (format) {
            FileFormat.TXT -> file.readText()
            FileFormat.RTF -> stripRtf(file.readText())
            FileFormat.HTML -> Jsoup.parse(file.readText()).text()
            FileFormat.PDF -> {
                val f = PdfConverter().convertPdfToText(file, context)
                try { f.readText() } finally { f.delete() }
            }
            FileFormat.DOCX -> {
                val f = DocxConverter().convertDocxToText(file, context)
                try { f.readText() } finally { f.delete() }
            }
            FileFormat.EPUB -> {
                val f = EpubConverter().convertEpubToText(file, context)
                try { f.readText() } finally { f.delete() }
            }
            else -> throw UnsupportedOperationException("No text preview for ${format.displayName}")
        }
        return full.take(TEXT_CHAR_LIMIT).let {
            if (full.length > TEXT_CHAR_LIMIT) "$it\n\n…" else it
        }
    }

    fun renderPdfPages(context: Context, file: File, maxPages: Int = 3): List<Bitmap> {
        if (!file.name.lowercase().endsWith(".pdf")) {
            throw IllegalArgumentException("Not a PDF file")
        }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            val count = minOf(renderer.pageCount, maxPages)
            val pages = mutableListOf<Bitmap>()
            for (i in 0 until count) {
                renderer.openPage(i).use { page ->
                    val scale = PDF_PREVIEW_DPI / 72f
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pages.add(bmp)
                }
            }
            return pages
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    fun ocrSample(context: Context, uri: Uri, fileName: String): String {
        val file = PreviewUriHelper.materializeIfNeeded(context, uri, fileName)
        return OcrHelper.recognizeTextFromFile(file).take(TEXT_CHAR_LIMIT)
    }

    private fun stripRtf(rtf: String): String =
        rtf.replace(Regex("\\\\[a-z]+\\d* ?"), "")
            .replace(Regex("[{}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
