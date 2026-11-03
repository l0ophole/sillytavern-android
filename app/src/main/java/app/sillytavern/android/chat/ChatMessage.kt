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

    /** `/sys` narrator lines are system messages that still go into the prompt. */
    val isNarrator: Boolean get() = extra["type"]?.jsonPrimitive?.contentOrNull == "narrator"
    val isHidden: Boolean get() = extra["hidden"]?.jsonPrimitive?.booleanOrNull == true
    /** Whether the message is sent to the model (hidden and plain system messages are not). */
    val inPrompt: Boolean get() = (!isSystem || isNarrator) && !isHidden

    fun withHidden(h: Boolean): ChatMessage {
        val newExtra = buildJsonObject {
            extra.forEach { (k, v) -> if (k != "hidden") put(k, v) }
            if (h) put("hidden", true)
        }
        return copy(extra = newExtra, isSystem = if (isNarrator) true else h)
    }

    /** Model reasoning/thinking for the current reply (SillyTavern keeps it in `extra.reasoning`). */
    val reasoning: String? get() = extra["reasoning"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun withReasoning(r: String?): ChatMessage = copy(extra = buildJsonObject {
        extra.forEach { (k, v) -> if (k != "reasoning") put(k, v) }
        if (!r.isNullOrBlank()) put("reasoning", r)
    })

    /**
     * Text shown instead of the message (translation). SillyTavern keeps it in `extra.display_text`; `display_text_for`
     * remembers which text it belongs to, so an edited or swiped message falls back to its own text.
     */
    val displayOverride: String? get() {
        val shown = (extra["display_text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val forHash = (extra["display_text_for"] as? JsonPrimitive)?.contentOrNull
        return shown.takeIf { forHash == null || forHash == text.hashCode().toString() }
    }

    fun withDisplayText(shown: String?): ChatMessage =
        if (shown == null) withExtra("display_text", null).withExtra("display_text_for", null)
        else withExtra("display_text", JsonPrimitive(shown)).withExtra("display_text_for", JsonPrimitive(text.hashCode().toString()))

    /** Checkpoint chat linked from this message (`extra.bookmark_link`). */
    val bookmarkLink: String? get() = (extra["bookmark_link"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    /** Branch chats created from this message (`extra.branches`). */
    val branches: List<String> get() = (extra["branches"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    fun withExtra(key: String, value: JsonElement?): ChatMessage = copy(extra = buildJsonObject {
        extra.forEach { (k, v) -> if (k != key) put(k, v) }
        if (value != null) put(key, value)
    })

    /** Group chats: avatar file name (with extension) of the member who wrote this message. */
    val originalAvatar: String? get() = (rest["original_avatar"] as? JsonPrimitive)?.contentOrNull
    fun withOriginalAvatar(a: String): ChatMessage = copy(rest = JsonObject(rest + ("original_avatar" to JsonPrimitive(a))))

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
        fun narrator(text: String, name: String = "System") = ChatMessage(
            name, isUser = false, isSystem = true, swipes = listOf(text),
            extra = buildJsonObject { put("type", "narrator") },
        )
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
