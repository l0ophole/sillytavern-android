package app.sillytavern.android.memory

import kotlinx.serialization.Serializable

@Serializable
enum class EmbedderKind { LOCAL_HASH, OPENAI, OLLAMA }

@Serializable
enum class ClassifierKind { KEYWORD, LLM }

@Serializable
data class EmbedderSettings(
    val kind: EmbedderKind = EmbedderKind.LOCAL_HASH,
    /** Base URL: `https://api.openai.com/v1` for OpenAI-compatible servers, `http://127.0.0.1:11434` for Ollama. */
    val url: String = "https://api.openai.com/v1",
    val model: String = "text-embedding-3-small",
)

/** Chat memory (SillyTavern's `vectors` extension): older messages are recalled by similarity to the latest ones. */
@Serializable
data class VectorSettings(
    val enabled: Boolean = false,
    /** How many past chunks are put into the prompt. */
    val retrieveCount: Int = 5,
    /** How many of the latest messages form the search query. */
    val queryMessages: Int = 2,
    val scoreThreshold: Double = 0.25,
    /** The newest N messages are still in context, so they are neither indexed nor recalled. */
    val protectRecent: Int = 5,
    /** Longest chunk of a message, in characters. */
    val chunkSize: Int = 800,
    val template: String = "Past events:\n{{text}}",
    val position: Int = 1,
    val depth: Int = 2,
    val role: Int = 0,
)

@Serializable
data class DataBankSettings(
    val enabled: Boolean = true,
    val retrieveCount: Int = 4,
    val scoreThreshold: Double = 0.25,
    val chunkSize: Int = 1000,
    /** Share of a chunk repeated at the start of the next one, percent. */
    val overlapPercent: Int = 10,
    val template: String = "Information that might be related to the current situation, such as recalling a memory or discussing a topic:\n{{text}}",
    val position: Int = 1,
    val depth: Int = 4,
    val role: Int = 0,
)

@Serializable
data class ExpressionSettings(
    val enabled: Boolean = false,
    val classifier: ClassifierKind = ClassifierKind.KEYWORD,
    /** Shown when nothing was classified or the sprite for a label is missing. */
    val fallback: String = "neutral",
    /** Ask the classifier after each character reply (otherwise only `/sprite` and `/expression-classify` set it). */
    val auto: Boolean = true,
    val showPanel: Boolean = true,
    /** Sprite panel height in dp. */
    val panelHeight: Int = 160,
)

@Serializable
data class MemorySettings(
    val embedder: EmbedderSettings = EmbedderSettings(),
    val vector: VectorSettings = VectorSettings(),
    val dataBank: DataBankSettings = DataBankSettings(),
    val expressions: ExpressionSettings = ExpressionSettings(),
) {
    companion object {
        /** Secret-store key for the embedding service's API key. */
        const val KEY_EMBEDDINGS = "memory:embeddings"
    }
}
