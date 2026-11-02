package app.sillytavern.android.worldinfo

import app.sillytavern.android.chat.Chat
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.prompt.GenerationMode
import app.sillytavern.android.prompt.PromptInput
import app.sillytavern.android.prompt.PromptSupport
import kotlinx.serialization.json.*

/** Runs the scanner for one generation and assembles the prompt pieces. */
object WorldInfoRunner {
    fun trigger(mode: GenerationMode) = when (mode) {
        GenerationMode.NORMAL -> "normal"; GenerationMode.CONTINUE -> "continue"; GenerationMode.IMPERSONATE -> "impersonate"
    }

    fun run(
        entries: List<ScanEntry>, history: List<ChatMessage>, input: PromptInput, settings: WorldInfoSettings,
        trigger: String, timed: TimedState, dryRun: Boolean,
    ): Pair<WorldInfoPrompt, TimedState> {
        if (entries.isEmpty()) return WorldInfoPrompt() to timed
        val ctx = PromptSupport.macroContext(input)
        val expand = { s: String -> Macros.expand(s, ctx) }
        val chat = history.filterNot { it.isSystem }.asReversed()
            .map { if (settings.includeNames) "${it.name}: ${it.text}" else it.text }
        val card = input.card
        val depthPrompt = (((card.raw["extensions"] as? JsonObject)?.get("depth_prompt") as? JsonObject)?.get("prompt") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val global = GlobalScanData(
            personaDescription = input.persona.description, characterDescription = card.description,
            characterPersonality = card.personality, characterDepthPrompt = depthPrompt,
            scenario = card.scenario, creatorNotes = card.creatorNotes, trigger = trigger,
        )
        val result = WorldInfoScanner(settings, input.counter, expand = expand)
            .scan(entries, chat, input.maxContextTokens, global, timed, dryRun)
        return WorldInfoAssembler.assemble(result.activated, expand) to result.timed
    }

    private fun metadata(chat: Chat) = chat.header["chat_metadata"] as? JsonObject ?: JsonObject(emptyMap())

    fun timedOf(chat: Chat?): TimedState =
        TimedState.fromJson(chat?.let { metadata(it)["timedWorldInfo"] as? JsonObject })

    fun chatBookOf(chat: Chat?): String? =
        chat?.let { (metadata(it)["world_info"] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }

    private fun withMetadata(chat: Chat, key: String, value: JsonElement?): Chat {
        val md = JsonObject(metadata(chat).toMutableMap().also { if (value == null) it.remove(key) else it[key] = value })
        return chat.copy(header = JsonObject(chat.header.toMutableMap().also { it["chat_metadata"] = md }))
    }

    fun withTimed(chat: Chat, timed: TimedState) = withMetadata(chat, "timedWorldInfo", timed.toJson())
    fun withChatBook(chat: Chat, name: String?) = withMetadata(chat, "world_info", name?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
}
