package app.sillytavern.android.api

import app.sillytavern.android.data.BackendType
import app.sillytavern.android.data.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

data class ModelInfo(val id: String, val label: String = id)

/** Fetches the model ids a connection offers. Unexpected response shapes give an empty list. */
object ModelLister {
    private val NOVELAI = listOf("kayra-v1", "clio-v1", "llama-3-erato-v1", "glm-4-6").map(::ModelInfo)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(profile: ConnectionProfile, apiKey: String?, client: OkHttpClient = Backends.defaultClient): List<ModelInfo> {
        if (profile.type == BackendType.NOVELAI) return NOVELAI
        val base = profile.baseUrl.trimEnd('/')
        val key = apiKey?.takeIf { it.isNotBlank() }
        val (path, headers) = when (profile.type) {
            BackendType.OPENAI, BackendType.TEXT_COMPLETION -> "/models" to (key?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
            BackendType.ANTHROPIC -> "/models?limit=1000" to mapOf("x-api-key" to (key ?: ""), "anthropic-version" to "2023-06-01")
            BackendType.GOOGLE -> "/models?pageSize=1000" to mapOf("x-api-key" to "", "x-goog-api-key" to (key ?: ""))
            BackendType.OLLAMA -> "/api/tags" to (key?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
            BackendType.KOBOLD -> "/api/v1/model" to emptyMap()
            BackendType.HORDE -> "/v2/status/models?type=text" to emptyMap()
            BackendType.NOVELAI -> return NOVELAI
        }
        val root = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(base + path).apply { headers.forEach { (k, v) -> if (v.isNotEmpty()) header(k, v) } }.get().build()
            val resp = try { client.newCall(req).execute() } catch (e: java.io.IOException) { throw BackendException("Network error: ${e.message}", e) }
            resp.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw BackendException("HTTP ${it.code}: ${text.take(300)}")
                runCatching { json.parseToJsonElement(text) }.getOrNull()
            }
        } ?: return emptyList()
        return parse(profile.type, root)
    }

    internal fun parse(type: BackendType, root: JsonElement): List<ModelInfo> {
        fun JsonElement.arr(key: String) = (this as? JsonObject)?.get(key) as? JsonArray
        fun JsonElement.s(key: String) = (this as? JsonObject)?.get(key)?.strOrNull()
        return when (type) {
            BackendType.OPENAI, BackendType.TEXT_COMPLETION, BackendType.ANTHROPIC -> {
                // OpenAI-style {data:[…]}; some servers return a bare array.
                val items = root.arr("data") ?: root as? JsonArray ?: return emptyList()
                items.mapNotNull { m -> m.s("id")?.let { id -> ModelInfo(id, (m.s("display_name") ?: m.s("name") ?: id)) } }
            }
            BackendType.GOOGLE -> root.arr("models")?.mapNotNull { m ->
                val methods = (m as? JsonObject)?.get("supportedGenerationMethods") as? JsonArray
                if (methods != null && methods.none { it.strOrNull() == "generateContent" }) return@mapNotNull null
                m.s("name")?.removePrefix("models/")?.let { id -> ModelInfo(id, m.s("displayName") ?: id) }
            } ?: emptyList()
            BackendType.OLLAMA -> root.arr("models")?.mapNotNull { m -> (m.s("name") ?: m.s("model"))?.let(::ModelInfo) } ?: emptyList()
            BackendType.KOBOLD -> root.s("result")?.let { listOf(ModelInfo(it)) } ?: emptyList()
            BackendType.HORDE -> (root as? JsonArray)
                ?.mapNotNull { m -> m.s("name")?.let { it to ((m as JsonObject)["count"] as? JsonPrimitive)?.intOrNull.let { c -> c ?: 0 } } }
                ?.sortedByDescending { it.second }
                ?.map { ModelInfo(it.first, "${it.first} (${it.second} workers)") } ?: emptyList()
            BackendType.NOVELAI -> NOVELAI
        }
    }
}
