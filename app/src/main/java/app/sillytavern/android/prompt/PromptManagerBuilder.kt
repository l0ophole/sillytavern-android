package app.sillytavern.android.prompt

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.prompt.model.PromptDefinition
import app.sillytavern.android.prompt.model.PromptManagerPreset
import app.sillytavern.android.prompt.model.SystemPromptPreset

/** Shared helpers for building prompts. */
internal object PromptSupport {
    fun macroContext(i: PromptInput) = MacroContext(
        user = i.persona.name, char = i.card.name,
        description = i.card.description, personality = i.card.personality,
        scenario = i.card.scenario, persona = i.persona.description,
    )

    /** Keeps the newest messages that fit [budget]; the very last message is always kept. */
    fun trimHistory(msgs: List<PromptMessage>, budget: Int, counter: TokenCounter): List<PromptMessage> {
        var left = budget
        val kept = ArrayDeque<PromptMessage>()
        for (m in msgs.asReversed()) {
            val cost = counter.count(m.content) + 4
            if (kept.isNotEmpty() && cost > left) break
            left -= cost
            kept.addFirst(m)
        }
        return kept.toList()
    }
}

/** Assembles chat-completion messages from a Prompt Manager preset (order, toggles, injection depth). */
object PromptManagerBuilder {
    private fun role(s: String) = when (s.lowercase()) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; else -> Role.SYSTEM }

    fun build(i: PromptInput, pm: PromptManagerPreset = i.promptManager, sys: SystemPromptPreset? = i.systemPrompt): List<PromptMessage> {
        val ctx = PromptSupport.macroContext(i)
        fun x(s: String) = Macros.expand(s, ctx).trim()
        val defs = pm.prompts.associateBy { it.identifier }
        val active = pm.order.filter { it.enabled }.mapNotNull { defs[it.identifier] }

        fun content(d: PromptDefinition): String = when (d.identifier) {
            "main" -> i.card.systemPrompt.takeIf { it.isNotBlank() && !d.forbidOverrides } ?: sys?.content?.takeIf { it.isNotBlank() } ?: d.content
            "jailbreak" -> i.card.postHistoryInstructions.takeIf { it.isNotBlank() && !d.forbidOverrides }
                ?: sys?.postHistory?.takeIf { it.isNotBlank() } ?: d.content
            "charDescription" -> i.card.description
            "charPersonality" -> if (i.card.personality.isBlank()) "" else pm.personalityFormat
            "scenario" -> if (i.card.scenario.isBlank()) "" else pm.scenarioFormat
            "personaDescription" -> i.persona.description
            "dialogueExamples" -> i.card.messageExample.takeIf { it.isNotBlank() }?.let { "${pm.newExampleChatPrompt}\n$it" } ?: ""
            else -> if (d.marker) "" else d.content
        }
        fun message(d: PromptDefinition): PromptMessage? =
            x(content(d)).takeIf { it.isNotBlank() }?.let { PromptMessage(role(d.role), it) }

        val histIdx = active.indexOfFirst { it.identifier == "chatHistory" }
        val relative = active.withIndex().filter { (_, d) -> d.identifier == "chatHistory" || d.injectionPosition != 1 }
        val pre = relative.filter { histIdx < 0 || it.index < histIdx }.mapNotNull { message(it.value) }
        val post = relative.filter { histIdx >= 0 && it.index > histIdx && it.value.identifier != "chatHistory" }
            .mapNotNull { message(it.value) }
        // Absolute injections: (depth from end, role) -> joined text, ordered by injection_order.
        val injections = active.filter { it.injectionPosition == 1 && !it.marker }
            .sortedBy { it.injectionOrder }
            .mapNotNull { d -> message(d)?.let { d.injectionDepth to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ms) -> ms.groupBy { it.role }.map { (r, l) -> PromptMessage(r, l.joinToString("\n") { it.content }) } }

        val nudge = when (i.mode) {
            GenerationMode.IMPERSONATE -> x(pm.impersonationPrompt)
            GenerationMode.CONTINUE -> x(pm.continueNudgePrompt)
            GenerationMode.NORMAL -> ""
        }.takeIf { it.isNotBlank() }?.let { PromptMessage(Role.SYSTEM, it) }

        var history = i.history.filterNot { it.isSystem }.map { m ->
            val asUser = if (i.mode == GenerationMode.IMPERSONATE) !m.isUser else m.isUser
            PromptMessage(if (asUser) Role.USER else Role.ASSISTANT, x(m.text))
        }
        if (history.isEmpty() && pm.sendIfEmpty.isNotBlank()) history = listOf(PromptMessage(Role.USER, x(pm.sendIfEmpty)))
        val newChat = x(pm.newChatPrompt).takeIf { it.isNotBlank() && histIdx >= 0 }?.let { PromptMessage(Role.SYSTEM, it) }

        val fixed = pre + post + listOfNotNull(nudge, newChat) + injections.values.flatten()
        val budget = i.maxContextTokens - i.maxResponseTokens - fixed.sumOf { i.counter.count(it.content) + 4 }
        val kept = if (histIdx < 0) emptyList() else PromptSupport.trimHistory(history, budget, i.counter)
            .map { if (pm.namesBehavior == 2) withName(it, i) else it }

        val withInjections = buildList {
            kept.forEachIndexed { idx, m ->
                injections[kept.size - idx]?.let { addAll(it) }
                add(m)
            }
            // Depth beyond the chat length lands at the very start; depth 0 at the very end.
            val deepest = injections.filterKeys { it > kept.size }.toSortedMap(compareByDescending { it }).values.flatten()
            addAll(0, deepest)
            injections[0]?.let { addAll(it) }
        }
        val result = pre + listOfNotNull(newChat) + withInjections + post + listOfNotNull(nudge)
        return if (pm.squashSystemMessages) squash(result) else result
    }

    private fun withName(m: PromptMessage, i: PromptInput): PromptMessage {
        val impersonate = i.mode == GenerationMode.IMPERSONATE
        val isUserSide = (m.role == Role.USER) != impersonate
        return m.copy(content = "${if (isUserSide) i.persona.name else i.card.name}: ${m.content}")
    }

    private fun squash(ms: List<PromptMessage>): List<PromptMessage> = ms.fold(mutableListOf()) { acc, m ->
        val last = acc.lastOrNull()
        if (last != null && last.role == Role.SYSTEM && m.role == Role.SYSTEM) acc[acc.lastIndex] = last.copy(content = last.content + "\n" + m.content)
        else acc += m
        acc
    }
}
