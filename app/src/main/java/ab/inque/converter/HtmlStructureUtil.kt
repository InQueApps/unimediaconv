package ab.inque.converter

import org.jsoup.Jsoup

/** Block-aware HTML text extraction shared by doc export paths. */
object HtmlStructureUtil {

    fun linesFromHtml(html: String): List<String> {
        val doc = Jsoup.parse(html)
        doc.select("br").append("\\n")
        doc.select("p, div, h1, h2, h3, h4, h5, h6, li, tr").append("\\n")
        return doc.text()
            .replace("\\n", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun plainTextFromHtml(html: String): String = linesFromHtml(html).joinToString("\n\n")
}
