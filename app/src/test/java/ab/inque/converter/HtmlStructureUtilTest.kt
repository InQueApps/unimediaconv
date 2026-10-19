package ab.inque.converter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlStructureUtilTest {

    @Test
    fun linesFromHtml_preservesParagraphBreaks() {
        val html = """<html><body><h1>Title</h1><p>Line one</p><p>Line two</p></body></html>"""
        val lines = HtmlStructureUtil.linesFromHtml(html)
        assertTrue(lines.any { it.contains("Title") })
        assertTrue(lines.any { it.contains("Line one") })
        assertTrue(lines.any { it.contains("Line two") })
        assertEquals(3, lines.size)
    }

    @Test
    fun plainTextFromHtml_joinsWithBlankLine() {
        val html = "<p>A</p><p>B</p>"
        assertEquals("A\n\nB", HtmlStructureUtil.plainTextFromHtml(html))
    }
}
