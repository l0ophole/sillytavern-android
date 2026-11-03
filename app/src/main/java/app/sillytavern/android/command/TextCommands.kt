package app.sillytavern.android.command

import kotlinx.serialization.json.*

object TextCommands {
    private fun jsonList(s: String): List<String>? = runCatching {
        (Json.parseToJsonElement(s) as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
    }.getOrNull()

    /** `/regex/flags` or plain text -> Regex; null when invalid. */
    fun toRegex(pattern: String): Regex? {
        val m = Regex("^/(.*)/([gimsuy]*)$", RegexOption.DOT_MATCHES_ALL).find(pattern)
        return runCatching {
            if (m == null) Regex(Regex.escape(pattern))
            else {
                val opts = buildSet {
                    if ('i' in m.groupValues[2]) add(RegexOption.IGNORE_CASE)
                    if ('m' in m.groupValues[2]) add(RegexOption.MULTILINE)
                    if ('s' in m.groupValues[2]) add(RegexOption.DOT_MATCHES_ALL)
                }
                Regex(m.groupValues[1], opts)
            }
        }.getOrNull()
    }

    fun register() {
        cmd("len", help = "Length of a string or JSON array.") { c -> (jsonList(c.unnamed)?.size ?: c.unnamed.length).toString() }
        cmd("upper", "uppercase", help = "Uppercase.") { it.unnamed.uppercase() }
        cmd("lower", "lowercase", help = "Lowercase.") { it.unnamed.lowercase() }
        cmd("trim", help = "Trim whitespace.") { it.unnamed.trim() }
        cmd("substr", help = "substr start= end= text") { c ->
            val s = c.unnamed
            val a = (c.named["start"]?.toIntOrNull() ?: 0).let { if (it < 0) (s.length + it).coerceAtLeast(0) else it.coerceAtMost(s.length) }
            val b = (c.named["end"]?.toIntOrNull() ?: s.length).let { if (it < 0) (s.length + it).coerceAtLeast(0) else it.coerceAtMost(s.length) }
            if (a >= b) "" else s.substring(a, b)
        }
        cmd("replace", help = "replace pattern= replacer= text (pattern may be /regex/flags)") { c ->
            val rx = toRegex(c.named["pattern"].orEmpty()) ?: throw CommandError("Invalid pattern")
            val global = c.named["pattern"]?.let { !it.startsWith("/") || it.endsWith("/g") || it.substringAfterLast('/').contains('g') } ?: true
            val rep = c.named["replacer"].orEmpty()
            if (global) c.unnamed.replace(rx, Regex.escapeReplacement(rep).replace("\\$", "$")) else rx.replaceFirst(c.unnamed, rep)
        }
        cmd("split", help = "split find=, text -> JSON array") { c ->
            val find = c.named["find"] ?: ","
            JsonArray(c.unnamed.split(find).map { JsonPrimitive(it) }).toString()
        }
        cmd("join", help = "join glue=, [json array]") { c -> (jsonList(c.unnamed) ?: listOf(c.unnamed)).joinToString(c.named["glue"] ?: ", ") }
        cmd("sort", help = "Sort a JSON array.") { c -> JsonArray((jsonList(c.unnamed) ?: emptyList()).sorted().map { JsonPrimitive(it) }).toString() }
        cmd("roll", help = "roll 2d6") { c -> app.sillytavern.android.macro.CoreMacros.roll(c.unnamed.trim(), c.host.macroContext().random) ?: throw CommandError("Invalid dice") }
    }
}
