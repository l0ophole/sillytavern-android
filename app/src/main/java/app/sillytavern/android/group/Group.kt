package app.sillytavern.android.group

import kotlinx.serialization.json.*

object ActivationStrategy { const val NATURAL = 0; const val LIST = 1; const val MANUAL = 2; const val POOLED = 3 }
object GenerationMode { const val SWAP = 0; const val APPEND = 1; const val APPEND_DISABLED = 2 }

/** A group as SillyTavern stores it (`groups/<id>.json`); members are avatar file names with extension. */
data class Group(
    val id: String,
    val name: String,
    val members: List<String> = emptyList(),
    val disabledMembers: List<String> = emptyList(),
    val activationStrategy: Int = ActivationStrategy.NATURAL,
    val generationMode: Int = GenerationMode.SWAP,
    val allowSelfResponses: Boolean = false,
    val autoModeDelay: Int = 5,
    val joinPrefix: String = "",
    val joinSuffix: String = "",
    val fav: Boolean = false,
    val chatId: String,
    val chats: List<String> = listOf(chatId),
    val avatarUrl: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun isMuted(avatar: String) = avatar in disabledMembers

    fun toJson(): JsonObject = buildJsonObject {
        raw.forEach { (k, v) -> if (k !in known) put(k, v) }
        put("id", id); put("name", name)
        put("members", JsonArray(members.map(::JsonPrimitive)))
        put("avatar_url", avatarUrl)
        put("allow_self_responses", allowSelfResponses)
        put("activation_strategy", activationStrategy)
        put("generation_mode", generationMode)
        put("disabled_members", JsonArray(disabledMembers.map(::JsonPrimitive)))
        put("fav", fav)
        put("chat_id", chatId)
        put("chats", JsonArray(chats.map(::JsonPrimitive)))
        put("auto_mode_delay", autoModeDelay)
        put("generation_mode_join_prefix", joinPrefix)
        put("generation_mode_join_suffix", joinSuffix)
    }

    companion object {
        private val known = setOf("id", "name", "members", "avatar_url", "allow_self_responses", "activation_strategy", "generation_mode",
            "disabled_members", "fav", "chat_id", "chats", "auto_mode_delay", "generation_mode_join_prefix", "generation_mode_join_suffix")

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

        fun fromJson(o: JsonObject): Group {
            val id = o.str("id") ?: error("Group without id")
            val chatId = o.str("chat_id") ?: o.list("chats").firstOrNull() ?: id
            return Group(
                id = id, name = o.str("name") ?: "Group",
                members = o.list("members").distinct(), disabledMembers = o.list("disabled_members"),
                activationStrategy = (o["activation_strategy"] as? JsonPrimitive)?.intOrNull ?: 0,
                generationMode = (o["generation_mode"] as? JsonPrimitive)?.intOrNull ?: 0,
                allowSelfResponses = (o["allow_self_responses"] as? JsonPrimitive)?.booleanOrNull ?: false,
                autoModeDelay = (o["auto_mode_delay"] as? JsonPrimitive)?.intOrNull ?: 5,
                joinPrefix = o.str("generation_mode_join_prefix").orEmpty(), joinSuffix = o.str("generation_mode_join_suffix").orEmpty(),
                fav = (o["fav"] as? JsonPrimitive)?.booleanOrNull ?: false,
                chatId = chatId, chats = o.list("chats").ifEmpty { listOf(chatId) },
                avatarUrl = o.str("avatar_url").orEmpty(), raw = o,
            )
        }
    }
}
