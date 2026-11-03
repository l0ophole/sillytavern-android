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
    val charPrompt: String = "",
    val charInstruction: String = "",
    val mesExamplesRaw: String = "",
    val mesExamples: String = "",
    val depthPrompt: String = "",
    val creatorNotes: String = "",
    val firstMessage: String = "",
    val charVersion: String = "",
    val model: String = "",
    val maxPrompt: Int = 0,
    val maxResponse: Int = 0,
    val lastGenerationType: String = "",
    val isMobile: Boolean = true,
    val input: String = "",
    val originalText: String? = null,
    val systemPromptText: String = "",
    val idleSince: java.time.Instant? = null,
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    val pickSeed: Int = 0,
    val chat: ChatMacroView? = null,
    val vars: app.sillytavern.android.macro.VariableStore? = null,
    val instruct: app.sillytavern.android.prompt.model.InstructTemplate? = null,
    /** One-shot macros (`{{original}}`) record their use here. */
    val used: MutableSet<String> = mutableSetOf(),
)

/** The visible chat as seen by `{{lastMessage}}` & co. */
data class ChatMacroView(val messages: List<ChatMessage>, val firstIncludedIndex: Int = 0)

/** Macro expansion facade; the engine lives in [app.sillytavern.android.macro]. */
object Macros {
    fun expand(input: String, ctx: MacroContext): String = app.sillytavern.android.macro.MacroEngine.expand(input, ctx)
}
