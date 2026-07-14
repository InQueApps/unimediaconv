package ab.inque.converter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPageMediaExtractorTest {

    @Test
    fun extractDirectMedia_videoTag_returnsMp4Url() {
        val html = """
            <html><body>
            <video src="/media/clip.mp4"></video>
            </body></html>
        """.trimIndent()
        val result = LinkPageMediaExtractor.extractDirectMedia(html, "https://example.com/page")
        assertNotNull(result)
        assertEquals("https://example.com/media/clip.mp4", result!!.first)
    }

    @Test
    fun extractDirectMedia_ogVideo_returnsUrl() {
        val html = """
            <html><head>
            <meta property="og:video" content="https://cdn.example.org/trailer.webm" />
            </head></html>
        """.trimIndent()
        val result = LinkPageMediaExtractor.extractDirectMedia(html, "https://example.com/v")
        assertNotNull(result)
        assertTrue(result!!.first.endsWith(".webm"))
    }

    @Test
    fun extractDirectMedia_pdfLink_returnsHref() {
        val html = """<html><body><a href="/docs/report.pdf">PDF</a></body></html>"""
        val result = LinkPageMediaExtractor.extractDirectMedia(html, "https://example.com")
        assertEquals("https://example.com/docs/report.pdf", result?.first)
    }

    @Test
    fun extractDirectMedia_youtubePage_returnsNull() {
        val html = """<html><body><video src="https://youtube.com/embed/x"></video></body></html>"""
        val result = LinkPageMediaExtractor.extractDirectMedia(
            html,
            "https://www.youtube.com/watch?v=abc"
        )
        assertNull(result)
    }

    @Test
    fun looksLikeDirectMediaUrl_rejectsWatchPages() {
        assertFalse(LinkPageMediaExtractor.looksLikeDirectMediaUrl("https://www.youtube.com/watch?v=x"))
        assertFalse(LinkPageMediaExtractor.looksLikeDirectMediaUrl("https://vimeo.com/123"))
    }

    @Test
    fun looksLikeDirectMediaUrl_acceptsFileExtensions() {
        assertTrue(LinkPageMediaExtractor.looksLikeDirectMediaUrl("https://cdn.example.com/file.mp4"))
        assertTrue(LinkPageMediaExtractor.looksLikeDirectMediaUrl("https://host.org/audio.mp3"))
    }
}
