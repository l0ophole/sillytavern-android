package app.sillytavern.android.macro

import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

object MacroDefinitions {
    fun registerAll() {
        CoreMacros.register()
        TimeMacros.register()
        VariableMacros.register()
        InstructMacros.register()
        ChatMacros.register()
    }
}

object CoreMacros {
    private fun simple(vararg names: String, f: (MacroCall) -> String?) =
        MacroRegistry.register(MacroDef(names.toList(), 0, 0, eval = f))

    fun register() {
        simple("user") { it.ctx.user }
        simple("char") { it.ctx.char }
        simple("description") { it.ctx.description }
        simple("personality") { it.ctx.personality }
        simple("scenario") { it.ctx.scenario }
        simple("persona") { it.ctx.persona }
        simple("summary") { it.ctx.summary }
        MacroRegistry.register(MacroDef(listOf("newline"), 0, 1) { "\n".repeat(it.arg(0).trim().toIntOrNull()?.coerceIn(0, 100) ?: 1) })
        MacroRegistry.register(MacroDef(listOf("space"), 0, 1) { " ".repeat(it.arg(0).trim().toIntOrNull()?.coerceIn(0, 100) ?: 1) })
        simple("noop") { "" }
        MacroRegistry.register(MacroDef(listOf("banned")) { "" })
        simple("group", "groupNotMuted", "charIfNotGroup") { it.ctx.char }
        simple("notChar") { it.ctx.user }
        simple("charPrompt") { it.ctx.charPrompt }
        simple("charInstruction") { it.ctx.charInstruction }
        simple("charDescription") { it.ctx.description }
        simple("charPersonality") { it.ctx.personality }
        simple("charScenario") { it.ctx.scenario }
        simple("mesExamplesRaw") { it.ctx.mesExamplesRaw }
        simple("mesExamples") { it.ctx.mesExamples }
        simple("charDepthPrompt") { it.ctx.depthPrompt }
        simple("charCreatorNotes", "creatorNotes") { it.ctx.creatorNotes }
        simple("charFirstMessage", "greeting") { it.ctx.firstMessage }
        simple("charVersion") { it.ctx.charVersion }
        simple("model") { it.ctx.model }
        simple("input") { it.ctx.input }
        simple("isMobile") { it.ctx.isMobile.toString() }
        simple("lastGenerationType") { it.ctx.lastGenerationType }
        simple("systemPrompt") { it.ctx.systemPromptText }
        simple("maxPrompt", "maxPromptTokens", "maxContext", "maxContextTokens") { it.ctx.maxPrompt.toString() }
        simple("maxResponse", "maxResponseTokens") { it.ctx.maxResponse.toString() }
        simple("original") { c -> if (c.ctx.used.add("original")) c.ctx.originalText.orEmpty() else "" }
        MacroRegistry.register(MacroDef(listOf("reverse"), 1, 1) { it.arg(0).reversed() })
        MacroRegistry.register(MacroDef(listOf("pick"), 1) { c ->
            c.args.randomOrNull(Random(c.ctx.pickSeed * 31 + c.args.joinToString("::").hashCode())) ?: ""
        })
        MacroRegistry.register(MacroDef(listOf("if"), 0, 1, scoped = true) { c ->
            var cond = c.arg(0).trim()
            val neg = cond.startsWith("!")
            if (neg) cond = cond.substring(1).trim()
            val truthy = cond.isNotEmpty() && cond.lowercase() !in setOf("false", "0")
            val (a, b) = MacroEngine.splitElse(c.scopeContent.orEmpty())
            (if (truthy != neg) a else b.orEmpty()).trim()
        })
        simple("trim") { "{{trim}}" }
        simple("weekday") { it.ctx.now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        MacroRegistry.register(MacroDef(listOf("outlet"), 1, 1) { it.ctx.outlets[it.arg(0).trim()] ?: "" })
        MacroRegistry.register(MacroDef(listOf("//")) { "" })
        MacroRegistry.register(MacroDef(listOf("random"), 1) { c ->
            val items = if (c.args.size == 1) c.arg(0).split(",").map { it.trim() } else c.args
            items.randomOrNull(c.ctx.random) ?: ""
        })
        MacroRegistry.register(MacroDef(listOf("roll"), 1, 1) { roll(it.arg(0).trim(), it.ctx.random) })
    }

    internal fun roll(spec: String, r: Random): String? {
        val m = Regex("""(?i)(\d*)d(\d+)""").matchEntire(spec) ?: return spec.toIntOrNull()?.takeIf { it >= 1 }?.let { r.nextInt(1, it + 1).toString() }
        val n = m.groupValues[1].ifEmpty { "1" }.toInt()
        val sides = m.groupValues[2].toInt()
        if (n !in 1..100 || sides < 1) return null
        return (1..n).sumOf { r.nextInt(1, sides + 1) }.toString()
    }
}
