package app.sillytavern.android.prompt.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/**
 * Lossless ST preset (de)serialization: keys the model doesn't know are carried in `extra` on decode
 * and merged back on encode, so re-exporting never drops fields.
 */
object PresetJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; isLenient = true }
    private const val EXTRA = "extra"

    fun <T> decode(serializer: KSerializer<T>, text: String): T = decodeObject(serializer, json.parseToJsonElement(text).jsonObject)

    private fun <T> decodeObject(serializer: KSerializer<T>, obj: JsonObject, drop: Set<String> = emptySet()): T {
        val known = (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }.toSet()
        val extra = JsonObject(obj.filterKeys { it !in known && it !in drop })
        val withExtra = JsonObject(obj.filterKeys { it in known && it != EXTRA } + (EXTRA to extra))
        return json.decodeFromJsonElement(serializer, withExtra)
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(JsonObject.serializer(), encodeObject(serializer, value))

    private fun <T> encodeObject(serializer: KSerializer<T>, value: T): JsonObject {
        val obj = json.encodeToJsonElement(serializer, value).jsonObject
        val extra = obj[EXTRA]?.jsonObject ?: JsonObject(emptyMap())
        return JsonObject(extra + obj.filterKeys { it != EXTRA })
    }

    // --- Prompt Manager presets store ordering under ST's `prompt_order` (per character_id) ---
    private const val GLOBAL_ID = 100001
    private const val DEFAULT_ID = 100000

    fun decodePromptManager(text: String): PromptManagerPreset {
        val obj = json.parseToJsonElement(text).jsonObject
        val entries = (obj["prompt_order"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val chosen = entries.firstOrNull { it["character_id"]?.jsonPrimitive?.intOrNull == GLOBAL_ID }
            ?: entries.firstOrNull { it["character_id"]?.jsonPrimitive?.intOrNull == DEFAULT_ID }
            ?: entries.firstOrNull()
        val order = chosen?.get("order") ?: JsonArray(emptyList())
        val prompts = (obj["prompts"] as? JsonArray)?.filter { (it as? JsonObject)?.containsKey("identifier") == true }
            ?: JsonArray(emptyList())
        val patched = JsonObject(obj + mapOf("order" to order, "prompts" to JsonArray(prompts)))
        // "prompt_order" stays in extra verbatim; encodePromptManager() rewrites the chosen entry.
        return decodeObject(PromptManagerPreset.serializer(), patched)
    }

    fun encodePromptManager(p: PromptManagerPreset): String {
        val obj = encodeObject(PromptManagerPreset.serializer(), p)
        val orderJson = obj["order"] ?: JsonArray(emptyList())
        val existing = (obj["prompt_order"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val target = existing.firstOrNull { it["character_id"]?.jsonPrimitive?.intOrNull == GLOBAL_ID }
            ?: existing.firstOrNull { it["character_id"]?.jsonPrimitive?.intOrNull == DEFAULT_ID }
        val promptOrder = if (target == null) {
            existing + buildJsonObject { put("character_id", GLOBAL_ID); put("order", orderJson) }
        } else existing.map { if (it === target) JsonObject(it + ("order" to orderJson)) else it }
        val out = JsonObject(obj.filterKeys { it != "order" } + ("prompt_order" to JsonArray(promptOrder)))
        return json.encodeToString(JsonObject.serializer(), out)
    }
}
