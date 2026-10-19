package ab.inque.converter.pipeline

import ab.inque.converter.FileFormat
import ab.inque.converter.FormatCategory

/**
 * Finds shortest single-hop or multi-hop conversion paths between formats.
 * Edges mirror what [ab.inque.converter.UniversalConverter.convertFile] can execute per hop.
 */
object ConversionPlanner {

    private val unsupportedVideoTargets = emptySet<FileFormat>()

    fun findPath(
        source: FileFormat,
        target: FileFormat,
        caps: FormatCapabilities
    ): List<FileFormat>? {
        if (source == target) return listOf(source)
        if (!semanticTargetAllowed(source, target, caps)) return null
        val visited = mutableSetOf(source)
        val queue = ArrayDeque<List<FileFormat>>()
        queue.add(listOf(source))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val current = path.last()
            for (next in directTargets(current, caps)) {
                if (next in visited) continue
                val newPath = path + next
                if (next == target) return newPath
                visited.add(next)
                queue.add(newPath)
            }
        }
        return null
    }

    fun reachableTargets(source: FileFormat, caps: FormatCapabilities): List<FileFormat> =
        FileFormat.entries
            .filter { it != source }
            .filter { semanticTargetAllowed(source, it, caps) }
            .filter { findPath(source, it, caps) != null }

    /** One-hop neighbors for BFS. */
    fun directTargets(from: FileFormat, caps: FormatCapabilities): List<FileFormat> {
        val result = mutableListOf<FileFormat>()
        val same = FileFormat.entries.filter {
            it != from &&
                it.category == from.category &&
                it !in unsupportedVideoTargets &&
                encodeTargetAllowed(it, caps)
        }
        result.addAll(same)

        val images = imageFormats(caps)
        val audios = audioFormats(caps)
        val docs = FileFormat.entries.filter { it.category == FormatCategory.DOCUMENT && it != from }
        val videos = FileFormat.entries.filter {
            it.category == FormatCategory.VIDEO && it !in unsupportedVideoTargets && it != from
        }

        when (from.category) {
            FormatCategory.DOCUMENT -> {
                result.addAll(audios)
                if (from == FileFormat.PDF) result.addAll(images)
                else {
                    // Non-PDF documents reach images via PDF in a second hop (BFS).
                }
                result.addAll(videos)
            }
            FormatCategory.IMAGE -> {
                result.add(FileFormat.PDF)
                if (caps.canOcr) result.addAll(docs)
                result.addAll(audios)
                result.addAll(videos)
            }
            FormatCategory.VIDEO -> {
                result.addAll(audios)
                result.add(FileFormat.PDF)
                result.addAll(images)
                if (caps.canTranscribe) result.addAll(docs)
            }
            FormatCategory.AUDIO -> {
                if (caps.canTranscribe) result.addAll(docs)
                val waveformImages = imageFormats(caps).filter {
                    it != FileFormat.HEIC || caps.canEncodeHeic
                }
                result.addAll(waveformImages)
                result.addAll(videos)
            }
        }

        // GIF → raster treated as video source with image targets
        if (from == FileFormat.GIF) {
            result.addAll(images)
        }

        return result.distinct().filter { it != from }
    }

    private fun encodeTargetAllowed(to: FileFormat, caps: FormatCapabilities): Boolean = when (to) {
        FileFormat.HEIC -> caps.canEncodeHeic
        FileFormat.OGG -> caps.canEncodeOgg
        FileFormat.FLAC -> caps.canEncodeFlac
        else -> true
    }

    /**
     * Audio/video → text documents require on-device STT. Video → PDF (frame pages) does not.
     * Blocks misleading multi-hop routes (e.g. MP3 → waveform PNG → PDF → TXT).
     */
    private fun semanticTargetAllowed(
        source: FileFormat,
        target: FileFormat,
        caps: FormatCapabilities
    ): Boolean {
        if (target.category != FormatCategory.DOCUMENT) return true
        return when (source.category) {
            FormatCategory.AUDIO -> caps.canTranscribe
            FormatCategory.VIDEO -> target == FileFormat.PDF || caps.canTranscribe
            FormatCategory.IMAGE -> target == FileFormat.PDF || caps.canOcr
            else -> true
        }
    }

    private fun imageFormats(caps: FormatCapabilities): List<FileFormat> =
        listOf(
            FileFormat.JPG, FileFormat.JPEG, FileFormat.PNG, FileFormat.WEBP,
            FileFormat.BMP, FileFormat.TIFF
        ) + if (caps.canEncodeHeic) listOf(FileFormat.HEIC) else emptyList()

    private fun audioFormats(caps: FormatCapabilities): List<FileFormat> {
        val base = mutableListOf(FileFormat.MP3, FileFormat.WAV, FileFormat.M4A)
        if (caps.canEncodeOgg) base.add(FileFormat.OGG)
        if (caps.canEncodeFlac) base.add(FileFormat.FLAC)
        return base
    }
}
