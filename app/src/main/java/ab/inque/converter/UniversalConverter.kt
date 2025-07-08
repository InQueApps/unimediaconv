package ab.inque.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
        
        if (sourceFormat.category != targetFormat.category) {
            throw IllegalArgumentException("Cannot convert between different categories")
        }
        
        return when (sourceFormat.category) {
            FormatCategory.DOCUMENT -> convertDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
            FormatCategory.IMAGE -> convertImage(sourceFile, sourceFormat, targetFormat, context, progressCallback, scale)
            FormatCategory.VIDEO -> convertVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback)
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
        
        try {
            progressCallback(0.1f) // Starting media extraction
            val extractor = MediaExtractor()
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
            val decoder = MediaCodec.createDecoderByType(decoderMime)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()
            
            // Set up encoder for target codec
            val (targetMime, muxerFormat) = when (targetFormat) {
                FileFormat.MP4 -> "video/avc" to MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 // H.264
                FileFormat.WEBM -> "video/x-vnd.on2.vp8" to MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM // VP8
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
            val encoder = MediaCodec.createByCodecName(encoderName)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            val muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
            var outputVideoTrackIndex = -1
            var muxerStarted = false
            
            val bufferInfo = MediaCodec.BufferInfo()
            val inputBuffers = decoder.inputBuffers
            val outputBuffers = encoder.outputBuffers
            var sawInputEOS = false
            var sawOutputEOS = false
            var totalFrames = 0
            val durationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
            
            while (!sawOutputEOS) {
                // Feed decoder
                if (!sawInputEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = inputBuffers[inputBufferIndex]
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
                // Get decoded output
                val decoderOutputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (decoderOutputBufferIndex >= 0) {
                    val decoderOutputBuffer = decoder.outputBuffers[decoderOutputBufferIndex]
                    // Feed to encoder
                    val encoderInputBufferIndex = encoder.dequeueInputBuffer(10000)
                    if (encoderInputBufferIndex >= 0) {
                        val encoderInputBuffer = encoder.inputBuffers[encoderInputBufferIndex]
                        encoderInputBuffer.clear()
                        encoderInputBuffer.put(decoderOutputBuffer)
                        encoder.queueInputBuffer(encoderInputBufferIndex, 0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    decoder.releaseOutputBuffer(decoderOutputBufferIndex, false)
                }
                // Get encoded output
                val encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (encoderOutputBufferIndex >= 0) {
                    val encodedData = encoder.outputBuffers[encoderOutputBufferIndex]
                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            val newFormat = encoder.outputFormat
                            outputVideoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(outputVideoTrackIndex, encodedData, bufferInfo)
                        totalFrames++
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
            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
            extractor.release()
            
            android.util.Log.d("VideoConverter", "Video transcoding completed")
            android.util.Log.d("VideoConverter", "Output file size: ${outputFile.length()} bytes")
            progressCallback(1.0f)
            return outputFile
        } catch (e: Exception) {
            android.util.Log.e("VideoConverter", "Error transcoding video: ${e.message}")
            throw IOException("Failed to transcode video: ${e.message}")
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
    

} 