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
    
    fun convertEpubToPdf(epubFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "epub_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            // Extract EPUB content
            progressCallback(0.2f) // Starting extraction
            val epubContent = extractEpubContent(epubFile, tempDir)
            progressCallback(0.4f) // Extraction complete
            
            // Create combined HTML
            progressCallback(0.5f) // Starting HTML creation
            val combinedHtml = createCombinedHtml(epubContent, tempDir)
            progressCallback(0.7f) // HTML creation complete
            
            // Convert HTML to PDF
            progressCallback(0.8f) // Starting PDF conversion
            val originalName = epubFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.pdf")
            convertHtmlToPdf(combinedHtml, outputFile, tempDir)
            progressCallback(0.95f) // PDF conversion complete
            
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
    
    fun convertEpubToWord(epubFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        android.util.Log.d("EpubConverter", "Starting EPUB to Word conversion for: ${epubFile.absolutePath}")
        
        val tempDir = File(context.cacheDir, "epub_word_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        android.util.Log.d("EpubConverter", "Created temp directory: ${tempDir.absolutePath}")
        
        try {
            // Extract EPUB content
            progressCallback(0.2f) // Starting extraction
            android.util.Log.d("EpubConverter", "Extracting EPUB content...")
            val epubContent = extractEpubContent(epubFile, tempDir)
            progressCallback(0.4f) // Extraction complete
            android.util.Log.d("EpubConverter", "Extracted ${epubContent.htmlFiles.size} HTML files, ${epubContent.cssFiles.size} CSS files, ${epubContent.images.size} images")
            
            // Create combined HTML
            progressCallback(0.5f) // Starting HTML creation
            android.util.Log.d("EpubConverter", "Creating combined HTML...")
            val combinedHtml = createCombinedHtml(epubContent, tempDir)
            progressCallback(0.7f) // HTML creation complete
            android.util.Log.d("EpubConverter", "Combined HTML created: ${combinedHtml.absolutePath}")
            
            // Convert HTML to Word document
            progressCallback(0.8f) // Starting Word conversion
            val originalName = epubFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.docx")
            android.util.Log.d("EpubConverter", "Output file will be: ${outputFile.absolutePath}")
            
            convertHtmlToWord(combinedHtml, outputFile, tempDir)
            progressCallback(0.95f) // Word conversion complete
            
            android.util.Log.d("EpubConverter", "Word conversion completed. File size: ${outputFile.length()} bytes")
            return outputFile
            
        } catch (e: Exception) {
            android.util.Log.e("EpubConverter", "Error in EPUB to Word conversion", e)
            // Clean up on error
            tempDir.deleteRecursively()
            throw e
        } finally {
            // Clean up temp directory
            try {
                tempDir.deleteRecursively()
                android.util.Log.d("EpubConverter", "Cleaned up temp directory")
            } catch (e: Exception) {
                android.util.Log.w("EpubConverter", "Error cleaning up temp directory", e)
                // Ignore cleanup errors
            }
        }
    }
    
    fun convertEpubToText(epubFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "epub_text_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            // Extract EPUB content
            progressCallback(0.3f) // Starting extraction
            val epubContent = extractEpubContent(epubFile, tempDir)
            progressCallback(0.6f) // Extraction complete
            
            // Convert to plain text
            progressCallback(0.7f) // Starting text conversion
            val originalName = epubFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.txt")
            convertToText(epubContent, outputFile)
            progressCallback(0.95f) // Text conversion complete
            
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
    
    fun convertEpubToRtf(epubFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "epub_rtf_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            // Extract EPUB content
            progressCallback(0.3f) // Starting extraction
            val epubContent = extractEpubContent(epubFile, tempDir)
            progressCallback(0.6f) // Extraction complete
            
            // Convert to RTF
            progressCallback(0.7f) // Starting RTF conversion
            val originalName = epubFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.rtf")
            convertToRtf(epubContent, outputFile)
            progressCallback(0.95f) // RTF conversion complete
            
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
    
    private fun convertHtmlToWord(htmlFile: File, outputFile: File, baseDir: File) {
        try {
            android.util.Log.d("EpubConverter", "Starting EPUB to Word conversion")
            android.util.Log.d("EpubConverter", "HTML file: ${htmlFile.absolutePath}")
            android.util.Log.d("EpubConverter", "Base directory: ${baseDir.absolutePath}")
            
            // Read HTML content
            val htmlContent = htmlFile.readText()
            android.util.Log.d("EpubConverter", "HTML content length: ${htmlContent.length}")
            
            val doc = Jsoup.parse(htmlContent)
            
            // Create Word document using Apache POI
            val workbook = org.apache.poi.xwpf.usermodel.XWPFDocument()
            android.util.Log.d("EpubConverter", "Created Word document")
            
            // Process body content
            val body = doc.body()
            if (body != null) {
                android.util.Log.d("EpubConverter", "Processing body content")
                processHtmlElements(body.children(), workbook, baseDir)
            } else {
                android.util.Log.d("EpubConverter", "No body found, processing entire document")
                processHtmlElements(doc.children(), workbook, baseDir)
            }
            
            android.util.Log.d("EpubConverter", "Writing Word document to: ${outputFile.absolutePath}")
            // Write to file
            FileOutputStream(outputFile).use { out ->
                workbook.write(out)
            }
            workbook.close()
            
            android.util.Log.d("EpubConverter", "Word conversion completed successfully")
            
        } catch (e: Exception) {
            android.util.Log.e("EpubConverter", "Error in EPUB to Word conversion", e)
            
            // Fallback: create a simple Word document with error message
            val workbook = org.apache.poi.xwpf.usermodel.XWPFDocument()
            val paragraph = workbook.createParagraph()
            val run = paragraph.createRun()
            run.setText("EPUB to Word Conversion")
            run.setBold(true)
            run.setFontSize(20)
            
            val errorParagraph = workbook.createParagraph()
            val errorRun = errorParagraph.createRun()
            errorRun.setText("Conversion completed with some formatting issues.")
            errorRun.setFontSize(12)
            
            val detailsParagraph = workbook.createParagraph()
            val detailsRun = detailsParagraph.createRun()
            detailsRun.setText("Error: ${e.message}")
            detailsRun.setFontSize(10)
            detailsRun.setColor("FF0000")
            
            FileOutputStream(outputFile).use { out ->
                workbook.write(out)
            }
            workbook.close()
        }
    }
    
    private fun processHtmlElements(elements: org.jsoup.select.Elements, workbook: org.apache.poi.xwpf.usermodel.XWPFDocument, baseDir: File) {
        android.util.Log.d("EpubConverter", "Processing ${elements.size} HTML elements")
        
        for (element in elements) {
            val tagName = element.tagName().lowercase()
            android.util.Log.d("EpubConverter", "Processing element: $tagName")
            
            when (tagName) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val paragraph = workbook.createParagraph()
                    val run = paragraph.createRun()
                    val text = element.text()
                    run.setText(text)
                    run.setBold(true)
                    run.setFontSize(when (element.tagName()) {
                        "h1" -> 24
                        "h2" -> 20
                        "h3" -> 18
                        "h4" -> 16
                        "h5" -> 14
                        else -> 12
                    })
                    android.util.Log.d("EpubConverter", "Added heading: $text")
                }
                "p" -> {
                    val paragraph = workbook.createParagraph()
                    val run = paragraph.createRun()
                    val text = element.text()
                    run.setText(text)
                    run.setFontSize(12)
                    android.util.Log.d("EpubConverter", "Added paragraph: ${text.take(50)}...")
                }
                "img" -> {
                    val src = element.attr("src")
                    android.util.Log.d("EpubConverter", "Processing image: $src")
                    
                    if (src.isNotEmpty()) {
                        try {
                            val imageFile = File(baseDir, src)
                            android.util.Log.d("EpubConverter", "Image file path: ${imageFile.absolutePath}")
                            android.util.Log.d("EpubConverter", "Image file exists: ${imageFile.exists()}")
                            
                            if (imageFile.exists()) {
                                val paragraph = workbook.createParagraph()
                                val run = paragraph.createRun()
                                
                                // Determine image type based on file extension
                                val imageType = when (imageFile.extension.lowercase()) {
                                    "jpg", "jpeg" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG
                                    "png" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG
                                    "gif" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF
                                    "bmp" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_BMP
                                    else -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG // Default
                                }
                                
                                run.addPicture(
                                    FileInputStream(imageFile),
                                    imageType,
                                    imageFile.name,
                                    400, 300
                                )
                                android.util.Log.d("EpubConverter", "Added image: ${imageFile.name}")
                            } else {
                                android.util.Log.w("EpubConverter", "Image file not found: ${imageFile.absolutePath}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("EpubConverter", "Error adding image: $src", e)
                            // Continue without image if there's an error
                        }
                    }
                }
                "div", "section", "article" -> {
                    // Recursively process child elements
                    android.util.Log.d("EpubConverter", "Processing container element: $tagName")
                    processHtmlElements(element.children(), workbook, baseDir)
                }
                "br" -> {
                    // Add line break
                    val paragraph = workbook.createParagraph()
                    android.util.Log.d("EpubConverter", "Added line break")
                }
                else -> {
                    // For other elements, just add the text
                    val text = element.text()
                    if (text.isNotEmpty()) {
                        val paragraph = workbook.createParagraph()
                        val run = paragraph.createRun()
                        run.setText(text)
                        run.setFontSize(12)
                        android.util.Log.d("EpubConverter", "Added text from $tagName: ${text.take(50)}...")
                    }
                }
            }
        }
    }
    
    private fun convertToText(epubContent: EpubContent, outputFile: File) {
        val textBuilder = StringBuilder()
        
        epubContent.htmlFiles.forEach { htmlFile ->
            val doc = Jsoup.parse(htmlFile.content)
            val body = doc.body()
            
            if (body != null) {
                // Extract text content, preserving some structure
                extractTextFromElement(body, textBuilder)
            } else {
                // If no body tag, extract from the whole document
                extractTextFromElement(doc, textBuilder)
            }
            
            // Add separator between chapters
            textBuilder.append("\n\n" + "=".repeat(50) + "\n\n")
        }
        
        outputFile.writeText(textBuilder.toString())
    }
    
    private fun extractTextFromElement(element: org.jsoup.nodes.Element, textBuilder: StringBuilder) {
        for (child in element.children()) {
            when (child.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    textBuilder.append("\n\n${child.text().uppercase()}\n")
                    textBuilder.append("-".repeat(child.text().length) + "\n\n")
                }
                "p" -> {
                    textBuilder.append(child.text())
                    textBuilder.append("\n\n")
                }
                "div", "section", "article" -> {
                    extractTextFromElement(child, textBuilder)
                }
                "br" -> {
                    textBuilder.append("\n")
                }
                else -> {
                    if (child.text().isNotEmpty()) {
                        textBuilder.append(child.text())
                        textBuilder.append("\n")
                    }
                }
            }
        }
    }
    
    private fun convertToRtf(epubContent: EpubContent, outputFile: File) {
        val rtfBuilder = StringBuilder()
        
        // RTF header
        rtfBuilder.append("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\n")
        rtfBuilder.append("\\f0\\fs24\n")
        
        epubContent.htmlFiles.forEach { htmlFile ->
            val doc = Jsoup.parse(htmlFile.content)
            val body = doc.body()
            
            if (body != null) {
                convertElementToRtf(body, rtfBuilder)
            } else {
                convertElementToRtf(doc, rtfBuilder)
            }
            
            // Add page break between chapters
            rtfBuilder.append("\\page\n")
        }
        
        rtfBuilder.append("}")
        outputFile.writeText(rtfBuilder.toString())
    }
    
    private fun convertElementToRtf(element: org.jsoup.nodes.Element, rtfBuilder: StringBuilder) {
        for (child in element.children()) {
            when (child.tagName().lowercase()) {
                "h1" -> {
                    rtfBuilder.append("{\\b\\fs36 ${escapeRtfText(child.text())}}\\par\n")
                }
                "h2" -> {
                    rtfBuilder.append("{\\b\\fs32 ${escapeRtfText(child.text())}}\\par\n")
                }
                "h3" -> {
                    rtfBuilder.append("{\\b\\fs28 ${escapeRtfText(child.text())}}\\par\n")
                }
                "h4", "h5", "h6" -> {
                    rtfBuilder.append("{\\b\\fs24 ${escapeRtfText(child.text())}}\\par\n")
                }
                "p" -> {
                    rtfBuilder.append("${escapeRtfText(child.text())}\\par\n")
                }
                "div", "section", "article" -> {
                    convertElementToRtf(child, rtfBuilder)
                }
                "br" -> {
                    rtfBuilder.append("\\par\n")
                }
                else -> {
                    if (child.text().isNotEmpty()) {
                        rtfBuilder.append("${escapeRtfText(child.text())}\\par\n")
                    }
                }
            }
        }
    }
    
    private fun escapeRtfText(text: String): String = RtfUtil.escapeRtfText(text)
} 