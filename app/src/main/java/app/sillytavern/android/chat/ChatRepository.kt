package app.sillytavern.android.chat

import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.data.writeAtomic
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

/** Chat header line (first JSONL line) plus messages. [file] is null until first saved. */
data class Chat(
    val file: File?,
    val header: JsonObject,
    val messages: List<ChatMessage>,
)

class ChatRepository(private val paths: StoragePaths) {
    private val json = Json { ignoreUnknownKeys = true }

    fun list(characterFileName: String): List<File> =
        (paths.chatDir(characterFileName).listFiles { f -> f.extension == "jsonl" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    fun create(characterFileName: String, characterName: String, userName: String, greeting: String?): Chat {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd@HH'h'mm'm'ss's'"))
        var file = File(paths.chatDir(characterFileName), "${StoragePaths.sanitizeFileName(characterName)} - $stamp.jsonl")
        var n = 1
        while (file.exists()) file = File(file.parentFile, "${file.nameWithoutExtension} ${n++}.jsonl")
        val header = buildJsonObject {
            put("user_name", userName)
            put("character_name", characterName)
            put("create_date", stamp)
            put("chat_metadata", JsonObject(emptyMap()))
        }
        val msgs = if (!greeting.isNullOrBlank()) listOf(ChatMessage.assistant(characterName, greeting)) else emptyList()
        return Chat(file, header, msgs).also(::save)
    }

    /** Creates a group chat file (ST header with placeholder names) holding [initial] messages. */
    fun createGroupChat(file: File, initial: List<ChatMessage>): Chat {
        val header = buildJsonObject {
            put("chat_metadata", JsonObject(emptyMap()))
            put("user_name", "unused")
            put("character_name", "unused")
        }
        file.parentFile?.mkdirs()
        return Chat(file, header, initial).also(::save)
    }

    fun load(file: File): Chat {
        val lines = file.readLines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "Empty chat file" }
        val first = json.parseToJsonElement(lines.first()).jsonObject
        // SillyTavern header has no "mes"; tolerate files lacking a header.
        val hasHeader = "mes" !in first
        val msgLines = if (hasHeader) lines.drop(1) else lines
        return Chat(
            file,
            if (hasHeader) first else JsonObject(emptyMap()),
            msgLines.mapNotNull { runCatching { ChatMessage.fromJson(json.parseToJsonElement(it).jsonObject) }.getOrNull() },
        )
    }

    fun save(chat: Chat) {
        val f = requireNotNull(chat.file) { "Chat has no file" }
        val text = (listOf(chat.header) + chat.messages.map { it.toJson() }).joinToString("\n") { it.toString() } + "\n"
        f.writeAtomic(text)
    }

    /** Renames the chat file (extension kept, name sanitized); returns the new file, or the old one if the name is taken. */
    fun rename(file: File, newBaseName: String): File {
        val target = File(file.parentFile, StoragePaths.sanitizeFileName(newBaseName) + ".jsonl")
        if (target == file || target.exists()) return file
        return if (file.renameTo(target)) target else file
    }

    fun delete(file: File) {
        file.delete()
    }
}
