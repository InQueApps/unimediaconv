package ab.inque.converter

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import com.itextpdf.kernel.geom.Rectangle
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumbering
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import java.math.BigInteger

class PdfConverter {
    
    data class PdfTextElement(
        val text: String,
        val fontSize: Float,
        val fontName: String,
        val isBold: Boolean,
        val isItalic: Boolean,
        val yPosition: Float,
        val xPosition: Float,
        val width: Float,
        val alignment: String
    )
    
    data class PdfPage(
        val pageNumber: Int,
        val textElements: List<PdfTextElement>,
        val paragraphs: List<PdfParagraph>,
        val headings: List<PdfHeading>
    )
    
    data class PdfParagraph(
        val text: String,
        val fontSize: Float,
        val fontName: String,
        val isBold: Boolean,
        val isItalic: Boolean,
        val alignment: String,
        val yPosition: Float
    )
    
    data class PdfHeading(
        val text: String,
        val fontSize: Float,
        val fontName: String,
        val level: Int,
        val yPosition: Float
    )
    
    data class EpubChapter(
        val id: String,
        val title: String,
        val content: String,
        val order: Int
    )
    
    fun convertPdfToEpub(pdfFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "pdf_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            // Extract text and structure from PDF with formatting
            progressCallback(0.2f) // Starting PDF extraction
            val pdfPages = extractPdfContentWithFormatting(pdfFile)
            progressCallback(0.5f) // PDF extraction complete
            
            // Create EPUB structure with enhanced formatting
            progressCallback(0.6f) // Starting EPUB creation
            val originalName = pdfFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.epub")
            createEnhancedEpub(pdfPages, outputFile, pdfFile.name, originalName)
            progressCallback(0.95f) // EPUB creation complete
            
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
    
    private fun extractPdfContentWithFormatting(pdfFile: File): List<PdfPage> {
        val pages = mutableListOf<PdfPage>()
        
        try {
            val reader = PdfReader(FileInputStream(pdfFile))
            val pdfDocument = PdfDocument(reader)
            
            for (i in 1..pdfDocument.numberOfPages) {
                val page = pdfDocument.getPage(i)
                val pageSize = page.pageSize
                
                // Extract text with positioning information
                val textElements = extractTextElements(page, pageSize)
                
                // Process text elements into paragraphs and headings
                val paragraphs = extractParagraphsWithFormatting(textElements)
                val headings = extractHeadingsWithFormatting(textElements)
                
                pages.add(PdfPage(i, textElements, paragraphs, headings))
            }
            
            pdfDocument.close()
            reader.close()
            
        } catch (e: Exception) {
            // Fallback: create a simple page with error message
            val errorElement = PdfTextElement("Error extracting text from PDF: ${e.message}", 12f, "Arial", false, false, 0f, 0f, 0f, "left")
            val errorParagraph = PdfParagraph("Error: ${e.message}", 12f, "Arial", false, false, "left", 0f)
            pages.add(PdfPage(1, listOf(errorElement), listOf(errorParagraph), emptyList()))
        }
        
        return pages
    }
    
    private fun extractTextElements(page: com.itextpdf.kernel.pdf.PdfPage, pageSize: Rectangle): List<PdfTextElement> {
        val textElements = mutableListOf<PdfTextElement>()
        
        try {
            // Use a custom strategy to extract text with positioning
            val strategy = object : LocationTextExtractionStrategy() {
                override fun getResultantText(): String {
                    // Override to get more detailed information
                    return super.getResultantText()
                }
            }
            
            val text = PdfTextExtractor.getTextFromPage(page, strategy)
            
            // Parse the text and create text elements with estimated formatting
            val lines = text.split("\n")
            var currentY = pageSize.height - 50f // Start from top with margin
            
            lines.forEach { line ->
                if (line.trim().isNotEmpty()) {
                    // Estimate formatting based on text characteristics
                    val fontSize = estimateFontSize(line)
                    val fontName = estimateFontName(line)
                    val isBold = estimateBold(line)
                    val isItalic = estimateItalic(line)
                    val alignment = estimateAlignment(line, pageSize.width)
                    
                    textElements.add(PdfTextElement(
                        line.trim(),
                        fontSize,
                        fontName,
                        isBold,
                        isItalic,
                        currentY,
                        50f, // Left margin
                        pageSize.width - 100f, // Width minus margins
                        alignment
                    ))
                    
                    currentY -= fontSize * 1.5f // Move down by font size plus spacing
                } else {
                    currentY -= 20f // Extra spacing for empty lines
                }
            }
            
        } catch (e: Exception) {
            // Fallback to simple text extraction
            val simpleText = PdfTextExtractor.getTextFromPage(page)
            val lines = simpleText.split("\n")
            var currentY = pageSize.height - 50f
            
            lines.forEach { line ->
                if (line.trim().isNotEmpty()) {
                    textElements.add(PdfTextElement(
                        line.trim(),
                        12f,
                        "Arial",
                        false,
                        false,
                        currentY,
                        50f,
                        pageSize.width - 100f,
                        "left"
                    ))
                    currentY -= 20f
                }
            }
        }
        
        return textElements.sortedByDescending { it.yPosition } // Sort by Y position (top to bottom)
    }
    
    private fun estimateFontSize(text: String): Float {
        return when {
            text.length < 15 && text.all { it.isUpperCase() } -> 20f // Main title
            text.length < 25 && text.all { it.isUpperCase() } -> 18f // Section title
            text.matches(Regex("^[A-Z][A-Za-z\\s]+$")) && text.length < 40 -> 16f // Heading
            text.matches(Regex("^\\d+\\.\\s+[A-Z]")) -> 14f // Numbered section
            text.matches(Regex("^[IVX]+\\.")) -> 14f // Roman numeral section
            text.matches(Regex("^[a-z]+\\.\\s+[A-Z]")) -> 13f // Lowercase numbered section
            text.length < 30 && text.any { it.isUpperCase() } -> 13f // Short emphasized text
            else -> 12f // Regular text
        }
    }
    
    private fun estimateFontName(text: String): String {
        return when {
            text.all { it.isUpperCase() } -> "Arial-Bold"
            text.matches(Regex("^[A-Z][A-Za-z\\s]+$")) -> "Times-Bold"
            else -> "Times-Roman"
        }
    }
    
    private fun estimateBold(text: String): Boolean {
        return text.all { it.isUpperCase() } ||
               text.matches(Regex("^[A-Z][A-Za-z\\s]+$")) ||
               text.matches(Regex("^\\d+\\.\\s+[A-Z]")) ||
               text.matches(Regex("^[IVX]+\\.")) ||
               text.matches(Regex("^[a-z]+\\.\\s+[A-Z]")) ||
               text.length < 30 && text.any { it.isUpperCase() } ||
               text.contains("**") || text.contains("__") // Markdown-style emphasis
    }
    
    private fun estimateItalic(text: String): Boolean {
        return text.contains("emphasis") || 
               text.contains("important") || 
               text.contains("note") ||
               text.contains("example") ||
               text.contains("*") && text.length < 40 ||
               text.matches(Regex("^[a-z]+\\.")) // Lowercase numbered items
    }
    
    private fun estimateAlignment(text: String, pageWidth: Float): String {
        return when {
            text.length < 20 && text.all { it.isUpperCase() } -> "center" // Short titles
            text.length < 30 && text.any { it.isUpperCase() } -> "center" // Short headings
            text.length > 100 -> "justify" // Long paragraphs
            text.matches(Regex("^\\s+.*")) -> "right" // Indented text
            text.endsWith(".") && text.length > 50 -> "justify" // Long sentences
            else -> "left"
        }
    }
    
    private fun extractParagraphsWithFormatting(textElements: List<PdfTextElement>): List<PdfParagraph> {
        val paragraphs = mutableListOf<PdfParagraph>()
        var currentParagraph = StringBuilder()
        var currentFontSize = 12f
        var currentFontName = "Times-Roman"
        var currentBold = false
        var currentItalic = false
        var currentAlignment = "left"
        var currentY = 0f
        
        textElements.forEach { element ->
            // Check if this should start a new paragraph
            val shouldStartNewParagraph = element.fontSize > currentFontSize + 2 ||
                                        element.isBold && !currentBold ||
                                        element.yPosition < currentY - 30f || // Significant vertical gap
                                        isListStart(element.text) || // List item
                                        isNewSection(element.text) // New section indicator
            
            if (shouldStartNewParagraph && currentParagraph.isNotEmpty()) {
                // Save current paragraph
                paragraphs.add(PdfParagraph(
                    currentParagraph.toString().trim(),
                    currentFontSize,
                    currentFontName,
                    currentBold,
                    currentItalic,
                    currentAlignment,
                    currentY
                ))
                currentParagraph.clear()
            }
            
            // Add text to current paragraph
            if (currentParagraph.isNotEmpty() && !isListStart(element.text)) {
                currentParagraph.append(" ")
            }
            currentParagraph.append(element.text)
            
            // Update current formatting
            currentFontSize = element.fontSize
            currentFontName = element.fontName
            currentBold = element.isBold
            currentItalic = element.isItalic
            currentAlignment = element.alignment
            currentY = element.yPosition
        }
        
        // Add the last paragraph
        if (currentParagraph.isNotEmpty()) {
            paragraphs.add(PdfParagraph(
                currentParagraph.toString().trim(),
                currentFontSize,
                currentFontName,
                currentBold,
                currentItalic,
                currentAlignment,
                currentY
            ))
        }
        
        return paragraphs
    }
    
    private fun isListStart(text: String): Boolean {
        return text.matches(Regex("^\\d+\\.\\s+")) ||
               text.matches(Regex("^[a-z]+\\.\\s+")) ||
               text.matches(Regex("^[IVX]+\\.\\s+")) ||
               text.matches(Regex("^[-•*]\\s+")) ||
               text.matches(Regex("^[A-Z]\\.\\s+"))
    }
    
    private fun isNewSection(text: String): Boolean {
        return text.matches(Regex("^Chapter\\s+\\d+", RegexOption.IGNORE_CASE)) ||
               text.matches(Regex("^Section\\s+\\d+", RegexOption.IGNORE_CASE)) ||
               text.matches(Regex("^Part\\s+\\d+", RegexOption.IGNORE_CASE)) ||
               text.matches(Regex("^\\d+\\.\\s+[A-Z][A-Za-z\\s]+$"))
    }
    
    private fun extractHeadingsWithFormatting(textElements: List<PdfTextElement>): List<PdfHeading> {
        return textElements
            .filter { element ->
                // Identify headings based on formatting characteristics
                element.fontSize >= 14f ||
                element.isBold ||
                element.text.length < 50 && element.text.all { it.isUpperCase() } ||
                element.text.matches(Regex("^Chapter\\s+\\d+", RegexOption.IGNORE_CASE)) ||
                element.text.matches(Regex("^\\d+\\.\\s+[A-Z]")) ||
                element.text.matches(Regex("^[IVX]+\\."))
            }
            .map { element ->
                val level = when {
                    element.fontSize >= 18f -> 1
                    element.fontSize >= 16f -> 2
                    element.fontSize >= 14f -> 3
                    else -> 4
                }
                PdfHeading(element.text, element.fontSize, element.fontName, level, element.yPosition)
            }
            .sortedByDescending { it.yPosition }
    }
    
    private fun cleanText(text: String): String {
        return text.replace(Regex("\\s+"), " ")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    private fun createEnhancedEpub(pdfPages: List<PdfPage>, outputFile: File, originalFileName: String, title: String) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            
            // Create META-INF/container.xml
            val containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
            """.trimIndent()
            
            addZipEntry(zip, "META-INF/container.xml", containerXml)
            
            // Create chapters from PDF pages
            val chapters = createChapters(pdfPages, title)
            
            // Create OEBPS/content.opf with all chapters
            val contentOpf = createContentOpf(chapters, originalFileName, title)
            addZipEntry(zip, "OEBPS/content.opf", contentOpf)
            
            // Create OEBPS/toc.ncx with navigation
            val tocNcx = createTocNcx(chapters, title)
            addZipEntry(zip, "OEBPS/toc.ncx", tocNcx)
            
            // Create CSS file for enhanced styling
            val cssContent = createEnhancedCss()
            addZipEntry(zip, "OEBPS/style.css", cssContent)
            
            // Create each chapter HTML file
            chapters.forEach { chapter ->
                val chapterHtml = createChapterHtml(chapter, title, originalFileName)
                addZipEntry(zip, "OEBPS/${chapter.id}.html", chapterHtml)
            }
        }
    }
    
    private fun createChapters(pdfPages: List<PdfPage>, title: String): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        
        // Create title page
        chapters.add(EpubChapter(
            "title",
            "Title Page",
            createTitlePageContent(title),
            0
        ))
        
        // Create chapters from PDF pages
        pdfPages.forEachIndexed { index, page ->
            val chapterId = "chapter-${index + 1}"
            val chapterTitle = if (page.headings.isNotEmpty()) {
                page.headings.first().text
            } else {
                "Page ${page.pageNumber}"
            }
            
            val chapterContent = createChapterContent(page, chapterTitle)
            chapters.add(EpubChapter(chapterId, chapterTitle, chapterContent, index + 1))
        }
        
        return chapters
    }
    
    private fun createTitlePageContent(title: String): String {
        return """
            <div class="title-page">
                <h1 class="book-title">$title</h1>
                <div class="conversion-info">
                    <p>Converted from PDF to EPUB</p>
                    <p>Generated by Universal Converter</p>
                    <p>${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy"))}</p>
                </div>
            </div>
        """.trimIndent()
    }
    
    private fun createChapterContent(page: PdfPage, title: String): String {
        val contentBuilder = StringBuilder()
        
        // Add chapter title
        contentBuilder.append("<h1 class=\"chapter-title\">$title</h1>")
        
        // Add paragraphs with preserved formatting
        page.paragraphs.forEach { paragraph ->
            if (paragraph.text.isNotEmpty()) {
                val cssClass = when {
                    paragraph.isBold && paragraph.fontSize > 14f -> "heading"
                    paragraph.isBold -> "bold-text"
                    paragraph.isItalic -> "italic-text"
                    paragraph.fontSize > 12f -> "large-text"
                    paragraph.fontSize < 12f -> "small-text"
                    else -> "normal-text"
                }
                
                val textAlign = when (paragraph.alignment) {
                    "center" -> "text-align: center;"
                    "right" -> "text-align: right;"
                    "justify" -> "text-align: justify;"
                    else -> "text-align: left;"
                }
                
                contentBuilder.append("""
                    <p class="$cssClass" style="font-size: ${paragraph.fontSize}pt; font-family: ${paragraph.fontName}; $textAlign">
                        ${cleanText(paragraph.text)}
                    </p>
                """.trimIndent())
            }
        }
        
        return contentBuilder.toString()
    }
    
    private fun createContentOpf(chapters: List<EpubChapter>, originalFileName: String, title: String): String {
        val manifestBuilder = StringBuilder()
        val spineBuilder = StringBuilder()
        
        // Add CSS to manifest
        manifestBuilder.append("""
            <item id="style" href="style.css" media-type="text/css"/>
        """.trimIndent())
        
        // Add chapters to manifest and spine
        chapters.forEach { chapter ->
            manifestBuilder.append("""
                <item id="${chapter.id}" href="${chapter.id}.html" media-type="application/xhtml+xml"/>
            """.trimIndent())
            
            spineBuilder.append("""
                <itemref idref="${chapter.id}"/>
            """.trimIndent())
        }
        
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                    <dc:creator>Universal Converter</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:identifier>id-${System.currentTimeMillis()}</dc:identifier>
                    <dc:source>$originalFileName</dc:source>
                    <dc:date>${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))}</dc:date>
                </metadata>
                <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    $manifestBuilder
                </manifest>
                <spine>
                    $spineBuilder
                </spine>
            </package>
        """.trimIndent()
    }
    
    private fun createTocNcx(chapters: List<EpubChapter>, title: String): String {
        val navPointsBuilder = StringBuilder()
        
        chapters.forEachIndexed { index, chapter ->
            navPointsBuilder.append("""
                <navPoint id="nav-${index + 1}" playOrder="${index + 1}">
                    <navLabel>
                        <text>${chapter.title}</text>
                    </navLabel>
                    <content src="${chapter.id}.html"/>
                </navPoint>
            """.trimIndent())
        }
        
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                <head>
                    <meta name="dtb:uid" content="id-${System.currentTimeMillis()}"/>
                    <meta name="dtb:depth" content="2"/>
                    <meta name="dtb:totalPageCount" content="0"/>
                    <meta name="dtb:maxPageNumber" content="0"/>
                </head>
                <docTitle>
                    <text>$title</text>
                </docTitle>
                <navMap>
                    $navPointsBuilder
                </navMap>
            </ncx>
        """.trimIndent()
    }
    
    private fun createEnhancedCss(): String {
        return """
            /* Enhanced PDF to EPUB CSS with Formatting Preservation */
            @page {
                margin: 1in;
            }
            
            body { 
                font-family: 'Times New Roman', serif; 
                margin: 2em; 
                line-height: 1.6;
                color: #333;
                font-size: 12pt;
            }
            
            .title-page {
                text-align: center;
                margin: 4em 0;
                page-break-after: always;
            }
            
            .book-title {
                font-size: 2.5em;
                font-weight: bold;
                color: #2c3e50;
                margin-bottom: 2em;
                text-align: center;
            }
            
            .conversion-info {
                background-color: #f8f9fa;
                padding: 2em;
                border-radius: 8px;
                margin: 2em 0;
                border-left: 4px solid #3498db;
                text-align: left;
            }
            
            .conversion-info p {
                margin: 0.5em 0;
                color: #666;
            }
            
            .chapter-title {
                font-size: 1.8em;
                font-weight: bold;
                color: #2c3e50;
                margin: 2em 0 1em 0;
                page-break-before: always;
                border-bottom: 2px solid #3498db;
                padding-bottom: 0.5em;
            }
            
            /* Preserved formatting classes */
            .heading {
                font-weight: bold;
                font-size: 1.4em;
                color: #34495e;
                margin: 1.5em 0 0.8em 0;
                page-break-after: avoid;
            }
            
            .bold-text {
                font-weight: bold;
                margin-bottom: 1em;
            }
            
            .italic-text {
                font-style: italic;
                margin-bottom: 1em;
            }
            
            .large-text {
                font-size: 1.2em;
                margin-bottom: 1em;
            }
            
            .small-text {
                font-size: 0.9em;
                margin-bottom: 1em;
            }
            
            .normal-text {
                margin-bottom: 1em;
                text-indent: 1.5em;
            }
            
            /* Typography enhancements */
            h1, h2, h3, h4, h5, h6 {
                page-break-after: avoid;
                page-break-inside: avoid;
            }
            
            p {
                page-break-inside: avoid;
                orphans: 2;
                widows: 2;
            }
            
            /* List styling */
            ul, ol {
                margin: 1em 0;
                padding-left: 2em;
            }
            
            li {
                margin-bottom: 0.5em;
            }
            
            /* Blockquote styling */
            blockquote {
                margin: 1em 0;
                padding: 1em;
                border-left: 4px solid #3498db;
                background-color: #f8f9fa;
                font-style: italic;
            }
            
            /* Code styling */
            code {
                background-color: #f4f4f4;
                padding: 2px 4px;
                border-radius: 3px;
                font-family: 'Courier New', monospace;
                font-size: 0.9em;
            }
            
            pre {
                background-color: #f4f4f4;
                padding: 1em;
                border-radius: 5px;
                overflow-x: auto;
                page-break-inside: avoid;
                font-family: 'Courier New', monospace;
            }
            
            /* Link styling */
            a {
                color: #3498db;
                text-decoration: none;
            }
            
            a:hover {
                text-decoration: underline;
            }
            
            /* Table styling */
            table {
                width: 100%;
                border-collapse: collapse;
                margin: 1em 0;
                page-break-inside: avoid;
            }
            
            th, td {
                border: 1px solid #ddd;
                padding: 8px;
                text-align: left;
            }
            
            th {
                background-color: #f2f2f2;
                font-weight: bold;
            }
            
            /* Print-specific styles */
            @media print {
                body {
                    font-size: 11pt;
                }
                
                .chapter-title {
                    font-size: 1.6em;
                }
                
                .heading {
                    font-size: 1.2em;
                }
            }
        """.trimIndent()
    }
    
    private fun createChapterHtml(chapter: EpubChapter, title: String, originalFileName: String): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <title>${chapter.title}</title>
                <meta charset="utf-8"/>
                <link rel="stylesheet" type="text/css" href="style.css"/>
            </head>
            <body>
                ${chapter.content}
            </body>
            </html>
        """.trimIndent()
    }
    
    private fun addZipEntry(zip: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    fun convertPdfToDocx(pdfFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "pdf_docx_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            progressCallback(0.2f) // Starting PDF extraction
            val pdfPages = extractPdfContentWithFormatting(pdfFile)
            progressCallback(0.5f) // PDF extraction complete
            
            progressCallback(0.6f) // Starting DOCX creation
            val originalName = pdfFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.docx")
            createDocxFromPdfPages(pdfPages, outputFile)
            progressCallback(0.95f) // DOCX creation complete
            
            return outputFile
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun convertPdfToRtf(pdfFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "pdf_rtf_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            progressCallback(0.2f) // Starting PDF extraction
            val pdfPages = extractPdfContentWithFormatting(pdfFile)
            progressCallback(0.5f) // PDF extraction complete
            
            progressCallback(0.6f) // Starting RTF creation
            val originalName = pdfFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.rtf")
            createRtfFromPdfPages(pdfPages, outputFile)
            progressCallback(0.95f) // RTF creation complete
            
            return outputFile
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun convertPdfToText(pdfFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "pdf_txt_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            progressCallback(0.2f) // Starting PDF extraction
            val pdfPages = extractPdfContentWithFormatting(pdfFile)
            progressCallback(0.5f) // PDF extraction complete
            
            progressCallback(0.6f) // Starting text creation
            val originalName = pdfFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.txt")
            createTextFromPdfPages(pdfPages, outputFile)
            progressCallback(0.95f) // Text creation complete
            
            return outputFile
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createDocxFromPdfPages(pdfPages: List<PdfPage>, outputFile: File) {
        val docx = org.apache.poi.xwpf.usermodel.XWPFDocument()
        // Create bullet list style
        val cTAbstractNum = CTAbstractNum.Factory.newInstance()
        cTAbstractNum.abstractNumId = BigInteger.ZERO
        val lvl = cTAbstractNum.addNewLvl()
        lvl.ilvl = BigInteger.ZERO
        lvl.addNewNumFmt().setVal(STNumberFormat.BULLET)
        lvl.addNewLvlText().setVal("•")
        lvl.addNewLvlJc().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc.LEFT)
        lvl.addNewPPr().addNewInd().left = BigInteger.valueOf(720)
        val abstractNum = XWPFAbstractNum(cTAbstractNum)
        val numbering = docx.createNumbering()
        val abstractNumId = numbering.addAbstractNum(abstractNum)
        val bulletNumId = numbering.addNum(abstractNumId)
        
        for (page in pdfPages) {
            if (page.pageNumber > 1) {
                val pageBreakPara = docx.createParagraph()
                pageBreakPara.setPageBreak(true)
            }
            for (heading in page.headings) {
                val para = docx.createParagraph()
                para.alignment = when (heading.fontName.lowercase()) {
                    "arial-bold", "times-bold" -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                    else -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT
                }
                val run = para.createRun()
                run.setText(heading.text)
                run.setBold(true)
                run.setFontSize(when (heading.level) {
                    1 -> 24
                    2 -> 20
                    3 -> 18
                    4 -> 16
                    else -> 14
                })
                when {
                    heading.fontName.contains("Arial", ignoreCase = true) -> run.setFontFamily("Arial")
                    heading.fontName.contains("Times", ignoreCase = true) -> run.setFontFamily("Times New Roman")
                    heading.fontName.contains("Calibri", ignoreCase = true) -> run.setFontFamily("Calibri")
                    else -> run.setFontFamily("Times New Roman")
                }
                para.spacingAfter = 240
            }
            for (paraData in page.paragraphs) {
                val isList = isListStart(paraData.text)
                val para = docx.createParagraph()
                para.alignment = when (paraData.alignment.lowercase()) {
                    "center" -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                    "right" -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT
                    "justify" -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH
                    else -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT
                }
                if (paraData.fontSize <= 12f) {
                    para.indentationFirstLine = 720
                }
                para.spacingLineRule = org.apache.poi.xwpf.usermodel.LineSpacingRule.AUTO
                para.spacingBetween = 1.15
                para.spacingAfter = 120
                if (isList) {
                    para.numID = bulletNumId
                }
                val inlineRuns = splitInlineStyles(paraData.text)
                for (inline in inlineRuns) {
                    val run = para.createRun()
                    run.setText(inline.text)
                    run.setFontSize(paraData.fontSize.toInt())
                    run.setBold(paraData.isBold || inline.bold)
                    run.setItalic(paraData.isItalic || inline.italic)
                    when {
                        paraData.fontName.contains("Arial", ignoreCase = true) -> run.setFontFamily("Arial")
                        paraData.fontName.contains("Times", ignoreCase = true) -> run.setFontFamily("Times New Roman")
                        paraData.fontName.contains("Calibri", ignoreCase = true) -> run.setFontFamily("Calibri")
                        paraData.fontName.contains("Helvetica", ignoreCase = true) -> run.setFontFamily("Arial")
                        else -> run.setFontFamily("Times New Roman")
                    }
                }
            }
        }
        FileOutputStream(outputFile).use { docx.write(it) }
        docx.close()
    }

    data class InlineRun(val text: String, val bold: Boolean = false, val italic: Boolean = false)
    private fun splitInlineStyles(text: String): List<InlineRun> {
        // Very basic: **bold**, *italic*, __bold__, _italic_
        val runs = mutableListOf<InlineRun>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        runs.add(InlineRun(text.substring(i + 2, end), bold = true))
                        i = end + 2
                    } else {
                        runs.add(InlineRun(text.substring(i)))
                        break
                    }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end > i) {
                        runs.add(InlineRun(text.substring(i + 2, end), bold = true))
                        i = end + 2
                    } else {
                        runs.add(InlineRun(text.substring(i)))
                        break
                    }
                }
                text.startsWith("*", i) -> {
                    val end = text.indexOf("*", i + 1)
                    if (end > i) {
                        runs.add(InlineRun(text.substring(i + 1, end), italic = true))
                        i = end + 1
                    } else {
                        runs.add(InlineRun(text.substring(i)))
                        break
                    }
                }
                text.startsWith("_", i) -> {
                    val end = text.indexOf("_", i + 1)
                    if (end > i) {
                        runs.add(InlineRun(text.substring(i + 1, end), italic = true))
                        i = end + 1
                    } else {
                        runs.add(InlineRun(text.substring(i)))
                        break
                    }
                }
                else -> {
                    val next = listOf(
                        text.indexOf("**", i).takeIf { it >= 0 },
                        text.indexOf("__", i).takeIf { it >= 0 },
                        text.indexOf("*", i).takeIf { it >= 0 },
                        text.indexOf("_", i).takeIf { it >= 0 }
                    ).filterNotNull().minOrNull() ?: text.length
                    runs.add(InlineRun(text.substring(i, next)))
                    i = next
                }
            }
        }
        return runs.filter { it.text.isNotEmpty() }
    }

    private fun createRtfFromPdfPages(pdfPages: List<PdfPage>, outputFile: File) {
        val rtfBuilder = StringBuilder()
        rtfBuilder.append("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\n")
        rtfBuilder.append("\\f0\\fs24\n")
        for (page in pdfPages) {
            for (heading in page.headings) {
                rtfBuilder.append("{\\b\\fs${when (heading.level) {1->36;2->32;3->28;else->24}} ${escapeRtfText(heading.text)}}\\par\n")
            }
            for (para in page.paragraphs) {
                val style = when {
                    para.isBold && para.fontSize > 14f -> "{\\b\\fs36 "
                    para.isBold -> "{\\b "
                    para.isItalic -> "{\\i "
                    para.fontSize > 12f -> "{\\fs28 "
                    para.fontSize < 12f -> "{\\fs20 "
                    else -> ""
                }
                val close = if (style.isNotEmpty()) "}" else ""
                rtfBuilder.append("$style${escapeRtfText(para.text)}$close\\par\n")
            }
            rtfBuilder.append("\\page\n")
        }
        rtfBuilder.append("}")
        outputFile.writeText(rtfBuilder.toString())
    }

    private fun createTextFromPdfPages(pdfPages: List<PdfPage>, outputFile: File) {
        val textBuilder = StringBuilder()
        for (page in pdfPages) {
            for (heading in page.headings) {
                textBuilder.append("\n\n${heading.text.uppercase()}\n")
                textBuilder.append("-".repeat(heading.text.length) + "\n\n")
            }
            for (para in page.paragraphs) {
                textBuilder.append(para.text)
                textBuilder.append("\n\n")
            }
            textBuilder.append("\n" + "=".repeat(50) + "\n\n")
        }
        outputFile.writeText(textBuilder.toString())
    }

    private fun escapeRtfText(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '{' -> sb.append("\\{")
                '}' -> sb.append("\\}")
                '\n' -> sb.append("\\par ")
                '\r' -> {} // skip
                in '\u0000'..'\u007f' -> sb.append(c) // ASCII
                else -> sb.append("\\u").append(c.code).append("?")
            }
        }
        return sb.toString()
    }
} 