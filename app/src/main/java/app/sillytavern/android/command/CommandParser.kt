package app.sillytavern.android.command

sealed interface Arg {
    data class Literal(val s: String) : Arg
    data class Closure(val body: CommandScript) : Arg
}

data class CommandCall(val name: String, val named: Map<String, Arg>, val unnamed: List<Arg>, val rawText: String)
data class CommandScript(val pipeline: List<CommandCall>)

class CommandParseError(message: String, val position: Int) : Exception(message)

/** Parser for SillyTavern slash-command scripts: `/cmd key=val text | /cmd2 {: closure :}`. */
object CommandParser {
    fun isCommand(text: String): Boolean {
        val t = text.trimStart()
        if (t.length < 2 || t[0] != '/' || t[1].isWhitespace()) return false
        if (t == "//") return false
        return true
    }

    fun parse(text: String): CommandScript = Parser(text).script(0, text.length)

    private class Parser(val s: String) {
        fun script(from: Int, to: Int): CommandScript {
            val calls = ArrayList<CommandCall>()
            var i = from
            while (true) {
                while (i < to && s[i].isWhitespace()) i++
                if (i >= to) break
                if (s[i] != '/') throw CommandParseError("Expected '/' at position $i", i)
                val (call, next) = command(i, to)
                if (call != null) calls.add(call)
                i = next
                if (i < to && s[i] == '|') i++ else break
            }
            return CommandScript(calls)
        }

        /** Parses one command starting at '/', returns it and the index of the terminating '|' or end. */
        private fun command(start: Int, to: Int): Pair<CommandCall?, Int> {
            var i = start + 1
            val nameStart = i
            if (i < to && (s[i] == '/' || s[i] == '#')) {
                // comment: swallow to the next unescaped pipe
                while (i < to && s[i] != '|') { if (s[i] == '\\' && i + 1 < to) i++; i++ }
                return null to i
            }
            while (i < to && !s[i].isWhitespace() && s[i] != '|') i++
            val name = s.substring(nameStart, i)
            if (name.isEmpty()) throw CommandParseError("Empty command name at position $start", start)
            val named = LinkedHashMap<String, Arg>()
            while (true) {
                while (i < to && s[i].isWhitespace()) i++
                val m = NAMED.matchAt(s, i)
                if (m == null || m.range.last + 1 > to) break
                val key = m.groupValues[1]
                i = m.range.last + 1
                val (v, ni) = namedValue(i, to)
                named[key] = v; i = ni
            }
            val unnamed = ArrayList<Arg>()
            val lit = StringBuilder()
            fun flush() { if (lit.isNotEmpty()) { unnamed.add(Arg.Literal(lit.toString())); lit.clear() } }
            while (i < to) {
                val c = s[i]
                when {
                    c == '|' -> break
                    (lit.isEmpty() || lit.last().isWhitespace()) && NAMED.matchAt(s, i) != null && NAMED.matchAt(s, i)!!.range.last < to -> {
                        val m = NAMED.matchAt(s, i)!!
                        val (v, ni) = namedValue(m.range.last + 1, to)
                        named[m.groupValues[1]] = v; i = ni
                    }
                    c == '\\' && i + 1 < to && s[i + 1] == '|' -> { lit.append('|'); i += 2 }
                    s.startsWith("{{", i) -> { val e = macroEnd(i, to); lit.append(s, i, e); i = e }
                    s.startsWith("{:", i) && (i == start || i == 0 || s[i - 1].isWhitespace() || lit.isEmpty()) -> {
                        val e = closureEnd(i, to)
                        flush(); unnamed.add(Arg.Closure(script(i + 2, e))); i = e + 2
                    }
                    c == '"' && (lit.isEmpty() || lit.last().isWhitespace()) -> {
                        val e = quoteEnd(i, to)
                        if (e < 0) { lit.append(c); i++ } else { lit.append(s, i, e + 1); i = e + 1 }
                    }
                    else -> { lit.append(c); i++ }
                }
            }
            // trim only the edges of the unnamed text
            if (unnamed.isNotEmpty() || lit.isNotEmpty()) {
                flush()
                val first = unnamed.firstOrNull()
                if (first is Arg.Literal) unnamed[0] = Arg.Literal(first.s.trimStart())
                val last = unnamed.lastOrNull()
                if (last is Arg.Literal) unnamed[unnamed.lastIndex] = Arg.Literal(last.s.trimEnd())
                unnamed.removeAll { it is Arg.Literal && it.s.isEmpty() }
            }
            return CommandCall(name, named, unnamed, s.substring(start, i).trim()) to i
        }

        private fun namedValue(from: Int, to: Int): Pair<Arg, Int> {
            if (from >= to) return Arg.Literal("") to from
            if (s[from] == '"') {
                val e = quoteEnd(from, to)
                if (e < 0) throw CommandParseError("Unterminated quote at position $from", from)
                return Arg.Literal(unescape(s.substring(from + 1, e))) to e + 1
            }
            if (s.startsWith("{:", from)) {
                val e = closureEnd(from, to)
                return Arg.Closure(script(from + 2, e)) to e + 2
            }
            var i = from
            while (i < to && !s[i].isWhitespace() && s[i] != '|') {
                i = if (s.startsWith("{{", i)) macroEnd(i, to) else i + 1
            }
            return Arg.Literal(s.substring(from, i)) to i
        }

        private fun unescape(v: String) = v.replace("\\\"", "\"").replace("\\\\", "\\")

        /** Index of the closing quote, or -1. */
        private fun quoteEnd(open: Int, to: Int): Int {
            var i = open + 1
            while (i < to) {
                if (s[i] == '\\' && i + 1 < to) { i += 2; continue }
                if (s[i] == '"') return i
                i++
            }
            return -1
        }

        /** Index just past the matching `}}` (nested), or end of input if unbalanced. */
        private fun macroEnd(open: Int, to: Int): Int {
            var depth = 0; var i = open
            while (i < to) {
                if (s.startsWith("{{", i)) { depth++; i += 2 }
                else if (s.startsWith("}}", i)) { depth--; i += 2; if (depth == 0) return i }
                else i++
            }
            return open + 2
        }

        /** Index of the `:}` that closes the `{:` at [open]. */
        private fun closureEnd(open: Int, to: Int): Int {
            var depth = 0; var i = open
            while (i < to) {
                if (s.startsWith("{:", i)) { depth++; i += 2 }
                else if (s.startsWith(":}", i)) { depth--; if (depth == 0) return i; i += 2 }
                else i++
            }
            throw CommandParseError("Unterminated closure at position $open", open)
        }
    }

    private val NAMED = Regex("""[A-Za-z_][A-Za-z0-9_-]*(?==)""").let { Regex("""([A-Za-z_][A-Za-z0-9_-]*)=""") }
}
