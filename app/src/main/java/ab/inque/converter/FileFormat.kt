package ab.inque.converter

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
    
    // Image Formats
    JPG("JPEG Image", "jpg", "image/jpeg", FormatCategory.IMAGE),
    JPEG("JPEG Image", "jpeg", "image/jpeg", FormatCategory.IMAGE),
    PNG("PNG Image", "png", "image/png", FormatCategory.IMAGE),
    WEBP("WebP Image", "webp", "image/webp", FormatCategory.IMAGE),
    BMP("Bitmap Image", "bmp", "image/bmp", FormatCategory.IMAGE),
    TIFF("TIFF Image", "tiff", "image/tiff", FormatCategory.IMAGE),
    
    // Video Formats
    MP4("MP4 Video", "mp4", "video/mp4", FormatCategory.VIDEO),
    AVI("AVI Video", "avi", "video/x-msvideo", FormatCategory.VIDEO),
    MOV("MOV Video", "mov", "video/quicktime", FormatCategory.VIDEO),
    MKV("MKV Video", "mkv", "video/x-matroska", FormatCategory.VIDEO),
    WEBM("WebM Video", "webm", "video/webm", FormatCategory.VIDEO);

    companion object {
        fun fromExtension(extension: String): FileFormat? {
            return values().find { it.extension.equals(extension, ignoreCase = true) }
        }
        
        fun fromMimeType(mimeType: String): FileFormat? {
            return values().find { it.mimeType.equals(mimeType, ignoreCase = true) }
        }
        
        fun getFormatsByCategory(category: FormatCategory): List<FileFormat> {
            return values().filter { it.category == category }
        }
        
        fun getSupportedConversions(fromFormat: FileFormat): List<FileFormat> {
            return when (fromFormat.category) {
                FormatCategory.DOCUMENT -> getFormatsByCategory(FormatCategory.DOCUMENT)
                FormatCategory.IMAGE -> getFormatsByCategory(FormatCategory.IMAGE)
                FormatCategory.VIDEO -> getFormatsByCategory(FormatCategory.VIDEO)
            }.filter { it != fromFormat }
        }
    }
}

enum class FormatCategory {
    DOCUMENT,
    IMAGE,
    VIDEO
} 