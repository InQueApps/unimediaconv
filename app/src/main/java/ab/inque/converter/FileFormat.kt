package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import android.content.Context

enum class FileFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val category: FormatCategory
) {
    // Document Formats
    PDF("PDF Document", "pdf", "application/pdf", FormatCategory.DOCUMENT),
    EPUB("EPUB Book", "epub", "application/epub+zip", FormatCategory.DOCUMENT),
    DOCX("Word Document", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", FormatCategory.DOCUMENT),
    TXT("Text File", "txt", "text/plain", FormatCategory.DOCUMENT),
    RTF("Rich Text Format", "rtf", "application/rtf", FormatCategory.DOCUMENT),
    HTML("HTML Document", "html", "text/html", FormatCategory.DOCUMENT),
    
    // Image Formats
    JPG("JPEG Image", "jpg", "image/jpeg", FormatCategory.IMAGE),
    JPEG("JPEG Image", "jpeg", "image/jpeg", FormatCategory.IMAGE),
    PNG("PNG Image", "png", "image/png", FormatCategory.IMAGE),
    WEBP("WebP Image", "webp", "image/webp", FormatCategory.IMAGE),
    HEIC("HEIC Image", "heic", "image/heic", FormatCategory.IMAGE),
    BMP("Bitmap Image", "bmp", "image/bmp", FormatCategory.IMAGE),
    TIFF("TIFF Image", "tiff", "image/tiff", FormatCategory.IMAGE),
    
    // Video Formats
    MP4("MP4 Video", "mp4", "video/mp4", FormatCategory.VIDEO),
    AVI("AVI Video", "avi", "video/x-msvideo", FormatCategory.VIDEO),
    MOV("MOV Video", "mov", "video/quicktime", FormatCategory.VIDEO),
    MKV("MKV Video", "mkv", "video/x-matroska", FormatCategory.VIDEO),
    WEBM("WebM Video", "webm", "video/webm", FormatCategory.VIDEO),
    THREEGP("3GP Video", "3gp", "video/3gpp", FormatCategory.VIDEO),
    GIF("GIF Animation", "gif", "image/gif", FormatCategory.VIDEO),
    
    // Audio Formats
    MP3("MP3 Audio", "mp3", "audio/mpeg", FormatCategory.AUDIO),
    WAV("WAV Audio", "wav", "audio/wav", FormatCategory.AUDIO),
    M4A("M4A Audio", "m4a", "audio/mp4", FormatCategory.AUDIO),
    OGG("OGG Audio", "ogg", "audio/ogg", FormatCategory.AUDIO),
    FLAC("FLAC Audio", "flac", "audio/flac", FormatCategory.AUDIO);

    companion object {
        fun fromExtension(extension: String): FileFormat? {
            val ext = extension.lowercase()
            if (ext == "heif") return HEIC
            if (ext == "htm") return HTML
            if (ext == "tif") return TIFF
            if (ext == "m4v") return MP4
            if (ext == "aac") return M4A
            return entries.find { it.extension.equals(extension, ignoreCase = true) }
        }
        
        fun fromMimeType(mimeType: String): FileFormat? {
            return entries.find { it.mimeType.equals(mimeType, ignoreCase = true) }
        }
        
        fun getFormatsByCategory(category: FormatCategory): List<FileFormat> {
            return entries.filter { it.category == category }
        }

        /** Targets reachable from [fromFormat] on this device (planner-driven). */
        fun getSupportedConversions(fromFormat: FileFormat, context: Context): List<FileFormat> {
            return ConversionPlanner.reachableTargets(fromFormat, FormatCapabilities.from(context))
        }

        /** @see getSupportedConversions with full capabilities (tests). */
        fun getSupportedConversions(fromFormat: FileFormat, caps: FormatCapabilities): List<FileFormat> {
            return ConversionPlanner.reachableTargets(fromFormat, caps)
        }

        /** Legacy entry without context — assumes all capabilities available. */
        fun getSupportedConversions(fromFormat: FileFormat): List<FileFormat> {
            return getSupportedConversions(fromFormat, FormatCapabilities.ALL)
        }
    }
}

enum class FormatCategory {
    DOCUMENT,
    IMAGE,
    VIDEO,
    AUDIO
}
