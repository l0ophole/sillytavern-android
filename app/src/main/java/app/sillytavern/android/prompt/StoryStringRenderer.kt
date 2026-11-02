package app.sillytavern.android.prompt

/**
 * Renders SillyTavern context-template story strings: `{{#if var}}..{{/if}}` blocks (nestable),
 * `{{var}}` substitution (missing -> empty) and `{{trim}}` (removes itself plus adjacent newlines).
 * Malformed templates never throw; unbalanced tags are emitted literally.
 */
object StoryStringRenderer {
    private val tag = Regex("""\{\{(#if\s+(\w+)|/if|(\w+))\}\}""")
    private val trim = Regex("""(?:\r?\n)*\{\{trim\}\}(?:\r?\n)*""")
    private const val TRIM_MARK = "\u0000TRIM\u0000"

    fun render(template: String, vars: Map<String, String>): String {
        val out = StringBuilder()
        parse(template, 0, vars, out, emit = true, top = true)
        return out.toString().replace(Regex("(?:\r?\n)*${Regex.escape(TRIM_MARK)}(?:\r?\n)*"), "")
    }

    /** Parses from [from]; returns the index after the matching `{{/if}}` (or end of input at top level). */
    private fun parse(t: String, from: Int, vars: Map<String, String>, out: StringBuilder, emit: Boolean, top: Boolean): Int {
        var pos = from
        while (pos < t.length) {
            val m = tag.find(t, pos)
            if (m == null) {
                if (emit) out.append(t, pos, t.length)
                return t.length
            }
            if (emit) out.append(t, pos, m.range.first)
            val body = m.groupValues[1]
            pos = m.range.last + 1
            when {
                body.startsWith("#if") -> {
                    val truthy = emit && !vars[m.groupValues[2]].isNullOrBlank()
                    if (!hasClosing(t, pos)) { if (emit) out.append(m.value); continue }
                    pos = parse(t, pos, vars, out, truthy, top = false)
                }
                body == "/if" -> {
                    if (!top) return pos
                    if (emit) out.append(m.value) // stray close at top level
                }
                body == "trim" -> if (emit) out.append(TRIM_MARK)
                else -> if (emit) out.append(vars[body].orEmpty())
            }
        }
        return pos
    }

    private fun hasClosing(t: String, from: Int): Boolean {
        var depth = 1
        for (m in tag.findAll(t, from)) {
            if (m.groupValues[1].startsWith("#if")) depth++ else if (m.groupValues[1] == "/if" && --depth == 0) return true
        }
        return false
    }
}
