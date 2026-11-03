package app.sillytavern.android.chat

import app.sillytavern.android.data.CharacterNote
import app.sillytavern.android.data.NoteSettings
import app.sillytavern.android.prompt.ExtensionPrompt
import kotlinx.serialization.json.*

/** Author's Note settings of one chat (stored in `chat_metadata`, defaults from [NoteSettings]). */
data class NoteConfig(val prompt: String, val interval: Int, val depth: Int, val position: Int, val role: Int)

object AuthorsNote {
    fun config(meta: JsonObject, d: NoteSettings) = NoteConfig(
        prompt = ChatMeta.string(meta, ChatMeta.NOTE_PROMPT).orEmpty(),
        interval = ChatMeta.int(meta, ChatMeta.NOTE_INTERVAL) ?: d.defaultInterval,
        depth = ChatMeta.int(meta, ChatMeta.NOTE_DEPTH) ?: d.defaultDepth,
        position = ChatMeta.int(meta, ChatMeta.NOTE_POSITION) ?: d.defaultPosition,
        role = ChatMeta.int(meta, ChatMeta.NOTE_ROLE) ?: d.defaultRole,
    )

    fun withConfig(chat: Chat, c: NoteConfig): Chat = listOf(
        ChatMeta.NOTE_PROMPT to JsonPrimitive(c.prompt), ChatMeta.NOTE_INTERVAL to JsonPrimitive(c.interval),
        ChatMeta.NOTE_DEPTH to JsonPrimitive(c.depth), ChatMeta.NOTE_POSITION to JsonPrimitive(c.position),
        ChatMeta.NOTE_ROLE to JsonPrimitive(c.role),
    ).fold(chat) { acc, (k, v) -> ChatMeta.with(acc, k, v) }

    /** SillyTavern's rule: interval 1 is always due, 0 never; otherwise every [interval]-th user message. */
    fun due(userMessages: Int, interval: Int): Boolean {
        if (interval <= 0) return false
        if (interval == 1) return true
        return userMessages > 0 && userMessages % interval == 0
    }

    /**
     * The note as an extension prompt, or null when it is not due or empty. [charNote] is the open character's note
     * (used only when `useChara`); [personaText] is merged in for persona positions TOP_AN (2) / BOTTOM_AN (3).
     */
    fun prompt(c: NoteConfig, history: List<ChatMessage>, charNote: CharacterNote?, personaText: String = "", personaPosition: Int = 0): ExtensionPrompt? {
        if (!due(history.count { it.isUser }, c.interval)) return null
        var text = c.prompt
        if (charNote != null && charNote.useChara && charNote.prompt.isNotEmpty()) {
            text = when (charNote.position) { 1 -> charNote.prompt + "\n" + text; 2 -> text + "\n" + charNote.prompt; else -> charNote.prompt }
        }
        if (personaText.isNotBlank()) {
            if (personaPosition == 2) text = personaText + "\n" + text
            else if (personaPosition == 3) text = text + "\n" + personaText
        }
        if (text.isBlank()) return null
        return ExtensionPrompt("authorsNote", text, c.position, c.depth, c.role)
    }
}
