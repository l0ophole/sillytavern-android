package app.sillytavern.android.prompt

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.DefaultPresets
import app.sillytavern.android.data.PromptPreset
import app.sillytavern.android.prompt.model.PromptManagerPreset
import app.sillytavern.android.prompt.model.SystemPromptPreset

enum class Role { SYSTEM, USER, ASSISTANT }
data class PromptMessage(val role: Role, val content: String)

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
)

/** Builds the provider-neutral prompt: system block, trimmed history, post-history instructions. */
object PromptBuilder {
    fun build(i: PromptInput): List<PromptMessage> {
        val ctx = MacroContext(
            user = i.persona.name, char = i.card.name,
            description = i.card.description, personality = i.card.personality,
            scenario = i.card.scenario, persona = i.persona.description,
        )
        fun x(s: String) = Macros.expand(s, ctx).trim()

        val system = buildList {
            add(x(i.card.systemPrompt.ifBlank { i.preset.mainPrompt }))
            if (i.card.description.isNotBlank()) add(x(i.card.description))
            if (i.card.personality.isNotBlank()) add("${i.card.name}'s personality: ${x(i.card.personality)}")
            if (i.card.scenario.isNotBlank()) add("Scenario: ${x(i.card.scenario)}")
            if (i.persona.description.isNotBlank()) add("${i.persona.name}'s description: ${x(i.persona.description)}")
            if (i.card.messageExample.isNotBlank()) add("[Example Chat]\n${x(i.card.messageExample)}")
        }.filter { it.isNotBlank() }.joinToString("\n\n")

        val tail = buildList {
            if (i.preset.jailbreak.isNotBlank()) add(x(i.preset.jailbreak))
            if (i.card.postHistoryInstructions.isNotBlank()) add(x(i.card.postHistoryInstructions))
            when (i.mode) {
                GenerationMode.IMPERSONATE -> add("[Write the next reply from ${i.persona.name}'s point of view, using the chat history so far as a guideline for the writing style of ${i.persona.name}.]")
                GenerationMode.CONTINUE -> add("[Continue your last message without repeating its original content.]")
                GenerationMode.NORMAL -> {}
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n")

        var budget = i.maxContextTokens - i.maxResponseTokens - i.counter.count(system) - i.counter.count(tail)
        val msgs = i.history.filterNot { it.isSystem }.map { m ->
            val asUser = if (i.mode == GenerationMode.IMPERSONATE) !m.isUser else m.isUser
            PromptMessage(if (asUser) Role.USER else Role.ASSISTANT, x(m.text))
        }
        // Keep the newest messages that fit; always keep the very last one.
        val kept = ArrayDeque<PromptMessage>()
        for (m in msgs.asReversed()) {
            val cost = i.counter.count(m.content) + 4
            if (kept.isNotEmpty() && cost > budget) break
            budget -= cost
            kept.addFirst(m)
        }
        return buildList {
            if (system.isNotBlank()) add(PromptMessage(Role.SYSTEM, system))
            addAll(kept)
            if (tail.isNotBlank()) add(PromptMessage(Role.SYSTEM, tail))
        }
    }

    /** Plain-text prompt for text-completion backends (no instruct template yet): "Name: text" lines. */
    fun toTextPrompt(messages: List<PromptMessage>, userName: String, charName: String, mode: GenerationMode): String {
        val sb = StringBuilder()
        for (m in messages) when (m.role) {
            Role.SYSTEM -> sb.append(m.content).append("\n\n")
            Role.USER -> sb.append(if (mode == GenerationMode.IMPERSONATE) charName else userName).append(": ").append(m.content).append('\n')
            Role.ASSISTANT -> sb.append(if (mode == GenerationMode.IMPERSONATE) userName else charName).append(": ").append(m.content).append('\n')
        }
        if (mode != GenerationMode.CONTINUE) sb.append(if (mode == GenerationMode.IMPERSONATE) userName else charName).append(':')
        return sb.toString()
    }
}
