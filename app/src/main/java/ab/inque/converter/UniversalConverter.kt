package ab.inque.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class UniversalConverter {
    
    fun convertFile(
        sourceFile: File,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        scale: Float? = null
    ): File {
        val sourceFormat = FileFormat.fromExtension(sourceFile.extension) 
            ?: throw IllegalArgumentException("Unsupported source format")
        
        // Cross-category: Image → PDF
        if (sourceFormat.category == FormatCategory.IMAGE && targetFormat == FileFormat.PDF) {
            return convertImageToPdf(sourceFile, sourceFormat, context, progressCallback, scale)
        }
        // Cross-category: PDF → Images (JPG/PNG)
        if (sourceFormat == FileFormat.PDF && (targetFormat == FileFormat.JPG || targetFormat == FileFormat.JPEG || targetFormat == FileFormat.PNG)) {
            return convertDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Cross-category: Video → Audio (MP3, WAV)
        if (sourceFormat.category == FormatCategory.VIDEO && targetFormat.category == FormatCategory.AUDIO) {
            return extractAudioFromVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback)
        }
        // Same category or allowed
        if (sourceFormat.category != targetFormat.category) {
            throw IllegalArgumentException("Cannot convert from ${sourceFormat.displayName} to ${targetFormat.displayName}")
        }
        
        return when (sourceFormat.category) {
            FormatCategory.DOCUMENT -> convertDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
            FormatCategory.IMAGE -> convertImage(sourceFile, sourceFormat, targetFormat, context, progressCallback, scale)
            FormatCategory.VIDEO -> convertVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback)
            FormatCategory.AUDIO -> convertAudio(sourceFile, sourceFormat, targetFormat, context, progressCallback)
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
            sourceFormat == FileFormat.RTF && targetFormat == FileFormat.PDF -> {
                convertRtfToPdf(sourceFile, context, progressCallback)
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
            sourceFormat == FileFormat.PDF && (targetFormat == FileFormat.JPG || targetFormat == FileFormat.JPEG || targetFormat == FileFormat.PNG) -> {
                val pdfConverter = PdfConverter()
                pdfConverter.convertPdfToImages(sourceFile, context, targetFormat, progressCallback)
            }
            else -> {
                throw UnsupportedOperationException("Conversion from ${sourceFormat.displayName} to ${targetFormat.displayName} is not yet implemented")
            }
        }.also { progressCallback(1.0f) }
    }
    
    private fun convertImage(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        scale: Float? = null // scale: 1.0 = original size, 0.5 = half size, etc.
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
                BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            FileFormat.TIFF -> {
                android.util.Log.d("ImageConverter", "Decoding TIFF image")
                BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            FileFormat.WEBP -> {
                android.util.Log.d("ImageConverter", "Decoding WebP image")
                BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            FileFormat.HEIC -> {
                android.util.Log.d("ImageConverter", "Decoding HEIC image")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(sourceFile)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    BitmapFactory.decodeFile(sourceFile.absolutePath)
                }
            }
            else -> throw UnsupportedOperationException("Unsupported image format: ${sourceFormat.displayName}")
        } ?: throw IOException("Failed to decode image")
        
        android.util.Log.d("ImageConverter", "Bitmap decoded successfully: ${bitmap.width}x${bitmap.height}")
        
        progressCallback(0.4f) // Image decoded successfully
        
        // Optionally scale the bitmap
        progressCallback(0.5f) // Starting scaling (if needed)
        val scaledBitmap = if (scale != null && scale > 0f && scale != 1.0f) {
            val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        progressCallback(0.6f) // Scaling complete

        progressCallback(0.7f) // Starting compression
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        android.util.Log.d("ImageConverter", "Output file: ${outputFile.absolutePath}")
        
        val outputStream = FileOutputStream(outputFile)
        val success = when (targetFormat) {
            FileFormat.JPG, FileFormat.JPEG -> {
                android.util.Log.d("ImageConverter", "Compressing to JPEG")
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }
            FileFormat.PNG -> {
                android.util.Log.d("ImageConverter", "Compressing to PNG")
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            FileFormat.WEBP -> {
                android.util.Log.d("ImageConverter", "Compressing to WebP")
                scaledBitmap.compress(Bitmap.CompressFormat.WEBP, 90, outputStream)
            }
            FileFormat.BMP -> {
                android.util.Log.d("ImageConverter", "Compressing to PNG (BMP not directly supported)")
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            FileFormat.TIFF -> {
                android.util.Log.d("ImageConverter", "Compressing to PNG (TIFF not directly supported)")
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            FileFormat.HEIC -> {
                android.util.Log.d("ImageConverter", "Compressing to JPEG (HEIC output not supported, using JPEG)")
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }
            else -> throw UnsupportedOperationException("Unsupported target image format: ${targetFormat.displayName}")
        }
        
        outputStream.close()
        android.util.Log.d("ImageConverter", "Compression result: $success")
        android.util.Log.d("ImageConverter", "Output file size: ${outputFile.length()} bytes")
        
        progressCallback(0.9f) // Compression complete, cleaning up
        
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        bitmap.recycle()
        
        if (!success) {
            throw IOException("Failed to compress image")
        }
        
        progressCallback(1.0f) // Conversion complete
        return outputFile
    }
    
    private fun convertVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.05f) // Start conversion
        
        android.util.Log.d("VideoConverter", "Transcoding from ${sourceFormat.displayName} to ${targetFormat.displayName}")
        android.util.Log.d("VideoConverter", "Source file: ${sourceFile.absolutePath}")
        android.util.Log.d("VideoConverter", "File exists: ${sourceFile.exists()}")
        android.util.Log.d("VideoConverter", "File size: ${sourceFile.length()} bytes")
        
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        android.util.Log.d("VideoConverter", "Output file: ${outputFile.absolutePath}")
        
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            progressCallback(0.1f) // Starting media extraction
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            
            // Find video track
            progressCallback(0.15f) // Analyzing video tracks
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex == -1 || videoFormat == null) {
                throw IOException("No video track found in source file")
            }
            extractor.selectTrack(videoTrackIndex)
            progressCallback(0.2f) // Video track selected
            
            // Set up decoder for source codec
            val decoderMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(decoderMime)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()
            
            // Set up encoder for target codec (MP4 and MOV both use MPEG-4 container)
            val (targetMime, muxerFormat) = when (targetFormat) {
                FileFormat.MP4, FileFormat.MOV -> "video/avc" to MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                FileFormat.WEBM -> "video/x-vnd.on2.vp8" to MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                FileFormat.GIF -> throw UnsupportedOperationException("Video to GIF is not yet supported. Use MP4 or MOV.")
                else -> throw IOException("Transcoding to ${targetFormat.displayName} is not supported on this device")
            }
            // Check if encoder is supported
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val encoderName = codecList.findEncoderForFormat(MediaFormat.createVideoFormat(targetMime, videoFormat.getInteger(MediaFormat.KEY_WIDTH), videoFormat.getInteger(MediaFormat.KEY_HEIGHT)))
            if (encoderName == null) {
                throw IOException("No encoder found for target codec: $targetMime")
            }
            val encoderFormat = MediaFormat.createVideoFormat(targetMime, videoFormat.getInteger(MediaFormat.KEY_WIDTH), videoFormat.getInteger(MediaFormat.KEY_HEIGHT))
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            encoder = MediaCodec.createByCodecName(encoderName)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
            var outputVideoTrackIndex = -1
            var muxerStarted = false
            
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val durationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
            
            while (!sawOutputEOS) {
                // Feed decoder (use getInputBuffer instead of deprecated inputBuffers)
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
                                val presentationTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                // Get decoded output (use getOutputBuffer instead of deprecated outputBuffers)
                val decoderOutputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (decoderOutputBufferIndex >= 0) {
                    val decoderOutputBuffer = decoder.getOutputBuffer(decoderOutputBufferIndex)
                    if (decoderOutputBuffer != null) {
                        val encoderInputBufferIndex = encoder.dequeueInputBuffer(10000)
                        if (encoderInputBufferIndex >= 0) {
                            val encoderInputBuffer = encoder.getInputBuffer(encoderInputBufferIndex)
                            if (encoderInputBuffer != null) {
                                encoderInputBuffer.clear()
                                encoderInputBuffer.put(decoderOutputBuffer)
                                encoder.queueInputBuffer(encoderInputBufferIndex, 0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                }
                // Get encoded output (use getOutputBuffer instead of deprecated outputBuffers)
                val encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (encoderOutputBufferIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderOutputBufferIndex)
                    if (encodedData != null && bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            val newFormat = encoder.outputFormat
                            outputVideoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(outputVideoTrackIndex, encodedData, bufferInfo)
                        if (durationUs > 0) {
                            val progress = 0.1f + (0.8f * bufferInfo.presentationTimeUs / durationUs)
                            progressCallback(progress.coerceAtMost(0.9f))
                        }
                    }
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }
            android.util.Log.d("VideoConverter", "Video transcoding completed")
            android.util.Log.d("VideoConverter", "Output file size: ${outputFile.length()} bytes")
            progressCallback(1.0f)
            return outputFile
        } catch (e: Exception) {
            android.util.Log.e("VideoConverter", "Error transcoding video: ${e.message}")
            if (outputFile.exists()) {
                try {
                    outputFile.delete()
                } catch (_: Exception) { /* ignore */ }
            }
            throw IOException("Failed to transcode video: ${e.message}")
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                android.util.Log.w("VideoConverter", "Error releasing decoder", e)
            }
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                android.util.Log.w("VideoConverter", "Error releasing encoder", e)
            }
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                android.util.Log.w("VideoConverter", "Error releasing muxer", e)
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                android.util.Log.w("VideoConverter", "Error releasing extractor", e)
            }
        }
    }
    
    private fun convertTxtToPdf(sourceFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.pdf")
        
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
                    val progress = 0.5f + (0.3f * index / lines.size)
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
        
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.pdf")
        
        progressCallback(0.3f) // Starting PDF creation
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        
        try {
            progressCallback(0.4f) // Reading RTF file
            val rtfContent = sourceFile.readText()
            
            progressCallback(0.5f) // Processing RTF content
            // Simple RTF to plain text conversion (removes RTF markup)
            val plainText = rtfContent.replace(Regex("\\\\[a-z0-9-]+\\d*\\s?"), "")
                .replace(Regex("\\{[^}]*\\}"), "")
                .replace("\\'", "'")
                .trim()
            
            progressCallback(0.7f) // Adding content to PDF
            document.add(
                com.itextpdf.layout.element.Paragraph(plainText)
                    .setFontSize(12f)
            )
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
                BitmapFactory.decodeFile(sourceFile.absolutePath)
            }
            FileFormat.HEIC -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(sourceFile)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    BitmapFactory.decodeFile(sourceFile.absolutePath)
                }
            }
            else -> throw UnsupportedOperationException("Image to PDF: unsupported format ${sourceFormat.displayName}")
        } ?: throw IOException("Failed to decode image for PDF")
        progressCallback(0.3f)
        val scaledBitmap = if (scale != null && scale > 0f && scale != 1.0f) {
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else bitmap
        if (scaledBitmap != bitmap) bitmap.recycle()
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.pdf")
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        val tempPng = File(context.cacheDir, "img_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(tempPng).use { scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val imageData = com.itextpdf.io.image.ImageDataFactory.create(tempPng.absolutePath)
            val image = com.itextpdf.layout.element.Image(imageData)
            image.setAutoScaleWidth(true)
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
                    ?: throw IllegalArgumentException("Unsupported image: ${sourceFile.name}")
                val bitmap = when (sourceFormat) {
                    FileFormat.JPG, FileFormat.JPEG, FileFormat.PNG, FileFormat.WEBP, FileFormat.BMP, FileFormat.TIFF ->
                        BitmapFactory.decodeFile(sourceFile.absolutePath)
                    FileFormat.HEIC -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val source = ImageDecoder.createSource(sourceFile)
                            ImageDecoder.decodeBitmap(source)
                        } else BitmapFactory.decodeFile(sourceFile.absolutePath)
                    }
                    else -> throw UnsupportedOperationException("Unsupported image format: ${sourceFormat.displayName}")
                } ?: throw IOException("Failed to decode: ${sourceFile.name}")
                FileOutputStream(tempPng).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val imageData = com.itextpdf.io.image.ImageDataFactory.create(tempPng.absolutePath)
                val image = com.itextpdf.layout.element.Image(imageData)
                image.setAutoScaleWidth(true)
                document.add(image)
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
    
    /** Extract audio track from video to WAV (or MP3 if encoder available). */
    private fun extractAudioFromVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        when (targetFormat) {
            FileFormat.WAV -> return extractVideoToWav(sourceFile, outputFile, progressCallback)
            FileFormat.MP3 -> throw UnsupportedOperationException("MP4/MOV to MP3 requires an MP3 encoder. Use WAV output for now.")
            else -> throw UnsupportedOperationException("Video to ${targetFormat.displayName} is not supported")
        }
    }
    
    private fun extractVideoToWav(sourceFile: File, outputFile: File, progressCallback: (Float) -> Unit): File {
        progressCallback(0.05f)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                throw IOException("No audio track found in video")
            }
            extractor.selectTrack(audioTrackIndex)
            progressCallback(0.15f)
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffer = java.io.ByteArrayOutputStream()
            var sawEOS = false
            val durationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)
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
                        if (durationUs > 0) {
                            val p = 0.2f + 0.7f * (bufferInfo.presentationTimeUs.toFloat() / durationUs)
                            progressCallback(p.coerceAtMost(0.95f))
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true
                }
            }
            progressCallback(0.98f)
            val pcm = pcmBuffer.toByteArray()
            writeWavFile(outputFile, pcm, sampleRate, channelCount)
            progressCallback(1.0f)
            return outputFile
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
        }
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
    
    /** Audio → Audio: M4A/MP3 → WAV (decode to PCM); WAV→MP3 not supported without encoder. */
    private fun convertAudio(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        if (targetFormat == FileFormat.MP3) {
            throw UnsupportedOperationException("Encoding to MP3 is not supported on this device. Use WAV output.")
        }
        if (targetFormat != FileFormat.WAV) {
            throw UnsupportedOperationException("Audio conversion to ${targetFormat.displayName} is not yet supported")
        }
        progressCallback(0.1f)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.wav")
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
} 