package app.sillytavern.android.prompt

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.DefaultPresets
import app.sillytavern.android.data.PromptPreset
import app.sillytavern.android.prompt.model.ContextTemplate
import app.sillytavern.android.prompt.model.InstructTemplate
import app.sillytavern.android.prompt.model.PromptManagerPreset
import app.sillytavern.android.prompt.model.SystemPromptPreset
import app.sillytavern.android.worldinfo.WorldInfoPrompt

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** [toolCalls] are on assistant messages that called tools; TOOL messages carry [toolCallId]/[toolName] of the call they answer. */
data class PromptMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<app.sillytavern.android.api.Chunk.ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

enum class GenerationMode { NORMAL, CONTINUE, IMPERSONATE }

data class PromptInput(
    val card: CharacterCard,
    val persona: Persona,
    val preset: PromptPreset,
    val history: List<ChatMessage>,
    val maxContextTokens: Int,
    val maxResponseTokens: Int,
    val counter: TokenCounter,
    val mode: GenerationMode = GenerationMode.NORMAL,
    val promptManager: PromptManagerPreset = DefaultPresets.promptManager(),
    val systemPrompt: SystemPromptPreset? = null,
    val worldInfo: WorldInfoPrompt = WorldInfoPrompt(),
    /** Variable store for `{{getvar}}`-style macros; null in tests and previews. */
    val vars: app.sillytavern.android.macro.VariableStore? = null,
)

/** Builds prompts: chat-completion messages via the Prompt Manager, or one text prompt via instruct/context templates. */
object PromptBuilder {
    fun build(i: PromptInput): List<PromptMessage> = PromptManagerBuilder.build(i)

    /** Text-completion prompt (instruct template optional) plus the stop strings it implies. */
    fun buildText(i: PromptInput, instruct: InstructTemplate?, context: ContextTemplate): FormattedPrompt {
        val pm = i.promptManager
        val ctx = PromptSupport.macroContext(i).copy(instruct = instruct)
        fun x(s: String) = Macros.expand(s, ctx).trim()
        fun mainContent() = pm.prompts.firstOrNull { it.identifier == "main" }?.content.orEmpty()
        fun jailbreakContent() = pm.prompts.firstOrNull { it.identifier == "jailbreak" }?.content.orEmpty()

        val vars = mapOf(
            "system" to x(i.card.systemPrompt.ifBlank { i.systemPrompt?.content?.ifBlank { mainContent() } ?: mainContent() }),
            "description" to x(i.card.description),
            "personality" to if (i.card.personality.isBlank()) "" else x(pm.personalityFormat),
            "scenario" to if (i.card.scenario.isBlank()) "" else x(pm.scenarioFormat),
            "persona" to x(i.persona.description),
            "wiBefore" to i.worldInfo.before, "wiAfter" to i.worldInfo.after, "anchorBefore" to "", "anchorAfter" to "",
        )
        val examples = i.worldInfo.exampleTop +
            i.card.messageExample.takeIf { it.isNotBlank() }?.let { listOf("${x(pm.newExampleChatPrompt)}\n${x(it)}") }.orEmpty() +
            i.worldInfo.exampleBottom
        val post = listOf(
            i.card.postHistoryInstructions.ifBlank { i.systemPrompt?.postHistory?.ifBlank { jailbreakContent() } ?: jailbreakContent() },
            when (i.mode) {
                GenerationMode.IMPERSONATE -> pm.impersonationPrompt
                else -> ""
            },
        ).map(::x).filter { it.isNotBlank() }.joinToString("\n\n")

        val fixedTokens = i.counter.count(vars.values.joinToString("\n") + examples.joinToString("\n") + post) + 64
        val all = i.history.filterNot { it.isSystem }.map { m ->
            val asUser = if (i.mode == GenerationMode.IMPERSONATE) !m.isUser else m.isUser
            PromptMessage(if (asUser) Role.USER else Role.ASSISTANT, x(m.text))
        }
        val wiInj = PromptSupport.depthInjections(i.worldInfo)
        val wiTokens = wiInj.values.flatten().sumOf { i.counter.count(it.content) + 4 }
        val kept = PromptSupport.injectAtDepth(
            PromptSupport.trimHistory(all, i.maxContextTokens - i.maxResponseTokens - fixedTokens - wiTokens, i.counter), wiInj,
        )
        return InstructFormatter.format(
            TextPromptParts(vars, examples, kept, post), instruct, context,
            i.persona.name, i.card.name, i.mode,
        )
    }
}
