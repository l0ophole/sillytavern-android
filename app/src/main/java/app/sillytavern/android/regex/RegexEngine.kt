package app.sillytavern.android.regex

import java.util.regex.Matcher
import java.util.regex.Pattern

data class RegexParams(
    val isMarkdown: Boolean = false,
    val isPrompt: Boolean = false,
    val isEdit: Boolean = false,
    val depth: Int? = null,
    /** Macro expander (`{{user}}` etc.); null leaves macros as they are. */
    val macros: ((String) -> String)? = null,
)

/** Port of SillyTavern's `getRegexedString` / `runRegexScript`. Never throws; bad scripts are skipped. */
object RegexEngine {
    private const val MAX_INPUT = 1_000_000
    private const val BUDGET_NANOS = 250_000_000L

    private class Compiled(val pattern: Pattern, val global: Boolean, val groupNames: Map<String, String>)

    private class Timeout : RuntimeException(null, null, false, false)

    /** CharSequence that aborts matching once the time budget is spent (guards catastrophic backtracking). */
    private class Budgeted(val s: String) : CharSequence {
        private val deadline = System.nanoTime() + BUDGET_NANOS
        private var n = 0
        override val length get() = s.length
        override fun get(index: Int): Char {
            if ((++n and 0x3FFF) == 0 && System.nanoTime() > deadline) throw Timeout()
            return s[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.subSequence(startIndex, endIndex)
        override fun toString() = s
    }

    fun apply(text: String, placement: Int, scripts: List<RegexScript>, p: RegexParams = RegexParams()): String {
        if (text.isEmpty() || scripts.isEmpty()) return text
        var out = text
        for (sc in scripts) {
            if (!applies(sc, placement, p)) continue
            out = run(sc, out, p)
        }
        return out
    }

    private fun applies(sc: RegexScript, placement: Int, p: RegexParams): Boolean {
        val kind = (sc.markdownOnly && p.isMarkdown) || (sc.promptOnly && p.isPrompt) ||
            (!sc.markdownOnly && !sc.promptOnly && !p.isMarkdown && !p.isPrompt)
        if (!kind) return false
        if (p.isEdit && !sc.runOnEdit) return false
        val d = p.depth
        if (d != null) {
            if (sc.minDepth != null && sc.minDepth >= -1 && d < sc.minDepth) return false
            if (sc.maxDepth != null && sc.maxDepth >= 0 && d > sc.maxDepth) return false
        }
        return placement in sc.placement
    }

    private val macroRe = Regex("""\{\{[^{}]*\}\}""")

    fun run(sc: RegexScript, input: String, p: RegexParams): String {
        if (sc.disabled || sc.findRegex.isEmpty() || input.isEmpty() || input.length > MAX_INPUT) return input
        val expand = p.macros ?: { it }
        val pattern = when (sc.substituteRegex) {
            1 -> expand(sc.findRegex)
            2 -> macroRe.replace(sc.findRegex) { escape(expand(it.value)) }
            else -> sc.findRegex
        }
        val c = compileFull(pattern) ?: return input
        return try {
            replace(c, input, sc, expand)
        } catch (e: Timeout) { input } catch (e: RuntimeException) { input }
    }

    private fun escape(s: String) = s.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\/]""")) { "\\" + it.value }

    private fun replace(c: Compiled, input: String, sc: RegexScript, expand: (String) -> String): String {
        val m: Matcher = c.pattern.matcher(Budgeted(input))
        val sb = StringBuilder()
        var last = 0
        val rep = sc.replaceString.replace(Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE), Regex.escapeReplacement("\$0"))
        val trims = sc.trimStrings.map { expand(it) }.filter { it.isNotEmpty() }
        while (m.find()) {
            sb.append(input, last, m.start())
            val withGroups = Regex("""\$(\d+)|\$<([^>]+)>""").replace(rep) { g ->
                val value: String? = when {
                    g.groupValues[1].isNotEmpty() -> g.groupValues[1].toInt().let { n -> if (n <= m.groupCount()) m.group(n) else null }
                    else -> c.groupNames[g.groupValues[2]]?.let { jn -> runCatching { m.group(jn) }.getOrNull() }
                }
                if (value.isNullOrEmpty()) "" else trims.fold(value) { acc, t -> acc.replace(t, "") }
            }
            sb.append(expand(withGroups))
            last = m.end()
            if (!c.global) break
        }
        sb.append(input, last.coerceAtMost(input.length), input.length)
        return sb.toString()
    }

    private val cache = object : LinkedHashMap<String, Compiled?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Compiled?>) = size > 500
    }

    /** `/pattern/flags` or a bare pattern; null if invalid. */
    fun compile(findRegex: String): Pattern? = compileFull(findRegex)?.pattern

    @Synchronized private fun compileFull(findRegex: String): Compiled? =
        if (cache.containsKey(findRegex)) cache[findRegex] else build(findRegex).also { cache[findRegex] = it }

    private fun build(src: String): Compiled? {
        val m = Regex("^/(.+)/([a-zA-Z]*)$", RegexOption.DOT_MATCHES_ALL).find(src)
        val (body, flags) = if (m != null && m.groupValues[2].all { it in "gmixXsuUAJy" } && m.groupValues[2].toSet().size == m.groupValues[2].length)
            m.groupValues[1] to m.groupValues[2] else src to ""
        var f = 0
        if ('i' in flags) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if ('m' in flags) f = f or Pattern.MULTILINE
        if ('s' in flags) f = f or Pattern.DOTALL
        // Java group names are alphanumeric only; JS allows underscores.
        val names = LinkedHashMap<String, String>()
        var idx = 0
        var translated = Regex("""\(\?<([A-Za-z_][A-Za-z0-9_]*)>""").replace(body) {
            val jn = "g${idx++}n"; names[it.groupValues[1]] = jn; "(?<$jn>"
        }
        translated = Regex("""\\k<([A-Za-z_][A-Za-z0-9_]*)>""").replace(translated) { "\\k<${names[it.groupValues[1]] ?: it.groupValues[1]}>" }
        return runCatching { Compiled(Pattern.compile(translated, f), 'g' in flags, names) }.getOrNull()
    }
}
