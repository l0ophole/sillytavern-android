package app.sillytavern.android.command

import kotlinx.coroutines.delay

object FlowCommands {
    fun truthy(s: String) = s.trim().let { it.isNotEmpty() && it.lowercase() != "false" && it != "0" }

    fun compare(left: String, right: String, rule: String?): Boolean {
        if (rule == null) return truthy(left)
        val l = left.trim().toDoubleOrNull(); val r = right.trim().toDoubleOrNull()
        val numeric = l != null && r != null
        fun cmp() = if (numeric) l!!.compareTo(r!!) else left.compareTo(right)
        return when (rule.lowercase()) {
            "eq" -> if (numeric) l == r else left == right
            "neq" -> if (numeric) l != r else left != right
            "lt" -> cmp() < 0
            "gt" -> cmp() > 0
            "lte" -> cmp() <= 0
            "gte" -> cmp() >= 0
            "not" -> !truthy(left)
            "in" -> left.contains(right, ignoreCase = true)
            "nin" -> !left.contains(right, ignoreCase = true)
            else -> throw CommandError("Unknown rule: $rule")
        }
    }

    private fun cond(c: CommandContext) = compare(c.expand(c.named["left"].orEmpty()), c.expand(c.named["right"].orEmpty()), c.named["rule"]?.takeIf { it.isNotBlank() })

    /** Runs the branch given as closure or raw text; returns the pipe. */
    private suspend fun branch(c: CommandContext, closure: CommandScript?, text: String?): String = when {
        closure != null -> c.executor.callClosure(closure, c.scope, c.pipe)
        !text.isNullOrBlank() -> c.executor.callText(text, c.scope, c.pipe)
        else -> c.pipe
    }

    fun register() {
        cmd("pass", help = "Returns its argument.") { it.unnamed }
        cmd("return", help = "Ends the current closure with a value.", usesPipe = true) { throw ReturnSignal(it.unnamed) }
        cmd("abort", help = "Stops the whole script.", usesPipe = false) { throw AbortSignal() }
        cmd("break", help = "Leaves the current loop.", usesPipe = false) { throw BreakSignal() }
        cmd("echo", help = "Shows a message.") { c -> c.host.echo(c.unnamed, c.named["severity"] ?: "info"); c.unnamed }
        cmd("sleep", "delay", help = "Waits N milliseconds.") { c -> delay((c.unnamed.trim().toLongOrNull() ?: 0L).coerceIn(0, 60_000)); c.pipe }
        cmd("help", help = "Lists commands.") { c ->
            val text = CommandRegistry.all().joinToString("\n") { "/${it.name}" + (if (it.aliases.isEmpty()) "" else " (${it.aliases.joinToString { a -> "/$a" }})") + " - ${it.help}" }
            c.host.echo(text); text
        }

        cmd("if", help = "if left= right= rule= {closure} else={closure}", rawUnnamed = true, rawNamed = setOf("left", "right", "else"), usesPipe = false) { c ->
            val raw = c.named["else"]
            if (cond(c)) branch(c, c.closures.firstOrNull(), c.unnamed)
            else branch(c, c.namedClosures["else"], raw)
        }
        cmd("while", help = "while left= right= rule= {closure}", rawUnnamed = true, rawNamed = setOf("left", "right"), usesPipe = false) { c ->
            var n = 0; var last = c.pipe
            try {
                while (cond(c)) {
                    if (++n > c.executor.limits.maxLoopIterations) throw CommandError("Loop limit (${c.executor.limits.maxLoopIterations}) exceeded")
                    last = branch(c, c.closures.firstOrNull(), c.unnamed)
                }
            } catch (e: BreakSignal) {}
            last
        }
        cmd("times", help = "times N {closure}", rawUnnamed = true, usesPipe = false) { c ->
            val lit = c.unnamed.trim()
            val count = c.expand(lit).trim().toIntOrNull() ?: 0
            if (count > c.executor.limits.maxLoopIterations) throw CommandError("Loop limit (${c.executor.limits.maxLoopIterations}) exceeded")
            var last = c.pipe
            try {
                for (i in 0 until count) {
                    val scope = CommandScope(c.scope).also { it.declare("timesIndex", ScopeValue(i.toString(), null)) }
                    last = c.executor.callClosure(c.closures.firstOrNull() ?: throw CommandError("/times needs a closure"), scope, last)
                }
            } catch (e: BreakSignal) {}
            last
        }
        cmd("run", ":", help = "Runs a closure, a closure variable or a Quick Reply (set.label).") { c ->
            val inline = c.closures.firstOrNull()
            if (inline != null) return@cmd c.executor.callClosure(inline, c.scope, c.pipe)
            val name = c.unnamed.trim()
            c.scope.get(name)?.closure?.let { return@cmd c.executor.callClosure(it, c.scope, c.pipe) }
            c.host.runStoredScript(name, c.executor) ?: throw CommandError("No closure or Quick Reply named \"$name\"")
        }
        cmd("let", help = "let name value - declare/read a scoped variable.", rawUnnamed = true) { c ->
            val raw = c.unnamed.trim()
            val name = raw.substringBefore(' ').trim().ifEmpty { c.named.keys.firstOrNull().orEmpty() }
            val rest = raw.substringAfter(' ', "")
            when {
                c.closures.isNotEmpty() -> { c.scope.declare(name, ScopeValue(null, c.closures.first())); "" }
                rest.isNotEmpty() || raw.contains(' ') -> { c.scope.declare(name, ScopeValue(c.expand(rest), null)); c.expand(rest) }
                else -> c.scope.get(name)?.text ?: ""
            }
        }
        cmd("var", help = "var NAME=value | var NAME - local (chat) variable.") { c ->
            val set = c.named.entries.firstOrNull { it.key != "index" }
            val vars = c.host.variables
            if (set != null) {
                val v = if (c.unnamed.isNotEmpty() && c.unnamed != c.pipe) c.unnamed else set.value
                vars.set(set.key, v, false, c.named["index"]); v
            } else vars.get(c.unnamed.trim(), false, c.named["index"])
        }
        for (global in listOf(false, true)) {
            val g = if (global) "global" else ""
            cmd("set${g}var", help = "set${g}var key=name value", usesPipe = true) { c ->
                c.host.variables.set(c.named["key"] ?: throw CommandError("key= required"), c.unnamed, global, c.named["index"]); c.unnamed
            }
            cmd("get${g}var", help = "get${g}var name") { c -> c.host.variables.get(c.named["key"] ?: c.unnamed.trim(), global, c.named["index"]) }
            cmd("add${g}var", help = "add${g}var key=name value") { c ->
                val k = c.named["key"] ?: throw CommandError("key= required")
                c.host.variables.add(k, c.unnamed, global); c.host.variables.get(k, global)
            }
            cmd("inc${g}var", help = "inc${g}var name") { c -> c.host.variables.inc(c.unnamed.trim(), global) }
            cmd("dec${g}var", help = "dec${g}var name") { c -> c.host.variables.dec(c.unnamed.trim(), global) }
            cmd("flush${g}var", help = "flush${g}var name", usesPipe = false) { c -> c.host.variables.delete(c.unnamed.trim(), global); "" }
        }
    }
}
