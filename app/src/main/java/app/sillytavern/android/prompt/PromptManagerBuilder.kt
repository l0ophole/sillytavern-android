package app.sillytavern.android.prompt

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.prompt.model.PromptDefinition
import app.sillytavern.android.prompt.model.PromptManagerPreset
import app.sillytavern.android.prompt.model.SystemPromptPreset
import app.sillytavern.android.worldinfo.WorldInfoPrompt

/** Shared helpers for building prompts. */
internal object PromptSupport {
    fun macroContext(i: PromptInput) = MacroContext(
        user = i.persona.name, char = i.card.name,
        description = i.card.description, personality = i.card.personality,
        scenario = i.card.scenario, persona = i.persona.description,
        outlets = i.worldInfo.outlets,
        charPrompt = i.card.systemPrompt, mesExamplesRaw = i.card.messageExample,
        mesExamples = i.card.messageExample, creatorNotes = i.card.creatorNotes,
        firstMessage = i.card.firstMessage, charVersion = i.card.characterVersion,
        depthPrompt = (((i.card.raw["extensions"] as? kotlinx.serialization.json.JsonObject)?.get("depth_prompt") as? kotlinx.serialization.json.JsonObject)
            ?.get("prompt") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
        maxPrompt = i.maxContextTokens, maxResponse = i.maxResponseTokens,
        chat = app.sillytavern.android.chat.ChatMacroView(i.history),
        vars = i.vars,
        summary = app.sillytavern.android.chat.Summary.latest(i.history)?.second.orEmpty(),
    )

    /** [text] prefixed with the message author's name when [prefix] is on (group chats); narrator lines are left alone. */
    fun speaker(m: app.sillytavern.android.chat.ChatMessage, text: String, i: PromptInput, prefix: Boolean): String =
        if (prefix && !m.isNarrator && m.name.isNotBlank() && text.isNotBlank()) "${m.name}: $text" else text

    /** Message text after regex scripts for the prompt (depth 0 = newest message). */
    fun regexed(m: app.sillytavern.android.chat.ChatMessage, depth: Int, i: PromptInput, ctx: MacroContext): String {
        if (i.regexScripts.isEmpty()) return m.text
        val placement = when { m.isNarrator -> app.sillytavern.android.regex.Placement.SLASH_COMMAND; m.isUser -> app.sillytavern.android.regex.Placement.USER_INPUT; else -> app.sillytavern.android.regex.Placement.AI_OUTPUT }
        return app.sillytavern.android.regex.RegexEngine.apply(m.text, placement, i.regexScripts,
            app.sillytavern.android.regex.RegexParams(isPrompt = true, depth = depth, macros = { Macros.expand(it, ctx) }))
    }

    /** World Info depth entries as (depth from end) -> messages. */
    fun depthInjections(wi: WorldInfoPrompt): Map<Int, List<PromptMessage>> = wi.depth.filter { it.content.isNotBlank() }
        .groupBy({ it.depth }, { PromptMessage(when (it.role) { 1 -> Role.USER; 2 -> Role.ASSISTANT; else -> Role.SYSTEM }, it.content) })

    /** Extension prompts placed in the chat at their depth. */
    fun inChat(i: PromptInput, x: (String) -> String): Map<Int, List<PromptMessage>> =
        i.extensionPrompts.filter { it.position == ExtensionPrompt.IN_CHAT && it.content.isNotBlank() }
            .mapNotNull { e -> x(e.content).takeIf { it.isNotBlank() }?.let { e.depth to PromptMessage(roleOf(e.role), it) } }
            .groupBy({ it.first }, { it.second })

    /** Extension prompts at the anchor [position] joined for the text-completion story string. */
    fun anchor(i: PromptInput, position: Int, x: (String) -> String): String =
        i.extensionPrompts.filter { it.position == position }.map { x(it.content) }.filter { it.isNotBlank() }.joinToString("\n")

    /** Persona description for the prompt body: only the in-prompt positions (0 and the legacy 1) put it there. */
    fun personaText(i: PromptInput): String = if (i.persona.position == 0 || i.persona.position == 1) i.persona.description else ""

    fun roleOf(r: Int) = when (r) { 1 -> Role.USER; 2 -> Role.ASSISTANT; else -> Role.SYSTEM }

    fun mergeInjections(a: Map<Int, List<PromptMessage>>, b: Map<Int, List<PromptMessage>>): Map<Int, List<PromptMessage>> =
        (a.keys + b.keys).associateWith { a[it].orEmpty() + b[it].orEmpty() }

    /** Inserts [injections] (depth counted from the end; deeper than the chat lands first, 0 last). */
    fun injectAtDepth(kept: List<PromptMessage>, injections: Map<Int, List<PromptMessage>>): List<PromptMessage> = buildList {
        kept.forEachIndexed { idx, m ->
            injections[kept.size - idx]?.let { addAll(it) }
            add(m)
        }
        addAll(0, injections.filterKeys { it > kept.size }.toSortedMap(compareByDescending { it }).values.flatten())
        injections[0]?.let { addAll(it) }
    }

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
            "personaDescription" -> PromptSupport.personaText(i)
            "dialogueExamples" -> (i.worldInfo.exampleTop +
                listOfNotNull(i.card.messageExample.takeIf { it.isNotBlank() }?.let { "${pm.newExampleChatPrompt}\n$it" }) +
                i.worldInfo.exampleBottom).joinToString("\n")
            "worldInfoBefore" -> wiFormat(i.worldInfo.before, pm)
            "worldInfoAfter" -> wiFormat(i.worldInfo.after, pm)
            else -> if (d.marker) "" else d.content
        }
        fun message(d: PromptDefinition): PromptMessage? =
            x(content(d)).takeIf { it.isNotBlank() }?.let { PromptMessage(role(d.role), it) }

        val histIdx = active.indexOfFirst { it.identifier == "chatHistory" }
        val relative = active.withIndex().filter { (_, d) -> d.identifier == "chatHistory" || d.injectionPosition != 1 }
        val preDefs = relative.filter { histIdx < 0 || it.index < histIdx }.map { it.value }
        val pre = withExtensionAnchors(preDefs.mapNotNull { d -> message(d)?.let { d.identifier to it } }, i, defs["main"]?.role, ::x)
        val post = relative.filter { histIdx >= 0 && it.index > histIdx && it.value.identifier != "chatHistory" }
            .mapNotNull { message(it.value) }
        // Absolute injections: (depth from end, role) -> joined text, ordered by injection_order.
        val injections = active.filter { it.injectionPosition == 1 && !it.marker }
            .sortedBy { it.injectionOrder }
            .mapNotNull { d -> message(d)?.let { d.injectionDepth to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ms) -> ms.groupBy { it.role }.map { (r, l) -> PromptMessage(r, l.joinToString("\n") { it.content }) } }
            .let { base -> PromptSupport.mergeInjections(PromptSupport.mergeInjections(base, PromptSupport.depthInjections(i.worldInfo)), PromptSupport.inChat(i, ::x)) }

        val nudge = when (i.mode) {
            GenerationMode.IMPERSONATE -> x(pm.impersonationPrompt)
            GenerationMode.CONTINUE -> x(pm.continueNudgePrompt)
            GenerationMode.NORMAL -> ""
        }.takeIf { it.isNotBlank() }?.let { PromptMessage(Role.SYSTEM, it) }

        val inPrompt = i.history.filter { it.inPrompt }
        var history = inPrompt.mapIndexed { idx, m ->
            val asUser = if (i.mode == GenerationMode.IMPERSONATE) !m.isUser else m.isUser
            PromptMessage(if (m.isNarrator) Role.SYSTEM else if (asUser) Role.USER else Role.ASSISTANT,
                PromptSupport.speaker(m, x(PromptSupport.regexed(m, inPrompt.size - 1 - idx, i, ctx)), i, i.groupNames))
        }
        if (history.isEmpty() && pm.sendIfEmpty.isNotBlank()) history = listOf(PromptMessage(Role.USER, x(pm.sendIfEmpty)))
        val newChat = x(pm.newChatPrompt).takeIf { it.isNotBlank() && histIdx >= 0 }?.let { PromptMessage(Role.SYSTEM, it) }

        val fixed = pre + post + listOfNotNull(nudge, newChat) + injections.values.flatten()
        val budget = i.maxContextTokens - i.maxResponseTokens - fixed.sumOf { i.counter.count(it.content) + 4 }
        val kept = if (histIdx < 0) emptyList() else PromptSupport.trimHistory(history, budget, i.counter)
            .map { if (pm.namesBehavior == 2 && !i.groupNames) withName(it, i) else it }

        val withInjections = PromptSupport.injectAtDepth(kept, injections)
        val result = pre + listOfNotNull(newChat) + withInjections + post + listOfNotNull(nudge)
        return if (pm.squashSystemMessages) squash(result) else result
    }

    /** BEFORE_PROMPT / IN_PROMPT extension prompts go right before / after the main prompt (front / back of [pre] when it is disabled). */
    private fun withExtensionAnchors(pre: List<Pair<String, PromptMessage>>, i: PromptInput, mainRole: String?, x: (String) -> String): List<PromptMessage> {
        fun ext(position: Int) = i.extensionPrompts.filter { it.position == position }
            .mapNotNull { e -> x(e.content).takeIf { it.isNotBlank() }?.let { PromptMessage(if (mainRole != null) role(mainRole) else PromptSupport.roleOf(e.role), it) } }
        val before = ext(ExtensionPrompt.BEFORE_PROMPT); val after = ext(ExtensionPrompt.IN_PROMPT)
        if (before.isEmpty() && after.isEmpty()) return pre.map { it.second }
        val mainAt = pre.indexOfFirst { it.first == "main" }
        val out = pre.map { it.second }.toMutableList()
        if (mainAt < 0) { out.addAll(0, before); out.addAll(after); return out }
        out.addAll(mainAt + 1, after); out.addAll(mainAt, before)
        return out
    }

    private fun wiFormat(text: String, pm: PromptManagerPreset) = if (text.isBlank()) "" else pm.wiFormat.replace("{0}", text)

    private fun withName(m: PromptMessage, i: PromptInput): PromptMessage {
        if (m.role == Role.SYSTEM) return m
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
