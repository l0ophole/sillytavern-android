package app.sillytavern.android.chat

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** Values available to {{macro}} expansion. */
data class MacroContext(
    val user: String,
    val char: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val persona: String = "",
    val now: LocalDateTime = LocalDateTime.now(),
    val random: Random = Random.Default,
)

/** Basic SillyTavern macro engine (subset). Unknown macros are left untouched. */
object Macros {
    private val pattern = Regex("""\{\{(.*?)}}""", RegexOption.DOT_MATCHES_ALL)

    fun expand(input: String, ctx: MacroContext): String {
        if (input.isEmpty()) return input
        var s = input.replace("<USER>", "{{user}}").replace("<BOT>", "{{char}}")
        // Nested macros: repeat until stable (bounded).
        repeat(3) {
            val next = pattern.replace(s) { m -> resolve(m.groupValues[1].trim(), ctx) ?: m.value }
            if (next == s) return finish(s)
            s = next
        }
        return finish(s)
    }

    private fun finish(s: String) = s.replace(Regex("\\s*\\{\\{trim}}\\s*", RegexOption.IGNORE_CASE), "")

    private fun resolve(body: String, c: MacroContext): String? {
        val lower = body.lowercase()
        return when {
            lower == "user" -> c.user
            lower == "char" -> c.char
            lower == "description" -> c.description
            lower == "personality" -> c.personality
            lower == "scenario" -> c.scenario
            lower == "persona" -> c.persona
            lower == "newline" -> "\n"
            lower == "trim" -> "{{trim}}"
            lower == "time" -> c.now.format(DateTimeFormatter.ofPattern("HH:mm"))
            lower == "date" -> c.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            lower == "isotime" -> c.now.format(DateTimeFormatter.ofPattern("HH:mm"))
            lower == "isodate" -> c.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            lower == "weekday" -> c.now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            body.startsWith("//") -> ""
            lower.startsWith("random::") -> body.substring(8).split("::").randomOrNull(c.random) ?: ""
            lower.startsWith("random:") -> body.substring(7).split(",").map { it.trim() }.randomOrNull(c.random) ?: ""
            lower.startsWith("roll:") -> roll(body.substring(5).trim(), c.random)
            else -> null
        }
    }

    private fun roll(spec: String, r: Random): String? {
        val m = Regex("""(?i)(\d*)d(\d+)""").matchEntire(spec) ?: return spec.toIntOrNull()?.let { r.nextInt(1, it + 1).toString() }
        val n = m.groupValues[1].ifEmpty { "1" }.toInt()
        val sides = m.groupValues[2].toInt()
        if (n !in 1..100 || sides < 1) return null
        return (1..n).sumOf { r.nextInt(1, sides + 1) }.toString()
    }
}
