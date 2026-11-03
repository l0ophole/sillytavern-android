package app.sillytavern.android.macro

import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

object MacroDefinitions {
    fun registerAll() {
        CoreMacros.register()
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
        simple("newline") { "\n" }
        simple("trim") { "{{trim}}" }
        simple("time", "isotime") { it.ctx.now.format(DateTimeFormatter.ofPattern("HH:mm")) }
        simple("date", "isodate") { it.ctx.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) }
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
