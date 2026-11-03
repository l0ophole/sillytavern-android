package app.sillytavern.android.group

import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.data.writeAtomic
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class GroupRepository(private val paths: StoragePaths) {
    private val json = Json { prettyPrint = true }

    private fun fileFor(id: String) = File(paths.groups, StoragePaths.sanitizeFileName(id) + ".json")

    fun list(): List<Group> = (paths.groups.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f -> runCatching { Group.fromJson(Json.parseToJsonElement(f.readText()).jsonObject) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    fun get(id: String): Group? = fileFor(id).takeIf { it.exists() }
        ?.let { f -> runCatching { Group.fromJson(Json.parseToJsonElement(f.readText()).jsonObject) }.getOrNull() }

    fun create(name: String, members: List<String>): Group {
        var n = System.currentTimeMillis()
        while (fileFor(n.toString()).exists()) n++
        val chat = stamp()
        return Group(id = n.toString(), name = name.ifBlank { "Group" }, members = members.distinct(), chatId = chat, chats = listOf(chat))
            .also(::save)
    }

    fun save(g: Group) = fileFor(g.id).writeAtomic(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), g.toJson()))

    /** Deletes the group and all its chat files. */
    fun delete(id: String) {
        get(id)?.chats?.forEach { chatFile(it).delete() }
        fileFor(id).delete()
    }

    /** Adds a fresh chat to the group and makes it current. */
    fun addChat(g: Group): Group {
        var c = stamp(); var i = 1
        while (c in g.chats) c = stamp() + " " + i++
        return g.copy(chatId = c, chats = g.chats + c).also(::save)
    }

    fun chatFile(chatId: String) = File(paths.groupChats, StoragePaths.sanitizeFileName(chatId) + ".jsonl")

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd@HH'h'mm'm'ss's'"))
}
