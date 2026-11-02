package app.sillytavern.android.api

import app.sillytavern.android.data.BackendType
import app.sillytavern.android.data.ConnectionProfile
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Everything a backend needs; [textPrompt] is only used by text-completion backends. */
data class GenerationRequest(
    val profile: ConnectionProfile,
    val apiKey: String?,
    val messages: List<PromptMessage>,
    val textPrompt: String,
    val stopSequences: List<String> = profile.sampler.stopSequences,
)

/** Streams generated text as incremental deltas (a single delta when streaming is off). */
fun interface Backend {
    fun generate(req: GenerationRequest): Flow<String>
}

object Backends {
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES) // slow local models may pause between tokens
            .build()
    }

    fun forType(type: BackendType, client: OkHttpClient = defaultClient): Backend = when (type) {
        BackendType.OPENAI -> OpenAiBackend(client)
        BackendType.ANTHROPIC -> AnthropicBackend(client)
        BackendType.TEXT_COMPLETION -> TextCompletionBackend(client)
    }
}

private val JSON_MEDIA = "application/json".toMediaType()

/** Shared HTTP/SSE plumbing. Subclasses supply the request and a parser for each JSON event. */
abstract class HttpBackend(private val client: OkHttpClient) : Backend {
    protected val json = Json { ignoreUnknownKeys = true }

    protected abstract fun buildRequest(req: GenerationRequest): Request
    /** Returns the text delta in one streamed event (or whole non-stream response), or null. */
    protected abstract fun parseEvent(event: JsonObject, streaming: Boolean): String?
    protected open fun isDone(event: JsonObject): Boolean = false

    override fun generate(req: GenerationRequest): Flow<String> = flow {
        val call = client.newCall(buildRequest(req))
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        val response = try {
            call.execute()
        } catch (e: java.io.IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }
        response.use { r ->
            val body = r.body ?: throw BackendException("Empty response")
            if (!r.isSuccessful) throw BackendException("HTTP ${r.code}: ${errorText(body.string())}")
            if (req.profile.sampler.stream) {
                val source = body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val ev = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    ev["error"]?.let { throw BackendException(errorText(it.toString())) }
                    parseEvent(ev, true)?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                    if (isDone(ev)) break
                }
            } else {
                val ev = json.parseToJsonElement(body.string()).jsonObject
                parseEvent(ev, false)?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    protected fun post(url: String, body: JsonObject, headers: Map<String, String>): Request =
        Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(JSON_MEDIA)).build()

    protected fun url(base: String, path: String) = base.trimEnd('/') + path

    private fun errorText(raw: String): String = runCatching {
        val o = json.parseToJsonElement(raw).jsonObject
        val e = o["error"]
        (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: (e as? JsonPrimitive)?.contentOrNull ?: raw
    }.getOrDefault(raw).take(500)
}

private fun JsonObjectBuilder.putSamplers(req: GenerationRequest, maxKey: String = "max_tokens") {
    val s = req.profile.sampler
    put("temperature", s.temperature)
    put("top_p", s.topP)
    put(maxKey, s.maxResponseTokens)
    if (req.stopSequences.isNotEmpty()) put("stop", JsonArray(req.stopSequences.map(::JsonPrimitive)))
}

class OpenAiBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val body = buildJsonObject {
            put("model", req.profile.model)
            put("messages", JsonArray(req.messages.map {
                buildJsonObject { put("role", it.role.name.lowercase()); put("content", it.content) }
            }))
            putSamplers(req)
            if (s.frequencyPenalty != 0.0) put("frequency_penalty", s.frequencyPenalty)
            if (s.presencePenalty != 0.0) put("presence_penalty", s.presencePenalty)
            // Non-standard but widely accepted by OpenAI-compatible servers (OpenRouter, vLLM, ...).
            if (s.topK > 0) put("top_k", s.topK)
            if (s.minP > 0) put("min_p", s.minP)
            if (s.repetitionPenalty != 1.0) put("repetition_penalty", s.repetitionPenalty)
            put("stream", s.stream)
        }
        return post(url(req.profile.baseUrl, "/chat/completions"), body,
            req.apiKey?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
    }

    override fun parseEvent(event: JsonObject, streaming: Boolean): String? {
        val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val node = if (streaming) choice["delta"] else choice["message"]
        return node?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
    }
}

class TextCompletionBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val body = buildJsonObject {
            if (req.profile.model.isNotBlank()) put("model", req.profile.model)
            put("prompt", req.textPrompt)
            putSamplers(req)
            if (s.topK > 0) put("top_k", s.topK)
            if (s.minP > 0) put("min_p", s.minP)
            if (s.repetitionPenalty != 1.0) put("repetition_penalty", s.repetitionPenalty)
            put("stream", s.stream)
        }
        return post(url(req.profile.baseUrl, "/completions"), body,
            req.apiKey?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
    }

    override fun parseEvent(event: JsonObject, streaming: Boolean): String? =
        event["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
}

class AnthropicBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val (system, turns) = toAnthropicMessages(req.messages)
        val s = req.profile.sampler
        val body = buildJsonObject {
            put("model", req.profile.model)
            if (system.isNotBlank()) put("system", system)
            put("messages", JsonArray(turns.map { buildJsonObject { put("role", it.first); put("content", it.second) } }))
            put("max_tokens", s.maxResponseTokens)
            put("temperature", s.temperature.coerceIn(0.0, 1.0))
            if (s.topP < 1.0) put("top_p", s.topP)
            if (s.topK > 0) put("top_k", s.topK)
            if (req.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(req.stopSequences.map(::JsonPrimitive)))
            put("stream", s.stream)
        }
        return post(url(req.profile.baseUrl, "/messages"), body, mapOf(
            "x-api-key" to (req.apiKey ?: ""),
            "anthropic-version" to "2023-06-01",
        ))
    }

    override fun parseEvent(event: JsonObject, streaming: Boolean): String? = if (streaming) {
        if (event["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta")
            event["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull else null
    } else {
        event["content"]?.jsonArray?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
    }

    override fun isDone(event: JsonObject) = event["type"]?.jsonPrimitive?.contentOrNull == "message_stop"

    companion object {
        /**
         * Anthropic wants one top-level system string and strictly alternating user/assistant turns that start
         * with user. The leading system message becomes `system`; later system messages are sent as user turns.
         */
        fun toAnthropicMessages(messages: List<PromptMessage>): Pair<String, List<Pair<String, String>>> {
            val system = messages.firstOrNull()?.takeIf { it.role == Role.SYSTEM }?.content ?: ""
            val rest = if (system.isNotEmpty() || messages.firstOrNull()?.role == Role.SYSTEM) messages.drop(1) else messages
            val turns = mutableListOf<Pair<String, String>>()
            for (m in rest) {
                val role = if (m.role == Role.ASSISTANT) "assistant" else "user"
                if (turns.isNotEmpty() && turns.last().first == role) {
                    turns[turns.lastIndex] = role to (turns.last().second + "\n\n" + m.content)
                } else turns += role to m.content
            }
            if (turns.isEmpty() || turns.first().first != "user") turns.add(0, "user" to "[Start a new chat]")
            return system to turns
        }
    }
}
