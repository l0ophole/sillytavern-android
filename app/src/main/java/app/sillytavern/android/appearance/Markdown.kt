package app.sillytavern.android.appearance

/** Inline run of text with formatting flags. [quote] marks text inside "double quotes" (SillyTavern colors it). */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val code: Boolean = false,
    val quote: Boolean = false,
    val link: String? = null,
)

sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Items(val ordered: Boolean, val start: Int, val items: List<List<MdSpan>>) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class Image(val url: String, val alt: String) : MdBlock
    data object Rule : MdBlock
}

/**
 * Small markdown + inline-HTML parser for chat messages, following SillyTavern's showdown setup:
 * single newlines are line breaks, `*text*` is italic (ST colors it), `"text"` is a quote run.
 * Raw HTML is limited to inline formatting tags; every other tag is dropped and its content kept.
 * With [allowHtml] false, tags are shown as plain text.
 */
object Markdown {
    fun parse(src: String, allowHtml: Boolean = true): List<MdBlock> = blocks(src.replace("\r\n", "\n").replace('\r', '\n').split('\n'), allowHtml)

    private val fence = Regex("^\\s*(`{3,}|~{3,})\\s*([\\w+#.-]*)\\s*$")
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val numbered = Regex("^\\s*(\\d{1,9})[.)]\\s+(.*)$")
    private val image = Regex("^\\s*!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")
    private val quoteLine = Regex("^\\s{0,3}>\\s?(.*)$")

    private fun blocks(lines: List<String>, html: Boolean): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var i = 0
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) { out += MdBlock.Paragraph(inline(para.joinToString("\n"), html)); para.clear() }
        }
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.find(line)
            when {
                f != null -> {
                    flush()
                    val mark = f.groupValues[1]
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !(lines[i].trim().startsWith(mark) && lines[i].trim().all { it == mark[0] })) { body += lines[i]; i++ }
                    out += MdBlock.Code(f.groupValues[2], body.joinToString("\n"))
                }
                line.isBlank() -> flush()
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                heading.matches(line) -> { flush(); val m = heading.find(line)!!; out += MdBlock.Heading(m.groupValues[1].length, inline(m.groupValues[2], html)) }
                image.matches(line) -> { flush(); val m = image.find(line)!!; out += MdBlock.Image(m.groupValues[2], m.groupValues[1]) }
                quoteLine.matches(line) -> {
                    flush()
                    val inner = mutableListOf<String>()
                    while (i < lines.size && quoteLine.matches(lines[i])) { inner += quoteLine.find(lines[i])!!.groupValues[1]; i++ }
                    out += MdBlock.Quote(blocks(inner, html))
                    continue
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    flush()
                    val ordered = numbered.matches(line) && !bullet.matches(line)
                    val start = if (ordered) numbered.find(line)!!.groupValues[1].toInt() else 1
                    val items = mutableListOf<MutableList<String>>()
                    while (i < lines.size) {
                        val l = lines[i]
                        val m = if (ordered) numbered.find(l)?.groupValues?.get(2) else bullet.find(l)?.groupValues?.get(1)
                        if (m != null && !rule.matches(l)) items += mutableListOf(m)
                        else if (l.isNotBlank() && l.startsWith(" ") && items.isNotEmpty()) items.last() += l.trim()
                        else break
                        i++
                    }
                    out += MdBlock.Items(ordered, start, items.map { inline(it.joinToString("\n"), html) })
                    continue
                }
                else -> para += line
            }
            i++
        }
        flush()
        return out
    }

    private val formatTags = mapOf(
        "b" to { s: MdSpan -> s.copy(bold = true) }, "strong" to { s: MdSpan -> s.copy(bold = true) },
        "i" to { s: MdSpan -> s.copy(italic = true) }, "em" to { s: MdSpan -> s.copy(italic = true) },
        "u" to { s: MdSpan -> s.copy(underline = true) }, "ins" to { s: MdSpan -> s.copy(underline = true) },
        "s" to { s: MdSpan -> s.copy(strike = true) }, "del" to { s: MdSpan -> s.copy(strike = true) }, "strike" to { s: MdSpan -> s.copy(strike = true) },
        "code" to { s: MdSpan -> s.copy(code = true) }, "q" to { s: MdSpan -> s.copy(quote = true) },
    )
    private val openTag = Regex("^<([a-zA-Z][a-zA-Z0-9]*)((?:\\s[^<>]*)?)(/?)>")
    private val closeTag = Regex("^</([a-zA-Z][a-zA-Z0-9]*)\\s*>")
    private val hrefAttr = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val punct = "\\`*_{}[]()#+-.!~<>\"|"

    fun inline(src: String, allowHtml: Boolean = true): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        parseInline(src, MdSpan(""), allowHtml, out)
        // merge neighbours with equal style
        val merged = mutableListOf<MdSpan>()
        for (s in out) {
            if (s.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) merged[merged.lastIndex] = last.copy(text = last.text + s.text) else merged += s
        }
        return merged
    }

    private fun emit(out: MutableList<MdSpan>, style: MdSpan, text: String) { if (text.isNotEmpty()) out += style.copy(text = text) }

    private fun parseInline(s: String, style: MdSpan, html: Boolean, out: MutableList<MdSpan>) {
        val buf = StringBuilder()
        fun flushBuf() { emit(out, style, buf.toString()); buf.setLength(0) }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // backslash escape
            if (c == '\\' && i + 1 < s.length && s[i + 1] in punct) { buf.append(s[i + 1]); i += 2; continue }
            // code span (no nested formatting)
            if (c == '`') {
                val j = s.indexOf('`', i + 1)
                if (j > i + 1) { flushBuf(); emit(out, style.copy(code = true), s.substring(i + 1, j)); i = j + 1; continue }
            }
            // *** bold italic
            if (s.startsWith("***", i)) {
                val j = s.indexOf("***", i + 3)
                if (j > i + 3) { flushBuf(); parseInline(s.substring(i + 3, j), style.copy(bold = true, italic = true), html, out); i = j + 3; continue }
            }
            if ((s.startsWith("**", i) || s.startsWith("__", i)) && i + 2 < s.length && !s[i + 2].isWhitespace()) {
                val d = s.substring(i, i + 2)
                val j = findClose(s, d, i + 2)
                if (j > 0) { flushBuf(); parseInline(s.substring(i + 2, j), style.copy(bold = true), html, out); i = j + 2; continue }
            }
            if (s.startsWith("~~", i)) {
                val j = s.indexOf("~~", i + 2)
                if (j > i + 2) { flushBuf(); parseInline(s.substring(i + 2, j), style.copy(strike = true), html, out); i = j + 2; continue }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                val boundaryOk = c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()
                if (boundaryOk) {
                    val j = findClose(s, c.toString(), i + 1)
                    if (j > 0 && (c == '*' || j + 1 >= s.length || !s[j + 1].isLetterOrDigit())) {
                        flushBuf(); parseInline(s.substring(i + 1, j), style.copy(italic = true), html, out); i = j + 1; continue
                    }
                }
            }
            // [text](url)
            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                val end = if (close > 0) s.indexOf(')', close + 2) else -1
                if (close > 0 && end > 0 && s.indexOf('\n', i) .let { it < 0 || it > end }) {
                    val url = s.substring(close + 2, end).trim().substringBefore(' ')
                    if (url.isNotEmpty()) { flushBuf(); parseInline(s.substring(i + 1, close), style.copy(link = url), html, out); i = end + 1; continue }
                }
            }
            // "quoted text" (also curly quotes)
            if ((c == '"' || c == '“') && !style.quote) {
                val closer = if (c == '"') '"' else '”'
                val j = s.indexOf(closer, i + 1)
                if (j > i + 1 && s.indexOf('\n', i).let { it < 0 || it > j } ) {
                    flushBuf(); out += style.copy(quote = true, text = c.toString())
                    parseInline(s.substring(i + 1, j), style.copy(quote = true), html, out)
                    out += style.copy(quote = true, text = closer.toString()); i = j + 1; continue
                }
            }
            if (c == '<' && html) {
                val rest = s.substring(i)
                val open = openTag.find(rest)
                if (open != null) {
                    val tag = open.groupValues[1].lowercase()
                    val selfClosing = open.groupValues[3] == "/"
                    when {
                        tag == "br" -> { buf.append('\n'); i += open.value.length; continue }
                        tag == "hr" -> { buf.append('\n'); i += open.value.length; continue }
                        tag in formatTags && !selfClosing -> {
                            val closeIdx = s.indexOf("</$tag>", i + open.value.length, ignoreCase = true)
                            val end = if (closeIdx < 0) s.length else closeIdx
                            flushBuf(); parseInline(s.substring(i + open.value.length, end), formatTags.getValue(tag)(style), html, out)
                            i = if (closeIdx < 0) s.length else closeIdx + tag.length + 3; continue
                        }
                        tag == "a" && !selfClosing -> {
                            val href = hrefAttr.find(open.groupValues[2])?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                            val closeIdx = s.indexOf("</a>", i + open.value.length, ignoreCase = true)
                            val end = if (closeIdx < 0) s.length else closeIdx
                            flushBuf(); parseInline(s.substring(i + open.value.length, end), if (href != null && isSafeUrl(href)) style.copy(link = href) else style, html, out)
                            i = if (closeIdx < 0) s.length else closeIdx + 4; continue
                        }
                        tag in dropWithContent -> {
                            val closeIdx = s.indexOf("</$tag>", i + open.value.length, ignoreCase = true)
                            i = if (closeIdx < 0) s.length else closeIdx + tag.length + 3; continue
                        }
                        else -> { i += open.value.length; continue }   // unknown opening tag: keep content only
                    }
                }
                val close = closeTag.find(rest)
                if (close != null) { i += close.value.length; continue }
            }
            buf.append(c); i++
        }
        flushBuf()
    }

    private val dropWithContent = setOf("script", "style", "iframe", "object", "embed")

    /** Links open in the browser: only web, mail and tel schemes. */
    fun isSafeUrl(url: String): Boolean = url.trim().lowercase().let { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") || it.startsWith("tel:") }

    /** Index of the next [delim] at or after [from] that closes (is not preceded by whitespace and is not part of a longer run). */
    private fun findClose(s: String, delim: String, from: Int): Int {
        var j = from
        while (true) {
            j = s.indexOf(delim, j)
            if (j < 0) return -1
            if (j > from && !s[j - 1].isWhitespace() && s[j - 1] != '\\') {
                // a single delimiter must not be half of a double one
                if (delim.length == 1 && (s.getOrNull(j + 1) == delim[0] || s.getOrNull(j - 1) == delim[0])) { j += 1; continue }
                return j
            }
            j += delim.length
        }
    }
}
