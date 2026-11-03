package app.sillytavern.android.chat

import java.time.Instant
import kotlinx.serialization.json.*

/** Chat import converters and export formats (SillyTavern `src/endpoints/chats.js`). */
object ChatIo {
    private val json = Json { ignoreUnknownKeys = true }

    /** Header SillyTavern writes for converted chats. */
    private fun unusedHeader() = buildJsonObject {
        put("chat_metadata", JsonObject(emptyMap()))
        put("user_name", "unused")
        put("character_name", "unused")
    }

    private fun line(name: String, isUser: Boolean, text: String) =
        ChatMessage(name = name, isUser = isUser, sendDate = Instant.now().toString(), swipes = listOf(text))

    /**
     * Converts [text] (an ST `.jsonl` chat or a `.json` export of Ooba, Agnai, CAI Tools or Kobold Lite) into chats.
     * [userName] / [characterName] name the two sides for formats that do not store names.
     */
    fun import(text: String, userName: String, characterName: String, repo: ChatRepository): Result<List<Chat>> = runCatching {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "The file is empty" }
        val whole = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
        when {
            whole != null && whole["savedsettings"] != null -> listOf(kobold(whole))
            whole != null && whole["histories"] != null -> cai(whole, userName, characterName)
            whole != null && whole["data_visible"] is JsonArray -> listOf(ooba(whole, userName, characterName))
            whole != null && whole["messages"] is JsonArray && whole["mes"] == null -> listOf(agnai(whole, userName, characterName))
            else -> listOf(stJsonl(trimmed, repo))
        }
    }

    private fun stJsonl(text: String, repo: ChatRepository): Chat {
        val first = runCatching { json.parseToJsonElement(text.lineSequence().first { it.isNotBlank() }) as? JsonObject }.getOrNull()
        require(first != null && ("user_name" in first || "name" in first || "chat_metadata" in first)) { "This is not a SillyTavern chat file" }
        // Chub exports keep swipes as objects with a `message` field: flatten them.
        val flat = text.lines().filter { it.isNotBlank() }.joinToString("\n") { l ->
            val o = runCatching { json.parseToJsonElement(l) as? JsonObject }.getOrNull() ?: return@joinToString l
            val sw = o["swipes"] as? JsonArray ?: return@joinToString l
            if (sw.none { it is JsonObject }) return@joinToString l
            val flatSwipes = sw.map { (it as? JsonObject)?.get("message") ?: it }
            JsonObject(o + ("swipes" to JsonArray(flatSwipes))).toString()
        }
        val chat = repo.parse(flat, null)
        require(chat.messages.isNotEmpty()) { "The chat has no messages" }
        return chat
    }

    private fun ooba(o: JsonObject, user: String, char: String): Chat {
        val rows = o["data_visible"] as JsonArray
        val msgs = rows.flatMap { r ->
            val a = r as? JsonArray ?: return@flatMap emptyList()
            listOfNotNull(
                a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { line(user, true, it) },
                a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { line(char, false, it) },
            )
        }
        return Chat(null, unusedHeader(), msgs)
    }

    private fun agnai(o: JsonObject, user: String, char: String): Chat {
        val msgs = (o["messages"] as JsonArray).mapNotNull { m ->
            val mo = m as? JsonObject ?: return@mapNotNull null
            val isUser = mo["userId"]?.let { it !is JsonNull && (it as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true } == true
            val text = (mo["msg"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            line(if (isUser) user else char, isUser, text)
        }
        return Chat(null, unusedHeader(), msgs)
    }

    private fun cai(o: JsonObject, user: String, char: String): List<Chat> {
        val histories = ((o["histories"] as? JsonObject)?.get("histories") as? JsonArray).orEmpty()
        return histories.mapNotNull { h ->
            val msgs = ((h as? JsonObject)?.get("msgs") as? JsonArray)?.mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                val human = ((mo["src"] as? JsonObject)?.get("is_human") as? JsonPrimitive)?.booleanOrNull ?: false
                val text = (mo["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                line(if (human) user else char, human, text)
            }
            msgs?.let { Chat(null, unusedHeader(), it) }
        }.also { require(it.isNotEmpty()) { "No chats found in this CAI Tools export" } }
    }

    private fun kobold(o: JsonObject): Chat {
        val settings = o["savedsettings"] as? JsonObject
        val user = (settings?.get("chatname") as? JsonPrimitive)?.contentOrNull ?: "User"
        val char = ((settings?.get("chatopponent") as? JsonPrimitive)?.contentOrNull ?: "Character").split("||$||").first()
        fun conv(s: String): ChatMessage {
            val isUser = s.contains("{{[INPUT]}}")
            return line(if (isUser) user else char, isUser, s.replace("{{[INPUT]}}", "").replace("{{[OUTPUT]}}", "").trim())
        }
        val actions = (o["actions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(::conv)
        val prompt = (o["prompt"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let(::conv)
        return Chat(null, unusedHeader(), listOfNotNull(prompt) + actions)
    }

    /** Plain-text export: `Name: text` blocks separated by blank lines, system lines skipped (as SillyTavern does). */
    fun exportText(messages: List<ChatMessage>): String = buildString {
        for (m in messages) {
            if (m.isSystem || m.text.isEmpty()) continue
            val shown = (m.extra["display_text"] as? JsonPrimitive)?.contentOrNull ?: m.text
            append(m.name).append(": ").append(shown.replace("\r\n", "\n")).append("\n\n")
        }
    }
}
