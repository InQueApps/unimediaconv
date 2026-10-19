package ab.inque.converter.pipeline

import ab.inque.converter.FileFormat
import java.io.File

/**
 * Resolves [FileFormat] from filename and optional file content sniffing.
 */
object FormatResolver {

    fun fromExtension(fileName: String): FileFormat? {
        val ext = fileName.substringAfterLast('.', "")
        if (ext.isBlank()) return null
        return FileFormat.fromExtension(ext)
    }

    fun fromFile(file: File): FileFormat? =
        fromExtension(file.name) ?: FormatSniffer.sniff(file)

    fun fromFileNameOrSniff(fileName: String, sniffFile: File?): FileFormat? {
        fromExtension(fileName)?.let { return it }
        sniffFile?.let { FormatSniffer.sniff(it) }?.let { return it }
        return null
    }
}
