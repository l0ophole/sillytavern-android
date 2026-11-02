package app.sillytavern.android.api

import app.sillytavern.android.data.BackendType
import app.sillytavern.android.data.ConnectionProfile

enum class ReasoningStyle { NONE, OPENAI_EFFORT, OPENROUTER, DEEPSEEK }

/** A named service/server a [ConnectionProfile] can point at; maps to a wire protocol ([type]) plus defaults/quirks. */
data class Provider(
    val id: String,
    val label: String,
    val type: BackendType,
    val baseUrl: String,
    val keyRequired: Boolean = true,
    val reasoningStyle: ReasoningStyle = ReasoningStyle.NONE,
    val extraHeaders: Map<String, String> = emptyMap(),
)

object Providers {
    val all: List<Provider> = listOf(
        Provider("openai", "OpenAI", BackendType.OPENAI, "https://api.openai.com/v1", reasoningStyle = ReasoningStyle.OPENAI_EFFORT),
        Provider("anthropic", "Claude (Anthropic)", BackendType.ANTHROPIC, "https://api.anthropic.com/v1"),
        Provider(
            "openrouter", "OpenRouter", BackendType.OPENAI, "https://openrouter.ai/api/v1",
            reasoningStyle = ReasoningStyle.OPENROUTER,
            extraHeaders = mapOf("HTTP-Referer" to "https://sillytavern.app", "X-Title" to "SillyTavern Android"),
        ),
        Provider("mistral", "Mistral AI", BackendType.OPENAI, "https://api.mistral.ai/v1"),
        Provider("groq", "Groq", BackendType.OPENAI, "https://api.groq.com/openai/v1", reasoningStyle = ReasoningStyle.OPENAI_EFFORT),
        Provider("deepseek", "DeepSeek", BackendType.OPENAI, "https://api.deepseek.com", reasoningStyle = ReasoningStyle.DEEPSEEK),
        Provider("xai", "xAI (Grok)", BackendType.OPENAI, "https://api.x.ai/v1", reasoningStyle = ReasoningStyle.OPENAI_EFFORT),
        Provider("cohere", "Cohere", BackendType.OPENAI, "https://api.cohere.ai/compatibility/v1"),
        Provider("perplexity", "Perplexity", BackendType.OPENAI, "https://api.perplexity.ai"),
        Provider("together", "Together AI", BackendType.OPENAI, "https://api.together.xyz/v1"),
        Provider("fireworks", "Fireworks AI", BackendType.OPENAI, "https://api.fireworks.ai/inference/v1"),
        Provider("nvidia", "NVIDIA NIM", BackendType.OPENAI, "https://integrate.api.nvidia.com/v1"),
        Provider("moonshot", "Moonshot AI", BackendType.OPENAI, "https://api.moonshot.ai/v1"),
        Provider("custom", "Custom (OpenAI-compatible)", BackendType.OPENAI, "http://127.0.0.1:8080/v1", keyRequired = false),
        Provider("tabby", "TabbyAPI", BackendType.TEXT_COMPLETION, "http://127.0.0.1:5000/v1", keyRequired = false),
        Provider("aphrodite", "Aphrodite", BackendType.TEXT_COMPLETION, "http://127.0.0.1:2242/v1", keyRequired = false),
        Provider("llamacpp", "llama.cpp server", BackendType.TEXT_COMPLETION, "http://127.0.0.1:8080/v1", keyRequired = false),
        Provider("ooba", "text-generation-webui", BackendType.TEXT_COMPLETION, "http://127.0.0.1:5000/v1", keyRequired = false),
        Provider("vllm", "vLLM", BackendType.TEXT_COMPLETION, "http://127.0.0.1:8000/v1", keyRequired = false),
        Provider("textgen", "Text completion (generic)", BackendType.TEXT_COMPLETION, "http://127.0.0.1:5001/v1", keyRequired = false),
    )

    private val index = all.associateBy { it.id }
    fun byId(id: String): Provider = index[id] ?: index.getValue("custom")

    /** The profile's provider; when the id is unknown/custom, a generic provider of the profile's own type. */
    fun forProfile(p: ConnectionProfile): Provider {
        val known = index[p.provider]
        if (known != null && known.type == p.type) return known
        return when (p.type) {
            BackendType.OPENAI -> byId("custom")
            BackendType.ANTHROPIC -> byId("anthropic")
            BackendType.TEXT_COMPLETION -> byId("textgen")
        }
    }

    /** Providers offered for a wire protocol. */
    fun ofType(t: BackendType) = all.filter { it.type == t }
}
