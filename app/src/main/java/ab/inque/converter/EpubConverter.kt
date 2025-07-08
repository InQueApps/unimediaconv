package ab.inque.converter

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EpubConverter {
    
    data class EpubContent(
        val htmlFiles: List<HtmlFile> = emptyList(),
        val cssFiles: List<CssFile> = emptyList(),
        val images: List<ImageFile> = emptyList()
    )
    
    data class HtmlFile(
        val name: String,
        val content: String,
        val styles: List<String> = emptyList()
    )
    
    data class CssFile(
        val name: String,
        val content: String
    )
    
    data class ImageFile(
        val name: String,
        val data: ByteArray
    )
    
    fun convertEpubToPdf(epubFile: File, context: Context): File {
        val tempDir = File(context.cacheDir, "epub_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            // Extract EPUB content
            val epubContent = extractEpubContent(epubFile, tempDir)
            
            // Create combined HTML
            val combinedHtml = createCombinedHtml(epubContent, tempDir)
            
            // Convert HTML to PDF
            val originalName = epubFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.pdf")
            convertHtmlToPdf(combinedHtml, outputFile, tempDir)
            
            return outputFile
            
        } catch (e: Exception) {
            // Clean up on error
            tempDir.deleteRecursively()
            throw e
        } finally {
            // Clean up temp directory
            try {
                tempDir.deleteRecursively()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
    
    private fun extractEpubContent(epubFile: File, tempDir: File): EpubContent {
        val htmlFiles = mutableListOf<HtmlFile>()
        val cssFiles = mutableListOf<CssFile>()
        val images = mutableListOf<ImageFile>()
        
        ZipInputStream(FileInputStream(epubFile)).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                val zipEntry = entry!!
                val fileName = zipEntry.name
                
                when {
                    fileName.endsWith(".html") || fileName.endsWith(".xhtml") -> {
                        val content = zip.readBytes().toString(Charsets.UTF_8)
                        val doc = Jsoup.parse(content)
                        val styles = extractStyles(doc)
                        htmlFiles.add(HtmlFile(fileName, content, styles))
                    }
                    fileName.endsWith(".css") -> {
                        val content = zip.readBytes().toString(Charsets.UTF_8)
                        cssFiles.add(CssFile(fileName, content))
                        
                        // Save CSS file to temp directory
                        val cssFile = File(tempDir, fileName)
                        cssFile.parentFile?.mkdirs()
                        cssFile.writeText(content)
                    }
                    fileName.matches(Regex(".*\\.(jpg|jpeg|png|gif|svg)$", RegexOption.IGNORE_CASE)) -> {
                        val imageData = zip.readBytes()
                        images.add(ImageFile(fileName, imageData))
                        
                        // Save image to temp directory
                        val imageFile = File(tempDir, fileName)
                        imageFile.parentFile?.mkdirs()
                        imageFile.writeBytes(imageData)
                    }
                }
            }
        }
        
        return EpubContent(htmlFiles, cssFiles, images)
    }
    
    private fun extractStyles(doc: Document): List<String> {
        val styles = mutableListOf<String>()
        
        // Extract inline styles from head
        doc.head()?.select("style")?.forEach { style ->
            styles.add(style.html())
        }
        
        // Extract styles from link tags
        doc.head()?.select("link[rel=stylesheet]")?.forEach { link ->
            val href = link.attr("href")
            if (href.isNotEmpty()) {
                styles.add("/* External CSS: $href */")
            }
        }
        
        return styles
    }
    
    private fun createCombinedHtml(epubContent: EpubContent, tempDir: File): File {
        val htmlBuilder = StringBuilder()
        
        // HTML header
        htmlBuilder.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <title>EPUB to PDF Conversion</title>
        """.trimIndent())
        
        // Add CSS links
        epubContent.cssFiles.forEach { cssFile ->
            htmlBuilder.append("""
                <link rel="stylesheet" type="text/css" href="${cssFile.name}">
            """.trimIndent())
        }
        
        // Add inline styles
        val allStyles = mutableSetOf<String>()
        epubContent.htmlFiles.forEach { htmlFile ->
            allStyles.addAll(htmlFile.styles)
        }
        
        allStyles.forEach { style ->
            htmlBuilder.append("<style>$style</style>")
        }
        
        // Add basic CSS if no styles found
        if (epubContent.cssFiles.isEmpty() && allStyles.isEmpty()) {
            htmlBuilder.append("""
                <style>
                    body { 
                        font-family: Arial, sans-serif; 
                        margin: 40px; 
                        line-height: 1.6;
                        color: #333;
                    }
                    h1, h2, h3, h4, h5, h6 { 
                        color: #2196F3; 
                        margin-top: 1.5em;
                        margin-bottom: 0.5em;
                    }
                    h1 { font-size: 2em; }
                    h2 { font-size: 1.5em; }
                    h3 { font-size: 1.3em; }
                    p { margin-bottom: 1em; }
                    img { 
                        max-width: 100%; 
                        height: auto; 
                        display: block;
                        margin: 1em auto;
                    }
                    .chapter { page-break-before: always; }
                    .chapter:first-child { page-break-before: avoid; }
                </style>
            """.trimIndent())
        }
        
        htmlBuilder.append("</head><body>")
        
        // Add body content from HTML files
        epubContent.htmlFiles.forEach { htmlFile ->
            val doc = Jsoup.parse(htmlFile.content)
            val body = doc.body()
            
            if (body != null) {
                // Add chapter break for better PDF formatting
                htmlBuilder.append("<div class='chapter'>")
                htmlBuilder.append(body.html())
                htmlBuilder.append("</div>")
            } else {
                // If no body tag, use the whole document
                htmlBuilder.append("<div class='chapter'>")
                htmlBuilder.append(doc.html())
                htmlBuilder.append("</div>")
            }
        }
        
        htmlBuilder.append("</body></html>")
        
        // Write combined HTML to file
        val htmlFile = File(tempDir, "combined.html")
        htmlFile.writeText(htmlBuilder.toString())
        
        return htmlFile
    }
    
    private fun convertHtmlToPdf(htmlFile: File, outputFile: File, baseDir: File) {
        try {
            val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
            val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
            val converterProperties = com.itextpdf.html2pdf.ConverterProperties()
            
            // Set base URL for resolving relative paths
            converterProperties.baseUri = baseDir.absolutePath
            
            com.itextpdf.html2pdf.HtmlConverter.convertToPdf(
                FileInputStream(htmlFile),
                pdfDocument,
                converterProperties
            )
            
            pdfDocument.close()
            
        } catch (e: Exception) {
            // Fallback: create a simple PDF with error message
            val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(outputFile)
            val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
            val document = com.itextpdf.layout.Document(pdfDocument)
            
            document.add(
                com.itextpdf.layout.element.Paragraph("EPUB to PDF Conversion")
                    .setFontSize(20f)
                    .setBold()
            )
            
            document.add(
                com.itextpdf.layout.element.Paragraph("Conversion completed with some formatting issues.")
                    .setFontSize(12f)
            )
            
            document.add(
                com.itextpdf.layout.element.Paragraph("Error: ${e.message}")
                    .setFontSize(10f)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.RED)
            )
            
            document.close()
        }
    }
} 