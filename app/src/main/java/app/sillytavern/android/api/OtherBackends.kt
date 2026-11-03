package app.sillytavern.android.api

import app.sillytavern.android.data.BackendType
import app.sillytavern.android.data.ConnectionProfile
import app.sillytavern.android.prompt.model.TextGenSamplerPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Backends that take the formatted text prompt (instruct/context applied by the caller). */
val BackendType.usesTextPrompt: Boolean
    get() = this == BackendType.TEXT_COMPLETION || this == BackendType.KOBOLD ||
        this == BackendType.NOVELAI || this == BackendType.HORDE

private fun bearer(key: String?) = key?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

/** Ollama native `/api/chat` (NDJSON stream). */
class OllamaBackend(client: OkHttpClient) : HttpBackend(client) {
    override val streamFormat = StreamFormat.NDJSON

    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val body = buildJsonObject {
            put("model", req.profile.model)
            put("messages", JsonArray(req.messages.map { m ->
                buildJsonObject {
                    put("role", m.role.name.lowercase()); put("content", m.content)
                    if (m.images.isNotEmpty()) put("images", JsonArray(m.images.mapNotNull {
                        app.sillytavern.android.media.MessageMedia.parseDataUrl(it)?.second?.let(::JsonPrimitive)
                    }))
                    if (m.role == app.sillytavern.android.prompt.Role.ASSISTANT && m.toolCalls.isNotEmpty()) {
                        put("tool_calls", JsonArray(m.toolCalls.map { c ->
                            buildJsonObject { put("function", buildJsonObject { put("name", c.name); put("arguments", parseArgs(c.arguments)) }) }
                        }))
                    }
                    if (m.role == app.sillytavern.android.prompt.Role.TOOL) m.toolName?.let { put("tool_name", it) }
                }
            }))
            if (req.tools.isNotEmpty()) put("tools", JsonArray(req.tools.map {
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject { put("name", it.name); put("description", it.description); put("parameters", it.parameters) })
                }
            }))
            put("stream", s.stream)
            when (req.profile.reasoning.effort) {
                "min" -> put("think", false)
                "low", "medium", "high" -> put("think", req.profile.reasoning.effort)
                "max" -> put("think", true)
            }
            put("options", buildJsonObject {
                put("temperature", s.temperature)
                put("top_p", s.topP)
                if (s.topK > 0) put("top_k", s.topK)
                if (s.minP > 0) put("min_p", s.minP)
                put("repeat_penalty", s.repetitionPenalty)
                if (s.frequencyPenalty != 0.0) put("frequency_penalty", s.frequencyPenalty)
                if (s.presencePenalty != 0.0) put("presence_penalty", s.presencePenalty)
                put("num_predict", s.maxResponseTokens)
                put("num_ctx", s.maxContextTokens)
                if (req.stopSequences.isNotEmpty()) put("stop", JsonArray(req.stopSequences.map(::JsonPrimitive)))
            })
        }
        return post(url(req.profile.baseUrl, "/api/chat"), body, bearer(req.apiKey))
    }

    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = object : EventParser {
        override fun parse(event: JsonObject): List<Chunk> {
            val m = event["message"] as? JsonObject ?: return emptyList()
            return buildList {
                m["thinking"]?.strOrNull()?.takeIf { it.isNotEmpty() }?.let { add(Chunk.Reasoning(it)) }
                m["content"]?.strOrNull()?.takeIf { it.isNotEmpty() }?.let { add(Chunk.Text(it)) }
                (m["tool_calls"] as? JsonArray)?.forEachIndexed { i, tc ->
                    val fn = (tc as? JsonObject)?.get("function") as? JsonObject ?: return@forEachIndexed
                    val name = fn["name"]?.strOrNull() ?: return@forEachIndexed
                    add(Chunk.ToolCall("$name-$i", name, (fn["arguments"] ?: JsonObject(emptyMap())).toString()))
                }
            }
        }

        override fun isDone(event: JsonObject) = (event["done"] as? JsonPrimitive)?.booleanOrNull == true
    }
}

/** KoboldCpp (`/api/extra/generate/stream`, SSE) and classic KoboldAI (`/api/v1/generate`). */
class KoboldBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val p = req.sampler
        val body = buildJsonObject {
            put("prompt", req.textPrompt)
            put("max_length", s.maxResponseTokens)
            put("max_context_length", s.maxContextTokens)
            put("temperature", p?.temp ?: s.temperature)
            put("top_p", p?.topP ?: s.topP)
            put("top_k", p?.topK ?: s.topK)
            put("rep_pen", p?.repPen ?: s.repetitionPenalty)
            val minP = p?.minP ?: s.minP
            if (minP > 0) put("min_p", minP)
            if (req.stopSequences.isNotEmpty()) put("stop_sequence", JsonArray(req.stopSequences.map(::JsonPrimitive)))
            if (p != null) {
                put("typical", p.typicalP); put("tfs", p.tfs); put("top_a", p.topA)
                put("rep_pen_range", p.repPenRange)
                if (p.mirostatMode > 0) { put("mirostat", p.mirostatMode); put("mirostat_tau", p.mirostatTau); put("mirostat_eta", p.mirostatEta) }
                if (p.seed >= 0) put("sampler_seed", p.seed)
                if (p.grammarString.isNotBlank()) put("grammar", p.grammarString)
            }
        }
        val path = if (isStreaming(req)) "/api/extra/generate/stream" else "/api/v1/generate"
        return post(url(req.profile.baseUrl, path), body, emptyMap())
    }

    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = object : EventParser {
        override fun parse(event: JsonObject): List<Chunk> {
            val t = if (streaming) event["token"]?.strOrNull()
            else ((event["results"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text")?.strOrNull()
            return t?.takeIf { it.isNotEmpty() }?.let { listOf(Chunk.Text(it)) } ?: emptyList()
        }
    }
}

/** NovelAI text generation. Stop strings are applied client-side (the API wants token-id stop sequences). */
class NovelAiBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val p = req.sampler
        val body = buildJsonObject {
            put("input", req.textPrompt)
            put("model", req.profile.model.ifBlank { "kayra-v1" })
            put("parameters", buildJsonObject {
                put("temperature", p?.temp ?: s.temperature)
                put("max_length", s.maxResponseTokens)
                put("min_length", 1)
                val topK = p?.topK ?: s.topK
                if (topK > 0) put("top_k", topK)
                put("top_p", p?.topP ?: s.topP)
                if (p != null && p.tfs != 1.0) put("tail_free_sampling", p.tfs)
                put("repetition_penalty", p?.repPen ?: s.repetitionPenalty)
                put("use_string", true)
                put("return_full_text", false)
                put("generate_until_sentence", false)
            })
        }
        val path = if (isStreaming(req)) "/ai/generate-stream" else "/ai/generate"
        return post(url(req.profile.baseUrl, path), body, bearer(req.apiKey))
    }

    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = object : EventParser {
        override fun parse(event: JsonObject): List<Chunk> =
            (event["token"] ?: event["output"])?.strOrNull()?.takeIf { it.isNotEmpty() }?.let { listOf(Chunk.Text(it)) } ?: emptyList()
    }

    override fun chunks(req: GenerationRequest): Flow<Chunk> = StopStrings.apply(super.chunks(req), req.stopSequences)
}

/** AI Horde: submit an async job, poll until done. No token streaming. */
class HordeBackend(private val http: OkHttpClient, private val pollMillis: Long = 2000) : HttpBackend(http) {
    override fun buildRequest(req: GenerationRequest): Request = throw UnsupportedOperationException()
    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = throw UnsupportedOperationException()

    override fun chunks(req: GenerationRequest): Flow<Chunk> = flow {
        val base = req.profile.baseUrl
        val key = req.apiKey?.takeIf { it.isNotBlank() } ?: "0000000000"
        val s = req.profile.sampler
        val p = req.sampler
        val body = buildJsonObject {
            put("prompt", req.textPrompt)
            put("params", buildJsonObject {
                put("max_length", s.maxResponseTokens.coerceAtMost(512))
                put("max_context_length", s.maxContextTokens.coerceAtMost(16384))
                put("temperature", p?.temp ?: s.temperature)
                put("top_p", p?.topP ?: s.topP)
                put("top_k", p?.topK ?: s.topK)
                put("rep_pen", p?.repPen ?: s.repetitionPenalty)
                if (req.stopSequences.isNotEmpty()) put("stop_sequence", JsonArray(req.stopSequences.map(::JsonPrimitive)))
            })
            if (req.profile.model.isNotBlank()) put("models", JsonArray(listOf(JsonPrimitive(req.profile.model))))
            put("trusted_workers", false)
        }
        val id = execJson(post(url(base, "/v2/generate/text/async"), body, mapOf("apikey" to key, "Client-Agent" to "SillyTavernAndroid:1:unknown")))["id"]?.strOrNull()
            ?: throw BackendException("Horde did not return a job id")
        val statusUrl = url(base, "/v2/generate/text/status/$id")
        try {
            while (true) {
                val st = execJson(Request.Builder().url(statusUrl).header("apikey", key).get().build())
                if ((st["faulted"] as? JsonPrimitive)?.booleanOrNull == true) throw BackendException("Horde job faulted")
                if ((st["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                    val text = ((st["generations"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text")?.strOrNull()
                    if (!text.isNullOrEmpty()) emit(Chunk.Text(text))
                    return@flow
                }
                delay(pollMillis)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { http.newCall(Request.Builder().url(statusUrl).header("apikey", key).delete().build()).execute().close() }
            }
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun execJson(r: Request): JsonObject {
        val resp = try { http.newCall(r).execute() } catch (e: java.io.IOException) { throw BackendException("Network error: ${e.message}", e) }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw BackendException("HTTP ${it.code}: ${errorText(text)}")
            return try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw BackendException("Unreadable response: ${e.message}", e) }
        }
    }
}

/** Cuts a text stream at the first stop string (also across chunk boundaries) and ends the flow. */
object StopStrings {
    fun apply(source: Flow<Chunk>, stops: List<String>): Flow<Chunk> {
        val active = stops.filter { it.isNotEmpty() }
        if (active.isEmpty()) return source
        val hold = active.maxOf { it.length } - 1
        return flow {
            val pending = StringBuilder()
            var stopped = false
            source.transformWhile { c ->
                if (c !is Chunk.Text) { emit(c); return@transformWhile true }
                pending.append(c.text)
                val cut = active.map { pending.indexOf(it) }.filter { it >= 0 }.minOrNull()
                if (cut != null) {
                    if (cut > 0) emit(Chunk.Text(pending.substring(0, cut)))
                    pending.clear(); stopped = true
                    false
                } else {
                    if (pending.length > hold) {
                        emit(Chunk.Text(pending.substring(0, pending.length - hold)))
                        pending.delete(0, pending.length - hold)
                    }
                    true
                }
            }.collect { emit(it) }
            if (!stopped && pending.isNotEmpty()) emit(Chunk.Text(pending.toString()))
        }
    }
}
