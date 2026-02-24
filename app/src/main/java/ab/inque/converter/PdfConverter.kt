package ab.inque.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.geom.Matrix
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.geom.Vector
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfIndirectReference
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.action.PdfAction
import com.itextpdf.kernel.pdf.annot.PdfLinkAnnotation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import java.math.BigInteger
import kotlin.text.Charsets

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

    sealed class LinkTarget {
        data class External(val uri: String) : LinkTarget()
        data class Internal(val pageNumber: Int, val namedDestination: String? = null) : LinkTarget()
    }

    data class PdfTextRun(
        val text: String,
        val fontSize: Float,
        val fontName: String,
        val isBold: Boolean,
        val isItalic: Boolean,
        val linkTarget: LinkTarget? = null
    )

    enum class ParagraphType {
        HEADING,
        LIST_ITEM,
        NORMAL
    }

    data class PdfParagraph(
        val text: String,
        val runs: List<PdfTextRun>,
        val fontSize: Float,
        val fontName: String,
        val isBold: Boolean,
        val isItalic: Boolean,
        val alignment: String,
        val yPosition: Float,
        val indent: Float,
        val type: ParagraphType,
        val listLevel: Int = 0,
        val listMarker: String? = null,
        val isOrderedList: Boolean = false
    )

    data class PdfHeading(
        val text: String,
        val fontSize: Float,
        val fontName: String,
        val level: Int,
        val yPosition: Float
    )

    data class PdfImage(
        val id: String,
        val fileName: String,
        val altText: String,
        val pageNumber: Int,
        val yPosition: Float,
        val width: Float,
        val height: Float
    )

    data class PdfImageResource(
        val image: PdfImage,
        val bytes: ByteArray,
        val extension: String
    )

    data class PdfExtractionResult(
        val pages: List<PdfPage>,
        val images: List<PdfImageResource>
    )

    private data class TextChunk(
        val text: String,
        val fontSize: Float,
        val fontName: String,
        val isBold: Boolean,
        val isItalic: Boolean,
        val x: Float,
        val y: Float,
        val width: Float,
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
        val linkTarget: LinkTarget? = null
    )

    private data class PdfLine(
        val y: Float,
        val height: Float,
        val indent: Float,
        val runs: MutableList<TextChunk>
    )

    private data class PageImageInternal(
        val bytes: ByteArray,
        val extension: String,
        val width: Float,
        val height: Float,
        val yPosition: Float
    )

    data class PdfPage(
        val pageNumber: Int,
        val textElements: List<PdfTextElement>,
        val paragraphs: List<PdfParagraph>,
        val headings: List<PdfHeading>,
        val images: List<PdfImage>
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
            val extraction = extractPdfContentWithFormatting(pdfFile)
            val pdfPages = extraction.pages
            progressCallback(0.5f) // PDF extraction complete
            
            // Create EPUB structure with enhanced formatting
            progressCallback(0.6f) // Starting EPUB creation
            val originalName = pdfFile.nameWithoutExtension
            val outputFile = File(context.cacheDir, "${originalName}.epub")
            val stableId = java.util.UUID.nameUUIDFromBytes(pdfFile.absolutePath.toByteArray(Charsets.UTF_8)).toString()
            createEnhancedEpub(pdfPages, extraction.images, outputFile, pdfFile.name, originalName, stableId)
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
    
    private fun extractPdfContentWithFormatting(pdfFile: File): PdfExtractionResult {
        val pages = mutableListOf<PdfPage>()
        val imageResources = mutableListOf<PdfImageResource>()

        try {
            PdfReader(FileInputStream(pdfFile)).use { reader ->
                PdfDocument(reader).use { pdfDocument ->
                    for (pageIndex in 1..pdfDocument.numberOfPages) {
                        val page = pdfDocument.getPage(pageIndex)
                        val pageSize = page.pageSize

                        val listener = PdfPageContentListener(pageSize.width, pageSize.height)
                        PdfCanvasProcessor(listener).processPageContent(page)

                        val linkedChunks = applyLinkTargets(page, pdfDocument, listener.textChunks)

                        val textElements = linkedChunks.map { chunk ->
                            PdfTextElement(
                                text = chunk.text,
                                fontSize = chunk.fontSize,
                                fontName = chunk.fontName,
                                isBold = chunk.isBold,
                                isItalic = chunk.isItalic,
                                yPosition = chunk.y,
                                xPosition = chunk.x,
                                width = chunk.width,
                                alignment = "left"
                            )
                        }

                        val lines = buildLines(linkedChunks)
                        val paragraphs = buildParagraphs(lines, pageSize.width)
                        val headings = buildHeadingsFromParagraphs(paragraphs)

                        val images = listener.images.mapIndexed { index, image ->
                            val id = "img-${pageIndex}-${index + 1}"
                            val extension = image.extension.ifBlank { "png" }
                            val fileName = "$id.$extension"
                            val imagePath = "images/$fileName"
                            val pdfImage = PdfImage(
                                id = id,
                                fileName = imagePath,
                                altText = "Image from page $pageIndex",
                                pageNumber = pageIndex,
                                yPosition = image.yPosition,
                                width = image.width,
                                height = image.height
                            )
                            imageResources.add(PdfImageResource(pdfImage, image.bytes, extension))
                            pdfImage
                        }

                        pages.add(
                            PdfPage(
                                pageNumber = pageIndex,
                                textElements = textElements,
                                paragraphs = paragraphs,
                                headings = headings,
                                images = images
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val message = "Error extracting text from PDF: ${e.message}"
            val fallbackRun = PdfTextRun(message, 12f, "Times-Roman", false, false)
            val fallbackParagraph = PdfParagraph(
                text = message,
                runs = listOf(fallbackRun),
                fontSize = 12f,
                fontName = "Times-Roman",
                isBold = false,
                isItalic = false,
                alignment = "left",
                yPosition = 0f,
                indent = 0f,
                type = ParagraphType.NORMAL
            )
            val fallbackPage = PdfPage(
                pageNumber = 1,
                textElements = emptyList(),
                paragraphs = listOf(fallbackParagraph),
                headings = emptyList(),
                images = emptyList()
            )
            return PdfExtractionResult(listOf(fallbackPage), emptyList())
        }

        return PdfExtractionResult(pages, imageResources)
    }
    
    private class PdfPageContentListener(
        private val pageWidth: Float,
        private val pageHeight: Float
    ) : IEventListener {
        val textChunks = mutableListOf<TextChunk>()
        val images = mutableListOf<PageImageInternal>()

        override fun eventOccurred(data: IEventData, type: EventType) {
            when (type) {
                EventType.RENDER_TEXT -> {
                    val renderInfo = data as? TextRenderInfo ?: return
                    val rawText = renderInfo.text ?: return
                    if (rawText.isBlank()) return

                    val baseline = renderInfo.baseline
                    val ascentLine = renderInfo.ascentLine
                    val descentLine = renderInfo.descentLine
                    val start = baseline.startPoint
                    val end = baseline.endPoint
                    val font = renderInfo.font
                    val fontNames = font?.fontProgram?.fontNames
                    val resolvedFontName = when {
                        fontNames?.fontName != null -> fontNames.fontName
                        fontNames?.fullName != null -> fontNames.fullName?.firstOrNull()?.joinToString(" ")
                        else -> null
                    } ?: "Unknown"

                    val fontSize = renderInfo.fontSize
                    val normalizedText = rawText.replace(Regex("\\s+"), " ")
                    if (normalizedText.isBlank()) return

                    val isBold = resolvedFontName.contains("bold", ignoreCase = true)
                    val isItalic = resolvedFontName.contains("italic", ignoreCase = true) || resolvedFontName.contains("oblique", ignoreCase = true)

                    val left = min(start.get(Vector.I1), end.get(Vector.I1))
                    val right = max(start.get(Vector.I1), end.get(Vector.I1))
                    val top = max(ascentLine.startPoint.get(Vector.I2), ascentLine.endPoint.get(Vector.I2))
                    val bottom = min(descentLine.startPoint.get(Vector.I2), descentLine.endPoint.get(Vector.I2))

                    val chunk = TextChunk(
                        text = normalizedText,
                        fontSize = fontSize,
                        fontName = resolvedFontName,
                        isBold = isBold,
                        isItalic = isItalic,
                        x = left,
                        y = top,
                        width = max(0f, right - left),
                        left = left,
                        right = right,
                        top = top,
                        bottom = bottom
                    )
                    textChunks.add(chunk)
                }

                EventType.RENDER_IMAGE -> {
                    val imageInfo = data as? ImageRenderInfo ?: return
                    val imageObject = imageInfo.image ?: return
                    try {
                        val bytes = imageObject.imageBytes ?: return
                        val extension = imageObject.identifyImageFileExtension() ?: "png"
                        val matrix = imageInfo.imageCtm
                        val y = matrix?.get(Matrix.I32) ?: 0f
                        images.add(
                            PageImageInternal(
                                bytes = bytes,
                                extension = extension,
                                width = imageObject.width,
                                height = imageObject.height,
                                yPosition = y
                            )
                        )
                    } catch (_: Exception) {
                        // ignore individual image failures
                    }
                }

                else -> Unit
            }
        }

        override fun getSupportedEvents(): Set<EventType> {
            return setOf(EventType.RENDER_TEXT, EventType.RENDER_IMAGE)
        }
    }

    private fun applyLinkTargets(
        page: com.itextpdf.kernel.pdf.PdfPage,
        pdfDocument: PdfDocument,
        chunks: List<TextChunk>
    ): List<TextChunk> {
        val annotations = page.annotations
            .filterIsInstance<PdfLinkAnnotation>()
            .filter { it.rectangle?.toRectangle() != null }

        if (annotations.isEmpty()) {
            return chunks
        }

        return chunks.map { chunk ->
            val linkTarget = annotations.asSequence()
                .mapNotNull { annotation ->
                    val rect = annotation.rectangle?.toRectangle() ?: return@mapNotNull null
                    if (!chunk.intersects(rect)) return@mapNotNull null
                    determineLinkTarget(annotation, pdfDocument)
                }
                .firstOrNull()
            if (linkTarget != null) chunk.copy(linkTarget = linkTarget) else chunk
        }
    }

    private fun determineLinkTarget(annotation: PdfLinkAnnotation, pdfDocument: PdfDocument): LinkTarget? {
        val uriFromAnnotation = annotation.uriActionObject
            ?.getAsString(PdfName.URI)
            ?.toUnicodeString()
        if (!uriFromAnnotation.isNullOrBlank()) {
            return LinkTarget.External(uriFromAnnotation)
        }

        val actionDictionary = annotation.action
        if (actionDictionary != null) {
            val actionUri = actionDictionary.getAsString(PdfName.URI)?.toUnicodeString()
            if (!actionUri.isNullOrBlank()) {
                return LinkTarget.External(actionUri)
            }
            val destinationObject = actionDictionary.get(PdfName.D)
            val internalTarget = resolveInternalDestination(pdfDocument, destinationObject)
            if (internalTarget != null) {
                return LinkTarget.Internal(internalTarget.first, internalTarget.second)
            }
        }

        val destinationObject = annotation.destinationObject
        val internalTarget = resolveInternalDestination(pdfDocument, destinationObject)
        if (internalTarget != null) {
            return LinkTarget.Internal(internalTarget.first, internalTarget.second)
        }

        return null
    }

    private fun resolveInternalDestination(
        pdfDocument: PdfDocument,
        destinationObject: PdfObject?
    ): Pair<Int, String?>? {
        when (destinationObject) {
            is PdfArray -> {
                val pageObject = destinationObject.get(0)
                val pageNumber = when (pageObject) {
                    is PdfDictionary -> pdfDocument.getPageNumber(pageObject)
                    is PdfIndirectReference -> {
                        val dictionary = pageObject.refersTo as? PdfDictionary
                        dictionary?.let { pdfDocument.getPageNumber(it) }
                    }
                    is PdfNumber -> pageObject.intValue() + 1
                    else -> null
                }
                if (pageNumber != null) {
                    return pageNumber to null
                }
            }
            is PdfString -> {
                val name = destinationObject.toUnicodeString()
                val destinations = pdfDocument.catalog.getNameTree(PdfName.Dests)?.names
                val targetObject = destinations?.get(name)
                val resolved = resolveInternalDestination(pdfDocument, targetObject)
                if (resolved != null) {
                    return resolved.first to name
                }
            }
            is PdfName -> {
                val name = destinationObject.value
                val destinations = pdfDocument.catalog.getNameTree(PdfName.Dests)?.names
                val targetObject = destinations?.get(name)
                val resolved = resolveInternalDestination(pdfDocument, targetObject)
                if (resolved != null) {
                    return resolved.first to name
                }
            }
            is PdfIndirectReference -> {
                val referred = destinationObject.refersTo
                val resolved = resolveInternalDestination(pdfDocument, referred)
                if (resolved != null) {
                    return resolved
                }
            }
        }
        return null
    }

    private fun TextChunk.intersects(rect: Rectangle): Boolean {
        val chunkLeft = min(left, right)
        val chunkRight = max(left, right)
        val chunkBottom = min(bottom, top)
        val chunkTop = max(bottom, top)
        return chunkRight >= rect.left && chunkLeft <= rect.right && chunkTop >= rect.bottom && chunkBottom <= rect.top
    }
    
    private fun PdfArray.toRectangle(): Rectangle? {
        if (size() < 4) return null
        val llx = getAsNumber(0)?.floatValue() ?: return null
        val lly = getAsNumber(1)?.floatValue() ?: return null
        val urx = getAsNumber(2)?.floatValue() ?: return null
        val ury = getAsNumber(3)?.floatValue() ?: return null
        return Rectangle(llx, lly, urx - llx, ury - lly)
    }

    private fun buildLines(chunks: List<TextChunk>): List<PdfLine> {
        if (chunks.isEmpty()) return emptyList()

        val sortedChunks = chunks.sortedWith(compareByDescending<TextChunk> { it.y }.thenBy { it.x })
        val lines = mutableListOf<PdfLine>()

        sortedChunks.forEach { chunk ->
            val lineMatch = lines.firstOrNull { line ->
                abs(line.y - chunk.y) <= max(line.height, chunk.fontSize) * 0.6f
            }

            if (lineMatch != null) {
                lineMatch.runs.add(chunk)
                val updatedHeight = max(lineMatch.height, chunk.fontSize)
                val updatedIndent = min(lineMatch.indent, chunk.x)
                val index = lines.indexOf(lineMatch)
                lines[index] = lineMatch.copy(height = updatedHeight, indent = updatedIndent)
            } else {
                lines.add(
                    PdfLine(
                        y = chunk.y,
                        height = chunk.fontSize,
                        indent = chunk.x,
                        runs = mutableListOf(chunk)
                    )
                )
            }
        }

        lines.forEach { line ->
            line.runs.sortBy { it.x }
        }

        return lines.sortedByDescending { it.y }
    }

    private fun buildParagraphs(lines: List<PdfLine>, pageWidth: Float): List<PdfParagraph> {
        if (lines.isEmpty()) return emptyList()

        val paragraphs = mutableListOf<PdfParagraph>()
        val allFontSizes = lines.flatMap { it.runs }.map { it.fontSize }
        val averageFontSize = if (allFontSizes.isNotEmpty()) allFontSizes.average().toFloat() else 12f
        val headingThreshold = averageFontSize * 1.35f
        val averageLineHeight = lines.map { it.height }.average().toFloat().coerceAtLeast(averageFontSize)
        val spacingThreshold = averageLineHeight * 1.6f
        val indentThreshold = 18f

        var currentRuns = mutableListOf<TextChunk>()
        var currentIndent = lines.first().indent
        var currentY = lines.first().y
        var currentType = ParagraphType.NORMAL
        var currentListLevel = 0
        var currentListMarker: String? = null
        var currentListOrdered = false
        var previousLine: PdfLine? = null

        lines.forEach { line ->
            val lineText = line.runs.joinToString("") { it.text }.trim()
            if (lineText.isEmpty()) {
                if (currentRuns.isNotEmpty()) {
                    paragraphs.add(
                        buildParagraphFromRuns(
                            runs = currentRuns,
                            indent = currentIndent,
                            yPosition = currentY,
                            type = currentType,
                            listLevel = currentListLevel,
                            listMarker = currentListMarker,
                            orderedList = currentListOrdered,
                            pageWidth = pageWidth
                        )
                    )
                    currentRuns = mutableListOf()
                    currentType = ParagraphType.NORMAL
                    currentListLevel = 0
                    currentListMarker = null
                    currentListOrdered = false
                }
                previousLine = line
                return@forEach
            }

            val newParagraph = when {
                previousLine == null -> currentRuns.isEmpty()
                (previousLine.y - line.y) > spacingThreshold -> true
                abs(line.indent - previousLine.indent) > indentThreshold -> true
                else -> false
            }

            if (newParagraph && currentRuns.isNotEmpty()) {
                paragraphs.add(
                    buildParagraphFromRuns(
                        runs = currentRuns,
                        indent = currentIndent,
                        yPosition = currentY,
                        type = currentType,
                        listLevel = currentListLevel,
                        listMarker = currentListMarker,
                        orderedList = currentListOrdered,
                        pageWidth = pageWidth
                    )
                )
                currentRuns = mutableListOf()
                currentType = ParagraphType.NORMAL
                currentListLevel = 0
                currentListMarker = null
                currentListOrdered = false
            }

            if (currentRuns.isEmpty()) {
                currentIndent = line.indent
                currentY = line.y
            }

            val listDetection = detectList(lineText, line.indent, pageWidth)
            if (listDetection != null) {
                currentType = ParagraphType.LIST_ITEM
                currentListLevel = listDetection.level
                currentListMarker = listDetection.marker
                currentListOrdered = listDetection.ordered
            } else {
                val maxFont = line.runs.maxOfOrNull { it.fontSize } ?: averageFontSize
                if (maxFont >= headingThreshold && lineText.length < 140) {
                    currentType = ParagraphType.HEADING
                }
            }

            currentRuns.addAll(line.runs)
            currentIndent = min(currentIndent, line.indent)
            currentY = line.y
            previousLine = line
        }

        if (currentRuns.isNotEmpty()) {
            paragraphs.add(
                buildParagraphFromRuns(
                    runs = currentRuns,
                    indent = currentIndent,
                    yPosition = currentY,
                    type = currentType,
                    listLevel = currentListLevel,
                    listMarker = currentListMarker,
                    orderedList = currentListOrdered,
                    pageWidth = pageWidth
                )
            )
        }

        return paragraphs.sortedByDescending { it.yPosition }
    }

    private fun buildParagraphFromRuns(
        runs: List<TextChunk>,
        indent: Float,
        yPosition: Float,
        type: ParagraphType,
        listLevel: Int,
        listMarker: String?,
        orderedList: Boolean,
        pageWidth: Float
    ): PdfParagraph {
        val mergedRuns = mergeRuns(runs)
        val text = mergedRuns.joinToString("") { it.text }.trim()
        val dominantFontSize = mergedRuns.groupBy { it.fontSize }
            .maxByOrNull { entry -> entry.value.sumOf { chunk -> chunk.text.length } }?.key ?: 12f
        val dominantFontName = mergedRuns.groupBy { it.fontName }
            .maxByOrNull { entry -> entry.value.sumOf { chunk -> chunk.text.length } }?.key ?: "Times-Roman"
        val isBold = mergedRuns.any { it.isBold }
        val isItalic = mergedRuns.any { it.isItalic }
        val alignment = determineAlignment(mergedRuns, indent, pageWidth)

        val paragraphRuns = mergedRuns.map {
            PdfTextRun(
                text = it.text,
                fontSize = it.fontSize,
                fontName = it.fontName,
                isBold = it.isBold,
                isItalic = it.isItalic,
                linkTarget = it.linkTarget
            )
        }

        return PdfParagraph(
            text = text,
            runs = paragraphRuns,
            fontSize = dominantFontSize,
            fontName = dominantFontName,
            isBold = isBold,
            isItalic = isItalic,
            alignment = alignment,
            yPosition = yPosition,
            indent = indent,
            type = if (text.isEmpty()) ParagraphType.NORMAL else type,
            listLevel = listLevel,
            listMarker = listMarker,
            isOrderedList = orderedList
        )
    }

    private fun mergeRuns(runs: List<TextChunk>): List<TextChunk> {
        if (runs.isEmpty()) return emptyList()
        val ordered = runs.sortedWith(compareByDescending<TextChunk> { it.y }.thenBy { it.x })
        val merged = mutableListOf<TextChunk>()

        ordered.forEach { chunk ->
            val last = merged.lastOrNull()
            if (last != null &&
                last.isBold == chunk.isBold &&
                last.isItalic == chunk.isItalic &&
                last.fontName == chunk.fontName &&
                last.linkTarget == chunk.linkTarget &&
                abs(last.fontSize - chunk.fontSize) < 0.2f &&
                abs(chunk.left - last.right) < chunk.fontSize * 0.6f
            ) {
                val newLeft = min(last.left, chunk.left)
                val newRight = max(last.right, chunk.right)
                val newTop = max(last.top, chunk.top)
                val newBottom = min(last.bottom, chunk.bottom)
                merged[merged.lastIndex] = last.copy(
                    text = last.text + chunk.text,
                    x = newLeft,
                    y = newTop,
                    width = max(0f, newRight - newLeft),
                    left = newLeft,
                    right = newRight,
                    top = newTop,
                    bottom = newBottom
                )
            } else {
                merged.add(chunk)
            }
        }

        return merged
    }

    private fun determineAlignment(runs: List<TextChunk>, indent: Float, pageWidth: Float): String {
        if (runs.isEmpty() || pageWidth <= 0f) return "left"
        val firstRun = runs.minByOrNull { it.x } ?: return "left"
        val lastRun = runs.maxByOrNull { it.x + it.width } ?: return "left"
        val leftMargin = firstRun.x
        val rightEdge = lastRun.x + lastRun.width
        val rightMargin = pageWidth - rightEdge
        val minLeft = runs.minOf { min(it.left, it.right) }
        val maxRight = runs.maxOf { max(it.left, it.right) }
        val totalWidth = maxRight - minLeft

        return when {
            leftMargin > pageWidth * 0.25f && rightMargin > pageWidth * 0.25f -> "center"
            rightMargin < pageWidth * 0.12f && leftMargin > pageWidth * 0.25f -> "right"
            totalWidth > pageWidth * 0.75f && leftMargin < pageWidth * 0.15f -> "justify"
            else -> "left"
        }
    }

    private data class ListDetectionResult(val marker: String, val level: Int, val ordered: Boolean)

    private sealed class ChapterElement(open val y: Float) {
        data class ParagraphElement(val paragraph: PdfParagraph) : ChapterElement(paragraph.yPosition)
        data class ImageElement(val image: PdfImage) : ChapterElement(image.yPosition)
    }

    private fun detectList(text: String, indent: Float, pageWidth: Float): ListDetectionResult? {
        val trimmed = text.trimStart()
        val bulletMatch = Regex("^([•\\-*])\\s+.+").find(trimmed)
        if (bulletMatch != null) {
            return ListDetectionResult(bulletMatch.groupValues[1], determineListLevel(indent, pageWidth), ordered = false)
        }
        val orderedMatch = Regex("^(\\(?\\d+[.)])\\s+.+").find(trimmed)
        if (orderedMatch != null) {
            return ListDetectionResult(orderedMatch.groupValues[1], determineListLevel(indent, pageWidth), ordered = true)
        }
        val alphaMatch = Regex("^(\\(?[A-Za-z]{1,2}[.)])\\s+.+").find(trimmed)
        if (alphaMatch != null) {
            return ListDetectionResult(alphaMatch.groupValues[1], determineListLevel(indent, pageWidth), ordered = true)
        }
        val romanMatch = Regex("^(\\(?[IVXLCDM]+[.)])\\s+.+", RegexOption.IGNORE_CASE).find(trimmed)
        if (romanMatch != null) {
            return ListDetectionResult(romanMatch.groupValues[1], determineListLevel(indent, pageWidth), ordered = true)
        }
        return null
    }

    private fun determineListLevel(indent: Float, pageWidth: Float): Int {
        if (pageWidth <= 0f) return 1
        val normalized = indent / pageWidth
        return when {
            normalized < 0.12f -> 1
            normalized < 0.22f -> 2
            normalized < 0.32f -> 3
            normalized < 0.42f -> 4
            else -> 5
        }
    }

    private fun buildHeadingsFromParagraphs(paragraphs: List<PdfParagraph>): List<PdfHeading> {
        val headingParagraphs = paragraphs.filter { it.type == ParagraphType.HEADING && it.text.isNotBlank() }
        if (headingParagraphs.isEmpty()) return emptyList()
        val distinctSizes = headingParagraphs.map { it.fontSize }.distinct().sortedDescending()

        return headingParagraphs.map { paragraph ->
            val level = determineHeadingLevel(paragraph.fontSize, distinctSizes)
            PdfHeading(
                text = paragraph.text,
                fontSize = paragraph.fontSize,
                fontName = paragraph.fontName,
                level = level,
                yPosition = paragraph.yPosition
            )
        }
    }

    private fun determineHeadingLevel(fontSize: Float, distinctSizes: List<Float>): Int {
        val index = distinctSizes.indexOfFirst { abs(it - fontSize) < 0.5f }
        return if (index >= 0) (index + 1).coerceAtMost(6) else 4
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("\\s+"), " ")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    private fun createEnhancedEpub(pdfPages: List<PdfPage>, imageResources: List<PdfImageResource>, outputFile: File, originalFileName: String, title: String, stableId: String) {
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
            val contentOpf = createContentOpf(chapters, imageResources, originalFileName, title, stableId)
            addZipEntry(zip, "OEBPS/content.opf", contentOpf)
            
            // Create OEBPS/toc.ncx with navigation
            val tocNcx = createTocNcx(chapters, title, stableId)
            addZipEntry(zip, "OEBPS/toc.ncx", tocNcx)
            
            // Create CSS file for enhanced styling
            val cssContent = createEnhancedCss()
            addZipEntry(zip, "OEBPS/style.css", cssContent)

            // Add images to EPUB
            imageResources.forEach { resource ->
                addZipEntry(zip, "OEBPS/${resource.image.fileName}", resource.bytes)
            }
            
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
        val pageIdMap = pdfPages.mapIndexed { index, page ->
            page.pageNumber to "chapter-${index + 1}"
        }.toMap()

        pdfPages.forEachIndexed { index, page ->
            val chapterId = pageIdMap[page.pageNumber] ?: "chapter-${index + 1}"
            val chapterTitle = if (page.headings.isNotEmpty()) {
                page.headings.first().text
            } else {
                "Page ${page.pageNumber}"
            }
            
            val chapterContent = createChapterContent(page, chapterTitle, chapterId, pageIdMap)
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
                    <p>Generated by AnyConvert</p>
                    <p>${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy"))}</p>
                </div>
            </div>
        """.trimIndent()
    }
    
    private fun createChapterContent(
        page: PdfPage,
        title: String,
        chapterId: String,
        pageIdMap: Map<Int, String>
    ): String {
        val builder = StringBuilder()
        builder.append("<div id=\"${escapeHtmlAttribute(chapterId)}\" class=\"chapter-content\">")
        builder.append("<h1 class=\"chapter-title\">${cleanText(title)}</h1>")

        val headingSizes = page.paragraphs
            .filter { it.type == ParagraphType.HEADING }
            .map { it.fontSize }
            .distinct()
            .sortedDescending()

        val elements = mutableListOf<ChapterElement>()
        page.paragraphs.forEach { paragraph ->
            elements.add(ChapterElement.ParagraphElement(paragraph))
        }
        page.images.forEach { image ->
            elements.add(ChapterElement.ImageElement(image))
        }

        elements.sortByDescending { it.y }

        var currentListTag: String? = null
        var currentListOrdered = false

        fun closeList() {
            if (currentListTag != null) {
                builder.append("</$currentListTag>")
                currentListTag = null
            }
        }

        fun openList(tag: String, level: Int) {
            closeList()
            builder.append("<$tag class=\"list level-$level\">")
            currentListTag = tag
            currentListOrdered = tag == "ol"
        }

        val linkResolver: (LinkTarget) -> String? = { target ->
            when (target) {
                is LinkTarget.External -> target.uri
                is LinkTarget.Internal -> pageIdMap[target.pageNumber]?.let { "#$it" }
            }
        }

        elements.forEach { element ->
            when (element) {
                is ChapterElement.ParagraphElement -> {
                    val paragraph = element.paragraph
                    if (paragraph.type == ParagraphType.LIST_ITEM) {
                        val desiredTag = if (paragraph.isOrderedList || paragraph.listMarker?.matches(Regex("\\d+|[A-Za-z]")) == true) "ol" else "ul"
                        if (currentListTag == null || currentListTag != desiredTag) {
                            openList(desiredTag, paragraph.listLevel.coerceAtLeast(1))
                        }
                        builder.append(paragraphToListItemHtml(paragraph, linkResolver))
                    } else {
                        closeList()
                        builder.append(paragraphToHtml(paragraph, headingSizes, linkResolver))
                    }
                }

                is ChapterElement.ImageElement -> {
                    closeList()
                    builder.append(imageToHtml(element.image))
                }
            }
        }

        closeList()

        builder.append("</div>")

        return builder.toString()
    }

    private fun paragraphToHtml(
        paragraph: PdfParagraph,
        headingSizes: List<Float>,
        linkResolver: (LinkTarget) -> String?
    ): String {
        val alignmentStyle = when (paragraph.alignment) {
            "center" -> "text-align: center;"
            "right" -> "text-align: right;"
            "justify" -> "text-align: justify;"
            else -> "text-align: left;"
        }
        val inlineContent = buildInlineHtml(paragraph.runs, linkResolver).ifBlank { cleanText(paragraph.text) }

        return when (paragraph.type) {
            ParagraphType.HEADING -> {
                val level = determineHeadingLevel(paragraph.fontSize, headingSizes).coerceAtLeast(2).coerceAtMost(6)
                "<h$level class=\"heading level-$level\" style=\"$alignmentStyle\">$inlineContent</h$level>"
            }
            ParagraphType.NORMAL -> {
                val indentStyle = if (paragraph.indent > 0f) "text-indent: ${paragraph.indent}px;" else ""
                "<p class=\"paragraph\" style=\"$alignmentStyle $indentStyle\">$inlineContent</p>"
            }
            ParagraphType.LIST_ITEM -> {
                val indentStyle = if (paragraph.indent > 0f) "text-indent: ${paragraph.indent}px;" else ""
                "<p class=\"paragraph\" style=\"$alignmentStyle $indentStyle\">$inlineContent</p>"
            }
        }
    }

    private fun paragraphToListItemHtml(
        paragraph: PdfParagraph,
        linkResolver: (LinkTarget) -> String?
    ): String {
        val inlineContent = buildInlineHtml(paragraph.runs, linkResolver).ifBlank { cleanText(paragraph.text) }
        val levelClass = paragraph.listLevel.coerceAtLeast(1)
        return "<li class=\"list-item level-$levelClass\">$inlineContent</li>"
    }

    private fun imageToHtml(image: PdfImage): String {
        return """
            <figure class="embedded-image">
                <img src="${escapeHtmlAttribute(image.fileName)}" alt="${escapeHtmlAttribute(cleanText(image.altText))}" />
            </figure>
        """.trimIndent()
    }

    private fun buildInlineHtml(
        runs: List<PdfTextRun>,
        linkResolver: (LinkTarget) -> String?
    ): String {
        if (runs.isEmpty()) return ""
        return runs.joinToString("") { run ->
            val classes = mutableListOf<String>()
            if (run.isBold) classes.add("bold")
            if (run.isItalic) classes.add("italic")
            val classAttr = if (classes.isNotEmpty()) """ class="${classes.joinToString(" ")}"""" else ""
            val fontFamily = run.fontName.replace("\"", "").replace("'", "")
            val styleAttr = """ style="font-size: ${"%.2f".format(run.fontSize)}pt; font-family: '$fontFamily';" """
            val span = "<span$classAttr$styleAttr>${cleanText(run.text)}</span>"
            val targetHref = run.linkTarget?.let(linkResolver)
            if (targetHref.isNullOrBlank()) {
                span
            } else {
                val extraAttrs = when (run.linkTarget) {
                    is LinkTarget.External -> """ target="_blank" rel="noopener noreferrer""""
                    else -> ""
                }
                """<a href="${escapeHtmlAttribute(targetHref)}"$extraAttrs>$span</a>"""
            }
        }
    }

    private fun escapeHtmlAttribute(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
    
    private fun createContentOpf(
        chapters: List<EpubChapter>,
        imageResources: List<PdfImageResource>,
        originalFileName: String,
        title: String,
        stableId: String
    ): String {
        val manifestBuilder = StringBuilder()
        val spineBuilder = StringBuilder()
        
        // Add CSS to manifest
        manifestBuilder.append("""
            <item id="style" href="style.css" media-type="text/css"/>
        """.trimIndent())

        imageResources.forEachIndexed { index, resource ->
            val itemId = "img${index + 1}"
            val mediaType = when (resource.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "webp" -> "image/webp"
                else -> "image/png"
            }
            manifestBuilder.append("""
                <item id="$itemId" href="${resource.image.fileName}" media-type="$mediaType"/>
            """.trimIndent())
        }
        
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
                    <dc:creator>AnyConvert</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:identifier id="uid">urn:uuid:$stableId</dc:identifier>
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
    
    private fun createTocNcx(chapters: List<EpubChapter>, title: String, stableId: String): String {
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
                    <meta name="dtb:uid" content="urn:uuid:$stableId"/>
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
                color: #1f2633;
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
            
            h1, h2, h3, h4, h5, h6 {
                page-break-after: avoid;
                page-break-inside: avoid;
                color: #2c3e50;
                font-family: 'Georgia', serif;
            }
            
            p {
                page-break-inside: avoid;
                orphans: 2;
                widows: 2;
                margin: 0 0 1em 0;
            }

            .paragraph {
                margin: 0 0 1.2em 0;
                text-indent: 1.2em;
            }
            
            .list {
                margin: 1em 0 1em 1.4em;
                padding-left: 1.2em;
            }

            .list-item {
                margin: 0.4em 0;
            }

            .list.level-1 { list-style-type: disc; }
            .list.level-2 { list-style-type: circle; }
            .list.level-3 { list-style-type: square; }
            ol.list { list-style-type: decimal; }

            span.bold {
                font-weight: 700;
            }

            span.italic {
                font-style: italic;
            }

            .heading {
                font-weight: 700;
                color: #2c3e50;
                margin: 1.2em 0 0.6em 0;
                page-break-after: avoid;
            }

            .embedded-image {
                margin: 2em 0;
                text-align: center;
                page-break-inside: avoid;
            }

            .embedded-image img {
                max-width: 100%;
                height: auto;
                border-radius: 8px;
                box-shadow: 0 4px 18px rgba(44, 62, 80, 0.15);
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
                font-size: 0.95em;
            }
            
            th, td {
                border: 1px solid #ddd;
                padding: 8px;
                text-align: left;
            }
            
            th {
                background-color: #f2f2f2;
                font-weight: bold;
                color: #2c3e50;
            }
            
            /* Print-specific styles */
            @media print {
                body {
                    font-size: 11pt;
                }
                
                .chapter-title {
                    font-size: 1.6em;
                }
                
                .heading,
                h2, h3, h4, h5, h6 {
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

    private fun addZipEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path)
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun convertPdfToDocx(pdfFile: File, context: Context, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f) // Start conversion
        
        val tempDir = File(context.cacheDir, "pdf_docx_conversion_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            progressCallback(0.2f) // Starting PDF extraction
            val extraction = extractPdfContentWithFormatting(pdfFile)
            val pdfPages = extraction.pages
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
            val extraction = extractPdfContentWithFormatting(pdfFile)
            val pdfPages = extraction.pages
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
            val extraction = extractPdfContentWithFormatting(pdfFile)
            val pdfPages = extraction.pages
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

    /** Renders each PDF page to an image (JPG or PNG) and returns a zip containing all images. */
    fun convertPdfToImages(pdfFile: File, context: Context, targetFormat: FileFormat, progressCallback: (Float) -> Unit = { _ -> }): File {
        progressCallback(0.1f)
        val zipFile = File(context.cacheDir, "${pdfFile.nameWithoutExtension}_pages.zip")
        val ext = when (targetFormat) {
            FileFormat.JPG, FileFormat.JPEG -> "jpg"
            FileFormat.PNG -> "png"
            else -> "png"
        }
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount
                if (pageCount == 0) throw IOException("PDF has no pages")
                ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                    for (i in 0 until pageCount) {
                        progressCallback(0.2f + 0.7f * (i + 1) / pageCount)
                        renderer.openPage(i).use { page ->
                            val width = page.width
                            val height = page.height
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val entry = ZipEntry("page_${i + 1}.$ext")
                            zipOut.putNextEntry(entry)
                            if (ext == "jpg") {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, zipOut)
                            } else {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zipOut)
                            }
                            zipOut.closeEntry()
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        progressCallback(1.0f)
        return zipFile
    }

    private fun createDocxFromPdfPages(pdfPages: List<PdfPage>, outputFile: File) {
        val docx = org.apache.poi.xwpf.usermodel.XWPFDocument()
        val numbering = docx.createNumbering()

        val bulletAbstract = CTAbstractNum.Factory.newInstance().apply {
            abstractNumId = BigInteger.ZERO
            addNewLvl().apply {
                ilvl = BigInteger.ZERO
                addNewNumFmt().setVal(STNumberFormat.BULLET)
                addNewLvlText().setVal("•")
                addNewLvlJc().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc.LEFT)
                addNewPPr().addNewInd().left = BigInteger.valueOf(720)
            }
        }
        val bulletNumId = numbering.addNum(numbering.addAbstractNum(XWPFAbstractNum(bulletAbstract)))

        val decimalAbstract = CTAbstractNum.Factory.newInstance().apply {
            abstractNumId = BigInteger.ONE
            addNewLvl().apply {
                ilvl = BigInteger.ZERO
                addNewNumFmt().setVal(STNumberFormat.DECIMAL)
                addNewLvlText().setVal("%1.")
                addNewLvlJc().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc.LEFT)
                addNewPPr().addNewInd().left = BigInteger.valueOf(720)
            }
        }
        val decimalNumId = numbering.addNum(numbering.addAbstractNum(XWPFAbstractNum(decimalAbstract)))
        
        for (page in pdfPages) {
            if (page.pageNumber > 1) {
                val pageBreakPara = docx.createParagraph()
                pageBreakPara.setPageBreak(true)
            }
            val headingSizes = page.paragraphs.filter { it.type == ParagraphType.HEADING }
                .map { it.fontSize }
                .distinct()
                .sortedDescending()

            page.paragraphs.sortedByDescending { it.yPosition }.forEach { paraData ->
                val para = docx.createParagraph()
                para.alignment = when (paraData.alignment.lowercase()) {
                    "center" -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
                    "right" -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT
                    "justify" -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH
                    else -> org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT
                }

                when (paraData.type) {
                    ParagraphType.HEADING -> {
                        val level = determineHeadingLevel(paraData.fontSize, headingSizes).coerceAtLeast(1).coerceAtMost(6)
                        para.style = "Heading$level"
                        para.setSpacingAfter(200)
                    }
                    ParagraphType.LIST_ITEM -> {
                        val numId = if (paraData.isOrderedList) decimalNumId else bulletNumId
                        para.setNumID(numId)
                        para.setNumILvl(BigInteger.valueOf((paraData.listLevel - 1).coerceAtLeast(0).toLong()))
                        para.setSpacingAfter(80)
                    }
                    ParagraphType.NORMAL -> {
                        if (paraData.indent > 0f) {
                            para.setIndentationFirstLine((paraData.indent * 20).toInt())
                        } else {
                            para.setIndentationFirstLine(360)
                        }
                        para.setSpacingAfter(120)
                    }
                }

                para.setSpacingLineRule(org.apache.poi.xwpf.usermodel.LineSpacingRule.AUTO)
                para.setSpacingBetween(1.15)

                val runs = if (paraData.runs.isNotEmpty()) paraData.runs else listOf(
                    PdfTextRun(
                        text = paraData.text,
                        fontSize = paraData.fontSize,
                        fontName = paraData.fontName,
                        isBold = paraData.isBold,
                        isItalic = paraData.isItalic
                    )
                )

                runs.forEach { runData ->
                    if (runData.text.isBlank()) return@forEach
                    val hyperlinkRun = when (val target = runData.linkTarget) {
                        is LinkTarget.External -> para.createHyperlinkRun(target.uri)
                        else -> null
                    }
                    val run = hyperlinkRun ?: para.createRun()
                    run.setText(runData.text)
                    run.setFontSize(runData.fontSize.toInt().coerceAtLeast(8))
                    run.setBold(runData.isBold)
                    run.setItalic(runData.isItalic)
                    run.setFontFamily(resolveFontFamily(runData.fontName))
                    if (hyperlinkRun != null) {
                        hyperlinkRun.color = "0563C1"
                        hyperlinkRun.underline = org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE
                    }
                }
            }
        }
        FileOutputStream(outputFile).use { docx.write(it) }
        docx.close()
    }

    private fun resolveFontFamily(fontName: String): String {
        return when {
            fontName.contains("arial", true) -> "Arial"
            fontName.contains("times", true) -> "Times New Roman"
            fontName.contains("calibri", true) -> "Calibri"
            fontName.contains("helvetica", true) -> "Helvetica"
            fontName.contains("courier", true) -> "Courier New"
            else -> "Times New Roman"
        }
    }

    private fun createRtfFromPdfPages(pdfPages: List<PdfPage>, outputFile: File) {
        val rtfBuilder = StringBuilder()
        rtfBuilder.append("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times New Roman;}}\n")
        rtfBuilder.append("\\f0\\fs24\n")

        pdfPages.forEach { page ->
            val headingSizes = page.paragraphs.filter { it.type == ParagraphType.HEADING }
                .map { it.fontSize }
                .distinct()
                .sortedDescending()

            page.paragraphs.sortedByDescending { it.yPosition }.forEach { para ->
                when (para.type) {
                    ParagraphType.HEADING -> {
                        val level = determineHeadingLevel(para.fontSize, headingSizes)
                        val size = when (level) {
                            1 -> 40
                            2 -> 36
                            3 -> 32
                            4 -> 28
                            else -> 26
                        }
                        rtfBuilder.append("{\\b\\fs$size ${buildRtfRuns(para.runs)}}\\par\n")
                    }

                    ParagraphType.LIST_ITEM -> {
                        val marker = if (para.isOrderedList) "${para.listMarker ?: ""} " else "\\bullet "
                        val content = buildRtfRuns(para.runs).ifBlank { escapeRtfText(para.text) }
                        rtfBuilder.append("{\\fs${fontSizeToRtf(para.fontSize)} $marker$content}\\par\n")
                    }

                    ParagraphType.NORMAL -> {
                        val content = buildRtfRuns(para.runs).ifBlank { escapeRtfText(para.text) }
                        rtfBuilder.append("{\\fs${fontSizeToRtf(para.fontSize)} $content}\\par\n")
                    }
                }
            }
            rtfBuilder.append("\\page\n")
        }

        rtfBuilder.append("}")
        outputFile.writeText(rtfBuilder.toString())
    }

    private fun createTextFromPdfPages(pdfPages: List<PdfPage>, outputFile: File) {
        val textBuilder = StringBuilder()
        pdfPages.forEach { page ->
            page.paragraphs.sortedByDescending { it.yPosition }.forEach { para ->
                when (para.type) {
                    ParagraphType.HEADING -> {
                        textBuilder.append("\n\n${para.text.uppercase()}\n")
                        textBuilder.append("-".repeat(para.text.length.coerceAtLeast(3)) + "\n\n")
                    }
                    ParagraphType.LIST_ITEM -> {
                        val prefix = if (para.isOrderedList) "${para.listMarker ?: "-"}" else "•"
                        textBuilder.append("$prefix ${para.text}\n")
                    }
                    ParagraphType.NORMAL -> {
                        textBuilder.append("${para.text}\n\n")
                    }
                }
            }
            textBuilder.append("\n" + "=".repeat(50) + "\n")
        }
        outputFile.writeText(textBuilder.toString())
    }

    private fun fontSizeToRtf(fontSize: Float): Int = (fontSize * 2).toInt().coerceAtLeast(16)

    private fun buildRtfRuns(runs: List<PdfTextRun>): String {
        if (runs.isEmpty()) return ""
        val builder = StringBuilder()
        runs.forEach { run ->
            val sizeTag = "\\fs${fontSizeToRtf(run.fontSize)}"
            val boldTag = if (run.isBold) "\\b" else ""
            val italicTag = if (run.isItalic) "\\i" else ""
            val resetBold = if (run.isBold) "\\b0" else ""
            val resetItalic = if (run.isItalic) "\\i0" else ""
            builder.append("$boldTag$italicTag$sizeTag ${escapeRtfText(run.text)}$resetItalic$resetBold ")
        }
        return builder.toString().trim()
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