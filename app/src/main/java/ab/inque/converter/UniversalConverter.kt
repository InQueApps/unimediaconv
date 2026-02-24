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
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
        scale: Float? = null,
        quality: QualityPreset = QualityPreset.MEDIUM,
        toolOptions: MediaToolOptions = MediaToolOptions()
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
        // Cross-category: Video → Audio (WAV, M4A)
        if (sourceFormat.category == FormatCategory.VIDEO && targetFormat.category == FormatCategory.AUDIO) {
            return extractAudioFromVideo(sourceFile, sourceFormat, targetFormat, context, progressCallback, toolOptions)
        }
        // Same category or allowed
        if (sourceFormat.category != targetFormat.category) {
            throw IllegalArgumentException("Cannot convert from ${sourceFormat.displayName} to ${targetFormat.displayName}")
        }

        // Fast lossless remux for same-format video/audio with trim/speed only (no flip)
        if (sourceFormat == targetFormat
            && (sourceFormat.category == FormatCategory.VIDEO || sourceFormat.category == FormatCategory.AUDIO)
            && (toolOptions.hasTrim || toolOptions.hasSpeed) && !toolOptions.hasFlip
        ) {
            return remuxWithTools(sourceFile, targetFormat, context, progressCallback, toolOptions)
        }
        
        return when (sourceFormat.category) {
            FormatCategory.DOCUMENT -> convertDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
            FormatCategory.IMAGE -> convertImage(sourceFile, sourceFormat, targetFormat, context, progressCallback, scale, quality, toolOptions)
            FormatCategory.VIDEO -> when (targetFormat) {
                FileFormat.GIF -> convertVideoToGif(sourceFile, context, progressCallback, toolOptions)
                FileFormat.AVI -> convertVideoToAvi(sourceFile, context, progressCallback, quality, toolOptions)
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

        // Apply flip / mirror if requested
        val flippedBitmap = if (toolOptions.hasFlip) {
            val matrix = android.graphics.Matrix()
            val sx = if (toolOptions.flipHorizontal) -1f else 1f
            val sy = if (toolOptions.flipVertical) -1f else 1f
            matrix.preScale(sx, sy)
            val result = Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true)
            if (result != scaledBitmap && scaledBitmap != bitmap) scaledBitmap.recycle()
            result
        } else {
            scaledBitmap
        }

        progressCallback(0.7f) // Starting compression
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
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
            else -> throw UnsupportedOperationException("Unsupported target image format: ${targetFormat.displayName}")
        }
        
        outputStream.close()
        android.util.Log.d("ImageConverter", "Compression result: $success")
        android.util.Log.d("ImageConverter", "Output file size: ${outputFile.length()} bytes")
        
        progressCallback(0.9f) // Compression complete, cleaning up
        
        if (flippedBitmap != scaledBitmap) flippedBitmap.recycle()
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
        progressCallback: (Float) -> Unit,
        quality: QualityPreset = QualityPreset.MEDIUM,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        progressCallback(0.05f)
        
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        
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

            val trimStartUs = if (toolOptions.hasTrim) toolOptions.trimStartMs * 1000L else 0L
            val trimEndUs = if (toolOptions.hasTrim) toolOptions.trimEndMs * 1000L else Long.MAX_VALUE
            if (toolOptions.hasTrim) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
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
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val encoderName = codecList.findEncoderForFormat(MediaFormat.createVideoFormat(targetMime, width, height))
                ?: throw IOException("No encoder found for target codec: $targetMime")

            val videoBitRate = when (quality) {
                QualityPreset.LOW -> 1_500_000
                QualityPreset.MEDIUM -> 4_000_000
                QualityPreset.HIGH -> 8_000_000
            }
            val encoderFormat = MediaFormat.createVideoFormat(targetMime, width, height)
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
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
                                    if (toolOptions.hasFlip) {
                                        val frameData = ByteArray(bufferInfo.size)
                                        decoderOutputBuffer.get(frameData)
                                        flipFrameNV12(frameData, width, height, toolOptions.flipHorizontal, toolOptions.flipVertical)
                                        encoderInputBuffer.put(frameData)
                                    } else {
                                        encoderInputBuffer.put(decoderOutputBuffer)
                                    }
                                    encoder.queueInputBuffer(encoderInputBufferIndex, 0, bufferInfo.size, adjustedPTS, bufferInfo.flags)
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
                            muxer.start()
                            muxerStarted = true
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(outputVideoTrackIndex, encodedData, bufferInfo)
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
            progressCallback(1.0f)
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
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(fmt)
                }
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
    
    /** Extract audio track from video. WAV = full decode to PCM; M4A = fast lossless remux. */
    private fun extractAudioFromVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions = MediaToolOptions()
    ): File {
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        return when (targetFormat) {
            FileFormat.WAV -> extractVideoToWav(sourceFile, outputFile, progressCallback, toolOptions)
            FileFormat.M4A -> remuxAudioToM4a(sourceFile, outputFile, progressCallback, toolOptions)
            else -> throw UnsupportedOperationException("Video to ${targetFormat.displayName} audio extraction is not supported")
        }
    }

    /**
     * Lossless audio extraction: copies the audio track from a video container into an M4A file
     * without decoding or re-encoding. Fast and quality-preserving.
     */
    private fun remuxAudioToM4a(
        sourceFile: File,
        outputFile: File,
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
            if (audioTrackIndex == -1 || audioFormat == null) {
                throw IOException("No audio track found in source file")
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
            val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.m4a")
            return encodeAudioToM4a(sourceFile, outputFile, quality, progressCallback)
        }
        if (targetFormat != FileFormat.WAV) {
            throw UnsupportedOperationException("Audio conversion to ${targetFormat.displayName} is not supported")
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

    // ── Video → GIF ─────────────────────────────────────────────────────

    private fun convertVideoToGif(
        sourceFile: File,
        context: Context,
        progressCallback: (Float) -> Unit,
        toolOptions: MediaToolOptions
    ): File {
        progressCallback(0.05f)
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.gif")

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IOException("Cannot read video duration")
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 320
            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 240
            progressCallback(0.1f)

            val startMs = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val endMs = if (toolOptions.hasTrim) toolOptions.trimEndMs.coerceAtMost(durationMs) else durationMs
            val effectiveDurationMs = (endMs - startMs).coerceAtLeast(1)

            val maxDim = 480
            val scale = if (maxOf(videoWidth, videoHeight) > maxDim) {
                maxDim.toFloat() / maxOf(videoWidth, videoHeight)
            } else 1f
            val gifWidth = (videoWidth * scale).toInt().coerceAtLeast(1)
            val gifHeight = (videoHeight * scale).toInt().coerceAtLeast(1)

            val baseFps = 10f
            val frameIntervalMs = (1000f / baseFps).toLong().coerceAtLeast(33)
            val displayDelayMs = (frameIntervalMs / toolOptions.speedFactor).toInt().coerceAtLeast(20)

            FileOutputStream(outputFile).use { fos ->
                val encoder = GifEncoder(fos)
                encoder.start(gifWidth, gifHeight, 0)

                var timeMs = startMs
                while (timeMs <= endMs) {
                    val bitmap = retriever.getFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, gifWidth, gifHeight, true)
                        val frame = if (toolOptions.hasFlip) {
                            val matrix = android.graphics.Matrix()
                            matrix.preScale(
                                if (toolOptions.flipHorizontal) -1f else 1f,
                                if (toolOptions.flipVertical) -1f else 1f
                            )
                            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
                        } else scaled

                        encoder.addFrame(frame, displayDelayMs)
                        if (frame !== scaled) frame.recycle()
                        if (scaled !== bitmap) scaled.recycle()
                        bitmap.recycle()
                    }
                    timeMs += frameIntervalMs
                    val p = 0.1f + 0.85f * (timeMs - startMs).toFloat() / effectiveDurationMs
                    progressCallback(p.coerceAtMost(0.95f))
                }
                encoder.finish()
            }
            progressCallback(1.0f)
            return outputFile
        } finally {
            retriever.release()
        }
    }

    // ── Video → AVI (Motion-JPEG) ───────────────────────────────────────

    private fun convertVideoToAvi(
        sourceFile: File,
        context: Context,
        progressCallback: (Float) -> Unit,
        quality: QualityPreset,
        toolOptions: MediaToolOptions
    ): File {
        progressCallback(0.05f)
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.avi")

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IOException("Cannot read video duration")
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 320
            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 240
            progressCallback(0.1f)

            val startMs = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
            val endMs = if (toolOptions.hasTrim) toolOptions.trimEndMs.coerceAtMost(durationMs) else durationMs
            val effectiveDurationMs = (endMs - startMs).coerceAtLeast(1)

            val fps = 15
            val frameIntervalMs = (1000L / fps)
            val jpegQuality = when (quality) {
                QualityPreset.LOW -> 60
                QualityPreset.MEDIUM -> 80
                QualityPreset.HIGH -> 92
            }

            val frames = mutableListOf<ByteArray>()
            var timeMs = startMs
            while (timeMs <= endMs) {
                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bitmap != null) {
                    val frame = if (toolOptions.hasFlip) {
                        val matrix = android.graphics.Matrix()
                        matrix.preScale(
                            if (toolOptions.flipHorizontal) -1f else 1f,
                            if (toolOptions.flipVertical) -1f else 1f
                        )
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else bitmap

                    val baos = ByteArrayOutputStream()
                    frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                    frames.add(baos.toByteArray())
                    if (frame !== bitmap) frame.recycle()
                    bitmap.recycle()
                }
                timeMs += frameIntervalMs
                progressCallback((0.1f + 0.6f * (timeMs - startMs).toFloat() / effectiveDurationMs).coerceAtMost(0.7f))
            }

            progressCallback(0.75f)
            writeAviFile(outputFile, frames, videoWidth, videoHeight, fps)
            progressCallback(1.0f)
            return outputFile
        } finally {
            retriever.release()
        }
    }

    private fun writeAviFile(
        file: File,
        frames: List<ByteArray>,
        width: Int,
        height: Int,
        fps: Int
    ) {
        val numFrames = frames.size
        if (numFrames == 0) throw IOException("No video frames extracted")
        val microSecPerFrame = 1_000_000 / fps
        val maxFrameSize = frames.maxOf { it.size }

        var moviContentSize = 4 // "movi" list type
        val frameOffsets = mutableListOf<Int>()
        var offset = 4
        for (frame in frames) {
            frameOffsets.add(offset)
            val paddedSize = frame.size + (frame.size % 2)
            moviContentSize += 8 + paddedSize
            offset += 8 + paddedSize
        }

        val strlContentSize = 4 + (8 + 56) + (8 + 40) // "strl" + strh chunk + strf chunk
        val hdrlContentSize = 4 + (8 + 56) + (8 + strlContentSize) // "hdrl" + avih chunk + strl LIST
        val indexDataSize = numFrames * 16
        val riffContentSize = 4 + (8 + hdrlContentSize) + (8 + moviContentSize) + (8 + indexDataSize)

        BufferedOutputStream(FileOutputStream(file)).use { buf ->
            fun writeInt(v: Int) {
                buf.write(v and 0xFF)
                buf.write((v shr 8) and 0xFF)
                buf.write((v shr 16) and 0xFF)
                buf.write((v shr 24) and 0xFF)
            }
            fun writeShort(v: Int) {
                buf.write(v and 0xFF)
                buf.write((v shr 8) and 0xFF)
            }
            fun fourCC(s: String) = buf.write(s.toByteArray(Charsets.US_ASCII))

            fourCC("RIFF"); writeInt(riffContentSize); fourCC("AVI ")

            // ── hdrl LIST ─────────────────────────
            fourCC("LIST"); writeInt(hdrlContentSize); fourCC("hdrl")

            // avih (MainAVIHeader, 56 bytes)
            fourCC("avih"); writeInt(56)
            writeInt(microSecPerFrame)
            writeInt(0)                // dwMaxBytesPerSec
            writeInt(0)                // dwPaddingGranularity
            writeInt(0x10)             // dwFlags = AVIF_HASINDEX
            writeInt(numFrames)
            writeInt(0)                // dwInitialFrames
            writeInt(1)                // dwStreams
            writeInt(maxFrameSize)     // dwSuggestedBufferSize
            writeInt(width)
            writeInt(height)
            writeInt(0); writeInt(0); writeInt(0); writeInt(0)

            // ── strl LIST ─────────────────────────
            fourCC("LIST"); writeInt(strlContentSize); fourCC("strl")

            // strh (AVIStreamHeader, 56 bytes)
            fourCC("strh"); writeInt(56)
            fourCC("vids")
            fourCC("MJPG")
            writeInt(0)                // dwFlags
            writeShort(0); writeShort(0)
            writeInt(0)                // dwInitialFrames
            writeInt(1)                // dwScale
            writeInt(fps)              // dwRate
            writeInt(0)                // dwStart
            writeInt(numFrames)        // dwLength
            writeInt(maxFrameSize)
            writeInt(-1)               // dwQuality
            writeInt(0)                // dwSampleSize
            writeShort(0); writeShort(0); writeShort(width); writeShort(height)

            // strf (BITMAPINFOHEADER, 40 bytes)
            fourCC("strf"); writeInt(40)
            writeInt(40)               // biSize
            writeInt(width)
            writeInt(height)
            writeShort(1)              // biPlanes
            writeShort(24)             // biBitCount
            fourCC("MJPG")             // biCompression
            writeInt(width * height * 3)
            writeInt(0); writeInt(0)   // ppm
            writeInt(0); writeInt(0)   // colors

            // ── movi LIST ─────────────────────────
            fourCC("LIST"); writeInt(moviContentSize); fourCC("movi")
            for (frame in frames) {
                fourCC("00dc"); writeInt(frame.size)
                buf.write(frame)
                if (frame.size % 2 != 0) buf.write(0)
            }

            // ── idx1 ─────────────────────────────
            fourCC("idx1"); writeInt(indexDataSize)
            for (i in frames.indices) {
                fourCC("00dc")
                writeInt(0x10) // AVIIF_KEYFRAME
                writeInt(frameOffsets[i])
                writeInt(frames[i].size)
            }

            buf.flush()
        }
    }

    // ── Image → BMP ─────────────────────────────────────────────────────

    private fun writeBmp(bitmap: Bitmap, file: File) {
        val w = bitmap.width
        val h = bitmap.height
        val rowBytes = w * 3
        val rowPadding = (4 - (rowBytes % 4)) % 4
        val paddedRowBytes = rowBytes + rowPadding
        val imageSize = paddedRowBytes * h
        val fileSize = 14 + 40 + imageSize

        FileOutputStream(file).use { fos ->
            val buf = BufferedOutputStream(fos)
            fun writeInt(v: Int) {
                buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF)
                buf.write((v shr 16) and 0xFF); buf.write((v shr 24) and 0xFF)
            }
            fun writeShort(v: Int) {
                buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF)
            }

            // BITMAPFILEHEADER (14 bytes)
            buf.write('B'.code); buf.write('M'.code)
            writeInt(fileSize)
            writeInt(0) // reserved
            writeInt(54) // pixel data offset

            // BITMAPINFOHEADER (40 bytes)
            writeInt(40)
            writeInt(w)
            writeInt(h) // positive = bottom-up
            writeShort(1) // planes
            writeShort(24) // bits per pixel
            writeInt(0) // BI_RGB
            writeInt(imageSize)
            writeInt(2835); writeInt(2835) // 72 DPI in pixels/meter
            writeInt(0); writeInt(0)

            val pixels = IntArray(w)
            val pad = ByteArray(rowPadding)
            for (y in h - 1 downTo 0) {
                bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val p = pixels[x]
                    buf.write(p and 0xFF)         // B
                    buf.write((p shr 8) and 0xFF) // G
                    buf.write((p shr 16) and 0xFF) // R
                }
                if (rowPadding > 0) buf.write(pad)
            }
            buf.flush()
        }
    }

    // ── Image → TIFF (uncompressed RGB) ─────────────────────────────────

    private fun writeTiff(bitmap: Bitmap, file: File) {
        val w = bitmap.width
        val h = bitmap.height
        val samplesPerPixel = 3
        val bitsPerSample = 8
        val imageDataSize = w * h * samplesPerPixel

        val numTags = 11
        val ifdSize = 2 + numTags * 12 + 4 // count + entries + next-IFD pointer
        val bpsArrayOffset = 8 + ifdSize     // BitsPerSample SHORT array (3 values)
        val xresOffset = bpsArrayOffset + 6  // XResolution RATIONAL (8 bytes)
        val yresOffset = xresOffset + 8
        val stripOffset = yresOffset + 8     // pixel data starts here

        FileOutputStream(file).use { fos ->
            val buf = BufferedOutputStream(fos)
            fun writeShort(v: Int) {
                buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF)
            }
            fun writeInt(v: Int) {
                buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF)
                buf.write((v shr 16) and 0xFF); buf.write((v shr 24) and 0xFF)
            }
            fun ifdEntry(tag: Int, type: Int, count: Int, value: Int) {
                writeShort(tag); writeShort(type); writeInt(count); writeInt(value)
            }

            // Header: little-endian, magic 42, IFD offset = 8
            buf.write('I'.code); buf.write('I'.code)
            writeShort(42)
            writeInt(8)

            // IFD
            writeShort(numTags)
            ifdEntry(256, 3, 1, w)                          // ImageWidth (SHORT)
            ifdEntry(257, 3, 1, h)                          // ImageLength (SHORT)
            ifdEntry(258, 3, 3, bpsArrayOffset)             // BitsPerSample → offset
            ifdEntry(259, 3, 1, 1)                          // Compression = none
            ifdEntry(262, 3, 1, 2)                          // PhotometricInterpretation = RGB
            ifdEntry(273, 4, 1, stripOffset)                // StripOffsets (LONG)
            ifdEntry(277, 3, 1, samplesPerPixel)            // SamplesPerPixel
            ifdEntry(278, 3, 1, h)                          // RowsPerStrip
            ifdEntry(279, 4, 1, imageDataSize)              // StripByteCounts (LONG)
            ifdEntry(282, 5, 1, xresOffset)                 // XResolution → offset
            ifdEntry(283, 5, 1, yresOffset)                 // YResolution → offset
            writeInt(0) // next IFD = 0

            // BitsPerSample array: 8, 8, 8
            writeShort(bitsPerSample); writeShort(bitsPerSample); writeShort(bitsPerSample)

            // XResolution: 72/1 as RATIONAL
            writeInt(72); writeInt(1)
            // YResolution: 72/1 as RATIONAL
            writeInt(72); writeInt(1)

            // Pixel data: RGB top-to-bottom
            val pixels = IntArray(w)
            for (y in 0 until h) {
                bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val p = pixels[x]
                    buf.write((p shr 16) and 0xFF) // R
                    buf.write((p shr 8) and 0xFF)  // G
                    buf.write(p and 0xFF)           // B
                }
            }
            buf.flush()
        }
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

    // ── Shared helpers ──────────────────────────────────────────────────

    private fun stripRtf(rtf: String): String {
        return rtf
            .replace(Regex("\\\\par\\b\\s*"), "\n")
            .replace(Regex("\\\\[a-z]+\\d*\\s?"), "")
            .replace(Regex("\\{[^{}]*\\}"), "")
            .replace(Regex("[{}]"), "")
            .replace("\\'", "'")
            .trim()
    }

    private fun escapeRtfText(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '{' -> sb.append("\\{")
                '}' -> sb.append("\\}")
                '\n' -> sb.append("\\par ")
                '\r' -> {}
                in '\u0000'..'\u007f' -> sb.append(c)
                else -> sb.append("\\u").append(c.code).append("?")
            }
        }
        return sb.toString()
    }

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
    <dc:creator>Universal Converter</dc:creator>
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
} 