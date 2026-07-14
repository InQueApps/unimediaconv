package ab.inque.converter

import org.jsoup.Jsoup

/**
 * Parses fetched HTML for direct media/PDF URLs (link import).
 * YouTube stream extraction stays in [MainActivity] (player-response JSON).
 */
object LinkPageMediaExtractor {

    fun isYouTubeWatchUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/watch") || lower.contains("youtu.be/")
    }

    fun looksLikeDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/") ||
            lower.contains("youtube.com/embed") || lower.contains("vimeo.com/")
        ) return false
        val path = try {
            java.net.URI(lower).path ?: lower
        } catch (_: Exception) {
            lower.substringBefore('?')
        }
        return path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3") ||
            path.endsWith(".m4a") || path.endsWith(".ogg") || path.endsWith(".wav") ||
            path.endsWith(".flac") || path.endsWith(".avi") || path.endsWith(".mov")
    }

    /**
     * Returns (url, mimeType?) for embeddable direct media, or null.
     * Skips YouTube pages — caller handles those separately.
     */
    fun extractDirectMedia(html: String, pageUrl: String): Pair<String, String?>? {
        if (isYouTubeWatchUrl(pageUrl)) return null
        val doc = Jsoup.parse(html, pageUrl)
        val video = doc.select("video").first()
        val videoSrc = video?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: doc.select("video source").first()?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: video?.attr("data-src")?.takeIf { it.isNotBlank() }
        if (!videoSrc.isNullOrBlank() && looksLikeDirectMediaUrl(videoSrc)) return videoSrc to null
        val audio = doc.select("audio").first()
        val audioSrc = audio?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: doc.select("audio source").first()?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: audio?.attr("data-src")?.takeIf { it.isNotBlank() }
        if (!audioSrc.isNullOrBlank() && looksLikeDirectMediaUrl(audioSrc)) return audioSrc to null
        val ogVideo = doc.select("meta[property=og:video], meta[property=og:video:url]").first()?.attr("content")
        if (!ogVideo.isNullOrBlank() && looksLikeDirectMediaUrl(ogVideo)) return ogVideo to null
        val ogAudio = doc.select("meta[property=og:audio]").first()?.attr("content")
        if (!ogAudio.isNullOrBlank() && looksLikeDirectMediaUrl(ogAudio)) return ogAudio to null
        val pdfHref = doc.select("a[href$=.pdf]").first()?.attr("abs:href")
            ?: doc.select("embed[type=application/pdf]").first()?.attr("abs:src")
            ?: doc.select("a[href*=.pdf]").first()?.attr("abs:href")
        return pdfHref?.takeIf { it.isNotBlank() }?.let { it to null }
    }
}
