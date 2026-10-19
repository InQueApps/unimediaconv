package ab.inque.converter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioFormat
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import androidx.heifwriter.HeifWriter
import ab.inque.converter.pipeline.ConversionPipeline
import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import ab.inque.converter.pipeline.FormatSniffer
import ab.inque.converter.pipeline.OcrHelper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

class UniversalConverter {

    /** Avoid writing transcode output over the source file when extensions match. */
    private fun cacheOutputFile(context: Context, sourceFile: File, extension: String): File {
        val direct = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.$extension")
        if (direct.absolutePath != sourceFile.absolutePath) return direct
        return File(context.cacheDir, "${sourceFile.nameWithoutExtension}_out_${System.currentTimeMillis()}.$extension")
    }
    
    fun convertFile(
        sourceFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        scale: Float? = null,
        quality: QualityPreset = QualityPreset.MEDIUM,
        toolOptions: MediaToolOptions = MediaToolOptions(),
        allowMultiHop: Boolean = true
    ): File {
        val sourceFormat = FileFormat.fromExtension(sourceFile.extension)
            ?: FormatSniffer.sniff(sourceFile)
            ?: throw IllegalArgumentException("Unsupported source format")

        val caps = FormatCapabilities.from(context)

        // GIF / video file → still image (first frame)
        if (sourceFormat == FileFormat.GIF && targetFormat.category == FormatCategory.IMAGE) {
            return convertGifToImage(sourceFile, targetFormat, context, progressCallback, scale, quality, toolOptions)
        }

        if (allowMultiHop) {
            val path = ConversionPlanner.findPath(sourceFormat, targetFormat, caps)
                ?: throw IllegalArgumentException(
                    "Cannot convert from ${sourceFormat.displayName} to ${targetFormat.displayName}"
                )
            if (path.size > 2) {
                return ConversionPipeline.execute(
                    this, sourceFile, path, context, progressCallback, scale, quality, toolOptions
                )
            }
        }

        // Cross-category: Image → PDF
        if (sourceFormat.category == FormatCategory.IMAGE && targetFormat == FileFormat.PDF) {
            return convertImageToPdf(sourceFile, sourceFormat, context, progressCallback, scale)
        }
        // Cross-category: PDF → Images
        if (sourceFormat == FileFormat.PDF && isRasterImageFormat(targetFormat)) {
            return convertDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Cross-category: Document → Images (via PDF intermediate)
        if (sourceFormat.category == FormatCategory.DOCUMENT && isRasterImageFormat(targetFormat)) {
            return convertDocumentToImages(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Cross-category: Image → Document (OCR)
        if (sourceFormat.category == FormatCategory.IMAGE && targetFormat.category == FormatCategory.DOCUMENT) {
            return convertImageToDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Cross-category: Image → Audio (OCR + TTS)
        if (sourceFormat.category == FormatCategory.IMAGE && targetFormat.category == FormatCategory.AUDIO) {
            return convertImageToAudio(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Cross-category: Image → Video (slideshow)
        if (sourceFormat.category == FormatCategory.IMAGE && targetFormat.category == FormatCategory.VIDEO) {
            return convertImagesToVideo(listOf(sourceFile), sourceFormat, targetFormat, context, progressCallback, quality)
        }
        // Cross-category: Video → Audio
        if (sourceFormat.category == FormatCategory.VIDEO && targetFormat.category == FormatCategory.AUDIO) {
            return extractAudioFromVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback, toolOptions)
        }
        // Cross-category: Video → PDF
        if (sourceFormat.category == FormatCategory.VIDEO && targetFormat == FileFormat.PDF) {
            return convertVideoToPdf(sourceFile, context, progressCallback, toolOptions)
        }
        // Cross-category: Video → Image
        if (sourceFormat.category == FormatCategory.VIDEO && isRasterImageFormat(targetFormat)) {
            return convertVideoToImage(sourceFile, targetFormat, context, progressCallback, toolOptions)
        }
        // Cross-category: Video → Document (extract audio → STT)
        if (sourceFormat.category == FormatCategory.VIDEO && targetFormat.category == FormatCategory.DOCUMENT) {
            return convertVideoToDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback, toolOptions)
        }
        // Cross-category: Document → Audio (TTS)
        if (sourceFormat.category == FormatCategory.DOCUMENT && targetFormat.category == FormatCategory.AUDIO) {
            return convertDocumentToAudio(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Cross-category: Document → Video (render pages as slideshow)
        if (sourceFormat.category == FormatCategory.DOCUMENT && targetFormat.category == FormatCategory.VIDEO) {
            return convertDocumentToVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback, quality)
        }
        // Cross-category: Audio → Document (STT)
        if (sourceFormat.category == FormatCategory.AUDIO && targetFormat.category == FormatCategory.DOCUMENT) {
            return convertAudioToDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Cross-category: Audio → Image (waveform)
        if (sourceFormat.category == FormatCategory.AUDIO && isRasterImageFormat(targetFormat)) {
            return convertAudioToImage(sourceFile, targetFormat, context, progressCallback, toolOptions)
        }
        // Cross-category: Audio → Video (static visual + audio)
        if (sourceFormat.category == FormatCategory.AUDIO && targetFormat.category == FormatCategory.VIDEO) {
            return convertAudioToVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback, quality, toolOptions)
        }
        // Same category or allowed
        if (sourceFormat.category != targetFormat.category) {
            throw IllegalArgumentException("Cannot convert from ${sourceFormat.displayName} to ${targetFormat.displayName}")
        }

        // Fast lossless remux for same-format video/audio with trim/speed only (no flip)
        if (sourceFormat == targetFormat
            && sourceFormat != FileFormat.GIF
            && sourceFormat != FileFormat.AVI
            && (sourceFormat.category == FormatCategory.VIDEO || sourceFormat.category == FormatCategory.AUDIO)
            && (toolOptions.hasTrim || toolOptions.hasSpeed) && !toolOptions.hasFlip && !toolOptions.hasRotation
        ) {
            return remuxWithTools(sourceFile, targetFormat, context, progressCallback, toolOptions)
        }
        
        return when (sourceFormat.category) {
            FormatCategory.DOCUMENT -> convertDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
            FormatCategory.IMAGE -> convertImage(sourceFile, sourceFormat, targetFormat, context, progressCallback, scale, quality, toolOptions)
            FormatCategory.VIDEO -> when (targetFormat) {
                FileFormat.GIF -> convertVideoToGif(sourceFile, context, progressCallback, toolOptions)
                FileFormat.AVI -> convertVideoToAvi(sourceFile, sourceFormat, context, progressCallback, quality, toolOptions)
                else -> convertVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback, quality, toolOptions)
            }
            FormatCategory.AUDIO -> convertAudio(sourceFile, sourceFormat, targetFormat, context, progressCallback, quality, toolOptions)
        }
    }
    
    private fun convertDocument(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.05f) // File validation
        
        return when {
            sourceFormat == FileFormat.EPUB && targetFormat == FileFormat.PDF -> {
                val epubConverter = EpubConverter()
                epubConverter.convertEpubToPdf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.EPUB && targetFormat == FileFormat.DOCX -> {
                val epubConverter = EpubConverter()
                epubConverter.convertEpubToWord(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.EPUB && targetFormat == FileFormat.TXT -> {
                val epubConverter = EpubConverter()
                epubConverter.convertEpubToText(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.EPUB && targetFormat == FileFormat.RTF -> {
                val epubConverter = EpubConverter()
                epubConverter.convertEpubToRtf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.PDF && targetFormat == FileFormat.EPUB -> {
                val pdfConverter = PdfConverter()
                pdfConverter.convertPdfToEpub(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.PDF && targetFormat == FileFormat.DOCX -> {
                val pdfConverter = PdfConverter()
                pdfConverter.convertPdfToDocx(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.PDF && targetFormat == FileFormat.RTF -> {
                val pdfConverter = PdfConverter()
                pdfConverter.convertPdfToRtf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.PDF && targetFormat == FileFormat.TXT -> {
                val pdfConverter = PdfConverter()
                pdfConverter.convertPdfToText(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.TXT && targetFormat == FileFormat.PDF -> {
                convertTxtToPdf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.TXT && targetFormat == FileFormat.DOCX -> {
                convertTxtToDocx(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.TXT && targetFormat == FileFormat.RTF -> {
                convertTxtToRtf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.TXT && targetFormat == FileFormat.EPUB -> {
                convertTxtToEpub(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.RTF && targetFormat == FileFormat.PDF -> {
                convertRtfToPdf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.RTF && targetFormat == FileFormat.TXT -> {
                convertRtfToTxt(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.RTF && targetFormat == FileFormat.DOCX -> {
                convertRtfToDocx(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.RTF && targetFormat == FileFormat.EPUB -> {
                convertRtfToEpub(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.DOCX && targetFormat == FileFormat.PDF -> {
                val docxConverter = DocxConverter()
                docxConverter.convertDocxToPdf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.DOCX && targetFormat == FileFormat.RTF -> {
                val docxConverter = DocxConverter()
                docxConverter.convertDocxToRtf(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.DOCX && targetFormat == FileFormat.TXT -> {
                val docxConverter = DocxConverter()
                docxConverter.convertDocxToText(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.DOCX && targetFormat == FileFormat.EPUB -> {
                val docxConverter = DocxConverter()
                docxConverter.convertDocxToEpub(sourceFile, context, progressCallback)
            }
            sourceFormat == FileFormat.HTML && targetFormat == FileFormat.PDF -> convertHtmlToPdf(sourceFile, context, progressCallback)
            sourceFormat == FileFormat.HTML && targetFormat == FileFormat.DOCX -> convertHtmlToDocx(sourceFile, context, progressCallback)
            sourceFormat == FileFormat.HTML && targetFormat == FileFormat.TXT -> convertHtmlToTxt(sourceFile, context, progressCallback)
            sourceFormat == FileFormat.HTML && targetFormat == FileFormat.RTF -> convertHtmlToRtf(sourceFile, context, progressCallback)
            sourceFormat == FileFormat.HTML && targetFormat == FileFormat.EPUB -> convertHtmlToEpub(sourceFile, context, progressCallback)
            sourceFormat == FileFormat.PDF && targetFormat == FileFormat.HTML -> convertDocumentToHtml(sourceFile, { PdfConverter().convertPdfToText(it, context, progressCallback) }, context, progressCallback)
            sourceFormat == FileFormat.DOCX && targetFormat == FileFormat.HTML -> convertDocumentToHtml(sourceFile, { DocxConverter().convertDocxToText(it, context, progressCallback) }, context, progressCallback)
            sourceFormat == FileFormat.TXT && targetFormat == FileFormat.HTML -> convertTxtToHtml(sourceFile, context, progressCallback)
            sourceFormat == FileFormat.EPUB && targetFormat == FileFormat.HTML -> convertDocumentToHtml(sourceFile, { EpubConverter().convertEpubToText(it, context, progressCallback) }, context, progressCallback)
            sourceFormat == FileFormat.RTF && targetFormat == FileFormat.HTML -> convertDocumentToHtml(sourceFile, { convertRtfToTxt(it, context, progressCallback) }, context, progressCallback)
            sourceFormat == FileFormat.PDF && isRasterImageFormat(targetFormat) -> {
                val pdfConverter = PdfConverter()
                pdfConverter.convertPdfToImages(sourceFile, context, targetFormat, progressCallback)
            }
            else -> {
                throw UnsupportedOperationException("Conversion from ${sourceFormat.displayName} to ${targetFormat.displayName} is not yet implemented")
            }
        }.also { progressCallback(1.0f) }
    }
    
    private fun decodeBitmapWithFallback(sourceFile: File, sourceFormat: FileFormat? = null): Bitmap? {
        val fmt = sourceFormat
            ?: FileFormat.fromExtension(sourceFile.extension)
            ?: FormatSniffer.sniff(sourceFile)
        if (fmt == FileFormat.HEIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(sourceFile)
                return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) {}
        }
        BitmapFactory.decodeFile(sourceFile.absolutePath)?.let { return it }
        ImageExportUtil.readTiff(sourceFile)?.let { return it }
        ImageExportUtil.readBmp(sourceFile)?.let { return it }
        return null
    }

    private fun extractTextFromImage(sourceFile: File): String {
        return OcrHelper.recognizeTextFromFile(sourceFile)
    }

    private fun convertImage(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        scale: Float? = null,
        quality: QualityPreset = QualityPreset.MEDIUM,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        progressCallback(0.1f) // Start conversion
        
        // Add debugging
        android.util.Log.d("ImageConverter", "Converting from ${sourceFormat.displayName} to ${targetFormat.displayName}")
        android.util.Log.d("ImageConverter", "Source file: ${sourceFile.absolutePath}")
        android.util.Log.d("ImageConverter", "File exists: ${sourceFile.exists()}")
        android.util.Log.d("ImageConverter", "File size: ${sourceFile.length()} bytes")
        
        progressCallback(0.2f) // Starting image decoding
        val bitmap = when (sourceFormat) {
            FileFormat.JPG, FileFormat.JPEG -> {
                android.util.Log.d("ImageConverter", "Decoding JPEG image")
                BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            FileFormat.PNG -> {
                android.util.Log.d("ImageConverter", "Decoding PNG image")
                BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            FileFormat.BMP -> {
                android.util.Log.d("ImageConverter", "Decoding BMP image")
                BitmapFactory.decodeFile(sourceFile.absolutePath) ?: ImageExportUtil.readBmp(sourceFile)
            }
            FileFormat.TIFF -> {
                android.util.Log.d("ImageConverter", "Decoding TIFF image")
                BitmapFactory.decodeFile(sourceFile.absolutePath) ?: ImageExportUtil.readTiff(sourceFile)
            }
            FileFormat.WEBP -> {
                android.util.Log.d("ImageConverter", "Decoding WebP image")
                BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            FileFormat.HEIC -> {
                android.util.Log.d("ImageConverter", "Decoding HEIC image")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(sourceFile)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    BitmapFactory.decodeFile(sourceFile.absolutePath)
                }
            }
            else -> throw UnsupportedOperationException("Unsupported image format: ${sourceFormat.displayName}")
        } ?: decodeBitmapWithFallback(sourceFile, sourceFormat)
            ?: throw IOException("Failed to decode image")
        
        android.util.Log.d("ImageConverter", "Bitmap decoded successfully: ${bitmap.width}x${bitmap.height}")
        
        progressCallback(0.4f) // Image decoded successfully

        // Apply EXIF orientation so output is not flipped/rotated vs. how the image displays in gallery
        val orientedBitmap = applyExifOrientation(bitmap, sourceFile, sourceFormat == FileFormat.HEIC)
        if (orientedBitmap != bitmap) bitmap.recycle()

        // Optionally scale the bitmap
        progressCallback(0.5f) // Starting scaling (if needed)
        val scaledBitmap = if (scale != null && scale > 0f && scale != 1.0f) {
            val newWidth = (orientedBitmap.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (orientedBitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(orientedBitmap, newWidth, newHeight, true)
        } else {
            orientedBitmap
        }
        progressCallback(0.6f) // Scaling complete

        // Apply rotation (90°, 180°, 270°) if requested
        val rotatedBitmap = if (toolOptions.hasRotation) {
            val result = BitmapGeometryUtil.rotateBitmap(scaledBitmap, toolOptions.rotationDegrees)
            if (result != scaledBitmap && scaledBitmap != bitmap) scaledBitmap.recycle()
            result
        } else {
            scaledBitmap
        }

        // Apply flip / mirror if requested
        val flippedBitmap = if (toolOptions.hasFlip) {
            val result = BitmapGeometryUtil.flipBitmap(
                rotatedBitmap,
                toolOptions.flipHorizontal,
                toolOptions.flipVertical
            )
            if (result != rotatedBitmap && rotatedBitmap != scaledBitmap) rotatedBitmap.recycle()
            result
        } else {
            rotatedBitmap
        }

        progressCallback(0.7f) // Starting compression
        val outputFile = cacheOutputFile(context, sourceFile, targetFormat.extension)
        android.util.Log.d("ImageConverter", "Output file: ${outputFile.absolutePath}")

        val jpegQuality = when (quality) {
            QualityPreset.LOW -> 60
            QualityPreset.MEDIUM -> 82
            QualityPreset.HIGH -> 95
        }
        val webpQuality = when (quality) {
            QualityPreset.LOW -> 65
            QualityPreset.MEDIUM -> 82
            QualityPreset.HIGH -> 95
        }
        
        val outputStream = FileOutputStream(outputFile)
        val success = when (targetFormat) {
            FileFormat.JPG, FileFormat.JPEG -> {
                android.util.Log.d("ImageConverter", "Compressing to JPEG (quality=$jpegQuality)")
                flippedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
            }
            FileFormat.PNG -> {
                android.util.Log.d("ImageConverter", "Compressing to PNG")
                flippedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            FileFormat.WEBP -> {
                android.util.Log.d("ImageConverter", "Compressing to WebP (quality=$webpQuality)")
                flippedBitmap.compress(Bitmap.CompressFormat.WEBP, webpQuality, outputStream)
            }
            FileFormat.BMP -> {
                android.util.Log.d("ImageConverter", "Encoding BMP")
                outputStream.close()
                writeBmp(flippedBitmap, outputFile)
                true
            }
            FileFormat.TIFF -> {
                android.util.Log.d("ImageConverter", "Encoding TIFF")
                outputStream.close()
                writeTiff(flippedBitmap, outputFile)
                true
            }
            FileFormat.HEIC -> {
                android.util.Log.d("ImageConverter", "Encoding HEIC (device-dependent)")
                outputStream.close()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { // HeifWriter needs API 28; P = 28
                    throw UnsupportedOperationException("HEIC output requires Android 9 (API 28) or later")
                }
                val heicQuality = when (quality) {
                    QualityPreset.LOW -> 70
                    QualityPreset.MEDIUM -> 85
                    QualityPreset.HIGH -> 95
                }
                writeHeic(flippedBitmap, outputFile, heicQuality)
                true
            }
            else -> throw UnsupportedOperationException("Unsupported target image format: ${targetFormat.displayName}")
        }
        
        outputStream.close()
        android.util.Log.d("ImageConverter", "Compression result: $success")
        android.util.Log.d("ImageConverter", "Output file size: ${outputFile.length()} bytes")
        
        progressCallback(0.9f) // Compression complete, cleaning up
        
        if (flippedBitmap != rotatedBitmap) flippedBitmap.recycle()
        if (rotatedBitmap != scaledBitmap) rotatedBitmap.recycle()
        if (scaledBitmap != orientedBitmap) scaledBitmap.recycle()
        orientedBitmap.recycle()
        
        if (!success) {
            throw IOException("Failed to compress image")
        }
        
        progressCallback(1.0f) // Conversion complete
        return outputFile
    }

    /**
     * Applies EXIF orientation so the bitmap matches how the image displays in gallery/viewers.
     * Returns the same bitmap if orientation is normal or unsupported; otherwise a new bitmap.
     * [decodedWithImageDecoder] — ImageDecoder already bakes EXIF into pixels (HEIC on API 28+).
     */
    private fun applyExifOrientation(
        bitmap: Bitmap,
        sourceFile: File,
        decodedWithImageDecoder: Boolean = false
    ): Bitmap {
        if (decodedWithImageDecoder) return bitmap
        val matrix = BitmapGeometryUtil.exifOrientationMatrix(
            BitmapGeometryUtil.readExifOrientation(sourceFile)
        ) ?: return bitmap
        return BitmapGeometryUtil.transformBitmap(bitmap, matrix)
    }
    
    private fun convertVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset = QualityPreset.MEDIUM,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        progressCallback(0.05f)

        if (sourceFormat == FileFormat.GIF) {
            return convertGifToVideo(sourceFile, targetFormat, context, progressCallback, quality, toolOptions)
        }
        
        if (targetFormat == FileFormat.MKV) {
            val mp4File = convertVideo(
                sourceFile, sourceFormat, FileFormat.MP4, context,
                { p -> progressCallback(p * 0.9f) },
                quality, toolOptions
            )
            val mkvFile = cacheOutputFile(context, sourceFile, "mkv")
            try {
                return ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToMkv(mp4File, mkvFile) { p ->
                    progressCallback(0.9f + 0.1f * p)
                }
            } finally {
                mp4File.delete()
            }
        }

        val outputFile = cacheOutputFile(context, sourceFile, targetFormat.extension)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            progressCallback(0.1f)
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> {
                        videoTrackIndex = i
                        videoFormat = format
                    }
                    mime.startsWith("audio/") -> {
                        audioTrackIndex = i
                        audioFormat = format
                    }
                }
            }
            if (videoTrackIndex == -1 || videoFormat == null) {
                throw IOException("No video track found in source file")
            }
            extractor.selectTrack(videoTrackIndex)

            val trimStartUs = if (toolOptions.hasTrim) toolOptions.trimStartMs * 1000L else 0L
            val trimEndUs = if (toolOptions.hasTrim) toolOptions.trimEndMs * 1000L else Long.MAX_VALUE
            if (toolOptions.hasTrim) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            // Pre-read audio samples (trim + speed adjusted) so we can mux with video
            data class AudioSample(val data: ByteArray, val size: Int, val presentationTimeUs: Long, val flags: Int)
            val audioSamples = mutableListOf<AudioSample>()
            if (audioTrackIndex >= 0 && audioFormat != null) {
                val audioExtractor = MediaExtractor()
                audioExtractor.setDataSource(sourceFile.absolutePath)
                audioExtractor.selectTrack(audioTrackIndex)
                if (toolOptions.hasTrim) {
                    audioExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
                val audioBuf = ByteBuffer.allocate(1024 * 1024)
                while (true) {
                    val size = audioExtractor.readSampleData(audioBuf, 0)
                    if (size < 0) break
                    val sampleTime = audioExtractor.sampleTime
                    if (toolOptions.hasTrim && sampleTime > trimEndUs + 500_000) break
                    if (sampleTime >= trimStartUs && sampleTime <= trimEndUs + 500_000) {
                        val adjustedPts = ((sampleTime - trimStartUs).coerceAtLeast(0) / toolOptions.speedFactor).toLong()
                        val copy = ByteArray(size)
                        audioBuf.position(0)
                        audioBuf.get(copy, 0, size)
                        audioSamples.add(AudioSample(copy, size, adjustedPts, audioExtractor.sampleFlags))
                    }
                    audioExtractor.advance()
                }
                audioExtractor.release()
            }

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val sourceRotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                videoFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            val fullDurationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
            val effectiveDurationUs = if (toolOptions.hasTrim) {
                ((trimEndUs.coerceAtMost(fullDurationUs) - trimStartUs) / toolOptions.speedFactor).toLong()
            } else {
                (fullDurationUs / toolOptions.speedFactor).toLong()
            }

            progressCallback(0.15f)
            val decoderMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(decoderMime)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()
            
            val (targetMime, muxerFormat) = when (targetFormat) {
                FileFormat.MP4, FileFormat.MOV, FileFormat.THREEGP -> "video/avc" to MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                FileFormat.WEBM -> "video/x-vnd.on2.vp8" to MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else -> throw IOException("Transcoding to ${targetFormat.displayName} is not supported on this device")
            }
            val outWidth: Int
            val outHeight: Int
            if (toolOptions.rotationDegrees == 90 || toolOptions.rotationDegrees == 270) {
                outWidth = height
                outHeight = width
            } else {
                outWidth = width
                outHeight = height
            }
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val encoderName = codecList.findEncoderForFormat(MediaFormat.createVideoFormat(targetMime, outWidth, outHeight))
                ?: throw IOException("No encoder found for target codec: $targetMime")

            val videoBitRate = when (quality) {
                QualityPreset.LOW -> 1_500_000
                QualityPreset.MEDIUM -> 4_000_000
                QualityPreset.HIGH -> 8_000_000
            }
            val encoderFormat = MediaFormat.createVideoFormat(targetMime, outWidth, outHeight)
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            encoder = MediaCodec.createByCodecName(encoderName)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
            var outputVideoTrackIndex = -1
            var outputAudioTrackIndex = -1
            var webmAudioNeedsFfmpegMux = false
            if (audioSamples.isNotEmpty() && audioFormat != null) {
                val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val addAudio = when (muxerFormat) {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> audioMime.startsWith("audio/")
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM -> audioMime == "audio/vorbis" || audioMime == "audio/opus"
                    else -> false
                }
                if (addAudio) {
                    try {
                        outputAudioTrackIndex = muxer!!.addTrack(audioFormat!!)
                    } catch (_: Exception) {
                        outputAudioTrackIndex = -1
                    }
                } else if (muxerFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM && audioMime.startsWith("audio/")) {
                    webmAudioNeedsFfmpegMux = true
                }
            }
            var muxerStarted = false
            var audioSampleIndex = 0
            
            val bufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                val rawPTS = extractor.sampleTime
                                if (toolOptions.hasTrim && rawPTS > trimEndUs) {
                                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEOS = true
                                } else {
                                    decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, rawPTS, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }
                }

                val decoderOutputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (decoderOutputBufferIndex >= 0) {
                    val decodedPTS = bufferInfo.presentationTimeUs
                    val beforeTrim = toolOptions.hasTrim && decodedPTS < trimStartUs

                    if (!beforeTrim && bufferInfo.size > 0) {
                        val adjustedPTS = ((decodedPTS - trimStartUs).coerceAtLeast(0) / toolOptions.speedFactor).toLong()
                        val decoderOutputBuffer = decoder.getOutputBuffer(decoderOutputBufferIndex)
                        if (decoderOutputBuffer != null) {
                            val encoderInputBufferIndex = encoder.dequeueInputBuffer(10000)
                            if (encoderInputBufferIndex >= 0) {
                                val encoderInputBuffer = encoder.getInputBuffer(encoderInputBufferIndex)
                                if (encoderInputBuffer != null) {
                                    encoderInputBuffer.clear()
                                    when {
                                        toolOptions.rotationDegrees == 90 || toolOptions.rotationDegrees == 270 -> {
                                            val frameData = ByteArray(bufferInfo.size)
                                            decoderOutputBuffer.get(frameData)
                                            val rotated = rotateFrameNV12(frameData, width, height, toolOptions.rotationDegrees)
                                            encoderInputBuffer.put(rotated)
                                            encoder.queueInputBuffer(encoderInputBufferIndex, 0, rotated.size, adjustedPTS, bufferInfo.flags)
                                        }
                                        toolOptions.rotationDegrees == 180 -> {
                                            val frameData = ByteArray(bufferInfo.size)
                                            decoderOutputBuffer.get(frameData)
                                            val rotated = rotateFrameNV12(frameData, width, height, 180)
                                            encoderInputBuffer.put(rotated)
                                            encoder.queueInputBuffer(encoderInputBufferIndex, 0, rotated.size, adjustedPTS, bufferInfo.flags)
                                        }
                                        toolOptions.hasFlip -> {
                                            val frameData = ByteArray(bufferInfo.size)
                                            decoderOutputBuffer.get(frameData)
                                            flipFrameNV12(frameData, width, height, toolOptions.flipHorizontal, toolOptions.flipVertical)
                                            encoderInputBuffer.put(frameData)
                                            encoder.queueInputBuffer(encoderInputBufferIndex, 0, bufferInfo.size, adjustedPTS, bufferInfo.flags)
                                        }
                                        else -> {
                                            encoderInputBuffer.put(decoderOutputBuffer)
                                            encoder.queueInputBuffer(encoderInputBufferIndex, 0, bufferInfo.size, adjustedPTS, bufferInfo.flags)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val isEOS = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEOS && !beforeTrim) {
                        val eosIdx = encoder.dequeueInputBuffer(10000)
                        if (eosIdx >= 0) {
                            encoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                    decoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                }

                val encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (encoderOutputBufferIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderOutputBufferIndex)
                    if (encodedData != null && bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            outputVideoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            val muxerOrientation = if (toolOptions.hasRotation) 0 else sourceRotation
                            if (muxerOrientation != 0) {
                                muxer.setOrientationHint(muxerOrientation)
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(outputVideoTrackIndex, encodedData, bufferInfo)
                        // Write any audio samples that are due before or at this video PTS
                        if (outputAudioTrackIndex >= 0) {
                            while (audioSampleIndex < audioSamples.size && audioSamples[audioSampleIndex].presentationTimeUs <= bufferInfo.presentationTimeUs) {
                                val sample = audioSamples[audioSampleIndex]
                                audioBufferInfo.set(0, sample.size, sample.presentationTimeUs, sample.flags)
                                val audioBuf = ByteBuffer.wrap(sample.data, 0, sample.size)
                                muxer.writeSampleData(outputAudioTrackIndex, audioBuf, audioBufferInfo)
                                audioSampleIndex++
                            }
                        }
                        if (effectiveDurationUs > 0) {
                            val progress = 0.1f + (0.8f * bufferInfo.presentationTimeUs / effectiveDurationUs)
                            progressCallback(progress.coerceAtMost(0.9f))
                        }
                    }
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }
            // Write any remaining audio samples (PTS after last video frame)
            if (outputAudioTrackIndex >= 0) {
                while (audioSampleIndex < audioSamples.size) {
                    val sample = audioSamples[audioSampleIndex]
                    audioBufferInfo.set(0, sample.size, sample.presentationTimeUs, sample.flags)
                    val audioBuf = ByteBuffer.wrap(sample.data, 0, sample.size)
                    muxer!!.writeSampleData(outputAudioTrackIndex, audioBuf, audioBufferInfo)
                    audioSampleIndex++
                }
            }
            progressCallback(1.0f)
            if (webmAudioNeedsFfmpegMux) {
                val withAudio = File(
                    context.cacheDir,
                    "${sourceFile.nameWithoutExtension}_webm.${targetFormat.extension}"
                )
                try {
                    ab.inque.converter.pipeline.FfmpegVideoExporter.muxWebmWithAudio(
                        videoOnly = outputFile,
                        audioSource = sourceFile,
                        output = withAudio,
                        toolOptions = toolOptions
                    ) { p -> progressCallback(0.95f + 0.05f * p) }
                    outputFile.delete()
                    return withAudio
                } catch (e: Exception) {
                    android.util.Log.w("VideoConverter", "WEBM audio mux via FFmpeg failed: ${e.message}")
                }
            }
            return outputFile
        } catch (e: Exception) {
            android.util.Log.e("VideoConverter", "Error transcoding video: ${e.message}")
            if (outputFile.exists()) { try { outputFile.delete() } catch (_: Exception) {} }
            throw IOException("Failed to transcode video: ${e.message}")
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Rotate NV12 (YUV420 semi-planar) by 90°, 180°, or 270°. Returns new buffer.
     * 90° CW: out[y][x] = in[height-1-x][y]. 270° CW: out[y][x] = in[x][width-1-y].
     */
    private fun rotateFrameNV12(data: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        val ySize = width * height
        val out = ByteArray(height * width * 3 / 2)
        when (degrees) {
            180 -> {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcY = y * width + x
                        val dstY = (height - 1 - y) * width + (width - 1 - x)
                        out[dstY] = data[srcY]
                    }
                }
                val uvH = height / 2
                val uvW = width / 2
                for (v in 0 until uvH) {
                    for (u in 0 until uvW) {
                        val srcOff = ySize + v * width + u * 2
                        val dstOff = ySize + (uvH - 1 - v) * width + (uvW - 1 - u) * 2
                        out[dstOff] = data[srcOff]
                        out[dstOff + 1] = data[srcOff + 1]
                    }
                }
            }
            90 -> {
                val outWidth = height
                val outHeight = width
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcY = y * width + x
                        val dstY = (width - 1 - x) * height + y
                        out[dstY] = data[srcY]
                    }
                }
                val uvW = width / 2
                val uvH = height / 2
                for (v in 0 until uvH) {
                    for (u in 0 until uvW) {
                        val srcOff = ySize + v * width + (width - 2 - u * 2)
                        val dstOff = ySize + v * outWidth + u * 2
                        out[dstOff] = data[srcOff]
                        out[dstOff + 1] = data[srcOff + 1]
                    }
                }
            }
            270 -> {
                val outWidth = height
                val outHeight = width
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcY = y * width + x
                        val dstY = x * height + (height - 1 - y)
                        out[dstY] = data[srcY]
                    }
                }
                val uvW = width / 2
                val uvH = height / 2
                for (v in 0 until uvH) {
                    for (u in 0 until uvW) {
                        val srcOff = ySize + (uvH - 1 - v) * width + u * 2
                        val dstOff = ySize + u * outWidth + v * 2
                        out[dstOff] = data[srcOff]
                        out[dstOff + 1] = data[srcOff + 1]
                    }
                }
            }
            else -> return data
        }
        return out
    }

    /**
     * Flip NV12 (YUV420 semi-planar) frame data in-place.
     * Y plane: width*height bytes. UV plane: width*(height/2) bytes, interleaved U,V pairs.
     */
    private fun flipFrameNV12(data: ByteArray, width: Int, height: Int, horizontal: Boolean, vertical: Boolean) {
        if (!horizontal && !vertical) return
        val ySize = width * height
        if (vertical) {
            val rowBuf = ByteArray(width)
            for (row in 0 until height / 2) {
                val top = row * width
                val bottom = (height - 1 - row) * width
                System.arraycopy(data, top, rowBuf, 0, width)
                System.arraycopy(data, bottom, data, top, width)
                System.arraycopy(rowBuf, 0, data, bottom, width)
            }
            val uvHeight = height / 2
            for (row in 0 until uvHeight / 2) {
                val top = ySize + row * width
                val bottom = ySize + (uvHeight - 1 - row) * width
                System.arraycopy(data, top, rowBuf, 0, width)
                System.arraycopy(data, bottom, data, top, width)
                System.arraycopy(rowBuf, 0, data, bottom, width)
            }
        }
        if (horizontal) {
            for (row in 0 until height) {
                val s = row * width
                var l = s; var r = s + width - 1
                while (l < r) { val t = data[l]; data[l] = data[r]; data[r] = t; l++; r-- }
            }
            val uvHeight = height / 2
            for (row in 0 until uvHeight) {
                val s = ySize + row * width
                var l = s; var r = s + width - 2
                while (l < r) {
                    val u = data[l]; val v = data[l + 1]
                    data[l] = data[r]; data[l + 1] = data[r + 1]
                    data[r] = u; data[r + 1] = v
                    l += 2; r -= 2
                }
            }
        }
    }
    
    /**
     * Fast lossless remux with trim and/or speed applied via PTS adjustment.
     * Handles both video and audio tracks. No re-encoding.
     */
    private fun remuxWithTools(
        sourceFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions
    ): File {
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}_edit.${targetFormat.extension}")
        progressCallback(0.05f)

        if (targetFormat == FileFormat.MKV) {
            val mp4Out = remuxWithTools(
                sourceFile, FileFormat.MP4, context, { p -> progressCallback(p * 0.85f) }, toolOptions
            )
            try {
                return ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToMkv(
                    mp4Out, outputFile
                ) { p -> progressCallback(0.85f + 0.15f * p) }
            } finally {
                mp4Out.delete()
            }
        }

        val muxerFormat = when (targetFormat) {
            FileFormat.MP4, FileFormat.MOV, FileFormat.M4A, FileFormat.THREEGP -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            FileFormat.WEBM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)

            val trackMap = mutableMapOf<Int, Int>()
            var orientationHint = 0
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    if (mime.startsWith("video/") && fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        orientationHint = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(fmt)
                }
            }
            if (orientationHint != 0) {
                muxer.setOrientationHint(orientationHint)
            }
            muxer.start()

            val trimStartUs = if (toolOptions.hasTrim) toolOptions.trimStartMs * 1000L else 0L
            val trimEndUs = if (toolOptions.hasTrim) toolOptions.trimEndMs * 1000L else Long.MAX_VALUE
            if (toolOptions.hasTrim) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val nominalDuration = if (toolOptions.hasTrim) trimEndUs - trimStartUs else 1L

            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val sampleTime = extractor.sampleTime
                val trackIdx = extractor.sampleTrackIndex

                if (toolOptions.hasTrim && sampleTime > trimEndUs + 500_000) break

                val muxerTrack = trackMap[trackIdx]
                if (muxerTrack != null && sampleTime >= trimStartUs && sampleTime <= trimEndUs) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (((sampleTime - trimStartUs).coerceAtLeast(0)) / toolOptions.speedFactor).toLong()
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buf, info)

                    if (toolOptions.hasTrim && nominalDuration > 0) {
                        val p = 0.1f + 0.85f * ((sampleTime - trimStartUs).toFloat() / nominalDuration)
                        progressCallback(p.coerceAtMost(0.95f))
                    }
                }
                extractor.advance()
            }

            muxer.stop()
            progressCallback(1.0f)
            return outputFile
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun convertTxtToPdf(sourceFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val outputFile = cacheOutputFile(context, sourceFile, "pdf")
        
        progressCallback(0.3f) // Starting PDF creation
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        
        try {
            progressCallback(0.4f) // Reading text file
            val text = sourceFile.readText()
            val lines = text.split("\n")
            
            progressCallback(0.5f) // Processing lines
            for ((index, line) in lines.withIndex()) {
                document.add(
                    com.itextpdf.layout.element.Paragraph(line)
                        .setFontSize(12f)
                )
                // Update progress every 100 lines
                if (index % 100 == 0) {
                    val progress = 0.5f + (0.3f * index / lines.size.coerceAtLeast(1))
                    progressCallback(progress)
                }
            }
        } catch (e: Exception) {
            document.add(
                com.itextpdf.layout.element.Paragraph("Error reading text file: ${e.message}")
                    .setFontSize(12f)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.RED)
            )
        }
        
        progressCallback(0.9f) // Finalizing PDF
        document.close()
        return outputFile
    }
    
    private fun convertRtfToPdf(sourceFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val outputFile = cacheOutputFile(context, sourceFile, "pdf")
        
        progressCallback(0.3f) // Starting PDF creation
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        
        try {
            progressCallback(0.4f) // Reading RTF file
            val rtfContent = sourceFile.readText()
            
            progressCallback(0.5f) // Processing RTF content
            val plainText = stripRtf(rtfContent)
            val lines = plainText.split("\n")
            
            progressCallback(0.7f) // Adding content to PDF
            for (line in lines) {
                document.add(
                    com.itextpdf.layout.element.Paragraph(line)
                        .setFontSize(12f)
                )
            }
        } catch (e: Exception) {
            document.add(
                com.itextpdf.layout.element.Paragraph("Error converting RTF file: ${e.message}")
                    .setFontSize(12f)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.RED)
            )
        }
        
        progressCallback(0.9f) // Finalizing PDF
        document.close()
        return outputFile
    }
    
    /** Single image (or first of set) → one PDF page. Supports JPEG, PNG, WEBP, HEIC, etc. */
    private fun convertImageToPdf(
        sourceFile: File,
        sourceFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        scale: Float?
    ): File {
        progressCallback(0.1f)
        val bitmap = when (sourceFormat) {
            FileFormat.JPG, FileFormat.JPEG, FileFormat.PNG, FileFormat.WEBP, FileFormat.BMP, FileFormat.TIFF -> {
                decodeBitmapWithFallback(sourceFile, sourceFormat)
            }
            FileFormat.HEIC -> {
                decodeBitmapWithFallback(sourceFile, sourceFormat)
            }
            else -> throw UnsupportedOperationException("Image to PDF: unsupported format ${sourceFormat.displayName}")
        } ?: throw IOException("Failed to decode image for PDF")
        progressCallback(0.3f)
        val orientedBitmap = applyExifOrientation(bitmap, sourceFile, sourceFormat == FileFormat.HEIC)
        if (orientedBitmap != bitmap) bitmap.recycle()
        val scaledBitmap = if (scale != null && scale > 0f && scale != 1.0f) {
            val w = (orientedBitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (orientedBitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(orientedBitmap, w, h, true)
        } else orientedBitmap
        if (scaledBitmap != orientedBitmap) orientedBitmap.recycle()
        val outputFile = cacheOutputFile(context, sourceFile, "pdf")
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val pageSize = com.itextpdf.kernel.geom.PageSize(
            scaledBitmap.width.toFloat(),
            scaledBitmap.height.toFloat()
        )
        pdfDocument.addNewPage(pageSize)
        val document = com.itextpdf.layout.Document(pdfDocument, pageSize)
        val tempPng = File(context.cacheDir, "img_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(tempPng).use { scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val imageData = com.itextpdf.io.image.ImageDataFactory.create(tempPng.absolutePath)
            val image = com.itextpdf.layout.element.Image(imageData)
            image.setWidth(pageSize.width)
            image.setHeight(pageSize.height)
            document.add(image)
        } finally {
            tempPng.delete()
            scaledBitmap.recycle()
        }
        progressCallback(0.9f)
        document.close()
        progressCallback(1.0f)
        return outputFile
    }

    /** Video → PDF: every frame in the video cutout (trim range) as one page each. */
    private fun convertVideoToPdf(
        sourceFile: File,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        val detectedFormat = FileFormat.fromExtension(sourceFile.extension) ?: FormatSniffer.sniff(sourceFile)
        if (detectedFormat == FileFormat.GIF) {
            return convertGifToPdf(sourceFile, context, progressCallback, toolOptions)
        }
        if (toolOptions.hasExtractFrame) {
            progressCallback(0.05f)
            val frameFile = captureVideoFramePng(sourceFile, context, toolOptions)
            try {
                progressCallback(0.4f)
                val pdfOut = convertImageToPdf(frameFile, FileFormat.PNG, context, { p ->
                    progressCallback(0.4f + 0.6f * p)
                }, null)
                val finalOut = cacheOutputFile(context, sourceFile, "pdf")
                if (pdfOut.absolutePath != finalOut.absolutePath) {
                    pdfOut.copyTo(finalOut, overwrite = true)
                    pdfOut.delete()
                }
                return finalOut
            } finally {
                frameFile.delete()
            }
        }
        progressCallback(0.05f)
        val retriever = MediaMetadataRetriever()
        try {
            val durationMs = try {
                retriever.setDataSource(sourceFile.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (durationMs <= 0L) {
                return convertGifToPdf(sourceFile, context, progressCallback, toolOptions)
            }
            val fpsMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsMeta?.toFloatOrNull()?.coerceIn(1f, 120f) ?: 30f
            val frameIntervalMs = (1000f / fps).toLong().coerceAtLeast(16) // ~60fps max step
            var rotation = getVideoRotation(sourceFile.absolutePath)
            if (rotation == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val rotMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                rotation = rotMeta?.toIntOrNull()?.takeIf { it in listOf(0, 90, 180, 270) } ?: 0
            }
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rotation == 0 && videoWidth > 0 && videoHeight > 0) {
                val firstFrame = retriever.getFrameAtTime(
                    (if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (firstFrame != null && firstFrame.width == videoHeight && firstFrame.height == videoWidth) {
                    rotation = 90
                }
                firstFrame?.recycle()
            }
            val displayWidth = if (rotation == 90 || rotation == 270) videoHeight else videoWidth
            val displayHeight = if (rotation == 90 || rotation == 270) videoWidth else videoHeight
            val startMs = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val endMs = if (toolOptions.hasTrim) toolOptions.trimEndMs.coerceAtMost(durationMs) else durationMs
            val effectiveDurationMs = (endMs - startMs).coerceAtLeast(1)
            progressCallback(0.1f)

            val outputFile = cacheOutputFile(context, sourceFile, "pdf")
            val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
            val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
            val pageSize = if (displayWidth > displayHeight) {
                com.itextpdf.kernel.geom.PageSize.A4.rotate()
            } else {
                com.itextpdf.kernel.geom.PageSize.A4
            }
            pdfDocument.setDefaultPageSize(pageSize)
            val document = com.itextpdf.layout.Document(pdfDocument)
            val tempPng = File(context.cacheDir, "video_frame_pdf_${System.currentTimeMillis()}.png")
            var frameCount = 0
            try {
                var timeMs = startMs
                while (timeMs <= endMs) {
                    val bitmap = retriever.getFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    if (bitmap != null) {
                        var oriented = rotateBitmap(bitmap, rotation)
                        if (oriented !== bitmap) bitmap.recycle()
                        if (toolOptions.hasRotation) {
                            val userRotated = rotateBitmap(oriented, toolOptions.rotationDegrees)
                            if (userRotated !== oriented) oriented.recycle()
                            oriented = userRotated
                        }
                        val pageW = 595
                        val pageH = 842
                        val toExport = scaleBitmapToFitInside(oriented, pageW, pageH)
                        if (toExport !== oriented) oriented.recycle()
                        FileOutputStream(tempPng).use { toExport.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        toExport.recycle()
                        val imageData = com.itextpdf.io.image.ImageDataFactory.create(tempPng.absolutePath)
                        val image = com.itextpdf.layout.element.Image(imageData)
                        image.setAutoScaleWidth(true)
                        document.add(image)
                        if (timeMs + frameIntervalMs <= endMs) {
                            document.add(com.itextpdf.layout.element.AreaBreak(com.itextpdf.layout.properties.AreaBreakType.NEXT_PAGE))
                        }
                        frameCount++
                    }
                    timeMs += frameIntervalMs
                    val p = 0.1f + 0.85f * (timeMs - startMs).toFloat() / effectiveDurationMs
                    progressCallback(p.coerceAtMost(0.95f))
                }
            } finally {
                tempPng.delete()
            }
            document.close()
            progressCallback(1.0f)
            return outputFile
        } finally {
            retriever.release()
        }
    }

    /** Multiple images → one PDF (one page per image, in order). */
    fun convertImagesToPdf(
        imageFiles: List<File>,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        if (imageFiles.isEmpty()) throw IllegalArgumentException("No images provided")
        progressCallback(0f)
        val outputFile = File(context.cacheDir, "images_${System.currentTimeMillis()}.pdf")
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        val total = imageFiles.size
        val tempPng = File(context.cacheDir, "img_multi_${System.currentTimeMillis()}.png")
        try {
            for ((index, sourceFile) in imageFiles.withIndex()) {
                val p0 = index.toFloat() / total
                val p1 = (index + 1).toFloat() / total
                val sourceFormat = FileFormat.fromExtension(sourceFile.extension)
                    ?: FormatSniffer.sniff(sourceFile)
                    ?: throw IllegalArgumentException("Unsupported image: ${sourceFile.name}")
                val bitmap = when (sourceFormat) {
                    FileFormat.JPG, FileFormat.JPEG, FileFormat.PNG, FileFormat.WEBP, FileFormat.BMP, FileFormat.TIFF, FileFormat.HEIC ->
                        decodeBitmapWithFallback(sourceFile, sourceFormat)
                    else -> throw UnsupportedOperationException("Unsupported image format: ${sourceFormat.displayName}")
                } ?: throw IOException("Failed to decode: ${sourceFile.name}")
                val oriented = applyExifOrientation(bitmap, sourceFile, sourceFormat == FileFormat.HEIC)
                if (oriented != bitmap) bitmap.recycle()
                FileOutputStream(tempPng).use { oriented.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val imageData = com.itextpdf.io.image.ImageDataFactory.create(tempPng.absolutePath)
                val pageSize = com.itextpdf.kernel.geom.PageSize(
                    oriented.width.toFloat(),
                    oriented.height.toFloat()
                )
                pdfDocument.addNewPage(pageSize)
                val image = com.itextpdf.layout.element.Image(imageData)
                image.setWidth(pageSize.width)
                image.setHeight(pageSize.height)
                document.add(image)
                oriented.recycle()
                if (index < total - 1) {
                    document.add(com.itextpdf.layout.element.AreaBreak(com.itextpdf.layout.properties.AreaBreakType.NEXT_PAGE))
                }
                progressCallback(p0 + (p1 - p0) * 0.9f)
            }
        } finally {
            tempPng.delete()
        }
        document.close()
        progressCallback(1f)
        return outputFile
    }
    
    /** Extract audio track from video. WAV = decode to PCM; M4A = remux; MP3/FLAC = decode then encode. */
    private fun extractAudioFromVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        val outputFile = cacheOutputFile(context, sourceFile, targetFormat.extension)
        return when (targetFormat) {
            FileFormat.WAV -> extractVideoToWav(sourceFile, outputFile, progressCallback, toolOptions)
            FileFormat.M4A -> remuxAudioToM4a(sourceFile, outputFile, context, progressCallback, toolOptions)
            FileFormat.MP3 -> {
                val wavFile = File(context.cacheDir, "video2mp3_${System.currentTimeMillis()}.wav")
                try {
                    extractVideoToWav(sourceFile, wavFile, { p -> progressCallback(p * 0.5f) }, toolOptions)
                    encodeAudioToMp3(wavFile, outputFile, QualityPreset.MEDIUM, { p -> progressCallback(0.5f + p * 0.5f) })
                } finally {
                    wavFile.delete()
                }
            }
            FileFormat.FLAC -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val wavFile = File(context.cacheDir, "video2flac_${System.currentTimeMillis()}.wav")
                    try {
                        extractVideoToWav(sourceFile, wavFile, { p -> progressCallback(p * 0.5f) }, toolOptions)
                        encodeAudioToFlac(wavFile, outputFile, { p -> progressCallback(0.5f + p * 0.5f) })
                    } finally {
                        wavFile.delete()
                    }
                } else throw UnsupportedOperationException("FLAC encoding requires Android 10 or later")
            }
            FileFormat.OGG -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val wavFile = File(context.cacheDir, "video2ogg_${System.currentTimeMillis()}.wav")
                    try {
                        extractVideoToWav(sourceFile, wavFile, { p -> progressCallback(p * 0.5f) }, toolOptions)
                        encodeAudioToOgg(wavFile, outputFile, QualityPreset.MEDIUM, { p -> progressCallback(0.5f + p * 0.5f) })
                    } finally {
                        wavFile.delete()
                    }
                } else throw UnsupportedOperationException("OGG (Opus) encoding requires Android 10 or later")
            }
            else -> throw UnsupportedOperationException("Video to ${targetFormat.displayName} audio extraction is not supported")
        }
    }

    /**
     * Lossless audio extraction: copies the audio track from a video container into an M4A file
     * without decoding or re-encoding when AAC is present; otherwise decodes/synthesizes WAV and encodes to M4A.
     */
    private fun remuxAudioToM4a(
        sourceFile: File,
        outputFile: File,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        progressCallback(0.05f)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            val mime = audioFormat?.getString(MediaFormat.KEY_MIME) ?: ""
            if (audioTrackIndex == -1 || audioFormat == null || mime != "audio/mp4a-latm" || toolOptions.hasSpeed) {
                throw IOException("Fallback to WAV+AAC transcode")
            }
            extractor.selectTrack(audioTrackIndex)
            progressCallback(0.15f)

            val trackDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION))
                audioFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val trimStartUs = if (toolOptions.hasTrim) toolOptions.trimStartMs * 1000L else 0L
            val trimEndUs = if (toolOptions.hasTrim) {
                val raw = toolOptions.trimEndMs * 1000L
                if (trackDurationUs > 0L) raw.coerceAtMost(trackDurationUs) else raw
            } else if (trackDurationUs > 0L) {
                trackDurationUs
            } else {
                Long.MAX_VALUE
            }
            if (toolOptions.hasTrim) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buf = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            val durationUs = if (trimEndUs == Long.MAX_VALUE) trackDurationUs else (trimEndUs - trimStartUs).coerceAtLeast(1L)

            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                if (sampleTime < trimStartUs) {
                    extractor.advance()
                    continue
                }
                if (sampleTime > trimEndUs) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (sampleTime - trimStartUs).coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buf, info)
                if (durationUs > 0) {
                    progressCallback(
                        (0.15f + 0.8f * info.presentationTimeUs.toFloat() / durationUs).coerceAtMost(0.95f)
                    )
                }
                extractor.advance()
            }
            muxer.stop()
            progressCallback(1.0f)
            return outputFile
        } catch (_: Exception) {
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            if (outputFile.exists()) outputFile.delete()
            val wavFile = File(context.cacheDir, "video2m4a_${System.currentTimeMillis()}.wav")
            try {
                extractVideoToWav(sourceFile, wavFile, { p -> progressCallback(p * 0.5f) }, toolOptions)
                return encodeAudioToM4a(wavFile, outputFile, QualityPreset.MEDIUM) { p ->
                    progressCallback(0.5f + 0.5f * p)
                }
            } finally {
                if (wavFile.exists()) wavFile.delete()
            }
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
    
    private fun extractVideoToWav(
        sourceFile: File,
        outputFile: File,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        progressCallback(0.05f)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var videoDurationUs = 0L
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(sourceFile.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                        audioTrackIndex = i
                        audioFormat = format
                    } else if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDurationUs = maxOf(videoDurationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                }
            } catch (_: Exception) {
                audioTrackIndex = -1
                audioFormat = null
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                val durationMs = estimateSilentMediaDurationMs(sourceFile, videoDurationUs, toolOptions)
                writeSilentWav(outputFile, durationMs)
                progressCallback(1.0f)
                return outputFile
            }
            extractor!!.selectTrack(audioTrackIndex)
            progressCallback(0.15f)
            val trackDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION))
                audioFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val trimStartUs = if (toolOptions.hasTrim) toolOptions.trimStartMs * 1000L else 0L
            val trimEndUs = if (toolOptions.hasTrim) {
                val raw = toolOptions.trimEndMs * 1000L
                if (trackDurationUs > 0L) raw.coerceAtMost(trackDurationUs) else raw
            } else if (trackDurationUs > 0L) {
                trackDurationUs
            } else {
                Long.MAX_VALUE
            }
            if (toolOptions.hasTrim) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = java.io.ByteArrayOutputStream()
            var sawEOS = false
            val durationUs = if (trimEndUs == Long.MAX_VALUE) trackDurationUs else (trimEndUs - trimStartUs).coerceAtLeast(1L)
            while (!sawEOS) {
                val inputIndex = decoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            if (sampleTime < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawEOS = true
                            } else if (sampleTime < trimStartUs) {
                                extractor.advance()
                            } else if (sampleTime > trimEndUs) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawEOS = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmBuffer.write(chunk)
                        if (durationUs > 0) {
                            val adjustedPts = (bufferInfo.presentationTimeUs - trimStartUs).coerceAtLeast(0L)
                            val p = 0.2f + 0.7f * (adjustedPts.toFloat() / durationUs)
                            progressCallback(p.coerceAtMost(0.95f))
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            progressCallback(0.98f)
            var pcm = pcmBuffer.toByteArray()
            if (toolOptions.hasSpeed && toolOptions.speedFactor != 1f && pcm.isNotEmpty()) {
                pcm = resamplePcmForSpeed(pcm, sampleRate, channelCount, toolOptions.speedFactor)
            }
            writeWavFile(outputFile, pcm, sampleRate, channelCount)
            progressCallback(1.0f)
            return outputFile
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
        }
    }

    private fun estimateSilentMediaDurationMs(
        sourceFile: File,
        videoDurationUs: Long,
        toolOptions: MediaToolOptions
    ): Long {
        var rawDurationMs = (videoDurationUs / 1000L).coerceAtLeast(0L)
        if (rawDurationMs <= 0L) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceFile.absolutePath)
                rawDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
        if (rawDurationMs <= 0L) {
            try {
                @Suppress("DEPRECATION")
                val movie = FileInputStream(sourceFile).use { android.graphics.Movie.decodeStream(it) }
                if (movie != null && movie.duration() > 0) {
                    rawDurationMs = movie.duration().toLong()
                }
            } catch (_: Exception) {}
        }
        if (rawDurationMs <= 0L) rawDurationMs = 1000L
        val trimmedMs = if (toolOptions.hasTrim) {
            val end = toolOptions.trimEndMs.coerceAtMost(rawDurationMs)
            (end - toolOptions.trimStartMs).coerceAtLeast(200L)
        } else {
            rawDurationMs
        }
        val speed = if (toolOptions.hasSpeed) toolOptions.speedFactor.coerceIn(0.25f, 4f) else 1f
        return (trimmedMs / speed).toLong().coerceIn(500L, 60_000L)
    }

    private fun writeSilentWav(outputFile: File, durationMs: Long, sampleRate: Int = 44100, channels: Int = 1) {
        val totalSamples = ((durationMs.coerceAtLeast(100L) * sampleRate) / 1000L).toInt().coerceAtLeast(sampleRate / 10)
        val pcmBytes = ByteArray(totalSamples * channels * 2) // 16-bit silence (all zeros)
        writeWavFile(outputFile, pcmBytes, sampleRate, channels)
    }
    
    private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        val bytesPerSample = 2
        val byteRate = sampleRate * channels * bytesPerSample
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray())
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize).array())
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array()) // PCM
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(channels.toShort()).array())
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array())
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate).array())
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((channels * bytesPerSample).toShort()).array())
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(16).array())
            out.write("data".toByteArray())
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataSize).array())
            out.write(pcmData)
        }
    }
    
    // ── Document → Audio (text-to-speech) ───────────────────────────────

    /**
     * Converts a text document to spoken-word audio using the device's TextToSpeech engine.
     * The document text is extracted, synthesized to a WAV via the system TTS engine, then
     * encoded to the requested audio format with the existing audio encoders.
     */
    private fun convertDocumentToAudio(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.02f)
        val text = extractPlainText(sourceFile, sourceFormat, context).trim()
        if (text.isBlank()) {
            throw IOException("No readable text found in the ${sourceFormat.displayName} to convert to audio")
        }
        progressCallback(0.08f)

        val wavFile = synthesizeTextToWav(text, context) { p -> progressCallback(0.08f + 0.72f * p) }
        try {
            progressCallback(0.82f)
            if (targetFormat == FileFormat.WAV) {
                val out = cacheOutputFile(context, sourceFile, "wav")
                wavFile.copyTo(out, overwrite = true)
                progressCallback(1.0f)
                return out
            }
            val out = cacheOutputFile(context, sourceFile, targetFormat.extension)
            val encProgress: (Float) -> Unit = { p -> progressCallback((0.82f + 0.18f * p).coerceAtMost(1.0f)) }
            return when (targetFormat) {
                FileFormat.MP3 -> encodeAudioToMp3(wavFile, out, QualityPreset.MEDIUM, encProgress)
                FileFormat.M4A -> encodeAudioToM4a(wavFile, out, QualityPreset.MEDIUM, encProgress)
                FileFormat.FLAC -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) encodeAudioToFlac(wavFile, out, encProgress)
                    else throw UnsupportedOperationException("FLAC encoding requires Android 10 or later")
                }
                FileFormat.OGG -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) encodeAudioToOgg(wavFile, out, QualityPreset.MEDIUM, encProgress)
                    else throw UnsupportedOperationException("OGG (Opus) encoding requires Android 10 or later")
                }
                else -> throw UnsupportedOperationException("Text to ${targetFormat.displayName} audio is not supported")
            }
        } finally {
            wavFile.delete()
        }
    }

    /** Extracts plain text from any supported document format. */
    private fun extractPlainText(sourceFile: File, sourceFormat: FileFormat, context: Context): String {
        return when (sourceFormat) {
            FileFormat.TXT -> sourceFile.readText()
            FileFormat.RTF -> stripRtf(sourceFile.readText())
            FileFormat.HTML -> Jsoup.parse(sourceFile.readText()).text()
            FileFormat.PDF -> {
                val f = PdfConverter().convertPdfToText(sourceFile, context)
                try { f.readText() } finally { f.delete() }
            }
            FileFormat.DOCX -> {
                val f = DocxConverter().convertDocxToText(sourceFile, context)
                try { f.readText() } finally { f.delete() }
            }
            FileFormat.EPUB -> {
                val f = EpubConverter().convertEpubToText(sourceFile, context)
                try { f.readText() } finally { f.delete() }
            }
            else -> throw UnsupportedOperationException("Cannot read text from ${sourceFormat.displayName}")
        }
    }

    /**
     * Synthesizes [text] to a single WAV file using the system TextToSpeech engine.
     * Long text is split into engine-sized chunks, each synthesized to a part file, then the
     * PCM is concatenated into one WAV. Runs on the calling (worker) thread and blocks on the
     * asynchronous TTS callbacks via latches.
     */
    private fun synthesizeTextToWav(
        text: String,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context.applicationContext) { status ->
            initStatus.set(status)
            initLatch.countDown()
        }
        if (!initLatch.await(15, TimeUnit.SECONDS) || initStatus.get() != TextToSpeech.SUCCESS) {
            try { tts.shutdown() } catch (_: Exception) {}
            throw IOException("Text-to-speech is not available on this device")
        }
        tts.setSpeechRate(ConversionPrefs.getTtsSpeechRate(context))
        ConversionPrefs.getSpeechLocaleTag(context)?.let { tag ->
            val locale = java.util.Locale.forLanguageTag(tag)
            if (locale.language.isNotEmpty()) {
                tts.language = locale
            }
        }
        ConversionPrefs.getTtsVoiceName(context)?.let { saved ->
            tts.voices?.find { it.name == saved }?.let { tts.voice = it }
        }

        val partFiles = mutableListOf<File>()
        try {
            val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceIn(500, 3900)
            val chunks = splitTextForTts(text, maxLen)
            for ((index, chunk) in chunks.withIndex()) {
                val partFile = File(context.cacheDir, "tts_part_${System.currentTimeMillis()}_$index.wav")
                val doneLatch = CountDownLatch(1)
                val success = AtomicBoolean(false)
                val utteranceId = "umc_tts_$index"
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(id: String?) {
                        success.set(true)
                        doneLatch.countDown()
                    }
                    @Deprecated("Deprecated in API 21", ReplaceWith(""))
                    override fun onError(utteranceId: String?) {
                        doneLatch.countDown()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        doneLatch.countDown()
                    }
                })
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                val queued = tts.synthesizeToFile(chunk, params, partFile, utteranceId)
                if (queued != TextToSpeech.SUCCESS) {
                    throw IOException("Text-to-speech failed to start synthesis")
                }
                if (!doneLatch.await(180, TimeUnit.SECONDS) || !success.get()
                    || !partFile.exists() || partFile.length() <= 44L
                ) {
                    throw IOException("Text-to-speech synthesis failed")
                }
                partFiles.add(partFile)
                progressCallback(((index + 1).toFloat() / chunks.size).coerceAtMost(1.0f))
            }
            return mergeWavFiles(partFiles, context)
        } finally {
            try { tts.stop() } catch (_: Exception) {}
            try { tts.shutdown() } catch (_: Exception) {}
            partFiles.forEach { it.delete() }
        }
    }

    /** Splits text into whitespace-aligned chunks no longer than [maxLen], hard-splitting oversized tokens. */
    private fun splitTextForTts(text: String, maxLen: Int): List<String> {
        val safeMax = maxLen.coerceAtLeast(100)
        if (text.length <= safeMax) return listOf(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                parts.add(current.toString())
                current.setLength(0)
            }
        }
        for (rawWord in text.split(Regex("\\s+"))) {
            if (rawWord.isEmpty()) continue
            var word = rawWord
            while (word.length > safeMax) {
                flush()
                parts.add(word.substring(0, safeMax))
                word = word.substring(safeMax)
            }
            if (word.isEmpty()) continue
            if (current.isNotEmpty() && current.length + 1 + word.length > safeMax) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        flush()
        return if (parts.isEmpty()) listOf(text.take(safeMax)) else parts
    }

    /** Concatenates the PCM of several WAV files (assumed identical format) into one WAV. */
    private fun mergeWavFiles(wavFiles: List<File>, context: Context): File {
        if (wavFiles.isEmpty()) throw IOException("No audio was produced")
        val output = File(context.cacheDir, "tts_merged_${System.currentTimeMillis()}.wav")
        if (wavFiles.size == 1) {
            wavFiles[0].copyTo(output, overwrite = true)
            return output
        }
        var sampleRate = 22050
        var channels = 1
        val pcm = ByteArrayOutputStream()
        for (f in wavFiles) {
            val wav = readWav(f)
            sampleRate = wav.sampleRate
            channels = wav.channels
            pcm.write(wav.pcm)
        }
        writeWavFile(output, pcm.toByteArray(), sampleRate, channels)
        return output
    }

    private data class WavInfo(val sampleRate: Int, val channels: Int, val pcm: ByteArray)

    /** Minimal RIFF/WAVE parser: reads the fmt header and the raw PCM in the data chunk. */
    private fun readWav(file: File): WavInfo {
        val bytes = file.readBytes()
        fun le16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
        fun le32(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or
            ((bytes[o + 2].toInt() and 0xff) shl 16) or ((bytes[o + 3].toInt() and 0xff) shl 24)
        var sampleRate = 22050
        var channels = 1
        var pcm = ByteArray(0)
        if (bytes.size < 12) return WavInfo(sampleRate, channels, pcm)
        var pos = 12 // skip "RIFF" <size> "WAVE"
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(pos + 4)
            val body = pos + 8
            if (size < 0 || body > bytes.size) break
            when (id) {
                "fmt " -> {
                    if (body + 16 <= bytes.size) {
                        channels = le16(body + 2).coerceAtLeast(1)
                        sampleRate = le32(body + 4).coerceAtLeast(8000)
                    }
                }
                "data" -> {
                    val len = size.coerceAtMost(bytes.size - body)
                    if (len > 0) pcm = bytes.copyOfRange(body, body + len)
                }
            }
            pos = body + size + (size and 1) // chunks are word-aligned
        }
        return WavInfo(sampleRate, channels, pcm)
    }

    // ── Audio → Document (speech-to-text) ───────────────────────────────

    /**
     * Transcribes an audio file to text using on-device speech recognition, then writes the
     * transcript into the requested document format using the existing text→document converters.
     */
    private fun convertAudioToDocument(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.02f)
        val transcript = transcribeAudioToText(sourceFile, context) { p -> progressCallback(0.02f + 0.72f * p) }
            .trim()
            .ifBlank { "[No speech was detected in the audio.]" }
        progressCallback(0.76f)

        val txtFile = cacheOutputFile(context, sourceFile, "txt")
        txtFile.writeText(transcript)
        if (targetFormat == FileFormat.TXT) {
            progressCallback(1.0f)
            return txtFile
        }
        try {
            val docProgress: (Float) -> Unit = { p -> progressCallback((0.76f + 0.24f * p).coerceAtMost(1.0f)) }
            return when (targetFormat) {
                FileFormat.PDF -> convertTxtToPdf(txtFile, context, docProgress)
                FileFormat.DOCX -> convertTxtToDocx(txtFile, context, docProgress)
                FileFormat.RTF -> convertTxtToRtf(txtFile, context, docProgress)
                FileFormat.EPUB -> convertTxtToEpub(txtFile, context, docProgress)
                FileFormat.HTML -> convertTxtToHtml(txtFile, context, docProgress)
                else -> throw UnsupportedOperationException("Audio to ${targetFormat.displayName} is not supported")
            }
        } finally {
            txtFile.delete()
        }
    }

    /**
     * Runs the device's on-device speech recognizer over an audio file and returns the transcript.
     * Requires Android 13 (API 33)+ (segmented-session file recognition) and the RECORD_AUDIO permission.
     * The audio is first decoded to 16 kHz mono 16-bit PCM (the format speech engines expect).
     */
    @android.annotation.SuppressLint("NewApi")
    private fun transcribeAudioToText(
        sourceFile: File,
        context: Context,
        progressCallback: (Float) -> Unit
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw UnsupportedOperationException("Audio transcription requires Android 13 or later")
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("Microphone permission is required to transcribe audio")
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw IOException("On-device speech recognition is not available on this device")
        }
        progressCallback(0.05f)

        val sampleRate = 16_000
        val wavFile = decodeAudioToMonoWav(sourceFile, context, sampleRate)
        var pfd: ParcelFileDescriptor? = null
        try {
            progressCallback(0.4f)
            // Rough audio duration (seconds) used to size the wait timeout for long files.
            val pcmBytes = (wavFile.length() - 44L).coerceAtLeast(0L)
            val durationSec = pcmBytes.toDouble() / (sampleRate * 2)
            val timeoutMs = (durationSec * 3000L).toLong().coerceIn(60_000L, 1_800_000L)

            pfd = ParcelFileDescriptor.open(wavFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val transcript = StringBuilder()
            val done = CountDownLatch(1)
            val errorCode = AtomicInteger(-1)
            val mainHandler = Handler(Looper.getMainLooper())
            val recognizerRef = java.util.concurrent.atomic.AtomicReference<SpeechRecognizer?>()

            fun appendResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull()?.trim()
                if (!best.isNullOrEmpty()) {
                    synchronized(transcript) {
                        if (transcript.isNotEmpty()) transcript.append(' ')
                        transcript.append(best)
                    }
                }
            }

            mainHandler.post {
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizerRef.set(recognizer)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    val localeTag = ConversionPrefs.getSpeechLocaleTag(context)
                        ?: java.util.Locale.getDefault().toLanguageTag()
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        errorCode.set(error)
                        done.countDown()
                    }
                    override fun onResults(results: Bundle?) {
                        appendResults(results)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onSegmentResults(segmentResults: Bundle) {
                        appendResults(segmentResults)
                    }
                    override fun onEndOfSegmentedSession() {
                        done.countDown()
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                recognizer.startListening(intent)
            }

            val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
            mainHandler.post {
                try { recognizerRef.get()?.destroy() } catch (_: Exception) {}
            }
            progressCallback(0.95f)

            val text = synchronized(transcript) { transcript.toString() }
            // Only surface an error if nothing at all was transcribed.
            if (text.isBlank()) {
                val code = errorCode.get()
                if (!finished) throw IOException("Speech recognition timed out")
                if (code != -1) throw IOException("Speech recognition failed (error $code)")
            }
            return text
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
            wavFile.delete()
        }
    }

    /** Decodes any audio file to a 16-bit PCM WAV at [targetRate] Hz, mono. */
    private fun decodeAudioToMonoWav(sourceFile: File, context: Context, targetRate: Int): File {
        var sampleRate = 44100
        var channelCount = 2
        val pcmData: ByteArray
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex == -1 || format == null) throw IOException("No audio track found")
            extractor.selectTrack(trackIndex)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val bufInfo = MediaCodec.BufferInfo()
            val pcmOut = ByteArrayOutputStream()
            var sawEOS = false
            while (!sawEOS) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufInfo.size > 0) {
                        val chunk = ByteArray(bufInfo.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            pcmData = pcmOut.toByteArray()
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }

        val mono = if (channelCount > 1) downmixToMono(pcmData, channelCount) else pcmData
        val resampled = resamplePcm(mono, sampleRate, targetRate, 1)
        val output = File(context.cacheDir, "stt_${System.currentTimeMillis()}.wav")
        writeWavFile(output, resampled, targetRate, 1)
        return output
    }

    /** Averages all channels of interleaved 16-bit PCM into a single mono channel. */
    private fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return pcm
        val frameBytes = channels * 2
        val frames = pcm.size / frameBytes
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val o = (f * channels + c) * 2
                val s = ((pcm[o + 1].toInt() shl 8) or (pcm[o].toInt() and 0xff)).toShort().toInt()
                sum += s
            }
            val v = (sum / channels).coerceIn(-32768, 32767)
            out[f * 2] = (v and 0xff).toByte()
            out[f * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Audio → Audio: any audio → WAV (decode to PCM) or → M4A (encode to AAC). */
    private fun convertAudio(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset = QualityPreset.MEDIUM,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        if (targetFormat == FileFormat.M4A) {
            val outputFile = cacheOutputFile(context, sourceFile, "m4a")
            if (toolOptions.hasTrim || toolOptions.hasSpeed) {
                val wav = File(context.cacheDir, "audio_trim_${System.currentTimeMillis()}.wav")
                try {
                    decodeAudioToWavWithTools(sourceFile, wav, toolOptions) { p -> progressCallback(p * 0.5f) }
                    return encodeAudioToM4a(wav, outputFile, quality) { p -> progressCallback(0.5f + 0.5f * p) }
                } finally {
                    wav.delete()
                }
            }
            return encodeAudioToM4a(sourceFile, outputFile, quality, progressCallback)
        }
        if (targetFormat == FileFormat.FLAC) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val outputFile = cacheOutputFile(context, sourceFile, "flac")
                if (toolOptions.hasTrim || toolOptions.hasSpeed) {
                    val wav = File(context.cacheDir, "audio_trim_${System.currentTimeMillis()}.wav")
                    try {
                        decodeAudioToWavWithTools(sourceFile, wav, toolOptions) { p -> progressCallback(p * 0.5f) }
                        return encodeAudioToFlac(wav, outputFile) { p -> progressCallback(0.5f + 0.5f * p) }
                    } finally {
                        wav.delete()
                    }
                }
                return encodeAudioToFlac(sourceFile, outputFile, progressCallback)
            }
            throw UnsupportedOperationException("FLAC encoding requires Android 10 or later")
        }
        if (targetFormat == FileFormat.MP3) {
            val outputFile = cacheOutputFile(context, sourceFile, "mp3")
            if (toolOptions.hasTrim || toolOptions.hasSpeed) {
                val wav = File(context.cacheDir, "audio_trim_${System.currentTimeMillis()}.wav")
                try {
                    decodeAudioToWavWithTools(sourceFile, wav, toolOptions) { p -> progressCallback(p * 0.5f) }
                    return encodeAudioToMp3(wav, outputFile, quality) { p -> progressCallback(0.5f + 0.5f * p) }
                } finally {
                    wav.delete()
                }
            }
            return encodeAudioToMp3(sourceFile, outputFile, quality, progressCallback)
        }
        if (targetFormat == FileFormat.OGG) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val outputFile = cacheOutputFile(context, sourceFile, "ogg")
                if (toolOptions.hasTrim || toolOptions.hasSpeed) {
                    val wav = File(context.cacheDir, "audio_trim_${System.currentTimeMillis()}.wav")
                    try {
                        decodeAudioToWavWithTools(sourceFile, wav, toolOptions) { p -> progressCallback(p * 0.5f) }
                        return encodeAudioToOgg(wav, outputFile, quality) { p -> progressCallback(0.5f + 0.5f * p) }
                    } finally {
                        wav.delete()
                    }
                }
                return encodeAudioToOgg(sourceFile, outputFile, quality, progressCallback)
            }
            throw UnsupportedOperationException("OGG (Opus) encoding requires Android 10 or later")
        }
        if (targetFormat != FileFormat.WAV) {
            throw UnsupportedOperationException("Audio conversion to ${targetFormat.displayName} is not supported")
        }
        progressCallback(0.1f)
        val outputFile = cacheOutputFile(context, sourceFile, "wav")
        if (toolOptions.hasTrim || toolOptions.hasSpeed) {
            decodeAudioToWavWithTools(sourceFile, outputFile, toolOptions, progressCallback)
            progressCallback(1f)
            return outputFile
        }
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex == -1 || format == null) throw IOException("No audio track")
            extractor.selectTrack(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = java.io.ByteArrayOutputStream()
            var sawEOS = false
            while (!sawEOS) {
                val inputIndex = decoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawEOS = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmBuffer.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            writeWavFile(outputFile, pcmBuffer.toByteArray(), sampleRate, channelCount)
            progressCallback(1.0f)
            return outputFile
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
        }
    }

    /**
     * Encode any audio file to M4A (AAC inside MPEG-4 container).
     * Two-pass: decode source to raw PCM, then encode PCM to AAC via MediaCodec.
     */
    private fun encodeAudioToM4a(
        sourceFile: File,
        outputFile: File,
        quality: QualityPreset,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.05f)

        // ── Pass 1: decode to PCM ──────────────────────────────────────────
        var sampleRate = 44100
        var channelCount = 2
        val pcmData: ByteArray
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex == -1 || format == null) throw IOException("No audio track found")
            extractor.selectTrack(trackIndex)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufInfo = MediaCodec.BufferInfo()
            val pcmOut = java.io.ByteArrayOutputStream()
            var sawEOS = false
            while (!sawEOS) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufInfo.size > 0) {
                        val chunk = ByteArray(bufInfo.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                        if (durationUs > 0) {
                            progressCallback(
                                (0.05f + 0.4f * bufInfo.presentationTimeUs.toFloat() / durationUs).coerceAtMost(0.45f)
                            )
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            pcmData = pcmOut.toByteArray()
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }

        progressCallback(0.5f)

        // ── Pass 2: encode PCM to AAC / M4A ───────────────────────────────
        val audioBitRate = when (quality) {
            QualityPreset.LOW -> 64_000
            QualityPreset.MEDIUM -> 128_000
            QualityPreset.HIGH -> 256_000
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1
            var muxerStarted = false
            val bufInfo = MediaCodec.BufferInfo()

            // PCM frames: AAC encoder typically consumes 1024 samples per frame
            val bytesPerSample = 2
            val frameBytes = 1024 * channelCount * bytesPerSample
            var inputOffset = 0
            var presentationUs = 0L
            var encoderInputDone = false
            var encoderDone = false

            while (!encoderDone) {
                // Feed PCM into encoder
                if (!encoderInputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remaining = pcmData.size - inputOffset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputDone = true
                        } else {
                            val toWrite = minOf(remaining, inBuf.remaining(), frameBytes)
                            inBuf.put(pcmData, inputOffset, toWrite)
                            encoder.queueInputBuffer(inIdx, 0, toWrite, presentationUs, 0)
                            inputOffset += toWrite
                            presentationUs += (toWrite / (channelCount * bytesPerSample)) * 1_000_000L / sampleRate
                        }
                    }
                }
                // Drain encoder output
                val outIdx = encoder.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val encBuf = encoder.getOutputBuffer(outIdx)
                        if (encBuf != null && bufInfo.size > 0 && muxerStarted) {
                            encBuf.position(bufInfo.offset)
                            encBuf.limit(bufInfo.offset + bufInfo.size)
                            muxer.writeSampleData(muxerTrack, encBuf, bufInfo)
                            if (pcmData.isNotEmpty()) {
                                progressCallback(
                                    (0.5f + 0.45f * inputOffset.toFloat() / pcmData.size).coerceAtMost(0.95f)
                                )
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                        }
                    }
                }
            }
            progressCallback(1.0f)
            return outputFile
        } finally {
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Encode any audio to FLAC (lossless). Requires Android 10 (API 29)+.
     * Decode to PCM then encode via MediaCodec "audio/flac"; write raw FLAC stream to file.
     */
    @android.annotation.SuppressLint("NewApi")
    private fun encodeAudioToFlac(
        sourceFile: File,
        outputFile: File,
        progressCallback: (Float) -> Unit
    ): File {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("FLAC requires Android 10+")
        progressCallback(0.05f)
        var sampleRate = 44100
        var channelCount = 2
        val pcmData: ByteArray
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex == -1 || format == null) throw IOException("No audio track found")
            extractor.selectTrack(trackIndex)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val bufInfo = MediaCodec.BufferInfo()
            val pcmOut = java.io.ByteArrayOutputStream()
            var sawEOS = false
            while (!sawEOS) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufInfo.size > 0) {
                        val chunk = ByteArray(bufInfo.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            pcmData = pcmOut.toByteArray()
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
        progressCallback(0.5f)
        var encoder: MediaCodec? = null
        try {
            val encFormat = MediaFormat.createAudioFormat("audio/flac", sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType("audio/flac")
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val bufInfo = MediaCodec.BufferInfo()
            val bytesPerSample = 2
            val frameSize = 4096 * channelCount * bytesPerSample
            var inputOffset = 0
            var presentationUs = 0L
            var encoderInputDone = false
            var encoderDone = false
            FileOutputStream(outputFile).use { fos ->
                while (!encoderDone) {
                    if (!encoderInputDone) {
                        val inIdx = encoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = encoder.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            val remaining = pcmData.size - inputOffset
                            if (remaining <= 0) {
                                encoder.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encoderInputDone = true
                            } else {
                                val toWrite = minOf(remaining, inBuf.remaining(), frameSize)
                                inBuf.put(pcmData, inputOffset, toWrite)
                                encoder.queueInputBuffer(inIdx, 0, toWrite, presentationUs, 0)
                                inputOffset += toWrite
                                presentationUs += (toWrite / (channelCount * bytesPerSample)) * 1_000_000L / sampleRate
                            }
                        }
                    }
                    val outIdx = encoder.dequeueOutputBuffer(bufInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                        outIdx >= 0 -> {
                            val encBuf = encoder.getOutputBuffer(outIdx)
                            if (encBuf != null && bufInfo.size > 0) {
                                encBuf.position(bufInfo.offset)
                                val chunk = ByteArray(bufInfo.size)
                                encBuf.get(chunk)
                                fos.write(chunk)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (pcmData.isNotEmpty()) {
                                progressCallback((0.5f + 0.45f * inputOffset.toFloat() / pcmData.size).coerceAtMost(0.95f))
                            }
                            if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true
                        }
                    }
                }
            }
            progressCallback(1.0f)
            return outputFile
        } finally {
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Encode any audio to OGG (Opus inside an Ogg container). Requires Android 10 (API 29)+.
     * Decode to PCM, resample to 48 kHz (Opus only supports a fixed set of rates),
     * encode via MediaCodec "audio/opus", and mux with [MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG].
     */
    @android.annotation.SuppressLint("NewApi")
    private fun encodeAudioToOgg(
        sourceFile: File,
        outputFile: File,
        quality: QualityPreset,
        progressCallback: (Float) -> Unit
    ): File {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("OGG (Opus) requires Android 10+")
        progressCallback(0.05f)

        // ── Pass 1: decode source to PCM ──────────────────────────────────
        var sampleRate = 44100
        var channelCount = 2
        val pcmData: ByteArray
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex == -1 || format == null) throw IOException("No audio track found")
            extractor.selectTrack(trackIndex)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val bufInfo = MediaCodec.BufferInfo()
            val pcmOut = java.io.ByteArrayOutputStream()
            var sawEOS = false
            while (!sawEOS) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufInfo.size > 0) {
                        val chunk = ByteArray(bufInfo.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            pcmData = pcmOut.toByteArray()
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }

        progressCallback(0.45f)

        // Opus only encodes 1 or 2 channels at a fixed set of sample rates; use 48 kHz.
        if (channelCount !in 1..2) throw IOException("OGG (Opus) supports mono or stereo audio only")
        val targetRate = 48_000
        val pcm = resamplePcm(pcmData, sampleRate, targetRate, channelCount)

        progressCallback(0.5f)

        // ── Pass 2: encode PCM to Opus and mux into Ogg ───────────────────
        val bitRate = when (quality) {
            QualityPreset.LOW -> 64_000
            QualityPreset.MEDIUM -> 96_000
            QualityPreset.HIGH -> 128_000
        }
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val encFormat = MediaFormat.createAudioFormat("audio/opus", targetRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            encoder = MediaCodec.createEncoderByType("audio/opus")
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var muxerTrack = -1
            var muxerStarted = false
            val bufInfo = MediaCodec.BufferInfo()

            val bytesPerSample = 2
            // Feed Opus complete 20 ms frames (48000 / 50 = 960 samples per channel).
            val frameBytes = 960 * channelCount * bytesPerSample
            var inputOffset = 0
            var presentationUs = 0L
            var encoderInputDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (!encoderInputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remaining = pcm.size - inputOffset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputDone = true
                        } else {
                            val toWrite = minOf(remaining, inBuf.remaining(), frameBytes)
                            inBuf.put(pcm, inputOffset, toWrite)
                            encoder.queueInputBuffer(inIdx, 0, toWrite, presentationUs, 0)
                            inputOffset += toWrite
                            presentationUs += (toWrite / (channelCount * bytesPerSample)) * 1_000_000L / targetRate
                        }
                    }
                }
                val outIdx = encoder.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val encBuf = encoder.getOutputBuffer(outIdx)
                        val isCodecConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (encBuf != null && bufInfo.size > 0 && muxerStarted && !isCodecConfig) {
                            encBuf.position(bufInfo.offset)
                            encBuf.limit(bufInfo.offset + bufInfo.size)
                            muxer.writeSampleData(muxerTrack, encBuf, bufInfo)
                            if (pcm.isNotEmpty()) {
                                progressCallback(
                                    (0.5f + 0.45f * inputOffset.toFloat() / pcm.size).coerceAtMost(0.95f)
                                )
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true
                    }
                }
            }
            progressCallback(1.0f)
            return outputFile
        } finally {
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Linear-interpolation resampler for interleaved signed 16-bit little-endian PCM.
     * Returns the input untouched when [srcRate] == [dstRate].
     */
    private fun resamplePcm(pcm: ByteArray, srcRate: Int, dstRate: Int, channels: Int): ByteArray {
        if (srcRate == dstRate || srcRate <= 0 || dstRate <= 0) return pcm
        val bytesPerFrame = channels * 2
        val srcFrames = pcm.size / bytesPerFrame
        if (srcFrames <= 1) return pcm
        val srcShorts = ShortArray(pcm.size / 2)
        var b = 0
        var s = 0
        while (b < pcm.size - 1) {
            srcShorts[s++] = ((pcm[b + 1].toInt() and 0xff) shl 8 or (pcm[b].toInt() and 0xff)).toShort()
            b += 2
        }
        val dstFrames = (srcFrames.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        val out = ByteArray(dstFrames * bytesPerFrame)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (d in 0 until dstFrames) {
            val srcPos = d * ratio
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            for (c in 0 until channels) {
                val v0 = srcShorts[i0 * channels + c].toInt()
                val v1 = srcShorts[i1 * channels + c].toInt()
                val v = (v0 + (v1 - v0) * frac).toInt().coerceIn(-32768, 32767)
                val o = (d * channels + c) * 2
                out[o] = (v and 0xff).toByte()
                out[o + 1] = ((v shr 8) and 0xff).toByte()
            }
        }
        return out
    }

    /**
     * Encode any audio file to MP3 using FFmpeg (libmp3lame).
     */
    private fun encodeAudioToMp3(
        sourceFile: File,
        outputFile: File,
        quality: QualityPreset,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.05f)
        val bitrateKbps = when (quality) {
            QualityPreset.LOW -> 96
            QualityPreset.MEDIUM -> 128
            QualityPreset.HIGH -> 256
        }
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-y",
                "-i", sourceFile.absolutePath,
                "-codec:a", "libmp3lame",
                "-b:a", "${bitrateKbps}k",
                outputFile.absolutePath
            )
        )
        progressCallback(0.95f)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val detail = session.failStackTrace ?: session.output ?: "unknown error"
            throw IOException("MP3 encoding failed: $detail")
        }
        progressCallback(1.0f)
        return outputFile
    }

    /** Returns video rotation in degrees (0, 90, 180, 270) from the first video track. */
    private fun getVideoRotation(sourcePath: String): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(sourcePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    return if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        format.getInteger(MediaFormat.KEY_ROTATION)
                    } else 0
                }
            }
        } finally {
            extractor.release()
        }
        return 0
    }

    /** Applies rotation (0, 90, 180, 270) to bitmap; returns new bitmap if rotation != 0. */
    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap =
        BitmapGeometryUtil.rotateBitmap(bitmap, rotationDegrees)

    /** Scales bitmap to fit inside (maxW x maxH) preserving aspect ratio; returns new bitmap if scaling needed. */
    private fun scaleBitmapToFitInside(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (bitmap.width <= maxW && bitmap.height <= maxH) return bitmap
        val scale = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * Applies source video rotation metadata plus user flip/rotate tools to a retriever frame.
     */
    private fun orientVideoFrame(bitmap: Bitmap, sourcePath: String, toolOptions: MediaToolOptions): Bitmap {
        val rotation = getVideoRotation(sourcePath)
        var result = rotateBitmap(bitmap, rotation)
        if (result !== bitmap) bitmap.recycle()
        if (toolOptions.hasRotation) {
            val userRotated = rotateBitmap(result, toolOptions.rotationDegrees)
            if (userRotated !== result) result.recycle()
            result = userRotated
        }
        if (toolOptions.hasFlip) {
            val flipped = BitmapGeometryUtil.flipBitmap(
                result,
                toolOptions.flipHorizontal,
                toolOptions.flipVertical
            )
            if (flipped !== result) result.recycle()
            result = flipped
        }
        return result
    }

    // ── Video → GIF ─────────────────────────────────────────────────────

    private fun convertVideoToGif(
        sourceFile: File,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions
    ): File {
        val detectedFormat = FileFormat.fromExtension(sourceFile.extension) ?: FormatSniffer.sniff(sourceFile)
        if (detectedFormat == FileFormat.GIF) {
            return convertGifToVideo(sourceFile, FileFormat.GIF, context, progressCallback, QualityPreset.MEDIUM, toolOptions)
        }
        progressCallback(0.05f)
        val outputFile = cacheOutputFile(context, sourceFile, "gif")

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IOException("Cannot read video duration")
            val rotation = getVideoRotation(sourceFile.absolutePath)
            progressCallback(0.1f)

            val startMs = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val endMs = if (toolOptions.hasTrim) toolOptions.trimEndMs.coerceAtMost(durationMs) else durationMs
            val effectiveDurationMs = (endMs - startMs).coerceAtLeast(1)

            val maxDim = 480
            val baseFps = 10f
            val frameIntervalMs = (1000f / baseFps).toLong().coerceAtLeast(33)
            val displayDelayMs = (frameIntervalMs / toolOptions.speedFactor).toInt().coerceAtLeast(20)

            val firstBitmap = retriever.getFrameAtTime(
                startMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            if (firstBitmap == null) throw IOException("Could not extract first video frame for GIF")
            var firstRotated = rotateBitmap(firstBitmap, rotation)
            if (firstRotated !== firstBitmap) firstBitmap.recycle()
            if (toolOptions.hasRotation) {
                val userRotated = rotateBitmap(firstRotated, toolOptions.rotationDegrees)
                if (userRotated !== firstRotated) firstRotated.recycle()
                firstRotated = userRotated
            }
            val frameW = firstRotated.width
            val frameH = firstRotated.height
            val scale = if (maxOf(frameW, frameH) > maxDim) {
                maxDim.toFloat() / maxOf(frameW, frameH)
            } else 1f
            val gifWidth = (frameW * scale).toInt().coerceAtLeast(1)
            val gifHeight = (frameH * scale).toInt().coerceAtLeast(1)

            FileOutputStream(outputFile).use { fos ->
                val encoder = GifEncoder(fos)
                encoder.start(gifWidth, gifHeight, 0)

                var timeMs = startMs
                var firstFrameAdded = false
                while (timeMs <= endMs) {
                    val bitmap = if (!firstFrameAdded) {
                        firstFrameAdded = true
                        firstRotated
                    } else {
                        retriever.getFrameAtTime(
                            timeMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                    }
                    if (bitmap != null) {
                        val rotated = if (bitmap === firstRotated) bitmap else {
                            var r = rotateBitmap(bitmap, rotation)
                            if (r !== bitmap) bitmap.recycle()
                            if (toolOptions.hasRotation) {
                                val userRotated = rotateBitmap(r, toolOptions.rotationDegrees)
                                if (userRotated !== r) r.recycle()
                                r = userRotated
                            }
                            r
                        }
                        val scaled = Bitmap.createScaledBitmap(rotated, gifWidth, gifHeight, true)
                        if (scaled !== rotated) rotated.recycle()
                        val frame = if (toolOptions.hasFlip) {
                            BitmapGeometryUtil.flipBitmap(
                                scaled,
                                toolOptions.flipHorizontal,
                                toolOptions.flipVertical
                            )
                        } else scaled

                        encoder.addFrame(frame, displayDelayMs)
                        if (frame !== scaled) frame.recycle()
                        scaled.recycle()
                        if (rotated !== firstRotated) rotated.recycle()
                    }
                    timeMs += frameIntervalMs
                    val p = 0.1f + 0.85f * (timeMs - startMs).toFloat() / effectiveDurationMs
                    progressCallback(p.coerceAtMost(0.95f))
                }
                encoder.finish()
            }
            firstRotated.recycle()
            progressCallback(1.0f)
            return outputFile
        } finally {
            retriever.release()
        }
    }

    // ── Video → AVI (FFmpeg) ───────────────────────────────────────────

    private fun convertVideoToAvi(
        sourceFile: File,
        sourceFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset,
        toolOptions: MediaToolOptions
    ): File {
        val mp4File = convertVideo(
            sourceFile, sourceFormat, FileFormat.MP4, context,
            { p -> progressCallback(p * 0.9f) },
            quality, toolOptions
        )
        val aviFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.avi")
        try {
            return ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToAvi(mp4File, aviFile) { p ->
                progressCallback(0.9f + 0.1f * p)
            }
        } finally {
            mp4File.delete()
        }
    }

    // ── Image → BMP ─────────────────────────────────────────────────────

    private fun writeBmp(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { ImageExportUtil.writeBmp(bitmap, it) }
    }

    // ── Image → TIFF (uncompressed RGB) ─────────────────────────────────

    private fun writeTiff(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { ImageExportUtil.writeTiff(bitmap, it) }
    }

    /** Encode bitmap to HEIC using AndroidX HeifWriter. Device-dependent; may throw if no HEIC encoder. */
    private fun writeHeic(bitmap: Bitmap, file: File, quality: Int) {
        ImageExportUtil.writeHeic(bitmap, file, quality)
    }

    // ── Document: TXT → DOCX ────────────────────────────────────────────

    private fun convertTxtToDocx(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val text = sourceFile.readText()
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.docx")
        progressCallback(0.3f)

        val doc = org.apache.poi.xwpf.usermodel.XWPFDocument()
        val lines = text.split("\n")
        for ((i, line) in lines.withIndex()) {
            val para = doc.createParagraph()
            val run = para.createRun()
            run.setText(line)
            run.setFontSize(12)
            if (i % 200 == 0) progressCallback(0.3f + 0.5f * i / lines.size.coerceAtLeast(1))
        }
        progressCallback(0.85f)
        FileOutputStream(outputFile).use { doc.write(it) }
        doc.close()
        progressCallback(1.0f)
        return outputFile
    }

    // ── Document: TXT → RTF ─────────────────────────────────────────────

    private fun convertTxtToRtf(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val text = sourceFile.readText()
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.rtf")
        progressCallback(0.3f)

        val rtf = StringBuilder()
        rtf.append("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\n")
        rtf.append("\\f0\\fs24\n")
        for (line in text.split("\n")) {
            rtf.append("${escapeRtfText(line)}\\par\n")
        }
        rtf.append("}")
        progressCallback(0.8f)
        outputFile.writeText(rtf.toString())
        progressCallback(1.0f)
        return outputFile
    }

    // ── Document: TXT → EPUB ────────────────────────────────────────────

    private fun convertTxtToEpub(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val text = sourceFile.readText()
        val title = sourceFile.nameWithoutExtension
        val outputFile = File(context.cacheDir, "${title}.epub")
        progressCallback(0.3f)
        writeSimpleEpub(outputFile, title, text)
        progressCallback(1.0f)
        return outputFile
    }

    // ── Document: HTML → PDF / DOCX / TXT / EPUB ─────────────────────────

    private fun convertHtmlToPdf(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.15f)
        val htmlContent = sourceFile.readText()
        val baseUri = resolveHtmlBaseUri(htmlContent, sourceFile.parentFile?.absolutePath ?: context.cacheDir.absolutePath)
        progressCallback(0.25f)
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.pdf")
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val converterProperties = com.itextpdf.html2pdf.ConverterProperties()
        converterProperties.baseUri = baseUri
        // Allow larger images so page screenshots and embedded media are included (default can be restrictive)
        try {
            val retriever = com.itextpdf.styledxmlparser.resolver.resource.DefaultResourceRetriever()
            retriever.setResourceSizeByteLimit(20 * 1024 * 1024) // 20 MB per resource
            converterProperties.resourceRetriever = retriever
        } catch (_: Throwable) { /* use default retriever */ }
        progressCallback(0.35f)
        java.io.ByteArrayInputStream(htmlContent.toByteArray(Charsets.UTF_8)).use { input ->
            com.itextpdf.html2pdf.HtmlConverter.convertToPdf(input, pdfDocument, converterProperties)
        }
        pdfDocument.close()
        progressCallback(1.0f)
        return outputFile
    }

    /** Resolves base URI for HTML so images and relative links load: prefers stored BASE_URL, then <base href>, then first absolute URL origin, then file dir. */
    private fun resolveHtmlBaseUri(htmlContent: String, fallbackFileDir: String): String {
        // Comment we inject when saving from link: <!-- BASE_URL:https://example.com/ -->
        val baseUrlComment = Regex("""<!--\s*BASE_URL:\s*(\S+)\s*-->""").find(htmlContent)
        if (baseUrlComment != null) {
            val base = baseUrlComment.groupValues[1].trim().removeSuffix("/") + "/"
            if (base.startsWith("http://") || base.startsWith("https://")) return base
        }
        val doc = Jsoup.parse(htmlContent)
        val baseHref = doc.select("base[href]").first()?.attr("abs:href")
        if (!baseHref.isNullOrBlank()) {
            val withSlash = baseHref.removeSuffix("/") + "/"
            if (withSlash.startsWith("http")) return withSlash
        }
        val firstEl = doc.select("img[src^=http], link[href^=http], a[href^=http]").first() ?: return fallbackFileDir.trimEnd('/') + "/"
        val firstAbsolute = (if (firstEl.tagName() == "img") firstEl.attr("abs:src") else firstEl.attr("abs:href")).trim()
        if (firstAbsolute.isNotEmpty()) {
            try {
                val url = java.net.URL(firstAbsolute)
                return "${url.protocol}://${url.host}/"
            } catch (_: Exception) { }
        }
        return fallbackFileDir.trimEnd('/') + "/"
    }

    private fun convertHtmlToTxt(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.2f)
        val text = HtmlStructureUtil.plainTextFromHtml(sourceFile.readText())
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.txt")
        outputFile.writeText(text)
        progressCallback(1.0f)
        return outputFile
    }

    private fun convertHtmlToDocx(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.05f)
        val pdf = convertHtmlToPdf(sourceFile, context) { p -> progressCallback(0.05f + 0.45f * p) }
        try {
            val docx = PdfConverter().convertPdfToDocx(pdf, context) { p -> progressCallback(0.5f + 0.5f * p) }
            val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.docx")
            if (docx.absolutePath != outputFile.absolutePath) {
                docx.copyTo(outputFile, overwrite = true)
                docx.delete()
            }
            return outputFile
        } finally {
            pdf.delete()
        }
    }

    private fun convertHtmlToRtf(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val lines = HtmlStructureUtil.linesFromHtml(sourceFile.readText())
        progressCallback(0.4f)
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.rtf")

        val rtf = StringBuilder()
        rtf.append("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\n")
        rtf.append("\\f0\\fs24\n")
        for (line in lines) {
            rtf.append("${escapeRtfText(line)}\\par\n")
        }
        rtf.append("}")
        progressCallback(0.8f)
        outputFile.writeText(rtf.toString())
        progressCallback(1.0f)
        return outputFile
    }

    private fun convertHtmlToEpub(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val html = sourceFile.readText()
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("title")?.text()?.ifBlank { null } ?: sourceFile.nameWithoutExtension
        val text = HtmlStructureUtil.plainTextFromHtml(html)
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.epub")
        progressCallback(0.3f)
        writeSimpleEpub(outputFile, title, text)
        progressCallback(1.0f)
        return outputFile
    }

    // ── Document: * → HTML (via text extraction) ─────────────────────────

    private fun convertDocumentToHtml(
        sourceFile: File,
        textExtractor: (File) -> File,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.1f)
        val textFile = textExtractor(sourceFile)
        try {
            val text = textFile.readText()
            val title = sourceFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${title}.html")
            progressCallback(0.8f)
            writeTextToHtml(outputFile, title, text)
            progressCallback(1.0f)
            return outputFile
        } finally {
            textFile.delete()
        }
    }

    private fun convertTxtToHtml(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.2f)
        val text = sourceFile.readText()
        val title = sourceFile.nameWithoutExtension
        val outputFile = File(context.cacheDir, "${title}.html")
        writeTextToHtml(outputFile, title, text)
        progressCallback(1.0f)
        return outputFile
    }

    private fun writeTextToHtml(outputFile: File, title: String, text: String) {
        val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val paragraphs = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (paragraphs.isEmpty()) {
            "  <p></p>"
        } else {
            paragraphs.joinToString("\n") { line ->
                val escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                "  <p>$escaped</p>"
            }
        }
        val html = """<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"/><title>$escapedTitle</title></head>
<body>
$body
</body>
</html>"""
        outputFile.writeText(html)
    }

    // ── Document: RTF → TXT ─────────────────────────────────────────────

    private fun convertRtfToTxt(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val rtfContent = sourceFile.readText()
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.txt")
        progressCallback(0.3f)
        val plainText = stripRtf(rtfContent)
        progressCallback(0.7f)
        outputFile.writeText(plainText)
        progressCallback(1.0f)
        return outputFile
    }

    // ── Document: RTF → DOCX ────────────────────────────────────────────

    private fun convertRtfToDocx(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val rtfContent = sourceFile.readText()
        val plainText = stripRtf(rtfContent)
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.docx")
        progressCallback(0.3f)

        val doc = org.apache.poi.xwpf.usermodel.XWPFDocument()
        for (line in plainText.split("\n")) {
            val para = doc.createParagraph()
            val run = para.createRun()
            run.setText(line)
            run.setFontSize(12)
        }
        progressCallback(0.8f)
        FileOutputStream(outputFile).use { doc.write(it) }
        doc.close()
        progressCallback(1.0f)
        return outputFile
    }

    // ── Document: RTF → EPUB ────────────────────────────────────────────

    private fun convertRtfToEpub(sourceFile: File, context: Context, progressCallback: (Float) -> Unit): File {
        progressCallback(0.1f)
        val rtfContent = sourceFile.readText()
        val plainText = stripRtf(rtfContent)
        val title = sourceFile.nameWithoutExtension
        val outputFile = File(context.cacheDir, "${title}.epub")
        progressCallback(0.3f)
        writeSimpleEpub(outputFile, title, plainText)
        progressCallback(1.0f)
        return outputFile
    }

    // ── Cross-category helpers (planner targets) ─────────────────────────

    private fun isRasterImageFormat(format: FileFormat): Boolean =
        format == FileFormat.JPG || format == FileFormat.JPEG || format == FileFormat.PNG ||
            format == FileFormat.WEBP || format == FileFormat.BMP || format == FileFormat.TIFF ||
            format == FileFormat.HEIC

    private fun convertDocumentToImages(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.05f)
        val pdfFile = if (sourceFormat == FileFormat.PDF) {
            sourceFile
        } else {
            convertDocument(sourceFile, sourceFormat, FileFormat.PDF, context) { p ->
                progressCallback(0.05f + 0.45f * p)
            }
        }
        try {
            return convertDocument(pdfFile, FileFormat.PDF, targetFormat, context) { p ->
                progressCallback(0.5f + 0.5f * p)
            }
        } finally {
            if (sourceFormat != FileFormat.PDF && pdfFile != sourceFile && pdfFile.exists()) {
                pdfFile.delete()
            }
        }
    }

    private fun convertImageToDocument(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.1f)
        val text = extractTextFromImage(sourceFile).ifBlank { "[No text detected in image]" }
        val txtFile = File(context.cacheDir, "ocr_${System.currentTimeMillis()}.txt")
        txtFile.writeText(text)
        if (targetFormat == FileFormat.TXT) {
            val out = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.txt")
            txtFile.copyTo(out, overwrite = true)
            txtFile.delete()
            progressCallback(1f)
            return out
        }
        try {
            return when (targetFormat) {
                FileFormat.PDF -> convertTxtToPdf(txtFile, context, progressCallback)
                FileFormat.DOCX -> convertTxtToDocx(txtFile, context, progressCallback)
                FileFormat.RTF -> convertTxtToRtf(txtFile, context, progressCallback)
                FileFormat.EPUB -> convertTxtToEpub(txtFile, context, progressCallback)
                FileFormat.HTML -> convertTxtToHtml(txtFile, context, progressCallback)
                else -> throw UnsupportedOperationException("Image to ${targetFormat.displayName} not supported")
            }
        } finally {
            txtFile.delete()
        }
    }

    private fun convertImageToAudio(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.1f)
        val text = extractTextFromImage(sourceFile).ifBlank { " " }
        val txtFile = File(context.cacheDir, "ocr_tts_${System.currentTimeMillis()}.txt")
        txtFile.writeText(text)
        try {
            return convertDocumentToAudio(txtFile, FileFormat.TXT, targetFormat, context) { p ->
                progressCallback(0.1f + 0.9f * p)
            }
        } finally {
            txtFile.delete()
        }
    }

    private fun convertVideoToImage(
        sourceFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions
    ): File {
        if (toolOptions.exportAllVideoFrames) {
            return convertVideoToFrameZip(sourceFile, context, progressCallback, toolOptions)
        }
        progressCallback(0.1f)
        val frameFile = captureVideoFramePng(sourceFile, context, toolOptions)
        try {
            progressCallback(0.5f)
            val result = convertImage(frameFile, FileFormat.PNG, targetFormat, context, { p ->
                progressCallback(0.5f + 0.5f * p)
            })
            return result
        } finally {
            frameFile.delete()
        }
    }

    /** Extract one oriented video frame to a temporary PNG (caller deletes). */
    private fun captureVideoFramePng(
        sourceFile: File,
        context: Context,
        toolOptions: MediaToolOptions
    ): File {
        val detected = FileFormat.fromExtension(sourceFile.extension) ?: FormatSniffer.sniff(sourceFile)
        if (detected == FileFormat.GIF) {
            val frames = decodeGifFrames(sourceFile)
            try {
                val targetMs = if (toolOptions.hasExtractFrame) toolOptions.extractFrameMs else 0L
                var accMs = 0L
                var chosen = frames.first().first
                for ((bmp, delay) in frames) {
                    chosen = bmp
                    if (accMs + delay > targetMs) break
                    accMs += delay
                }
                var oriented = if (toolOptions.hasRotation) rotateBitmap(chosen, toolOptions.rotationDegrees) else chosen
                if (toolOptions.hasFlip) {
                    val flipped = BitmapGeometryUtil.flipBitmap(oriented, toolOptions.flipHorizontal, toolOptions.flipVertical)
                    if (flipped !== oriented && oriented !== chosen) oriented.recycle()
                    oriented = flipped
                }
                val frameFile = File(context.cacheDir, "frame_${System.currentTimeMillis()}.png")
                FileOutputStream(frameFile).use { fos ->
                    oriented.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                if (oriented !== chosen) oriented.recycle()
                return frameFile
            } finally {
                frames.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val trimStart = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val trimEnd = if (toolOptions.hasTrim) toolOptions.trimEndMs else durationMs
            val sampleMs = when {
                toolOptions.hasExtractFrame -> toolOptions.extractFrameMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
                trimEnd > trimStart -> (trimStart + trimEnd) / 2
                durationMs > 0 -> durationMs / 2
                else -> 0L
            }
            val frameOption = if (toolOptions.hasExtractFrame) {
                MediaMetadataRetriever.OPTION_CLOSEST
            } else {
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            }
            val bitmap = retriever.getFrameAtTime(sampleMs * 1000, frameOption)
                ?: throw IOException("Could not extract video frame")
            val oriented = orientVideoFrame(bitmap, sourceFile.absolutePath, toolOptions)
            val frameFile = File(context.cacheDir, "frame_${System.currentTimeMillis()}.png")
            FileOutputStream(frameFile).use { fos ->
                oriented.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            oriented.recycle()
            return frameFile
        } finally {
            retriever.release()
        }
    }

    private fun convertGifToImage(
        sourceFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        scale: Float?,
        quality: QualityPreset,
        toolOptions: MediaToolOptions
    ): File {
        if (toolOptions.exportAllVideoFrames) {
            return convertVideoToFrameZip(sourceFile, context, progressCallback, toolOptions)
        }
        progressCallback(0.1f)
        val bitmap = if (toolOptions.hasExtractFrame) {
            val frames = decodeGifFrames(sourceFile)
            var accMs = 0L
            var chosen = frames.first().first
            for ((bmp, delay) in frames) {
                chosen = bmp
                if (accMs + delay > toolOptions.extractFrameMs) break
                accMs += delay
            }
            val copy = chosen.copy(Bitmap.Config.ARGB_8888, false)
            frames.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            copy
        } else {
            BitmapFactory.decodeFile(sourceFile.absolutePath)
                ?: decodeGifFrames(sourceFile).let { frames ->
                    val first = frames.first().first.copy(Bitmap.Config.ARGB_8888, false)
                    frames.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
                    first
                }
        } ?: throw IOException("Could not decode GIF frame")

        val hasTransform = (scale != null && scale > 0f && scale != 1.0f) ||
            toolOptions.hasRotation ||
            toolOptions.hasFlip
        if (targetFormat == FileFormat.PNG && !hasTransform) {
            val outputFile = File(context.cacheDir, "gif_out_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { fos ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw IOException("Failed to encode GIF frame to PNG")
                }
            }
            bitmap.recycle()
            progressCallback(1f)
            return outputFile
        }
        val frameFile = File(context.cacheDir, "gif_frame_${System.currentTimeMillis()}.png")
        FileOutputStream(frameFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
        progressCallback(0.5f)
        try {
            return convertImage(
                frameFile, FileFormat.PNG, targetFormat, context,
                { p -> progressCallback(0.5f + 0.5f * p) },
                scale, quality, toolOptions
            )
        } finally {
            if (frameFile.exists()) frameFile.delete()
        }
    }

    private fun convertVideoToDocument(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions
    ): File {
        val detected = FileFormat.fromExtension(sourceFile.extension) ?: FormatSniffer.sniff(sourceFile) ?: sourceFormat
        if (detected == FileFormat.GIF || sourceFormat == FileFormat.GIF || toolOptions.hasExtractFrame) {
            progressCallback(0.05f)
            val frameFile = captureVideoFramePng(sourceFile, context, toolOptions)
            try {
                progressCallback(0.2f)
                return convertImageToDocument(frameFile, FileFormat.PNG, targetFormat, context) { p ->
                    progressCallback(0.2f + 0.8f * p)
                }
            } finally {
                frameFile.delete()
            }
        }
        val wavFile = File(context.cacheDir, "v2doc_${System.currentTimeMillis()}.wav")
        try {
            extractVideoToWav(sourceFile, wavFile, { p -> progressCallback(p * 0.45f) }, toolOptions)
            return convertAudioToDocument(wavFile, FileFormat.WAV, targetFormat, context) { p ->
                progressCallback(0.45f + 0.55f * p)
            }
        } finally {
            wavFile.delete()
        }
    }

    private fun convertAudioToImage(
        sourceFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions
    ): File {
        progressCallback(0.1f)
        val wav = File(context.cacheDir, "wave_${System.currentTimeMillis()}.wav")
        try {
            if (toolOptions.hasTrim || toolOptions.hasSpeed) {
                decodeAudioToWavWithTools(sourceFile, wav, toolOptions) { p -> progressCallback(0.1f + 0.4f * p) }
            } else {
                val tempWav = decodeAudioToMonoWav(sourceFile, context, 44100)
                tempWav.copyTo(wav, overwrite = true)
                tempWav.delete()
                progressCallback(0.4f)
            }
            val info = readWavInfo(wav)
            val width = 1200
            val height = 400
            val samples = info.pcm.size / 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                strokeWidth = 1f
                isAntiAlias = true
            }
            if (samples > 1) {
                val step = (samples / width).coerceAtLeast(1)
                val mid = height / 2f
                for (x in 0 until width) {
                    val idx = (x * step * 2).coerceAtMost(info.pcm.size - 2)
                    val sample = ((info.pcm[idx + 1].toInt() shl 8) or (info.pcm[idx].toInt() and 0xff)).toShort().toInt()
                    val y = mid - (sample / 32768f) * (height / 2f - 4f)
                    canvas.drawPoint(x.toFloat(), y, paint)
                }
            }
            val png = File(context.cacheDir, "waveform_${System.currentTimeMillis()}.png")
            FileOutputStream(png).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) }
            bitmap.recycle()
            progressCallback(0.7f)
            val out = convertImage(png, FileFormat.PNG, targetFormat, context, { p ->
                progressCallback(0.7f + 0.3f * p)
            })
            if (out.absolutePath != png.absolutePath && png.exists()) {
                png.delete()
            }
            return out
        } finally {
            wav.delete()
        }
    }

    private data class WavPcmInfo(val sampleRate: Int, val channels: Int, val pcm: ByteArray)

    private fun readWavInfo(file: File): WavPcmInfo {
        val parsed = readWav(file)
        return WavPcmInfo(parsed.sampleRate, parsed.channels, parsed.pcm)
    }

    private fun convertAudioToVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        progressCallback(0.1f)
        val imageTarget = FileFormat.PNG
        val imageFile = convertAudioToImage(sourceFile, imageTarget, context, { p ->
            progressCallback(0.1f + 0.35f * p)
        }, toolOptions)
        val durationMs = getAudioDurationMs(sourceFile, toolOptions).coerceAtLeast(1000L)
        val frameCount = (durationMs / 1000).toInt().coerceIn(1, 600)
        val frameList = List(frameCount) { imageFile }

        if (targetFormat == FileFormat.GIF) {
            try {
                val silentGif = convertImagesToVideo(frameList, FileFormat.PNG, FileFormat.GIF, context, { p ->
                    progressCallback(0.45f + 0.55f * p)
                }, quality)
                val outGif = cacheOutputFile(context, sourceFile, "gif")
                if (silentGif.absolutePath != outGif.absolutePath) {
                    silentGif.copyTo(outGif, overwrite = true)
                    silentGif.delete()
                }
                progressCallback(1f)
                return outGif
            } finally {
                if (imageFile.exists()) imageFile.delete()
            }
        }

        if (targetFormat == FileFormat.WEBM) {
            val silentWebm = try {
                convertImagesToVideo(frameList, FileFormat.PNG, FileFormat.WEBM, context, { p ->
                    progressCallback(0.45f + 0.35f * p)
                }, quality)
            } finally {
                if (imageFile.exists()) imageFile.delete()
            }
            var tempWav: File? = null
            try {
                val audioForMux = if (toolOptions.hasTrim || toolOptions.hasSpeed) {
                    val wav = File(context.cacheDir, "mux_wav_${System.currentTimeMillis()}.wav")
                    decodeAudioToWavWithTools(sourceFile, wav, toolOptions) { }
                    tempWav = wav
                    wav
                } else {
                    sourceFile
                }
                val outputFile = cacheOutputFile(context, sourceFile, "webm")
                return ab.inque.converter.pipeline.FfmpegVideoExporter.muxWebmWithAudio(
                    videoOnly = silentWebm,
                    audioSource = audioForMux,
                    output = outputFile
                ) { p -> progressCallback(0.8f + 0.2f * p) }
            } finally {
                if (silentWebm.exists()) silentWebm.delete()
                tempWav?.let { if (it.exists()) it.delete() }
            }
        }

        val videoContainerFormat = when (targetFormat) {
            FileFormat.MKV, FileFormat.AVI -> FileFormat.MP4
            else -> targetFormat
        }
        val silentVideo = try {
            convertImagesToVideo(frameList, FileFormat.PNG, videoContainerFormat, context, { p ->
                progressCallback(0.45f + 0.30f * p)
            }, quality)
        } finally {
            if (imageFile.exists()) imageFile.delete()
        }
        val audioForMux = File(context.cacheDir, "mux_audio_${System.currentTimeMillis()}.m4a")
        try {
            if (toolOptions.hasTrim || toolOptions.hasSpeed) {
                val wav = File(context.cacheDir, "mux_wav_${System.currentTimeMillis()}.wav")
                try {
                    decodeAudioToWavWithTools(sourceFile, wav, toolOptions) { }
                    encodeAudioToM4a(wav, audioForMux, quality) { }
                } finally {
                    wav.delete()
                }
            } else {
                encodeAudioToM4a(sourceFile, audioForMux, quality) { }
            }
            if (targetFormat == FileFormat.MKV || targetFormat == FileFormat.AVI) {
                val muxedMp4 = muxVideoWithAudioTrack(
                    silentVideo, audioForMux, FileFormat.MP4, context
                ) { p -> progressCallback(0.75f + 0.15f * p) }
                val outputFile = cacheOutputFile(context, sourceFile, targetFormat.extension)
                try {
                    return if (targetFormat == FileFormat.MKV) {
                        ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToMkv(muxedMp4, outputFile) { p ->
                            progressCallback(0.9f + 0.1f * p)
                        }
                    } else {
                        ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToAvi(muxedMp4, outputFile) { p ->
                            progressCallback(0.9f + 0.1f * p)
                        }
                    }
                } finally {
                    if (muxedMp4.exists()) muxedMp4.delete()
                }
            }
            return muxVideoWithAudioTrack(
                silentVideo, audioForMux, targetFormat, context
            ) { p -> progressCallback(0.8f + 0.2f * p) }
        } finally {
            if (silentVideo.exists()) silentVideo.delete()
            if (audioForMux.exists()) audioForMux.delete()
        }
    }

    private fun convertDocumentToVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset
    ): File {
        progressCallback(0.05f)
        val zipOrImage = convertDocumentToImages(sourceFile, sourceFormat, FileFormat.PNG, context) { p ->
            progressCallback(0.05f + 0.55f * p)
        }
        val images = if (zipOrImage.extension.equals("zip", true)) {
            unzipImages(zipOrImage, context)
        } else {
            listOf(zipOrImage)
        }
        try {
            return convertImagesToVideo(images, FileFormat.PNG, targetFormat, context, { p ->
                progressCallback(0.6f + 0.4f * p)
            }, quality)
        } finally {
            if (zipOrImage.extension.equals("zip", true)) {
                val unzipDir = images.firstOrNull()?.parentFile
                images.forEach { if (it.exists()) it.delete() }
                if (unzipDir != null && unzipDir != context.cacheDir && unzipDir.name.startsWith("unz_") && unzipDir.exists()) {
                    unzipDir.deleteRecursively()
                }
            }
            if (zipOrImage != sourceFile && zipOrImage.exists()) {
                zipOrImage.delete()
            }
        }
    }

    private fun unzipImages(zipFile: File, context: Context): List<File> {
        val dir = File(context.cacheDir, "unz_${System.currentTimeMillis()}").apply { mkdirs() }
        val out = mutableListOf<File>()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val f = File(dir, entry.name.substringAfterLast('/'))
                    FileOutputStream(f).use { fos -> zis.copyTo(fos) }
                    out.add(f)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out.sortedBy { it.name }
    }

    internal fun convertImagesToVideo(
        imageFiles: List<File>,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset
    ): File {
        if (imageFiles.isEmpty()) throw IOException("No images for video")
        val displayDelayMs = 1000
        val decodedCache = LinkedHashMap<String, Bitmap>()
        val bitmaps = mutableListOf<Bitmap>()
        try {
            for (file in imageFiles) {
                val bmp = decodedCache.getOrPut(file.absolutePath) {
                    decodeBitmapWithFallback(file, sourceFormat)
                        ?: throw IOException("Could not decode image for video: ${file.name}")
                }
                bitmaps.add(bmp)
            }
            if (bitmaps.isEmpty()) throw IOException("Could not decode images for video")
            val (rawCanvasW, rawCanvasH) = BitmapGeometryUtil.slideshowCanvasSize(
                bitmaps.first().width, bitmaps.first().height
            )
            if (targetFormat == FileFormat.GIF) {
                val gifFile = File(context.cacheDir, "slideshow_${System.currentTimeMillis()}.gif")
                FileOutputStream(gifFile).use { fos ->
                    val encoder = GifEncoder(fos)
                    encoder.start(rawCanvasW, rawCanvasH, 0)
                    for ((index, bitmap) in bitmaps.withIndex()) {
                        val framed = BitmapGeometryUtil.fitBitmapOnCanvas(bitmap, rawCanvasW, rawCanvasH)
                        encoder.addFrame(framed, displayDelayMs)
                        if (framed !== bitmap) framed.recycle()
                        progressCallback(((index + 1).toFloat() / bitmaps.size).coerceAtMost(0.98f))
                    }
                    encoder.finish()
                }
                progressCallback(1f)
                return gifFile
            }

            val canvasW = (rawCanvasW and 1.inv()).coerceAtLeast(2)
            val canvasH = (rawCanvasH and 1.inv()).coerceAtLeast(2)
            val mp4File = File(
                context.cacheDir,
                "slideshow_${System.currentTimeMillis()}.${if (targetFormat == FileFormat.MOV || targetFormat == FileFormat.THREEGP) targetFormat.extension else "mp4"}"
            )
            encodeBitmapsToMp4(
                bitmaps = bitmaps,
                canvasW = canvasW,
                canvasH = canvasH,
                frameDurationMs = displayDelayMs,
                outputFile = mp4File,
                quality = quality
            ) { p ->
                progressCallback((p * 0.8f).coerceAtMost(0.8f))
            }

            return when (targetFormat) {
                FileFormat.MP4, FileFormat.MOV, FileFormat.THREEGP -> {
                    progressCallback(1f)
                    mp4File
                }
                FileFormat.WEBM -> {
                    try {
                        convertVideo(
                            mp4File, FileFormat.MP4, FileFormat.WEBM, context,
                            { p -> progressCallback(0.8f + 0.2f * p) },
                            quality, MediaToolOptions()
                        )
                    } finally {
                        if (mp4File.exists()) mp4File.delete()
                    }
                }
                FileFormat.MKV -> {
                    val mkvFile = File(context.cacheDir, "slideshow_${System.currentTimeMillis()}.mkv")
                    try {
                        ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToMkv(mp4File, mkvFile) { p ->
                            progressCallback(0.8f + 0.2f * p)
                        }
                    } finally {
                        if (mp4File.exists()) mp4File.delete()
                    }
                }
                FileFormat.AVI -> {
                    val aviFile = File(context.cacheDir, "slideshow_${System.currentTimeMillis()}.avi")
                    try {
                        ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToAvi(mp4File, aviFile) { p ->
                            progressCallback(0.8f + 0.2f * p)
                        }
                    } finally {
                        if (mp4File.exists()) mp4File.delete()
                    }
                }
                else -> {
                    progressCallback(1f)
                    mp4File
                }
            }
        } finally {
            decodedCache.values.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun encodeBitmapsToMp4(
        bitmaps: List<Bitmap>,
        canvasW: Int,
        canvasH: Int,
        frameDurationMs: Int,
        outputFile: File,
        quality: QualityPreset,
        progressCallback: (Float) -> Unit
    ) {
        val frames = bitmaps.map { it to frameDurationMs.coerceAtLeast(20) }
        encodeFrameListToMp4(frames, canvasW, canvasH, outputFile, quality, progressCallback)
    }

    private fun encodeFrameListToMp4(
        frames: List<Pair<Bitmap, Int>>,
        canvasW: Int,
        canvasH: Int,
        outputFile: File,
        quality: QualityPreset,
        progressCallback: (Float) -> Unit
    ) {
        if (frames.isEmpty()) throw IOException("No frames to encode")
        val width = (canvasW and 1.inv()).coerceAtLeast(2)
        val height = (canvasH and 1.inv()).coerceAtLeast(2)

        val videoBitRate = when (quality) {
            QualityPreset.LOW -> 1_000_000
            QualityPreset.MEDIUM -> 2_500_000
            QualityPreset.HIGH -> 5_000_000
        }
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        // Expand frames so that even a 1-frame slideshow has >= 2 samples spanning the full duration
        val expandedFrames = mutableListOf<Pair<Bitmap, Long>>()
        var currentPtsUs = 0L
        for ((bmp, delayMs) in frames) {
            val durMs = delayMs.coerceAtLeast(20)
            expandedFrames.add(bmp to currentPtsUs)
            currentPtsUs += durMs * 1000L
        }
        // Add final sentinel frame at currentPtsUs so the last frame's duration is preserved in MP4
        expandedFrames.add(frames.last().first to currentPtsUs)

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val encoderName = codecList.findEncoderForFormat(format)
            encoder = if (encoderName != null) {
                MediaCodec.createByCodecName(encoderName)
            } else {
                MediaCodec.createEncoderByType(mime)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            val argbScratch = IntArray(width * height)
            val nv12Scratch = ByteArray(width * height * 3 / 2)
            var cachedSourceBitmap: Bitmap? = null

            var inputIndex = 0
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inBufIdx = encoder.dequeueInputBuffer(10_000)
                    if (inBufIdx >= 0) {
                        if (inputIndex >= expandedFrames.size) {
                            encoder.queueInputBuffer(
                                inBufIdx, 0, 0, currentPtsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val (bmp, ptsUs) = expandedFrames[inputIndex]
                            if (bmp !== cachedSourceBitmap) {
                                val framed = if (bmp.width == width && bmp.height == height) {
                                    bmp
                                } else {
                                    BitmapGeometryUtil.fitBitmapOnCanvas(bmp, width, height)
                                }
                                framed.getPixels(argbScratch, 0, width, 0, 0, width, height)
                                if (framed !== bmp) framed.recycle()
                                argbToNv12(argbScratch, nv12Scratch, width, height)
                                cachedSourceBitmap = bmp
                            }

                            val inputImage = try { encoder.getInputImage(inBufIdx) } catch (_: Exception) { null }
                            val queuedSize: Int
                            if (inputImage != null && inputImage.planes.size >= 3) {
                                writeArgbToYuvImage(argbScratch, width, height, inputImage)
                                val cap = encoder.getInputBuffer(inBufIdx)?.capacity() ?: nv12Scratch.size
                                queuedSize = nv12Scratch.size.coerceAtMost(cap)
                            } else {
                                val inBuf = encoder.getInputBuffer(inBufIdx)!!
                                inBuf.clear()
                                val toPut = nv12Scratch.size.coerceAtMost(inBuf.remaining())
                                inBuf.put(nv12Scratch, 0, toPut)
                                queuedSize = toPut
                            }
                            encoder.queueInputBuffer(inBufIdx, 0, queuedSize, ptsUs, 0)
                            inputIndex++
                            progressCallback((inputIndex.toFloat() / expandedFrames.size).coerceAtMost(0.95f))
                        }
                    }
                }

                val outBufIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outBufIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outBufIdx)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (isConfig) {
                            bufferInfo.size = 0
                        }
                        if (outBuf != null && bufferInfo.size > 0) {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outBufIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                }
            }
            if (muxerStarted) {
                muxer.stop()
            }
            progressCallback(1f)
        } finally {
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun argbToNv12(argb: IntArray, nv12: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        var yIndex = 0
        var uvIndex = ySize
        for (j in 0 until height) {
            val rowStart = j * width
            for (i in 0 until width) {
                val c = argb[rowStart + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                nv12[yIndex++] = y.toByte()
                if ((j and 1) == 0 && (i and 1) == 0 && uvIndex + 1 < nv12.size) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    nv12[uvIndex++] = u.toByte()
                    nv12[uvIndex++] = v.toByte()
                }
            }
        }
    }

    private fun writeArgbToYuvImage(argb: IntArray, width: Int, height: Int, image: android.media.Image) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        for (y in 0 until height) {
            val rowOff = y * width
            val yRowPos = y * yRowStride
            for (x in 0 until width) {
                val c = argb[rowOff + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yVal = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yBuf.put(yRowPos + x * yPixStride, yVal.toByte())
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uVal = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val vVal = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val uvX = x shr 1
                    val uvY = y shr 1
                    val uPos = uvY * uRowStride + uvX * uPixStride
                    val vPos = uvY * vRowStride + uvX * vPixStride
                    if (uPos < uBuf.limit()) uBuf.put(uPos, uVal.toByte())
                    if (vPos < vBuf.limit()) vBuf.put(vPos, vVal.toByte())
                }
            }
        }
    }

    private fun decodeGifFrames(gifFile: File): List<Pair<Bitmap, Int>> {
        @Suppress("DEPRECATION")
        val movie = try {
            FileInputStream(gifFile).use { android.graphics.Movie.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
        if (movie != null && movie.width() > 0 && movie.height() > 0) {
            val w = movie.width()
            val h = movie.height()
            val duration = movie.duration()
            if (duration > 0) {
                val stepMs = 100
                val frames = mutableListOf<Pair<Bitmap, Int>>()
                var t = 0
                while (t < duration && frames.size < 300) {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    movie.setTime(t)
                    movie.draw(canvas, 0f, 0f)
                    val delay = minOf(stepMs, (duration - t).coerceAtLeast(20))
                    frames.add(bmp to delay)
                    t += stepMs
                }
                if (frames.isNotEmpty()) return frames
            } else {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                movie.setTime(0)
                movie.draw(canvas, 0f, 0f)
                return listOf(bmp to 1000)
            }
        }
        val fallback = decodeBitmapWithFallback(gifFile, FileFormat.GIF)
            ?: throw IOException("Could not decode GIF frames: ${gifFile.name}")
        return listOf(fallback to 1000)
    }

    private fun convertGifToVideo(
        sourceFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset,
        toolOptions: MediaToolOptions
    ): File {
        progressCallback(0.05f)
        val rawFrames = decodeGifFrames(sourceFile)
        val transformedFrames = mutableListOf<Pair<Bitmap, Int>>()
        try {
            val startMs = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val endMs = if (toolOptions.hasTrim) toolOptions.trimEndMs else Long.MAX_VALUE
            var elapsedMs = 0L
            for ((bmp, delayMs) in rawFrames) {
                val frameEnd = elapsedMs + delayMs
                val include = !toolOptions.hasTrim || (frameEnd > startMs && elapsedMs <= endMs)
                elapsedMs = frameEnd
                if (!include) continue
                var current = bmp
                if (toolOptions.hasRotation) {
                    current = rotateBitmap(current, toolOptions.rotationDegrees)
                }
                if (toolOptions.hasFlip) {
                    val flipped = BitmapGeometryUtil.flipBitmap(current, toolOptions.flipHorizontal, toolOptions.flipVertical)
                    if (flipped !== current && current !== bmp) current.recycle()
                    current = flipped
                }
                val outBmp = if (current === bmp) bmp.copy(Bitmap.Config.ARGB_8888, false) else current
                val adjustedDelay = (delayMs / toolOptions.speedFactor).toInt().coerceAtLeast(20)
                transformedFrames.add(outBmp to adjustedDelay)
            }
            if (transformedFrames.isEmpty()) {
                var current = rawFrames.first().first
                if (toolOptions.hasRotation) current = rotateBitmap(current, toolOptions.rotationDegrees)
                if (toolOptions.hasFlip) {
                    val flipped = BitmapGeometryUtil.flipBitmap(current, toolOptions.flipHorizontal, toolOptions.flipVertical)
                    if (flipped !== current && current !== rawFrames.first().first) current.recycle()
                    current = flipped
                }
                val outBmp = if (current === rawFrames.first().first) current.copy(Bitmap.Config.ARGB_8888, false) else current
                transformedFrames.add(outBmp to 1000)
            }
        } finally {
            rawFrames.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
        }

        try {
            val firstBmp = transformedFrames.first().first
            val (rawW, rawH) = BitmapGeometryUtil.slideshowCanvasSize(firstBmp.width, firstBmp.height)
            if (targetFormat == FileFormat.GIF) {
                val outGif = cacheOutputFile(context, sourceFile, "gif")
                FileOutputStream(outGif).use { fos ->
                    val encoder = GifEncoder(fos)
                    encoder.start(rawW, rawH, 0)
                    for ((idx, pair) in transformedFrames.withIndex()) {
                        val framed = BitmapGeometryUtil.fitBitmapOnCanvas(pair.first, rawW, rawH)
                        encoder.addFrame(framed, pair.second)
                        if (framed !== pair.first) framed.recycle()
                        progressCallback(0.1f + 0.85f * (idx + 1) / transformedFrames.size)
                    }
                    encoder.finish()
                }
                progressCallback(1f)
                return outGif
            }

            val canvasW = (rawW and 1.inv()).coerceAtLeast(2)
            val canvasH = (rawH and 1.inv()).coerceAtLeast(2)
            val mp4File = if (targetFormat == FileFormat.MP4 || targetFormat == FileFormat.MOV || targetFormat == FileFormat.THREEGP) {
                cacheOutputFile(context, sourceFile, targetFormat.extension)
            } else {
                File(context.cacheDir, "gif2vid_${System.currentTimeMillis()}.mp4")
            }

            encodeFrameListToMp4(
                frames = transformedFrames,
                canvasW = canvasW,
                canvasH = canvasH,
                outputFile = mp4File,
                quality = quality
            ) { p ->
                progressCallback(0.1f + 0.7f * p)
            }

            return when (targetFormat) {
                FileFormat.MP4, FileFormat.MOV, FileFormat.THREEGP -> {
                    progressCallback(1f)
                    mp4File
                }
                FileFormat.WEBM -> {
                    try {
                        convertVideo(
                            mp4File, FileFormat.MP4, FileFormat.WEBM, context,
                            { p -> progressCallback(0.8f + 0.2f * p) },
                            quality, MediaToolOptions()
                        )
                    } finally {
                        if (mp4File.exists()) mp4File.delete()
                    }
                }
                FileFormat.MKV -> {
                    val mkvFile = cacheOutputFile(context, sourceFile, "mkv")
                    try {
                        ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToMkv(mp4File, mkvFile) { p ->
                            progressCallback(0.8f + 0.2f * p)
                        }
                    } finally {
                        if (mp4File.exists()) mp4File.delete()
                    }
                }
                FileFormat.AVI -> {
                    val aviFile = cacheOutputFile(context, sourceFile, "avi")
                    try {
                        ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToAvi(mp4File, aviFile) { p ->
                            progressCallback(0.8f + 0.2f * p)
                        }
                    } finally {
                        if (mp4File.exists()) mp4File.delete()
                    }
                }
                else -> {
                    progressCallback(1f)
                    mp4File
                }
            }
        } finally {
            transformedFrames.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
        }
    }

    private fun convertGifToPdf(
        sourceFile: File,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        if (toolOptions.hasExtractFrame) {
            progressCallback(0.05f)
            val frameFile = captureVideoFramePng(sourceFile, context, toolOptions)
            try {
                progressCallback(0.4f)
                val pdfOut = convertImageToPdf(frameFile, FileFormat.PNG, context, { p ->
                    progressCallback(0.4f + 0.6f * p)
                }, null)
                val finalOut = cacheOutputFile(context, sourceFile, "pdf")
                if (pdfOut.absolutePath != finalOut.absolutePath) {
                    pdfOut.copyTo(finalOut, overwrite = true)
                    pdfOut.delete()
                }
                return finalOut
            } finally {
                frameFile.delete()
            }
        }
        progressCallback(0.1f)
        val rawFrames = decodeGifFrames(sourceFile)
        val outputFile = cacheOutputFile(context, sourceFile, "pdf")
        val tempPng = File(context.cacheDir, "gif_pdf_${System.currentTimeMillis()}.png")
        try {
            val firstBmp = rawFrames.first().first
            val rot = if (toolOptions.hasRotation) toolOptions.rotationDegrees else 0
            val displayW = if (rot == 90 || rot == 270) firstBmp.height else firstBmp.width
            val displayH = if (rot == 90 || rot == 270) firstBmp.width else firstBmp.height
            val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
            val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
            val pageSize = if (displayW > displayH) {
                com.itextpdf.kernel.geom.PageSize.A4.rotate()
            } else {
                com.itextpdf.kernel.geom.PageSize.A4
            }
            pdfDocument.setDefaultPageSize(pageSize)
            val document = com.itextpdf.layout.Document(pdfDocument)

            val startMs = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val endMs = if (toolOptions.hasTrim) toolOptions.trimEndMs else Long.MAX_VALUE
            var elapsedMs = 0L
            val selectedFrames = mutableListOf<Bitmap>()
            for ((bmp, delayMs) in rawFrames) {
                val frameEnd = elapsedMs + delayMs
                val include = !toolOptions.hasTrim || (frameEnd > startMs && elapsedMs <= endMs)
                elapsedMs = frameEnd
                if (include) selectedFrames.add(bmp)
            }
            if (selectedFrames.isEmpty()) selectedFrames.add(rawFrames.first().first)

            for ((idx, bmp) in selectedFrames.withIndex()) {
                var oriented = if (toolOptions.hasRotation) rotateBitmap(bmp, toolOptions.rotationDegrees) else bmp
                if (toolOptions.hasFlip) {
                    val flipped = BitmapGeometryUtil.flipBitmap(oriented, toolOptions.flipHorizontal, toolOptions.flipVertical)
                    if (flipped !== oriented && oriented !== bmp) oriented.recycle()
                    oriented = flipped
                }
                val toExport = scaleBitmapToFitInside(oriented, 595, 842)
                if (toExport !== oriented && oriented !== bmp) oriented.recycle()
                FileOutputStream(tempPng).use { toExport.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (toExport !== bmp) toExport.recycle()

                val imageData = com.itextpdf.io.image.ImageDataFactory.create(tempPng.absolutePath)
                val image = com.itextpdf.layout.element.Image(imageData)
                image.setAutoScaleWidth(true)
                document.add(image)
                if (idx < selectedFrames.size - 1) {
                    document.add(com.itextpdf.layout.element.AreaBreak(com.itextpdf.layout.properties.AreaBreakType.NEXT_PAGE))
                }
                progressCallback(0.1f + 0.85f * (idx + 1) / selectedFrames.size)
            }
            document.close()
            progressCallback(1f)
            return outputFile
        } finally {
            if (tempPng.exists()) tempPng.delete()
            rawFrames.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
        }
    }

    private fun decodeAudioToWavWithTools(
        sourceFile: File,
        outputFile: File,
        toolOptions: MediaToolOptions,
        progressCallback: (Float) -> Unit
    ) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex == -1 || format == null) throw IOException("No audio track")
            extractor.selectTrack(trackIndex)
            val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val trimStartUs = if (toolOptions.hasTrim) toolOptions.trimStartMs * 1000L else 0L
            val trimEndUs = if (toolOptions.hasTrim) {
                val raw = toolOptions.trimEndMs * 1000L
                if (trackDurationUs > 0L) raw.coerceAtMost(trackDurationUs) else raw
            } else if (trackDurationUs > 0L) trackDurationUs else Long.MAX_VALUE
            if (toolOptions.hasTrim) extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = java.io.ByteArrayOutputStream()
            var sawEOS = false
            while (!sawEOS) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < trimStartUs) {
                            extractor.advance()
                        } else if (sampleTime > trimEndUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawEOS = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmBuffer.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            var pcm = pcmBuffer.toByteArray()
            if (toolOptions.hasSpeed && toolOptions.speedFactor != 1f) {
                pcm = resamplePcmForSpeed(pcm, sampleRate, channelCount, toolOptions.speedFactor)
            }
            writeWavFile(outputFile, pcm, sampleRate, channelCount)
            progressCallback(1f)
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun resamplePcmForSpeed(pcm: ByteArray, sampleRate: Int, channels: Int, speed: Float): ByteArray {
        if (speed == 1f) return pcm
        val frameBytes = channels * 2
        val frames = pcm.size / frameBytes
        val outFrames = (frames / speed).toInt().coerceAtLeast(1)
        val out = ByteArray(outFrames * frameBytes)
        for (i in 0 until outFrames) {
            val srcFrame = (i * speed).toInt().coerceAtMost(frames - 1)
            System.arraycopy(pcm, srcFrame * frameBytes, out, i * frameBytes, frameBytes)
        }
        return out
    }

    // ── Shared helpers ──────────────────────────────────────────────────

    private fun stripRtf(rtf: String): String = RtfUtil.stripRtf(rtf)

    private fun escapeRtfText(text: String): String = RtfUtil.escapeRtfText(text)

    private fun writeSimpleEpub(outputFile: File, title: String, text: String) {
        val escapedTitle = text.take(60)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val escapedBody = text.split("\n").joinToString("\n") { line ->
            "<p>${line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</p>"
        }
        val stableId = java.util.UUID.nameUUIDFromBytes(title.toByteArray()).toString()

        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            fun addEntry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            addEntry("mimetype", "application/epub+zip")

            addEntry("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""")

            addEntry("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$title</dc:title>
    <dc:creator>AnyConvert</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="uid">urn:uuid:$stableId</dc:identifier>
  </metadata>
  <manifest>
    <item id="chapter1" href="chapter1.html" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="chapter1"/></spine>
</package>""")

            addEntry("OEBPS/toc.ncx", """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:$stableId"/></head>
  <docTitle><text>$title</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1">
      <navLabel><text>$title</text></navLabel>
      <content src="chapter1.html"/>
    </navPoint>
  </navMap>
</ncx>""")

            addEntry("OEBPS/chapter1.html", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>$title</title><meta charset="utf-8"/></head>
<body>
<h1>$title</h1>
$escapedBody
</body>
</html>""")
        }
    }

    private fun getAudioDurationMs(sourceFile: File, toolOptions: MediaToolOptions): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var maxUs = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    maxUs = maxOf(maxUs, fmt.getLong(MediaFormat.KEY_DURATION))
                }
            }
            val durationMs = (maxUs / 1000).coerceAtLeast(0)
            return if (toolOptions.hasTrim) {
                (toolOptions.trimEndMs - toolOptions.trimStartMs).coerceAtLeast(0)
            } else {
                durationMs
            }
        } finally {
            extractor.release()
        }
    }

    /** Replaces silent video audio with the track from [audioFile]. */
    private fun muxVideoWithAudioTrack(
        videoFile: File,
        audioFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        val outputFile = File(context.cacheDir, "${videoFile.nameWithoutExtension}_av.${targetFormat.extension}")
        if (targetFormat == FileFormat.MKV) {
            val mp4Out = muxVideoWithAudioTrack(
                videoFile, audioFile, FileFormat.MP4, context
            ) { p -> progressCallback(p * 0.85f) }
            try {
                return ab.inque.converter.pipeline.FfmpegVideoExporter.remuxToMkv(
                    mp4Out, outputFile
                ) { p -> progressCallback(0.85f + 0.15f * p) }
            } finally {
                mp4Out.delete()
            }
        }
        val muxerFormat = when (targetFormat) {
            FileFormat.WEBM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            var videoTrackIdx = -1
            var videoFormat: MediaFormat? = null
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val f = videoExtractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrackIdx = i
                    videoFormat = f
                    break
                }
            }
            for (i in 0 until audioExtractor.trackCount) {
                val f = audioExtractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIdx = i
                    audioFormat = f
                    break
                }
            }
            if (videoTrackIdx < 0 || audioTrackIdx < 0 || videoFormat == null || audioFormat == null) {
                throw IOException("Could not mux: missing video or audio track")
            }
            videoExtractor.selectTrack(videoTrackIdx)
            audioExtractor.selectTrack(audioTrackIdx)
            muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()
            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = videoExtractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = videoExtractor.sampleTime
                info.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(outVideo, buf, info)
                videoExtractor.advance()
            }
            while (true) {
                val size = audioExtractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = audioExtractor.sampleTime
                info.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(outAudio, buf, info)
                audioExtractor.advance()
            }
            muxer.stop()
            progressCallback(1f)
            return outputFile
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    /** Samples frames in trim range and packs PNGs into a ZIP. */
    private fun convertVideoToFrameZip(
        sourceFile: File,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions
    ): File {
        progressCallback(0.05f)
        val retriever = MediaMetadataRetriever()
        val zipFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}_frames.zip")
        val tempDir = File(context.cacheDir, "frames_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            retriever.setDataSource(sourceFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IOException("Cannot read video duration")
            val fpsMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsMeta?.toFloatOrNull()?.coerceIn(1f, 30f) ?: 10f
            val frameIntervalMs = (1000f / fps).toLong().coerceAtLeast(33)
            val startMs = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val endMs = if (toolOptions.hasTrim) toolOptions.trimEndMs.coerceAtMost(durationMs) else durationMs
            var timeMs = startMs
            var index = 0
            val frameFiles = mutableListOf<File>()
            while (timeMs <= endMs) {
                val bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) {
                    val oriented = orientVideoFrame(bitmap, sourceFile.absolutePath, toolOptions)
                    val frameFile = File(tempDir, "frame_${index++}.png")
                    FileOutputStream(frameFile).use { fos ->
                        oriented.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    oriented.recycle()
                    frameFiles.add(frameFile)
                }
                timeMs += frameIntervalMs
                progressCallback((0.1f + 0.7f * ((timeMs - startMs).toFloat() / (endMs - startMs + 1).coerceAtLeast(1))).coerceAtMost(0.8f))
            }
            if (frameFiles.isEmpty()) throw IOException("No frames extracted")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (f in frameFiles) {
                    zos.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            progressCallback(1f)
            return zipFile
        } finally {
            retriever.release()
            tempDir.deleteRecursively()
        }
    }
} 
