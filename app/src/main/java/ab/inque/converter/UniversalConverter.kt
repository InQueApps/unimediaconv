package ab.inque.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
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
        progressCallback: (Float) -> Unit
    ): File {
        val sourceFormat = FileFormat.fromExtension(sourceFile.extension) 
            ?: throw IllegalArgumentException("Unsupported source format")
        
        if (sourceFormat.category != targetFormat.category) {
            throw IllegalArgumentException("Cannot convert between different categories")
        }
        
        return when (sourceFormat.category) {
            FormatCategory.DOCUMENT -> convertDocument(sourceFile, sourceFormat, targetFormat, context, progressCallback)
            FormatCategory.IMAGE -> convertImage(sourceFile, sourceFormat, targetFormat, context, progressCallback)
            FormatCategory.AUDIO -> convertAudio(sourceFile, sourceFormat, targetFormat, context, progressCallback)
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
        progressCallback(0.1f)
        
        return when {
            sourceFormat == FileFormat.EPUB && targetFormat == FileFormat.PDF -> {
                val epubConverter = EpubConverter()
                epubConverter.convertEpubToPdf(sourceFile, context)
            }
            sourceFormat == FileFormat.PDF && targetFormat == FileFormat.EPUB -> {
                val pdfConverter = PdfConverter()
                pdfConverter.convertPdfToEpub(sourceFile, context)
            }
            sourceFormat == FileFormat.TXT && targetFormat == FileFormat.PDF -> {
                convertTxtToPdf(sourceFile, context)
            }
            sourceFormat == FileFormat.RTF && targetFormat == FileFormat.PDF -> {
                convertRtfToPdf(sourceFile, context)
            }
            sourceFormat == FileFormat.DOCX && targetFormat == FileFormat.PDF -> {
                convertDocxToPdf(sourceFile, context)
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
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.2f)
        
        val bitmap = when (sourceFormat) {
            FileFormat.JPG, FileFormat.JPEG -> BitmapFactory.decodeFile(sourceFile.absolutePath)
            FileFormat.PNG -> BitmapFactory.decodeFile(sourceFile.absolutePath)
            FileFormat.BMP -> BitmapFactory.decodeFile(sourceFile.absolutePath)
            FileFormat.TIFF -> BitmapFactory.decodeFile(sourceFile.absolutePath)
            FileFormat.WEBP -> BitmapFactory.decodeFile(sourceFile.absolutePath)
            else -> throw UnsupportedOperationException("Unsupported image format: ${sourceFormat.displayName}")
        } ?: throw IOException("Failed to decode image")
        
        progressCallback(0.5f)
        
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        
        val outputStream = FileOutputStream(outputFile)
        val success = when (targetFormat) {
            FileFormat.JPG, FileFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            FileFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            FileFormat.WEBP -> bitmap.compress(Bitmap.CompressFormat.WEBP, 90, outputStream)
            FileFormat.BMP -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream) // BMP not directly supported, use PNG
            FileFormat.TIFF -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream) // TIFF not directly supported, use PNG
            else -> throw UnsupportedOperationException("Unsupported target image format: ${targetFormat.displayName}")
        }
        
        outputStream.close()
        bitmap.recycle()
        
        if (!success) {
            throw IOException("Failed to compress image")
        }
        
        progressCallback(1.0f)
        return outputFile
    }
    
    private fun convertAudio(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.1f)
        
        // For now, we'll create a simple conversion that copies the file
        // In a real implementation, you would use MediaCodec or FFmpeg for proper audio conversion
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            throw IOException("Failed to convert audio: ${e.message}")
        }
        
        progressCallback(1.0f)
        return outputFile
    }
    
    private fun convertVideo(
        sourceFile: File,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        context: Context,
        progressCallback: (Float) -> Unit
    ): File {
        progressCallback(0.1f)
        
        // For now, we'll create a simple conversion that copies the file
        // In a real implementation, you would use MediaCodec or FFmpeg for proper video conversion
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.${targetFormat.extension}")
        
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            throw IOException("Failed to convert video: ${e.message}")
        }
        
        progressCallback(1.0f)
        return outputFile
    }
    
    private fun convertTxtToPdf(sourceFile: File, context: Context): File {
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.pdf")
        
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        
        try {
            val text = sourceFile.readText()
            val lines = text.split("\n")
            
            for (line in lines) {
                document.add(
                    com.itextpdf.layout.element.Paragraph(line)
                        .setFontSize(12f)
                )
            }
        } catch (e: Exception) {
            document.add(
                com.itextpdf.layout.element.Paragraph("Error reading text file: ${e.message}")
                    .setFontSize(12f)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.RED)
            )
        }
        
        document.close()
        return outputFile
    }
    
    private fun convertRtfToPdf(sourceFile: File, context: Context): File {
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.pdf")
        
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        
        try {
            val rtfContent = sourceFile.readText()
            // Simple RTF to plain text conversion (removes RTF markup)
            val plainText = rtfContent.replace(Regex("\\\\[a-z0-9-]+\\d*\\s?"), "")
                .replace(Regex("\\{[^}]*\\}"), "")
                .replace("\\'", "'")
                .trim()
            
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
        
        document.close()
        return outputFile
    }
    
    private fun convertDocxToPdf(sourceFile: File, context: Context): File {
        val outputFile = File(context.cacheDir, "${sourceFile.nameWithoutExtension}.pdf")
        
        val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
        val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
        val document = com.itextpdf.layout.Document(pdfDocument)
        
        try {
            // For now, we'll create a simple PDF with a message
            // In a real implementation, you would use Apache POI or similar to read DOCX
            document.add(
                com.itextpdf.layout.element.Paragraph("DOCX to PDF Conversion")
                    .setFontSize(20f)
                    .setBold()
            )
            
            document.add(
                com.itextpdf.layout.element.Paragraph("DOCX conversion requires additional libraries (Apache POI).")
                    .setFontSize(12f)
            )
            
            document.add(
                com.itextpdf.layout.element.Paragraph("File: ${sourceFile.name}")
                    .setFontSize(10f)
            )
        } catch (e: Exception) {
            document.add(
                com.itextpdf.layout.element.Paragraph("Error converting DOCX file: ${e.message}")
                    .setFontSize(12f)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.RED)
            )
        }
        
        document.close()
        return outputFile
    }
} 