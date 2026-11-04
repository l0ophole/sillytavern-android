package app.sillytavern.android.polish

import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.chat.ChatRepository
import app.sillytavern.android.chat.ChatUiState
import app.sillytavern.android.data.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class PolishSettings(
    /** Run a foreground service while a reply is generating so the system does not kill the process. */
    val keepAlive: Boolean = true,
    /** Post a notification when a reply finishes while the app is in the background. */
    val notifyReplies: Boolean = true,
    /** Stop group auto-mode and postpone auto-backups while Battery Saver is on. */
    val respectBatterySaver: Boolean = true,
)

/** What the platform layer must do; implemented by the Android notifier and by a fake in tests. */
interface GenerationSink {
    fun running(characterName: String)
    fun idle()
    fun replyReady(characterName: String, preview: String, characterFile: String?, groupId: String?)
}

/**
 * Turns [ChatUiState] changes into [GenerationSink] calls: `running` when a reply starts, `idle` when it ends,
 * and `replyReady` when it ended with a new character message while the app was not in the foreground.
 */
class GenerationWatcher(
    private val states: Flow<ChatUiState>,
    private val settings: () -> PolishSettings,
    private val foreground: () -> Boolean,
    private val sink: GenerationSink,
    private val previewLength: Int = 120,
) {
    private var wasGenerating = false
    private var messagesAtStart = 0

    fun start(scope: CoroutineScope): Job = scope.launch {
        states.distinctUntilChangedBy { Triple(it.generating, it.error, it.chat?.messages?.size) }.collect(::onState)
    }

    internal fun onState(s: ChatUiState) {
        val name = s.group?.name ?: s.character?.card?.name ?: ""
        if (s.generating && !wasGenerating) {
            wasGenerating = true
            messagesAtStart = s.chat?.messages?.size ?: 0
            if (settings().keepAlive) sink.running(name)
        } else if (!s.generating && wasGenerating) {
            wasGenerating = false
            sink.idle()
            val last = s.chat?.messages?.lastOrNull()
            val produced = last != null && !last.isUser && !last.isSystem && (s.chat?.messages?.size ?: 0) > messagesAtStart
            if (produced && s.error == null && settings().notifyReplies && !foreground()) {
                val text = last!!.text.replace(Regex("\\s+"), " ").trim()
                val preview = if (text.length > previewLength) text.take(previewLength).trimEnd() + "…" else text
                sink.replyReady(last.name.ifBlank { name }, preview, s.character?.fileName, s.group?.id)
            }
        }
    }
}

/** The chat that was written to last, for the home-screen widget. */
data class RecentChat(val characterFile: String, val characterName: String, val lastLine: String)

object RecentChatFinder {
    fun find(paths: StoragePaths, characters: CharacterRepository, chats: ChatRepository): RecentChat? {
        val dirs = paths.chats.listFiles { f -> f.isDirectory } ?: return null
        val newest = dirs.mapNotNull { d ->
            val chat = d.listFiles { f -> f.extension == "jsonl" }?.maxByOrNull { it.lastModified() } ?: return@mapNotNull null
            d.name to chat
        }.sortedByDescending { it.second.lastModified() }
        for ((dirName, file) in newest) {
            // The folder is named like the character file; skip chats of characters that no longer exist.
            val ch = characters.get(dirName) ?: continue
            val last = runCatching { chats.load(file).messages.lastOrNull { !it.isSystem } }.getOrNull()
            return RecentChat(ch.fileName, ch.card.name, last?.text?.replace(Regex("\\s+"), " ")?.trim()?.take(140).orEmpty())
        }
        return null
    }
}

enum class ShareKind { CARD, CHAT, UNKNOWN }

data class SharedItem(val name: String, val bytes: ByteArray)

data class ShareResult(val characters: List<String>, val chats: Int, val notes: List<String>) {
    val imported get() = characters.size + chats
    fun summary(): String = buildString {
        append(if (imported == 0) "Nothing imported" else "Imported ${characters.size} character(s), $chats chat(s)")
        notes.forEach { append("\n").append(it) }
    }
}

/** Imports files shared from other apps. Classifies by content, never by mime type or extension. */
class ShareImporter(
    private val characters: CharacterRepository,
    private val chats: ChatRepository,
    private val userName: () -> String,
    private val maxBytes: Int = 50 * 1024 * 1024,
) {
    fun classify(bytes: ByteArray): ShareKind {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
            return if (runCatching { app.sillytavern.android.card.CardCodec.fromBytes(bytes) }.isSuccess) ShareKind.CARD else ShareKind.UNKNOWN
        }
        val text = runCatching { String(bytes, Charsets.UTF_8).trimStart(0xFEFF.toChar()).trim() }.getOrNull() ?: return ShareKind.UNKNOWN
        if (!text.startsWith("{") && !text.startsWith("[")) return ShareKind.UNKNOWN
        val whole = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (whole != null) {
            if (whole["spec"]?.jsonPrimitive?.contentOrNull?.startsWith("chara_card") == true) return ShareKind.CARD
            if ("first_mes" in whole || ("name" in whole && "description" in whole)) return ShareKind.CARD
        }
        // A chat is JSONL: the first line is a header object or a message.
        val first = runCatching { Json.parseToJsonElement(text.lineSequence().first { it.isNotBlank() }) as? JsonObject }.getOrNull()
        if (first != null && ("chat_metadata" in first || "user_name" in first || "mes" in first)) return ShareKind.CHAT
        if (whole != null && ("data_visible" in whole || "histories" in whole || "savedsettings" in whole || "messages" in whole)) return ShareKind.CHAT
        return ShareKind.UNKNOWN
    }

    fun import(items: List<SharedItem>): ShareResult {
        val chars = ArrayList<String>(); var chatCount = 0; val notes = ArrayList<String>()
        for (item in items) {
            if (item.bytes.size > maxBytes) { notes += "${item.name}: too large"; continue }
            when (classify(item.bytes)) {
                ShareKind.CARD -> runCatching { characters.import(item.bytes) }
                    .onSuccess { chars += it.fileName }.onFailure { notes += "${item.name}: ${it.message}" }
                ShareKind.CHAT -> importChat(item, notes)?.let { chatCount++ }
                ShareKind.UNKNOWN -> notes += "${item.name}: not a character card or chat"
            }
        }
        return ShareResult(chars, chatCount, notes)
    }

    private fun importChat(item: SharedItem, notes: MutableList<String>): File? {
        val text = String(item.bytes, Charsets.UTF_8)
        val headerName = runCatching {
            val o = Json.parseToJsonElement(text.lineSequence().first { it.isNotBlank() }) as? JsonObject
            (o?.get("character_name") ?: o?.get("name"))?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val matches = characters.list().filter { it.card.name == headerName }
        val target = matches.singleOrNull()
        if (target == null) {
            notes += "${item.name}: " + if (matches.isEmpty()) "no character named \"${headerName.orEmpty()}\"; open the character and use Import chat" else "several characters share the name \"$headerName\"; open one and use Import chat"
            return null
        }
        val imported = app.sillytavern.android.chat.ChatIo.import(text, userName(), target.card.name, chats)
            .getOrElse { notes += "${item.name}: ${it.message}"; return null }
        var last: File? = null
        val dir = chats.dirFor(target.fileName)
        for ((i, c) in imported.withIndex()) {
            val base = "${StoragePaths.sanitizeFileName(target.card.name)} - shared ${System.currentTimeMillis()}" + if (i > 0) " ${i + 1}" else ""
            var file = File(dir, "$base.jsonl"); var n = 1
            while (file.exists()) file = File(dir, "$base ${n++}.jsonl")
            chats.save(c.copy(file = file)); last = file
        }
        return last
    }
}

/** Battery Saver rules, kept pure so they can be tested. */
object PowerPolicy {
    fun pauseAuto(powerSave: Boolean, s: PolishSettings) = powerSave && s.respectBatterySaver
    fun postponeBackup(powerSave: Boolean, s: PolishSettings) = powerSave && s.respectBatterySaver
}
