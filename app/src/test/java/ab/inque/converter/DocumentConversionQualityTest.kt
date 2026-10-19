package ab.inque.converter

import android.content.Context
import android.content.ContextWrapper
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * JVM quality verification tests across all 30 directed Document ↔ Document conversion pairs
 * (`PDF`, `DOCX`, `EPUB`, `TXT`, `RTF`, `HTML`).
 */
class DocumentConversionQualityTest {

    private lateinit var rootTempDir: File
    private lateinit var tempCacheDir: File
    private lateinit var fixturesDir: File
    private lateinit var stubContext: Context

    private class StubJvmContext(private val cacheAndFilesDir: File) : ContextWrapper(null) {
        override fun getCacheDir(): File = cacheAndFilesDir
        override fun getFilesDir(): File = cacheAndFilesDir
        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val HEADING_TEXT = "Document Quality Title"
        private const val BODY_TEXT = "The quick brown fox jumps over the lazy dog."
        private const val KEY_PHRASE = "quick brown fox"
        private const val BULLET_ONE = "First item"
        private const val BULLET_TWO = "Second item"

        private val DOCUMENT_FORMATS = listOf(
            FileFormat.PDF,
            FileFormat.DOCX,
            FileFormat.EPUB,
            FileFormat.TXT,
            FileFormat.RTF,
            FileFormat.HTML
        )
    }

    @Before
    fun setUp() {
        rootTempDir = Files.createTempDirectory("doc_quality_test_").toFile()
        tempCacheDir = File(rootTempDir, "cache").apply { mkdirs() }
        fixturesDir = File(rootTempDir, "fixtures").apply { mkdirs() }
        stubContext = StubJvmContext(tempCacheDir)
    }

    @After
    fun tearDown() {
        rootTempDir.deleteRecursively()
    }

    @Test
    fun all30DocumentToDocumentPairs_preserveContentAndStructure() {
        val converter = UniversalConverter()
        var testedPairs = 0

        for (sourceFormat in DOCUMENT_FORMATS) {
            for (targetFormat in DOCUMENT_FORMATS) {
                if (sourceFormat == targetFormat) continue
                val pairLabel = "${sourceFormat.name} -> ${targetFormat.name}"

                val sourceFile = createFixtureForFormat(
                    format = sourceFormat,
                    baseName = "src_${sourceFormat.extension}_to_${targetFormat.extension}"
                )
                assertTrue("Source fixture missing for $pairLabel", sourceFile.exists() && sourceFile.length() > 0)

                val output = converter.convertFile(
                    sourceFile = sourceFile,
                    targetFormat = targetFormat,
                    context = stubContext,
                    progressCallback = {},
                    scale = null,
                    quality = QualityPreset.MEDIUM,
                    toolOptions = MediaToolOptions()
                )

                assertTrue("Output file does not exist for $pairLabel", output.exists())
                assertTrue("Output file is empty for $pairLabel", output.length() > 0L)
                assertEquals(
                    "Unexpected output extension for $pairLabel",
                    targetFormat.extension.lowercase(),
                    output.extension.lowercase()
                )

                verifyTargetDocumentQuality(sourceFormat, targetFormat, output, pairLabel)
                output.delete()
                testedPairs++
            }
        }

        assertEquals("Expected all 30 directed Document <-> Document pairs to be tested", 30, testedPairs)
    }

    @Test
    fun rtfToTxt_stripsFontTableAndControlWordsCleanly() {
        val rtfFile = createFixtureForFormat(FileFormat.RTF, "rtf_clean_check")
        val output = UniversalConverter().convertFile(
            sourceFile = rtfFile,
            targetFormat = FileFormat.TXT,
            context = stubContext,
            progressCallback = {},
            scale = null,
            quality = QualityPreset.MEDIUM,
            toolOptions = MediaToolOptions()
        )
        val txt = output.readText()
        assertTrue("Expected key phrase in RTF -> TXT output", txt.contains(KEY_PHRASE, ignoreCase = true))
        assertFalse(
            "Font table metadata 'Times New Roman' should not leak into TXT output, got: $txt",
            txt.contains("Times New Roman", ignoreCase = true)
        )
    }

    private fun verifyTargetDocumentQuality(
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        output: File,
        pairLabel: String
    ) {
        when (targetFormat) {
            FileFormat.TXT -> {
                val text = output.readText()
                assertTrue(
                    "TXT output for $pairLabel did not contain '$KEY_PHRASE'. Content:\n$text",
                    text.contains(KEY_PHRASE, ignoreCase = true)
                )
                if (sourceFormat == FileFormat.RTF) {
                    assertFalse(
                        "RTF -> TXT leaked font table metadata ('Times New Roman'):\n$text",
                        text.contains("Times New Roman", ignoreCase = true)
                    )
                }
            }

            FileFormat.HTML -> {
                val rawHtml = output.readText()
                val parsedText = Jsoup.parse(rawHtml).text()
                assertTrue(
                    "HTML output for $pairLabel did not contain '$KEY_PHRASE'. Parsed text:\n$parsedText",
                    parsedText.contains(KEY_PHRASE, ignoreCase = true)
                )
            }

            FileFormat.RTF -> {
                val rawRtf = output.readText().trimStart()
                assertTrue(
                    "RTF output for $pairLabel must start with '{\\rtf'",
                    rawRtf.startsWith("{\\rtf")
                )
                assertTrue(
                    "RTF output for $pairLabel did not contain '$KEY_PHRASE'",
                    rawRtf.contains(KEY_PHRASE, ignoreCase = true)
                )
            }

            FileFormat.DOCX -> {
                FileInputStream(output).use { fis ->
                    XWPFDocument(fis).use { docx ->
                        val combinedParagraphs = docx.paragraphs.joinToString("\n") { it.text }
                        assertTrue(
                            "DOCX output for $pairLabel did not contain '$KEY_PHRASE'. Extracted:\n$combinedParagraphs",
                            combinedParagraphs.contains(KEY_PHRASE, ignoreCase = true)
                        )
                    }
                }
            }

            FileFormat.EPUB -> {
                ZipFile(output).use { zip ->
                    val entryNames = zip.entries().asSequence().map { it.name }.toList()
                    assertTrue(
                        "EPUB for $pairLabel missing mimetype or container.xml ($entryNames)",
                        entryNames.any { it == "mimetype" || it.endsWith("container.xml") }
                    )
                    assertTrue(
                        "EPUB for $pairLabel missing .opf package file ($entryNames)",
                        entryNames.any { it.endsWith(".opf") }
                    )
                    val htmlEntries = entryNames.filter { it.endsWith(".html") || it.endsWith(".xhtml") }
                    assertTrue(
                        "EPUB for $pairLabel has no HTML chapter entries ($entryNames)",
                        htmlEntries.isNotEmpty()
                    )
                    val combinedHtmlText = htmlEntries.joinToString("\n") { name ->
                        val entry = zip.getEntry(name)
                        val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                        Jsoup.parse(html).text()
                    }
                    assertTrue(
                        "EPUB HTML content for $pairLabel did not contain '$KEY_PHRASE'. Text:\n$combinedHtmlText",
                        combinedHtmlText.contains(KEY_PHRASE, ignoreCase = true)
                    )
                }
            }

            FileFormat.PDF -> {
                PdfReader(output).use { reader ->
                    PdfDocument(reader).use { pdfDoc ->
                        assertTrue("PDF for $pairLabel has 0 pages", pdfDoc.numberOfPages >= 1)
                        val firstPageText = PdfTextExtractor.getTextFromPage(pdfDoc.getPage(1))
                        val allPagesText = (1..pdfDoc.numberOfPages).joinToString("\n") { pageNum ->
                            PdfTextExtractor.getTextFromPage(pdfDoc.getPage(pageNum))
                        }
                        assertTrue(
                            "PDF for $pairLabel did not contain '$KEY_PHRASE'. Page 1: '$firstPageText', All: '$allPagesText'",
                            firstPageText.contains(KEY_PHRASE, ignoreCase = true) ||
                                allPagesText.contains(KEY_PHRASE, ignoreCase = true)
                        )
                    }
                }
            }

            else -> error("Unexpected non-document format in DocumentConversionQualityTest: $targetFormat")
        }
    }

    private fun createFixtureForFormat(format: FileFormat, baseName: String): File {
        val file = File(fixturesDir, "$baseName.${format.extension}")
        when (format) {
            FileFormat.PDF -> writePdfFixture(file)
            FileFormat.DOCX -> writeDocxFixture(file)
            FileFormat.EPUB -> writeEpubFixture(file)
            FileFormat.HTML -> writeHtmlFixture(file)
            FileFormat.TXT -> writeTxtFixture(file)
            FileFormat.RTF -> writeRtfFixture(file)
            else -> error("Unsupported fixture format: $format")
        }
        return file
    }

    private fun writePdfFixture(file: File) {
        PdfWriter(file).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                Document(pdfDoc).use { doc ->
                    doc.add(
                        Paragraph(HEADING_TEXT)
                            .setFontSize(22f)
                            .setBold()
                            .setMarginBottom(14f)
                    )
                    doc.add(
                        Paragraph(BODY_TEXT)
                            .setFontSize(12f)
                            .setMarginBottom(12f)
                    )
                    doc.add(
                        Paragraph("• $BULLET_ONE")
                            .setFontSize(12f)
                            .setMarginBottom(6f)
                    )
                    doc.add(
                        Paragraph("• $BULLET_TWO")
                            .setFontSize(12f)
                            .setMarginBottom(6f)
                    )
                }
            }
        }
    }

    private fun writeDocxFixture(file: File) {
        XWPFDocument().use { docx ->
            val headingPara = docx.createParagraph().apply {
                style = "Heading1"
            }
            headingPara.createRun().apply {
                isBold = true
                fontSize = 20
                setText(HEADING_TEXT)
            }

            val bodyPara = docx.createParagraph()
            bodyPara.createRun().apply {
                fontSize = 12
                setText(BODY_TEXT)
            }

            val item1Para = docx.createParagraph()
            item1Para.createRun().apply {
                fontSize = 12
                setText(BULLET_ONE)
            }

            val item2Para = docx.createParagraph()
            item2Para.createRun().apply {
                fontSize = 12
                setText(BULLET_TWO)
            }

            FileOutputStream(file).use { out ->
                docx.write(out)
            }
        }
    }

    private fun writeEpubFixture(file: File) {
        val tempDocx = File(fixturesDir, "epub_seed_${System.nanoTime()}.docx")
        writeDocxFixture(tempDocx)
        try {
            val generatedEpub = DocxConverter().convertDocxToEpub(tempDocx, stubContext)
            generatedEpub.copyTo(file, overwrite = true)
            generatedEpub.delete()
        } catch (_: Throwable) {
            // Fallback direct valid EPUB zip if needed
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                fun putEntry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                putEntry("mimetype", "application/epub+zip")
                putEntry(
                    "META-INF/container.xml",
                    """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
                )
                putEntry(
                    "OEBPS/content.opf",
                    """<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$HEADING_TEXT</dc:title>
    <dc:language>en</dc:language>
    <dc:identifier id="uid">urn:uuid:12345678-1234-1234-1234-1234567890ab</dc:identifier>
  </metadata>
  <manifest>
    <item id="chapter1" href="chapter1.html" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="chapter1"/></spine>
</package>"""
                )
                putEntry(
                    "OEBPS/chapter1.html",
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>$HEADING_TEXT</title><meta charset="utf-8"/></head>
<body>
  <h1>$HEADING_TEXT</h1>
  <p>$BODY_TEXT</p>
  <ul><li>$BULLET_ONE</li><li>$BULLET_TWO</li></ul>
</body>
</html>"""
                )
            }
        } finally {
            tempDocx.delete()
        }
    }

    private fun writeHtmlFixture(file: File) {
        val html = """<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"/><title>$HEADING_TEXT</title></head>
<body>
  <h1>$HEADING_TEXT</h1>
  <p>$BODY_TEXT</p>
  <ul>
    <li>$BULLET_ONE</li>
    <li>$BULLET_TWO</li>
  </ul>
</body>
</html>"""
        file.writeText(html, Charsets.UTF_8)
    }

    private fun writeTxtFixture(file: File) {
        val txt = "$HEADING_TEXT\n\n$BODY_TEXT\n\n- $BULLET_ONE\n- $BULLET_TWO\n"
        file.writeText(txt, Charsets.UTF_8)
    }

    private fun writeRtfFixture(file: File) {
        val rtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\\f0\\fs24 {\\b $HEADING_TEXT}\\par $BODY_TEXT\\par}"
        file.writeText(rtf, Charsets.UTF_8)
    }
}
