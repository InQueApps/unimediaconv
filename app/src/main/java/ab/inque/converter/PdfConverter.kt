package ab.inque.converter

import android.content.Context
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PdfConverter {
    
    fun convertPdfToEpub(pdfFile: File, context: Context): File {
        val tempDir = File(context.cacheDir, "pdf_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            // Extract text from PDF
            val pdfText = extractTextFromPdf(pdfFile)
            
            // Create EPUB structure
            val originalName = pdfFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.epub")
            createEpubFromText(pdfText, outputFile, pdfFile.name)
            
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
    
    private fun extractTextFromPdf(pdfFile: File): String {
        val textBuilder = StringBuilder()
        
        try {
            val reader = PdfReader(FileInputStream(pdfFile))
            val pdfDocument = PdfDocument(reader)
            
            for (i in 1..pdfDocument.numberOfPages) {
                val page = pdfDocument.getPage(i)
                val text = PdfTextExtractor.getTextFromPage(page)
                textBuilder.append(text).append("\n\n")
            }
            
            pdfDocument.close()
            reader.close()
            
        } catch (e: Exception) {
            textBuilder.append("Error extracting text from PDF: ${e.message}")
        }
        
        return textBuilder.toString()
    }
    
    private fun createEpubFromText(text: String, outputFile: File, originalFileName: String) {
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
            
            // Create OEBPS/content.opf
            val contentOpf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Converted from $originalFileName</dc:title>
                        <dc:creator>Universal Converter</dc:creator>
                        <dc:language>en</dc:language>
                        <dc:identifier>id-${System.currentTimeMillis()}</dc:identifier>
                    </metadata>
                    <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="content" href="content.html" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine>
                        <itemref idref="content"/>
                    </spine>
                </package>
            """.trimIndent()
            
            addZipEntry(zip, "OEBPS/content.opf", contentOpf)
            
            // Create OEBPS/toc.ncx
            val tocNcx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <head>
                        <meta name="dtb:uid" content="id-${System.currentTimeMillis()}"/>
                        <meta name="dtb:depth" content="1"/>
                        <meta name="dtb:totalPageCount" content="0"/>
                        <meta name="dtb:maxPageNumber" content="0"/>
                    </head>
                    <docTitle>
                        <text>Converted from $originalFileName</text>
                    </docTitle>
                    <navMap>
                        <navPoint id="nav-1" playOrder="1">
                            <navLabel>
                                <text>Content</text>
                            </navLabel>
                            <content src="content.html"/>
                        </navPoint>
                    </navMap>
                </ncx>
            """.trimIndent()
            
            addZipEntry(zip, "OEBPS/toc.ncx", tocNcx)
            
            // Create OEBPS/content.html
            val contentHtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <title>Converted from $originalFileName</title>
                    <meta charset="utf-8"/>
                    <style>
                        body { 
                            font-family: Arial, sans-serif; 
                            margin: 2em; 
                            line-height: 1.6;
                            color: #333;
                        }
                        h1 { 
                            color: #2196F3; 
                            border-bottom: 2px solid #2196F3;
                            padding-bottom: 0.5em;
                        }
                        p { margin-bottom: 1em; }
                        .conversion-info {
                            background-color: #f5f5f5;
                            padding: 1em;
                            border-radius: 8px;
                            margin-bottom: 2em;
                            border-left: 4px solid #2196F3;
                        }
                    </style>
                </head>
                <body>
                    <h1>PDF to EPUB Conversion</h1>
                    <div class="conversion-info">
                        <p><strong>Original file:</strong> $originalFileName</p>
                        <p><strong>Converted on:</strong> ${java.time.LocalDateTime.now()}</p>
                        <p>This EPUB was converted from a PDF file. The formatting may have been simplified during conversion.</p>
                    </div>
                    <div class="content">
                        ${text.replace("\n", "<br/>")}
                    </div>
                </body>
                </html>
            """.trimIndent()
            
            addZipEntry(zip, "OEBPS/content.html", contentHtml)
        }
    }
    
    private fun addZipEntry(zip: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
} 