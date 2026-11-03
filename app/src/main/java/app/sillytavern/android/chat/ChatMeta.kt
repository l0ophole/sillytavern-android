package app.sillytavern.android.chat

import kotlinx.serialization.json.*

/** Typed access to the `chat_metadata` object in a chat header. */
object ChatMeta {
    const val NOTE_PROMPT = "note_prompt"
    const val NOTE_INTERVAL = "note_interval"
    const val NOTE_DEPTH = "note_depth"
    const val NOTE_POSITION = "note_position"
    const val NOTE_ROLE = "note_role"
    const val MAIN_CHAT = "main_chat"
    const val PERSONA = "persona"

    fun of(chat: Chat?): JsonObject = chat?.header?.get("chat_metadata") as? JsonObject ?: JsonObject(emptyMap())

    fun string(meta: JsonObject, key: String): String? = (meta[key] as? JsonPrimitive)?.contentOrNull
    fun int(meta: JsonObject, key: String): Int? = (meta[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

    /** Returns [chat] with [key] set (or removed when [value] is null) in its metadata. */
    fun with(chat: Chat, key: String, value: JsonElement?): Chat {
        val meta = JsonObject(of(chat).toMutableMap().also { if (value == null) it.remove(key) else it[key] = value })
        return chat.copy(header = JsonObject(chat.header + ("chat_metadata" to meta)))
    }
}
