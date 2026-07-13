package ab.inque.converter.pipeline

import ab.inque.converter.FileFormat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Detects [FileFormat] from magic bytes when the file extension is missing or wrong.
 */
object FormatSniffer {

    fun sniff(file: File): FileFormat? {
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            return sniffBytes(header, read)
        }
    }

    fun sniffStream(input: InputStream): FileFormat? {
        val header = ByteArray(12)
        val read = input.read(header)
        return sniffBytes(header, read)
    }

    fun sniffBytes(header: ByteArray, read: Int): FileFormat? {
        if (read < 4) return null
        return when {
            header.startsWith("%PDF") -> FileFormat.PDF
            header.startsWith("PK") -> FileFormat.DOCX
            header.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> FileFormat.JPG
            header.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> FileFormat.PNG
            header.startsWith("RIFF".toByteArray()) && read >= 12 &&
                header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) -> FileFormat.WAV
            header.startsWith("GIF8".toByteArray()) -> FileFormat.GIF
            header.startsWith("ID3".toByteArray()) -> FileFormat.MP3
            header.startsWith("fLaC".toByteArray()) -> FileFormat.FLAC
            read >= 8 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> FileFormat.MP4
            header.startsWith("BM".toByteArray()) -> FileFormat.BMP
            else -> null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.startsWith(text: String): Boolean = startsWith(text.toByteArray())
}
