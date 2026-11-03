package app.sillytavern.android.chat

import java.time.LocalDateTime
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
    val outlets: Map<String, String> = emptyMap(),
)

/** Macro expansion facade; the engine lives in [app.sillytavern.android.macro]. */
object Macros {
    fun expand(input: String, ctx: MacroContext): String = app.sillytavern.android.macro.MacroEngine.expand(input, ctx)
}
