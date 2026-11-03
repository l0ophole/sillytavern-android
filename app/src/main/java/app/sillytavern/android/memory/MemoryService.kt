package app.sillytavern.android.memory

import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.prompt.ExtensionPrompt

/** What retrieval needs to know about the open chat. */
class MemoryContext(
    /** Chat file name without extension; null for unsaved chats. */
    val chatKey: String?,
    /** Character file name (or group id) used for character-scoped Data Bank documents. */
    val characterKey: String?,
    val history: List<ChatMessage>,
)

/** Facade over the vector store: builds the extension prompts for vector memory (and the Data Bank). */
class MemoryService(
    val store: VectorStore,
    private val settings: () -> MemorySettings,
    private val apiKey: () -> String?,
    private val remote: (EmbedderSettings, String?) -> Embedder = { s, k -> RemoteEmbedder(s, k) },
) {
    constructor(paths: StoragePaths, settings: () -> MemorySettings, apiKey: () -> String?) : this(VectorStore(paths.vectors), settings, apiKey)

    val vector = VectorMemory(store)
    private val local by lazy { HashingEmbedder() }

    fun embedder(): Embedder {
        val s = settings().embedder
        return if (s.kind == EmbedderKind.LOCAL_HASH) local else remote(s, apiKey())
    }

    fun chatCollection(chatKey: String) = "chat_$chatKey"

    suspend fun prompts(ctx: MemoryContext): List<ExtensionPrompt> {
        val s = settings()
        val out = ArrayList<ExtensionPrompt>()
        if (s.vector.enabled && ctx.chatKey != null) {
            vector.prompt(chatCollection(ctx.chatKey), ctx.history, s.vector, embedder())?.let(out::add)
        }
        return out
    }

    /** Removes the vectors of a chat (after deleting it, or to force a re-index). */
    fun forgetChat(chatKey: String) = vector.clear(chatCollection(chatKey))
}
