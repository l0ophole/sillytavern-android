package app.sillytavern.android.chat

import kotlinx.serialization.json.*

/** Pure helpers for chat branches and checkpoints (SillyTavern `bookmarks.js`). */
object ChatBranching {
    private val branchSuffix = Regex(""" - Branch #\d+$""")
    private val branchPrefix = Regex("""^Branch #\d+ - """)
    private val checkpointSuffix = Regex(""" - Checkpoint #\d+$""")
    private val checkpointPrefix = Regex("""^Checkpoint #\d+ - """)

    fun branchName(base: String, i: Int) = "${base.replace(branchSuffix, "").replace(branchPrefix, "")} - Branch #$i"
    fun checkpointName(base: String, i: Int) = "${base.replace(checkpointSuffix, "").replace(checkpointPrefix, "")} - Checkpoint #$i"

    /** First `build(base, i)` for i = 1.. that [exists] does not report; null after 1000 tries. */
    fun uniqueName(base: String, build: (String, Int) -> String, exists: (String) -> Boolean): String? =
        (1..1000).map { build(base, it) }.firstOrNull { !exists(it) }

    /** Messages `0..mesId`; with [swipeId] the last message shows that swipe. Null when an index is invalid. */
    fun snapshot(messages: List<ChatMessage>, mesId: Int, swipeId: Int? = null): List<ChatMessage>? {
        if (mesId !in messages.indices) return null
        val head = messages.take(mesId + 1)
        if (swipeId == null) return head
        val last = head.last()
        if (swipeId !in last.swipes.indices) return null
        return head.dropLast(1) + last.selectSwipe(swipeId)
    }

    /** Header for a derived chat: the source metadata plus `main_chat` pointing at the parent chat. */
    fun childHeader(header: JsonObject, parentName: String): JsonObject {
        val meta = header["chat_metadata"] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(header + ("chat_metadata" to JsonObject(meta + (ChatMeta.MAIN_CHAT to JsonPrimitive(parentName)))))
    }

    fun withBranch(m: ChatMessage, name: String): ChatMessage =
        m.withExtra("branches", JsonArray((m.branches + name).map(::JsonPrimitive)))

    fun withCheckpoint(m: ChatMessage, name: String): ChatMessage = m.withExtra("bookmark_link", JsonPrimitive(name))

    /** (message index, checkpoint chat name) for every message that links a checkpoint. */
    fun checkpoints(messages: List<ChatMessage>): List<Pair<Int, String>> =
        messages.mapIndexedNotNull { i, m -> m.bookmarkLink?.let { i to it } }
}
