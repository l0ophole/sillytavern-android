package app.sillytavern.android.macro

sealed interface MacroNode {
    data class Text(val s: String) : MacroNode
    /** [args] are the `::`-separated arguments (each a node list); [raw] is the original `{{...}}` source. */
    data class Call(val name: String, val args: List<List<MacroNode>>, val raw: String) : MacroNode
}

/** Renders nodes back to their source form. */
fun List<MacroNode>.raw(): String = joinToString("") { if (it is MacroNode.Text) it.s else (it as MacroNode.Call).raw }

object MacroParser {
    private val nameRe = Regex("""^\s*[!?~>]*(//|/?[A-Za-z_][A-Za-z0-9_+\-]*)""")

    fun parse(input: String): List<MacroNode> = parseSeq(input, 0, false).first

    /** Returns nodes, end index, and (for call bodies) the segments split at top-level `::`. */
    private class Result(val nodes: List<MacroNode>, val end: Int, val closed: Boolean, val segments: List<List<MacroNode>>)

    private fun parseSeq(s: String, start: Int, inCall: Boolean): Triple<List<MacroNode>, Int, Boolean> {
        val r = scan(s, start, inCall)
        return Triple(r.nodes, r.end, r.closed)
    }

    private fun scan(s: String, start: Int, inCall: Boolean): Result {
        val segments = ArrayList<List<MacroNode>>()
        var cur = ArrayList<MacroNode>()
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { cur.add(MacroNode.Text(text.toString())); text.clear() } }
        var i = start
        while (i < s.length) {
            if (s.startsWith("{{", i)) {
                val body = scan(s, i + 2, true)
                if (body.closed) {
                    flush()
                    cur.add(makeCall(s.substring(i, body.end), body.segments))
                    i = body.end
                    continue
                }
                text.append("{{"); i += 2; continue
            }
            if (inCall && s.startsWith("}}", i)) {
                flush(); segments.add(cur)
                return Result(emptyList(), i + 2, true, segments)
            }
            if (inCall && s.startsWith("::", i)) {
                flush(); segments.add(cur); cur = ArrayList(); i += 2; continue
            }
            text.append(s[i]); i++
        }
        flush()
        if (inCall) return Result(emptyList(), s.length, false, emptyList())
        segments.add(cur)
        return Result(cur, s.length, false, segments)
    }

    private fun makeCall(raw: String, segs: List<List<MacroNode>>): MacroNode {
        val first = segs.firstOrNull().orEmpty()
        val t0 = (first.firstOrNull() as? MacroNode.Text)?.s ?: return MacroNode.Text(raw)
        val m = nameRe.find(t0) ?: return MacroNode.Text(raw)
        val name = m.groupValues[1]
        var rest = t0.substring(m.value.length)
        val args = ArrayList<List<MacroNode>>()
        val tail = first.drop(1)
        if (name == "//") {
            return MacroNode.Call("//", emptyList(), raw)
        }
        val restNodes = ArrayList<MacroNode>()
        if (rest.startsWith(":") ) { // legacy single colon: {{random:a,b}}
            restNodes.add(MacroNode.Text(rest.substring(1)))
            restNodes.addAll(tail)
            args.add(restNodes)
        } else if (rest.isNotEmpty() && rest[0].isWhitespace() || (rest.isEmpty() && tail.isNotEmpty() && false)) {
            val trimmed = rest.trimStart()
            if (trimmed.isNotEmpty()) restNodes.add(MacroNode.Text(trimmed))
            restNodes.addAll(tail)
            if (restNodes.isNotEmpty()) args.add(restNodes)
        } else if (rest.isBlank() && tail.isEmpty()) {
            // plain {{name}}
        } else if (rest.isEmpty() && tail.isNotEmpty()) {
            // {{name{{x}}}} — not a macro
            return MacroNode.Text(raw)
        } else return MacroNode.Text(raw)
        segs.drop(1).forEach { args.add(it) }
        return MacroNode.Call(name, args, raw)
    }
}
