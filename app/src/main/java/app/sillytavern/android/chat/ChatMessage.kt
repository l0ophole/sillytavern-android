package app.sillytavern.android.chat

import java.time.Instant
import kotlinx.serialization.json.*

/** One chat line, SillyTavern JSONL compatible. Unknown keys are kept in [rest]. */
data class ChatMessage(
    val name: String,
    val isUser: Boolean,
    val isSystem: Boolean = false,
    val sendDate: String = Instant.now().toString(),
    val swipes: List<String>,
    val swipeId: Int = 0,
    val swipeInfo: JsonArray = JsonArray(emptyList()),
    val extra: JsonObject = JsonObject(emptyMap()),
    val rest: JsonObject = JsonObject(emptyMap()),
) {
    val text: String get() = swipes.getOrElse(swipeId) { swipes.firstOrNull() ?: "" }

    fun withText(t: String): ChatMessage =
        copy(swipes = swipes.toMutableList().also { if (it.isEmpty()) it += t else it[swipeId.coerceIn(it.indices)] = t })

    fun addSwipe(t: String) = copy(swipes = swipes + t, swipeId = swipes.size)
    fun selectSwipe(i: Int) = copy(swipeId = i.coerceIn(0, (swipes.size - 1).coerceAtLeast(0)))

    fun toJson(): JsonObject = buildJsonObject {
        rest.forEach { (k, v) -> put(k, v) }
        put("name", name)
        put("is_user", isUser)
        put("is_system", isSystem)
        put("send_date", sendDate)
        put("mes", text)
        put("swipes", JsonArray(swipes.map(::JsonPrimitive)))
        put("swipe_id", swipeId)
        put("swipe_info", swipeInfo)
        put("extra", extra)
    }

    companion object {
        private val known = setOf("name", "is_user", "is_system", "send_date", "mes", "swipes", "swipe_id", "swipe_info", "extra")

        fun user(name: String, text: String) = ChatMessage(name, isUser = true, swipes = listOf(text))
        fun assistant(name: String, text: String) = ChatMessage(name, isUser = false, swipes = listOf(text))

        fun fromJson(o: JsonObject): ChatMessage {
            val mes = o["mes"]?.jsonPrimitive?.contentOrNull ?: ""
            val swipes = (o["swipes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotEmpty() } ?: listOf(mes)
            val id = o["swipe_id"]?.jsonPrimitive?.intOrNull ?: 0
            return ChatMessage(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                isUser = o["is_user"]?.jsonPrimitive?.booleanOrNull ?: false,
                isSystem = o["is_system"]?.jsonPrimitive?.booleanOrNull ?: false,
                sendDate = o["send_date"]?.jsonPrimitive?.contentOrNull ?: "",
                swipes = swipes,
                swipeId = id.coerceIn(0, swipes.size - 1),
                swipeInfo = o["swipe_info"] as? JsonArray ?: JsonArray(emptyList()),
                extra = o["extra"] as? JsonObject ?: JsonObject(emptyMap()),
                rest = JsonObject(o.filterKeys { it !in known }),
            )
        }
    }
}
