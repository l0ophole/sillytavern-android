package app.sillytavern.android.worldinfo

import app.sillytavern.android.card.CardCodec
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.data.writeAtomic
import java.io.File
import kotlinx.serialization.json.*

/** World books stored as `worlds/<name>.json` in SillyTavern's world-file format. */
class WorldBookRepository(private val paths: StoragePaths) {
    private fun fileFor(name: String) = File(paths.worlds, StoragePaths.sanitizeFileName(name) + ".json")

    fun list(): List<String> =
        (paths.worlds.listFiles { f -> f.extension.equals("json", true) } ?: emptyArray())
            .map { it.nameWithoutExtension }.sortedBy { it.lowercase() }

    fun load(name: String): WorldBook? {
        val f = fileFor(name)
        if (!f.isFile) return null
        return runCatching { WorldJson.decode(name, f.readText()) }.getOrNull()
    }

    fun save(book: WorldBook) { fileFor(book.name).writeAtomic(WorldJson.encode(book)) }

    fun delete(name: String) { fileFor(name).delete() }

    fun export(name: String): String? = load(name)?.let(WorldJson::encode)

    /** Imports a world file, a bare `character_book`, or a character card carrying one. Returns the book name. */
    fun import(fileName: String, text: String): Result<String> = runCatching {
        val root = try { Json.parseToJsonElement(text.trimStart(0xFEFF.toChar())).jsonObject } catch (e: Exception) { error("Not a valid JSON object") }
        val base = fileName.substringAfterLast('/').removeSuffix(".json").ifBlank { "Imported" }
        val book: WorldBook = when {
            root["entries"] is JsonObject -> WorldJson.decode(base, text)
            root["entries"] is JsonArray -> {
                val first = (root["entries"] as JsonArray).firstOrNull() as? JsonObject
                if (first == null || first.containsKey("keys")) {
                    WorldJson.fromCharacterBook(root["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: base, root)
                } else WorldJson.decode(base, text)
            }
            else -> {
                val card = runCatching { CardCodec.parse(text) }.getOrNull() ?: error("This does not look like a world book")
                val cb = card.raw["character_book"] as? JsonObject ?: error("The card has no embedded lorebook")
                WorldJson.fromCharacterBook(cb["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "${card.name} lore", cb)
            }
        }
        save(book)
        book.name
    }
}
