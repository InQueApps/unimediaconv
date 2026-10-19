package ab.inque.converter

/**
 * Shared RTF escaping and plain-text extraction utilities used across document converters.
 */
object RtfUtil {

    private val IGNORED_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
        "fldinst", "generator", "themedata", "colorschememapping", "latentstyles",
        "datastore", "listtable", "listoverridetable", "rsidtbl", "mmathPr", "shppict", "nonshppict"
    )

    fun escapeRtfText(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '{' -> sb.append("\\{")
                '}' -> sb.append("\\}")
                '\n' -> sb.append("\\par ")
                '\r' -> {}
                in '\u0000'..'\u007f' -> sb.append(c)
                else -> sb.append("\\u").append(c.code).append("?")
            }
        }
        return sb.toString()
    }

    /**
     * Extracts readable plain text from an RTF document string, skipping metadata/font/style groups
     * and decoding Unicode (`\uN?`) and hex (`\'hh`) escapes.
     */
    fun stripRtf(rtf: String): String {
        if (rtf.isBlank()) return ""
        val trimmed = rtf.trimStart()
        if (!trimmed.startsWith("{\\rtf")) {
            return trimmed.trim()
        }

        val out = StringBuilder()
        val ucStack = ArrayDeque<Int>()
        val skipDepthStack = ArrayDeque<Boolean>()
        var ucSkip = 1
        var skipGroup = false
        var i = 0
        val n = rtf.length

        while (i < n) {
            when (val ch = rtf[i]) {
                '{' -> {
                    ucStack.addLast(ucSkip)
                    skipDepthStack.addLast(skipGroup)
                    i++
                    if (!skipGroup && i < n) {
                        var peek = i
                        while (peek < n && (rtf[peek] == ' ' || rtf[peek] == '\r' || rtf[peek] == '\n')) peek++
                        if (rtf.startsWith("\\*", peek)) {
                            skipGroup = true
                            i = peek + 2
                        } else if (peek < n && rtf[peek] == '\\') {
                            val word = readControlWordName(rtf, peek + 1)
                            if (word in IGNORED_DESTINATIONS) {
                                skipGroup = true
                            }
                        }
                    }
                }
                '}' -> {
                    if (ucStack.isNotEmpty()) ucSkip = ucStack.removeLast()
                    if (skipDepthStack.isNotEmpty()) skipGroup = skipDepthStack.removeLast()
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val next = rtf[i]
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            if (!skipGroup) out.append(next)
                            i++
                        }
                        next == '~' -> {
                            if (!skipGroup) out.append('\u00A0')
                            i++
                        }
                        next == '_' -> {
                            if (!skipGroup) out.append('-')
                            i++
                        }
                        next == '-' || next == '*' -> {
                            i++
                        }
                        next == '\n' || next == '\r' -> {
                            if (!skipGroup) out.append('\n')
                            i++
                        }
                        next == '\'' -> {
                            i++
                            if (i + 1 < n) {
                                val hex = rtf.substring(i, i + 2)
                                val byteVal = hex.toIntOrNull(16)
                                if (byteVal != null && !skipGroup) {
                                    out.append(decodeWindows1252Byte(byteVal))
                                }
                                i += 2
                            }
                        }
                        next.isLetter() -> {
                            val wordStart = i
                            while (i < n && rtf[i].isLetter()) i++
                            val word = rtf.substring(wordStart, i)

                            val paramStart = i
                            if (i < n && (rtf[i] == '-' || rtf[i].isDigit())) {
                                i++
                                while (i < n && rtf[i].isDigit()) i++
                            }
                            val paramStr = rtf.substring(paramStart, i)
                            val hasDelimiterSpace = i < n && rtf[i] == ' '
                            if (hasDelimiterSpace) i++

                            if (word in IGNORED_DESTINATIONS && skipDepthStack.isNotEmpty()) {
                                // If destination control word appears at group start
                                skipGroup = true
                            }

                            if (!skipGroup) {
                                when (word) {
                                    "par", "line", "page", "sect", "row" -> out.append('\n')
                                    "tab", "cell" -> out.append('\t')
                                    "bullet" -> out.append('•')
                                    "endash" -> out.append('–')
                                    "emdash" -> out.append('—')
                                    "lquote" -> out.append('‘')
                                    "rquote" -> out.append('’')
                                    "ldblquote" -> out.append('“')
                                    "rdblquote" -> out.append('”')
                                    "uc" -> {
                                        paramStr.toIntOrNull()?.let { ucSkip = it.coerceAtLeast(0) }
                                    }
                                    "u" -> {
                                        val code = paramStr.toIntOrNull()
                                        if (code != null) {
                                            val unsigned = if (code < 0) code + 65536 else code
                                            out.append(unsigned.toChar())
                                            var toSkip = ucSkip
                                            while (toSkip > 0 && i < n) {
                                                if (rtf.startsWith("\\'", i) && i + 3 < n) {
                                                    i += 4
                                                } else if (rtf[i] != '{' && rtf[i] != '}' && rtf[i] != '\\') {
                                                    i++
                                                } else {
                                                    break
                                                }
                                                toSkip--
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> {
                    i++
                }
                else -> {
                    if (!skipGroup) out.append(ch)
                    i++
                }
            }
        }

        return out.toString()
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun readControlWordName(rtf: String, start: Int): String {
        var idx = start
        while (idx < rtf.length && rtf[idx].isLetter()) idx++
        return rtf.substring(start, idx)
    }

    private fun decodeWindows1252Byte(b: Int): Char = when (b and 0xFF) {
        0x80 -> '\u20AC'
        0x82 -> '\u201A'
        0x83 -> '\u0192'
        0x84 -> '\u201E'
        0x85 -> '\u2026'
        0x86 -> '\u2020'
        0x87 -> '\u2021'
        0x88 -> '\u02C6'
        0x89 -> '\u2030'
        0x8A -> '\u0160'
        0x8B -> '\u2039'
        0x8C -> '\u0152'
        0x8E -> '\u017D'
        0x91 -> '\u2018'
        0x92 -> '\u2019'
        0x93 -> '\u201C'
        0x94 -> '\u201D'
        0x95 -> '\u2022'
        0x96 -> '\u2013'
        0x97 -> '\u2014'
        0x98 -> '\u02DC'
        0x99 -> '\u2122'
        0x9A -> '\u0161'
        0x9B -> '\u203A'
        0x9C -> '\u0153'
        0x9E -> '\u017E'
        0x9F -> '\u0178'
        else -> (b and 0xFF).toChar()
    }
}
