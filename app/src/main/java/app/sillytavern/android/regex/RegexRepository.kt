package app.sillytavern.android.regex

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.data.writeAtomic
import java.io.File
import kotlinx.serialization.json.*

/** Global regex scripts (`regex/global.json`) and per-character scripts (`extensions.regex_scripts` of the card). */
class RegexRepository(private val paths: StoragePaths) {
    val root: File = paths.root
    private val json = Json { prettyPrint = true }
    private val file get() = File(paths.regexDir, "global.json")

    fun global(): List<RegexScript> = runCatching { parse(file.readText()) }.getOrDefault(emptyList())

    fun saveGlobal(list: List<RegexScript>) {
        paths.regexDir.mkdirs()
        file.writeAtomic(json.encodeToString(JsonArray.serializer(), JsonArray(list.map { it.toJson() })))
    }

    /** Accepts a single script object or an array; throws on anything else. */
    fun importJson(text: String): List<RegexScript> = parse(text).map { it.copy(id = java.util.UUID.randomUUID().toString()) }

    fun exportJson(s: RegexScript): String = json.encodeToString(JsonObject.serializer(), s.toJson())

    fun scopedOf(card: CharacterCard): List<RegexScript> =
        ((card.raw["extensions"] as? JsonObject)?.get("regex_scripts") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(RegexScript::fromJson) }.orEmpty()

    fun withScoped(card: CharacterCard, list: List<RegexScript>): CharacterCard {
        val ext = card.raw["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val newExt = JsonObject(ext + ("regex_scripts" to JsonArray(list.map { it.toJson() })))
        return card.copy(raw = JsonObject(card.raw + ("extensions" to newExt)))
    }

    private fun parse(text: String): List<RegexScript> = when (val e = Json.parseToJsonElement(text)) {
        is JsonArray -> e.mapNotNull { (it as? JsonObject)?.let(RegexScript::fromJson) }
        is JsonObject -> listOf(RegexScript.fromJson(e))
        else -> throw IllegalArgumentException("Not a regex script")
    }
}
