package app.sillytavern.android.card

import kotlinx.serialization.json.*

/**
 * Character card (Tavern V1 / V2 / V3). Typed fields cover what the app edits; [raw] keeps the original
 * "data" object so unknown fields (character_book, extensions, ...) survive an edit/export round trip.
 */
data class CharacterCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val messageExample: String = "",
    val creatorNotes: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    private fun extra(key: String): JsonPrimitive? =
        ((raw["extensions"] as? JsonObject)?.get(key) as? JsonPrimitive) ?: raw[key] as? JsonPrimitive

    /** Favorite flag (`extensions.fav`; V1 cards may store the string "true"). */
    val fav: Boolean get() = extra("fav")?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

    /** How eager the character is to speak in natural-order group chats, 0..1 (default 0.5). */
    val talkativeness: Double get() = (extra("talkativeness")?.doubleOrNull ?: 0.5).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.5

    private fun withExtension(key: String, value: JsonElement): CharacterCard {
        val ext = JsonObject(((raw["extensions"] as? JsonObject) ?: JsonObject(emptyMap())) + (key to value))
        return copy(raw = JsonObject(raw + ("extensions" to ext)))
    }
    fun withFav(f: Boolean) = withExtension("fav", JsonPrimitive(f))
    fun withTalkativeness(t: Double) = withExtension("talkativeness", JsonPrimitive(t.coerceIn(0.0, 1.0)))

    /** Greetings the user can pick from: first message then alternates. */
    val greetings get() = (listOf(firstMessage) + alternateGreetings).filter { it.isNotBlank() }
}

object CardCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(text: String): CharacterCard {
        val root = json.parseToJsonElement(text).jsonObject
        val spec = root["spec"]?.jsonPrimitive?.contentOrNull
        val data = if (spec != null && root["data"] is JsonObject) root["data"]!!.jsonObject else root
        require(data["name"] != null) { "Not a character card (missing name)" }
        fun s(k: String) = data[k]?.jsonPrimitive?.contentOrNull ?: ""
        fun list(k: String) = (data[k] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        return CharacterCard(
            name = s("name"),
            description = s("description"),
            personality = s("personality"),
            scenario = s("scenario"),
            firstMessage = s("first_mes"),
            messageExample = s("mes_example"),
            creatorNotes = s("creator_notes"),
            systemPrompt = s("system_prompt"),
            postHistoryInstructions = s("post_history_instructions"),
            alternateGreetings = list("alternate_greetings"),
            tags = list("tags"),
            creator = s("creator"),
            characterVersion = s("character_version"),
            raw = data,
        )
    }

    /** Serialise as chara_card_v2 with SillyTavern's legacy flat fields mirrored at the top level. */
    fun toJson(card: CharacterCard, spec: String = "chara_card_v2"): String {
        val data = buildJsonObject {
            card.raw.forEach { (k, v) -> put(k, v) }
            put("name", card.name)
            put("description", card.description)
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.firstMessage)
            put("mes_example", card.messageExample)
            put("creator_notes", card.creatorNotes)
            put("system_prompt", card.systemPrompt)
            put("post_history_instructions", card.postHistoryInstructions)
            put("alternate_greetings", JsonArray(card.alternateGreetings.map(::JsonPrimitive)))
            put("tags", JsonArray(card.tags.map(::JsonPrimitive)))
            put("creator", card.creator)
            put("character_version", card.characterVersion)
            if ("extensions" !in card.raw) put("extensions", JsonObject(emptyMap()))
        }
        val root = buildJsonObject {
            put("spec", spec)
            put("spec_version", if (spec == "chara_card_v3") "3.0" else "2.0")
            put("name", card.name)
            put("description", card.description)
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.firstMessage)
            put("mes_example", card.messageExample)
            put("data", data)
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** Reads a card from PNG bytes (ccv3 preferred over chara) or raw JSON bytes. */
    fun fromBytes(bytes: ByteArray): CharacterCard {
        if (!PngChunks.isPng(bytes)) return parse(String(bytes, Charsets.UTF_8).trimStart(0xFEFF.toChar()))
        val text = PngChunks.textChunks(bytes)
        val b64 = text["ccv3"] ?: text["chara"] ?: error("PNG has no character data")
        return parse(String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8))
    }

    fun toPng(card: CharacterCard, avatarPng: ByteArray): ByteArray {
        val enc = java.util.Base64.getEncoder()
        return PngChunks.withText(
            avatarPng,
            mapOf(
                "chara" to enc.encodeToString(toJson(card).toByteArray(Charsets.UTF_8)),
                "ccv3" to enc.encodeToString(toJson(card, "chara_card_v3").toByteArray(Charsets.UTF_8)),
            ),
        )
    }
}
