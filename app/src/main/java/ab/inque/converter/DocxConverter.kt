package ab.inque.converter
import com.itextpdf.layout.properties.TextAlignment
import android.content.Context
import android.util.Log
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Image
import com.itextpdf.io.image.ImageDataFactory
import org.apache.poi.xwpf.usermodel.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class DocxContent(
    val paragraphs: kotlin.collections.List<DocxParagraph>,
    val images: kotlin.collections.List<DocxImage>,
    val tables: kotlin.collections.List<DocxTable>
)

data class DocxParagraph(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderlined: Boolean = false,
    val alignment: String = "LEFT",
    val fontSize: Int = 12,
    val isHeading: Boolean = false,
    val headingLevel: Int = 0
)

data class DocxImage(
    val data: ByteArray,
    val name: String,
    val mimeType: String
)

data class DocxTable(
    val rows: kotlin.collections.List<kotlin.collections.List<String>>
)

class DocxConverter {
    
    fun convertDocxToPdf(docxFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        try {
            progressCallback(0.2f) // Reading DOCX file
            val docxContent = extractDocxContent(docxFile)
            progressCallback(0.4f) // Content extracted
            
            progressCallback(0.5f) // Creating PDF
            val outputFile = File(context.cacheDir, "${docxFile.nameWithoutExtension}.pdf")
            createPdfFromDocx(docxContent, outputFile)
            progressCallback(0.9f) // PDF created
            
            return outputFile
            
        } catch (e: Exception) {
            Log.e("DocxConverter", "Error converting DOCX to PDF", e)
            throw e
        }
    }
    
    fun convertDocxToRtf(docxFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        try {
            progressCallback(0.2f) // Reading DOCX file
            val docxContent = extractDocxContent(docxFile)
            progressCallback(0.4f) // Content extracted
            
            progressCallback(0.5f) // Creating RTF
            val outputFile = File(context.cacheDir, "${docxFile.nameWithoutExtension}.rtf")
            createRtfFromDocx(docxContent, outputFile)
            progressCallback(0.9f) // RTF created
            
            return outputFile
            
        } catch (e: Exception) {
            Log.e("DocxConverter", "Error converting DOCX to RTF", e)
            throw e
        }
    }
    
    fun convertDocxToText(docxFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        try {
            progressCallback(0.2f) // Reading DOCX file
            val docxContent = extractDocxContent(docxFile)
            progressCallback(0.4f) // Content extracted
            
            progressCallback(0.5f) // Creating text file
            val outputFile = File(context.cacheDir, "${docxFile.nameWithoutExtension}.txt")
            createTextFromDocx(docxContent, outputFile)
            progressCallback(0.9f) // Text file created
            
            return outputFile
            
        } catch (e: Exception) {
            Log.e("DocxConverter", "Error converting DOCX to text", e)
            throw e
        }
    }
    
    fun convertDocxToEpub(docxFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        try {
            progressCallback(0.2f) // Reading DOCX file
            val docxContent = extractDocxContent(docxFile)
            progressCallback(0.4f) // Content extracted
            
            progressCallback(0.5f) // Creating EPUB
            val outputFile = File(context.cacheDir, "${docxFile.nameWithoutExtension}.epub")
            createEpubFromDocx(docxContent, outputFile, docxFile.name)
            progressCallback(0.9f) // EPUB created
            
            return outputFile
            
        } catch (e: Exception) {
            Log.e("DocxConverter", "Error converting DOCX to EPUB", e)
            throw e
        }
    }
    
    private fun extractDocxContent(docxFile: File): DocxContent {
        val paragraphs = mutableListOf<DocxParagraph>()
        val images = mutableListOf<DocxImage>()
        val tables = mutableListOf<DocxTable>()
        
        try {
            val fis = FileInputStream(docxFile)
            val document = XWPFDocument(fis)
            
            // Extract paragraphs
            for (paragraph in document.paragraphs) {
                val text = StringBuilder()
                var isBold = false
                var isItalic = false
                var isUnderlined = false
                var fontSize = 12
                var alignment = "LEFT"
                var isHeading = false
                var headingLevel = 0
                
                // Check if it's a heading
                val style = paragraph.style
                if (style != null && style.contains("Heading")) {
                    isHeading = true
                    headingLevel = style.replace("Heading", "").toIntOrNull() ?: 1
                }
                
                // Get alignment
                when (paragraph.alignment) {
                    ParagraphAlignment.CENTER -> alignment = "CENTER"
                    ParagraphAlignment.RIGHT -> alignment = "RIGHT"
                    ParagraphAlignment.BOTH -> alignment = "JUSTIFIED"
                    else -> alignment = "LEFT"
                }
                
                // Extract runs (text with formatting)
                for (run in paragraph.runs) {
                    val runText = run.getText(0) ?: ""
                    text.append(runText)
                    
                    // Check formatting
                    if (run.isBold) isBold = true
                    if (run.isItalic) isItalic = true
                    if (run.isStrike) isUnderlined = true
                    
                    // Get font size
                    val runFontSize = run.fontSize
                    if (runFontSize != -1) fontSize = runFontSize
                }
                
                if (text.isNotEmpty()) {
                    paragraphs.add(DocxParagraph(
                        text = text.toString(),
                        isBold = isBold,
                        isItalic = isItalic,
                        isUnderlined = isUnderlined,
                        alignment = alignment,
                        fontSize = fontSize,
                        isHeading = isHeading,
                        headingLevel = headingLevel
                    ))
                }
            }
            
            // Extract tables
            for (table in document.tables) {
                val tableRows = mutableListOf<kotlin.collections.List<String>>()
                for (row in table.rows) {
                    val rowCells = mutableListOf<String>()
                    for (cell in row.tableCells) {
                        val cellText = StringBuilder()
                        for (paragraph in cell.paragraphs) {
                            for (run in paragraph.runs) {
                                cellText.append(run.getText(0) ?: "")
                            }
                        }
                        rowCells.add(cellText.toString())
                    }
                    tableRows.add(rowCells)
                }
                if (tableRows.isNotEmpty()) {
                    tables.add(DocxTable(rows = tableRows))
                }
            }
            
            // Extract images from the document
            extractImagesFromDocx(docxFile, images)
            
            fis.close()
            
        } catch (e: Exception) {
            Log.e("DocxConverter", "Error extracting DOCX content", e)
            throw e
        }
        
        return DocxContent(paragraphs, images, tables)
    }
    
    private fun extractImagesFromDocx(docxFile: File, images: MutableList<DocxImage>) {
        try {
            val zipStream = ZipInputStream(FileInputStream(docxFile))
            var entry: ZipEntry?
            
            while (zipStream.nextEntry.also { entry = it } != null) {
                val entryName = entry?.name ?: continue
                
                if (entryName.startsWith("word/media/")) {
                    val imageName = entryName.substringAfter("word/media/")
                    val imageData = zipStream.readBytes()
                    
                    val mimeType = when {
                        imageName.endsWith(".png", ignoreCase = true) -> "image/png"
                        imageName.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
                        imageName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                        imageName.endsWith(".gif", ignoreCase = true) -> "image/gif"
                        imageName.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
                        else -> "image/png"
                    }
                    
                    images.add(DocxImage(data = imageData, name = imageName, mimeType = mimeType))
                }
            }
            zipStream.close()
            
        } catch (e: Exception) {
            Log.w("DocxConverter", "Error extracting images from DOCX", e)
            // Don't throw, just log the warning
        }
    }
    
    private fun createPdfFromDocx(content: DocxContent, outputFile: File) {
        val pdfWriter = PdfWriter(outputFile)
        val pdfDocument = PdfDocument(pdfWriter)
        val document = Document(pdfDocument)
        
        try {
            // Add paragraphs
            for (paragraph in content.paragraphs) {
                val pdfParagraph = Paragraph(paragraph.text)
                
                // Apply formatting
                if (paragraph.isBold) pdfParagraph.setBold()
                if (paragraph.isItalic) pdfParagraph.setItalic()
                
                // Set font size
                pdfParagraph.setFontSize(paragraph.fontSize.toFloat())
                
                // Set alignment
                when (paragraph.alignment) {
                    "CENTER" -> pdfParagraph.setTextAlignment(TextAlignment.CENTER)
                    "RIGHT" -> pdfParagraph.setTextAlignment(TextAlignment.RIGHT)
                    "JUSTIFIED" -> pdfParagraph.setTextAlignment(TextAlignment.JUSTIFIED)
                    else -> pdfParagraph.setTextAlignment(TextAlignment.LEFT)
                }
                
                // Add extra spacing for headings
                if (paragraph.isHeading) {
                    pdfParagraph.setBold()
                    pdfParagraph.setFontSize((paragraph.fontSize + 4).toFloat())
                    document.add(pdfParagraph)
                    document.add(Paragraph("")) // Add space after heading
                } else {
                    document.add(pdfParagraph)
                }
            }
            
            // Add tables
            for (table in content.tables) {
                if (table.rows.isNotEmpty()) {
                    val pdfTable = Table(table.rows[0].size)
                    
                    for (row in table.rows) {
                        for (cellText in row) {
                            val cell = Cell().add(Paragraph(cellText))
                            pdfTable.addCell(cell)
                        }
                    }
                    
                    document.add(pdfTable)
                    document.add(Paragraph("")) // Add space after table
                }
            }
            
            // Add images (if any)
            for (image in content.images) {
                try {
                    val imageData = ImageDataFactory.create(image.data)
                    val pdfImage = Image(imageData)
                    pdfImage.setWidth(400f) // Set reasonable width
                    document.add(pdfImage as com.itextpdf.layout.element.IBlockElement)
                    document.add(Paragraph("")) // Add space after image
                } catch (e: Exception) {
                    Log.w("DocxConverter", "Error adding image to PDF: ${image.name}", e)
                }
            }
            
        } finally {
            document.close()
        }
    }
    
    private fun createRtfFromDocx(content: DocxContent, outputFile: File) {
        val rtfContent = StringBuilder()
        
        // RTF header
        rtfContent.append("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\n")
        rtfContent.append("\\viewkind4\\uc1\\pard\\f0\\fs24\n")
        
        // Add paragraphs
        for (paragraph in content.paragraphs) {
            // Start paragraph
            when (paragraph.alignment) {
                "CENTER" -> rtfContent.append("\\qc ")
                "RIGHT" -> rtfContent.append("\\qr ")
                "JUSTIFIED" -> rtfContent.append("\\qj ")
                else -> rtfContent.append("\\ql ")
            }
            
            // Apply formatting
            if (paragraph.isBold) rtfContent.append("\\b ")
            if (paragraph.isItalic) rtfContent.append("\\i ")
            if (paragraph.isUnderlined) rtfContent.append("\\ul ")
            
            // Set font size (RTF uses half-points)
            val fontSize = paragraph.fontSize * 2
            rtfContent.append("\\fs$fontSize ")
            
            // Add text (escape special characters)
            val escapedText = escapeRtfText(paragraph.text)
            rtfContent.append(escapedText)
            
            // End formatting
            if (paragraph.isBold) rtfContent.append("\\b0 ")
            if (paragraph.isItalic) rtfContent.append("\\i0 ")
            if (paragraph.isUnderlined) rtfContent.append("\\ul0 ")
            
            rtfContent.append("\\par\n")
            
            // Add extra space for headings
            if (paragraph.isHeading) {
                rtfContent.append("\\par\n")
            }
        }
        
        // Add tables as simple text
        for (table in content.tables) {
            for (row in table.rows) {
                val rowText = row.joinToString(" | ")
                rtfContent.append("\\ql $rowText\\par\n")
            }
            rtfContent.append("\\par\n")
        }
        
        // RTF footer
        rtfContent.append("}")
        
        outputFile.writeText(rtfContent.toString())
    }
    
    private fun createTextFromDocx(content: DocxContent, outputFile: File) {
        val textContent = StringBuilder()
        
        // Add paragraphs
        for (paragraph in content.paragraphs) {
            textContent.append(paragraph.text)
            textContent.append("\n")
            
            // Add extra line for headings
            if (paragraph.isHeading) {
                textContent.append("\n")
            }
        }
        
        // Add tables
        for (table in content.tables) {
            for (row in table.rows) {
                val rowText = row.joinToString(" | ")
                textContent.append(rowText)
                textContent.append("\n")
            }
            textContent.append("\n")
        }
        
        outputFile.writeText(textContent.toString())
    }
    
    private fun createEpubFromDocx(content: DocxContent, outputFile: File, originalFileName: String) {
        // Create EPUB structure similar to PdfConverter
        val tempDir = File(outputFile.parentFile, "epub_temp_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            // Create content.opf
            val contentOpf = createEpubContentOpf(content, originalFileName)
            val contentOpfFile = File(tempDir, "content.opf")
            contentOpfFile.writeText(contentOpf)
            
            // Create toc.ncx
            val tocNcx = createEpubTocNcx(originalFileName)
            val tocNcxFile = File(tempDir, "toc.ncx")
            tocNcxFile.writeText(tocNcx)
            
            // Create chapter HTML files
            val chapters = splitIntoChapters(content.paragraphs)
            val chapterFiles = mutableListOf<String>()
            
            for ((index, chapter) in chapters.withIndex()) {
                val chapterHtml = createChapterHtml(chapter, index + 1)
                val chapterFile = File(tempDir, "chapter_${index + 1}.html")
                chapterFile.writeText(chapterHtml)
                chapterFiles.add("chapter_${index + 1}.html")
            }
            
            // Create EPUB file
            createEpubFile(outputFile, tempDir, contentOpfFile, tocNcxFile, chapterFiles)
            
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    private fun createEpubContentOpf(content: DocxContent, title: String): String {
        val chapters = splitIntoChapters(content.paragraphs)
        val chapterItems = chapters.mapIndexed { index, _ ->
            """    <item id="chapter_${index + 1}" href="chapter_${index + 1}.html" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n")
        
        val chapterSpine = chapters.mapIndexed { index, _ ->
            """    <itemref idref="chapter_${index + 1}"/>"""
        }.joinToString("\n")
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>$title</dc:title>
        <dc:creator>Universal Converter</dc:creator>
        <dc:language>en</dc:language>
        <dc:identifier id="uid">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier>
    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
$chapterItems
    </manifest>
    <spine toc="ncx">
$chapterSpine
    </spine>
</package>"""
    }
    
    private fun createEpubTocNcx(title: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
    <head>
        <meta name="dtb:uid" content="urn:uuid:${java.util.UUID.randomUUID()}"/>
        <meta name="dtb:depth" content="1"/>
        <meta name="dtb:totalPageCount" content="0"/>
        <meta name="dtb:maxPageNumber" content="0"/>
    </head>
    <docTitle>
        <text>$title</text>
    </docTitle>
    <navMap>
        <navPoint id="navpoint-1" playOrder="1">
            <navLabel>
                <text>$title</text>
            </navLabel>
            <content src="chapter_1.html"/>
        </navPoint>
    </navMap>
</ncx>"""
    }
    
    private fun splitIntoChapters(paragraphs: kotlin.collections.List<DocxParagraph>): kotlin.collections.List<kotlin.collections.List<DocxParagraph>> {
        val chapters = mutableListOf<MutableList<DocxParagraph>>()
        var currentChapter = mutableListOf<DocxParagraph>()
        
        for (paragraph in paragraphs) {
            // Start new chapter if it's a heading level 1
            if (paragraph.isHeading && paragraph.headingLevel == 1 && currentChapter.isNotEmpty()) {
                chapters.add(currentChapter)
                currentChapter = mutableListOf()
            }
            currentChapter.add(paragraph)
        }
        
        // Add the last chapter
        if (currentChapter.isNotEmpty()) {
            chapters.add(currentChapter)
        }
        
        // If no chapters were created, create one with all content
        if (chapters.isEmpty()) {
            chapters.add(paragraphs.toMutableList())
        }
        
        return chapters
    }
    
    private fun createChapterHtml(chapter: kotlin.collections.List<DocxParagraph>, chapterNumber: Int): String {
        val content = StringBuilder()
        
        for (paragraph in chapter) {
            val tag = when {
                paragraph.isHeading && paragraph.headingLevel == 1 -> "h1"
                paragraph.isHeading && paragraph.headingLevel == 2 -> "h2"
                paragraph.isHeading && paragraph.headingLevel == 3 -> "h3"
                paragraph.isHeading && paragraph.headingLevel == 4 -> "h4"
                paragraph.isHeading && paragraph.headingLevel == 5 -> "h5"
                paragraph.isHeading && paragraph.headingLevel == 6 -> "h6"
                else -> "p"
            }
            
            val style = StringBuilder()
            if (paragraph.isBold) style.append("font-weight: bold; ")
            if (paragraph.isItalic) style.append("font-style: italic; ")
            if (paragraph.isUnderlined) style.append("text-decoration: underline; ")
            if (paragraph.fontSize != 12) style.append("font-size: ${paragraph.fontSize}px; ")
            
            when (paragraph.alignment) {
                "CENTER" -> style.append("text-align: center; ")
                "RIGHT" -> style.append("text-align: right; ")
                "JUSTIFIED" -> style.append("text-align: justify; ")
                else -> style.append("text-align: left; ")
            }
            
            val styleAttr = if (style.isNotEmpty()) " style=\"${style}\"" else ""
            content.append("<$tag$styleAttr>${escapeHtml(paragraph.text)}</$tag>\n")
        }
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>Chapter $chapterNumber</title>
    <meta charset="utf-8"/>
</head>
<body>
$content
</body>
</html>"""
    }
    
    private fun createEpubFile(outputFile: File, tempDir: File, contentOpfFile: File, tocNcxFile: File, chapterFiles: kotlin.collections.List<String>) {
        val zipStream = java.util.zip.ZipOutputStream(FileOutputStream(outputFile))
        
        try {
            // Add mimetype
            zipStream.putNextEntry(java.util.zip.ZipEntry("mimetype"))
            zipStream.write("application/epub+zip".toByteArray())
            zipStream.closeEntry()
            
            // Add META-INF/container.xml
            zipStream.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
    <rootfiles>
        <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>"""
            zipStream.write(containerXml.toByteArray())
            zipStream.closeEntry()
            
            // Add content.opf
            zipStream.putNextEntry(java.util.zip.ZipEntry("content.opf"))
            zipStream.write(contentOpfFile.readBytes())
            zipStream.closeEntry()
            
            // Add toc.ncx
            zipStream.putNextEntry(java.util.zip.ZipEntry("toc.ncx"))
            zipStream.write(tocNcxFile.readBytes())
            zipStream.closeEntry()
            
            // Add chapter files
            for (chapterFile in chapterFiles) {
                val file = File(tempDir, chapterFile)
                zipStream.putNextEntry(java.util.zip.ZipEntry(chapterFile))
                zipStream.write(file.readBytes())
                zipStream.closeEntry()
            }
            
        } finally {
            zipStream.close()
        }
    }
    
    private fun escapeRtfText(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\n", "\\par ")
    }
    
    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
} 