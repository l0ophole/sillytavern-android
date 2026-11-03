package app.sillytavern.android.macro

import app.sillytavern.android.chat.MacroContext

object MacroEngine {
    private const val MAX_PASSES = 8
    private val trimRe = Regex("\\s*\\{\\{trim}}\\s*", RegexOption.IGNORE_CASE)

    fun expand(input: String, ctx: MacroContext): String {
        if (input.isEmpty() || !input.contains("{{") && !input.contains('<')) return input
        var s = input.replace("<USER>", "{{user}}").replace("<BOT>", "{{char}}").replace("<GROUP>", "{{group}}")
        repeat(MAX_PASSES) {
            val next = expandPass(s, ctx)
            if (next == s) return finish(s)
            s = next
        }
        return finish(s)
    }

    private fun finish(s: String) = if (s.contains("{{")) trimRe.replace(s, "") else s

    /** One left-to-right evaluation pass (no re-expansion of results). */
    fun expandPass(input: String, ctx: MacroContext): String {
        if (!input.contains("{{")) return input
        return eval(MacroParser.parse(input), ctx)
    }

    private fun isOpen(n: MacroNode, def: MacroDef?) = n is MacroNode.Call && def?.scoped == true

    private fun eval(nodes: List<MacroNode>, ctx: MacroContext): String {
        val sb = StringBuilder()
        var i = 0
        while (i < nodes.size) {
            val n = nodes[i]
            if (n is MacroNode.Text) { sb.append(n.s); i++; continue }
            n as MacroNode.Call
            if (n.name.startsWith("/") && n.name != "//") { sb.append(n.raw); i++; continue }
            val def = MacroRegistry.find(n.name)
            if (def == null) { sb.append(n.raw); i++; continue }
            if (def.scoped) {
                val close = findClose(nodes, i, n.name)
                if (close < 0) { sb.append(n.raw); i++; continue }
                val content = nodes.subList(i + 1, close).raw()
                val args = n.args.map { eval(it, ctx) }
                sb.append(run(def, n, MacroCall(ctx, args, content, n.name)))
                i = close + 1
                continue
            }
            val args = n.args.map { eval(it, ctx) }
            if (args.size < def.minArgs || args.size > def.maxArgs) { sb.append(n.raw); i++; continue }
            sb.append(run(def, n, MacroCall(ctx, args, null, n.name)))
            i++
        }
        return sb.toString()
    }

    private fun run(def: MacroDef, n: MacroNode.Call, call: MacroCall): String =
        runCatching { def.eval(call) }.getOrNull() ?: n.raw

    private fun findClose(nodes: List<MacroNode>, open: Int, name: String): Int {
        var depth = 0
        for (j in open + 1 until nodes.size) {
            val c = nodes[j] as? MacroNode.Call ?: continue
            if (c.name.equals(name, true)) depth++
            else if (c.name.equals("/$name", true)) { if (depth == 0) return j else depth-- }
        }
        return -1
    }

    /** Splits scoped content at a top-level `{{else}}`. */
    fun splitElse(content: String): Pair<String, String?> {
        val nodes = MacroParser.parse(content)
        var depth = 0
        for ((idx, n) in nodes.withIndex()) {
            val c = n as? MacroNode.Call ?: continue
            val def = MacroRegistry.find(c.name)
            if (def?.scoped == true) depth++
            else if (c.name.startsWith("/") && MacroRegistry.find(c.name.drop(1))?.scoped == true) depth--
            else if (depth == 0 && c.name.equals("else", true)) {
                return nodes.subList(0, idx).raw() to nodes.subList(idx + 1, nodes.size).raw()
            }
        }
        return content to null
    }
}
