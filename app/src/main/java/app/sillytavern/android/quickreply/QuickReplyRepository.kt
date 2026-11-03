package app.sillytavern.android.quickreply

import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.data.writeAtomic
import java.io.File
import kotlinx.serialization.json.Json

class QuickReplyRepository(private val paths: StoragePaths) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private fun file(name: String) = File(paths.quickReplies, StoragePaths.sanitizeFileName(name) + ".json")

    fun list(): List<String> = (paths.quickReplies.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f -> runCatching { json.decodeFromString(QuickReplySet.serializer(), f.readText()).name.ifBlank { f.nameWithoutExtension } }.getOrNull() }
        .sorted()

    fun load(name: String): QuickReplySet? = runCatching {
        json.decodeFromString(QuickReplySet.serializer(), file(name).readText()).let { if (it.name.isBlank()) it.copy(name = name) else it }
    }.getOrNull()

    fun save(set: QuickReplySet) {
        paths.quickReplies.mkdirs()
        file(set.name).writeAtomic(json.encodeToString(QuickReplySet.serializer(), set))
    }

    fun delete(name: String) { file(name).delete() }

    /** Imports a set file; a name collision gets " (2)", " (3)", … */
    fun importJson(text: String): QuickReplySet {
        val parsed = json.decodeFromString(QuickReplySet.serializer(), text)
        require(parsed.name.isNotBlank()) { "Quick Reply set has no name" }
        var name = parsed.name; var n = 2
        while (file(name).exists()) name = "${parsed.name} (${n++})"
        return parsed.copy(name = name).also(::save)
    }

    fun exportJson(set: QuickReplySet): String = json.encodeToString(QuickReplySet.serializer(), set)
}
