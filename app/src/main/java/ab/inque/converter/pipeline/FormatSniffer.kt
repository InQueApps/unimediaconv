package ab.inque.converter.pipeline

import ab.inque.converter.FileFormat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Detects [FileFormat] from magic bytes when the file extension is missing or wrong.
 */
object FormatSniffer {

    private const val SNIFF_HEADER_SIZE = 64

    fun sniff(file: File): FileFormat? {
        if (!file.exists() || !file.isFile) return null
        FileInputStream(file).use { input ->
            return sniffStream(input)
        }
    }

    fun sniffStream(input: InputStream): FileFormat? {
        val header = ByteArray(SNIFF_HEADER_SIZE)
        var totalRead = 0
        while (totalRead < header.size) {
            val n = input.read(header, totalRead, header.size - totalRead)
            if (n <= 0) break
            totalRead += n
        }
        return sniffBytes(header, totalRead)
    }

    fun sniffBytes(header: ByteArray, read: Int): FileFormat? {
        val len = read.coerceAtMost(header.size)
        if (len < 2) return null
        val active = if (len == header.size) header else header.copyOf(len)

        if (active.startsWith("BM")) return FileFormat.BMP

        // MP3 frame sync (can be 2+ bytes) or ID3
        if (active.startsWith("ID3")) return FileFormat.MP3
        val b0 = active[0].toInt() and 0xFF
        val b1 = active[1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x18) != 0x08 && (b1 and 0x06) != 0x00) {
            return FileFormat.MP3
        }

        if (len < 3) return null
        if (active.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return FileFormat.JPG

        if (len < 4) return null

        // Documents & Archives
        if (active.startsWith("%PDF")) return FileFormat.PDF
        if (active.startsWith("{\\rtf")) return FileFormat.RTF
        if (active.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) {
            val ascii = String(active, Charsets.ISO_8859_1)
            return if (ascii.contains("application/epub+zip")) FileFormat.EPUB else FileFormat.DOCX
        }

        // Images
        if (active.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) return FileFormat.PNG
        if (active.startsWith("GIF87a") || active.startsWith("GIF89a") || active.startsWith("GIF8")) return FileFormat.GIF
        if (active.startsWith(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) ||
            active.startsWith(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))
        ) {
            return FileFormat.TIFF
        }

        // RIFF containers: WAV, WEBP, AVI
        if (active.startsWith("RIFF") && len >= 12) {
            val fourCc = String(active, 8, 4, Charsets.US_ASCII)
            when (fourCc) {
                "WAVE" -> return FileFormat.WAV
                "WEBP" -> return FileFormat.WEBP
                "AVI " -> return FileFormat.AVI
            }
        }

        // Audio
        if (active.startsWith("OggS")) return FileFormat.OGG
        if (active.startsWith("fLaC")) return FileFormat.FLAC

        // EBML: MKV or WEBM
        if (active.startsWith(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) {
            val ebmlAscii = String(active, Charsets.ISO_8859_1).lowercase()
            return if (ebmlAscii.contains("webm")) FileFormat.WEBM else FileFormat.MKV
        }

        // ISO BMFF (ftyp at offset 4): HEIC, M4A, MOV, 3GP, MP4
        if (len >= 8 && active.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) {
            if (len >= 12) {
                val brand = String(active, 8, 4, Charsets.US_ASCII)
                val brandLower = brand.lowercase()
                return when {
                    brandLower in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> FileFormat.HEIC
                    brand == "M4A " || brand == "M4B " -> FileFormat.M4A
                    brand == "qt  " -> FileFormat.MOV
                    brandLower.startsWith("3gp") || brandLower.startsWith("3g2") -> FileFormat.THREEGP
                    else -> FileFormat.MP4
                }
            }
            return FileFormat.MP4
        }

        // HTML (check after binary signatures, ignoring leading UTF-8 BOM / whitespace)
        val textPreview = String(active, Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
            .lowercase()
        if (textPreview.startsWith("<!doctype html") ||
            textPreview.startsWith("<html") ||
            textPreview.startsWith("<!--") ||
            textPreview.startsWith("<head") ||
            textPreview.startsWith("<body")
        ) {
            return FileFormat.HTML
        }

        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.startsWith(text: String): Boolean = startsWith(text.toByteArray(Charsets.US_ASCII))
}
