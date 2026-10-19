package ab.inque.converter.preview

import android.graphics.Bitmap
import android.net.Uri

/**
 * Loaded preview payload for the floating preview panel — never a full converted output file.
 */
sealed class PreviewContent {
    data class MediaClip(
        val uri: Uri,
        val mimeType: String,
        val startMs: Long,
        val endMs: Long,
        val speed: Float,
        val audioOnly: Boolean,
        val subtitle: String,
        val disclaimer: String? = null
    ) : PreviewContent()

    data class StillImage(
        val bitmaps: List<Bitmap>,
        val subtitle: String,
        val disclaimer: String? = null
    ) : PreviewContent()

    data class PagedImages(
        val bitmaps: List<Bitmap>,
        val subtitle: String,
        val disclaimer: String? = null
    ) : PreviewContent()

    data class TextSample(
        val body: String,
        val subtitle: String,
        val disclaimer: String? = null
    ) : PreviewContent()

    data class HtmlPage(
        val uri: Uri,
        val subtitle: String,
        val disclaimer: String? = null
    ) : PreviewContent()

    data class Unavailable(
        val message: String
    ) : PreviewContent()
}

enum class PreviewStrategy {
    MEDIA_CLIP,
    STILL_IMAGE,
    VIDEO_FRAME,
    PAGED_DOCUMENT,
    TEXT_SAMPLE,
    HTML_WEB,
    OCR_TEXT,
    WAVEFORM_IMAGE,
    SLIDESHOW_HINT,
    UNAVAILABLE
}

data class PreviewPlan(
    val strategy: PreviewStrategy,
    val subtitle: String,
    val disclaimer: String? = null,
    val audioOnly: Boolean = false,
    val formatDisclaimer: Boolean = false
)
