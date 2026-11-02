package app.sillytavern.android.api

import app.sillytavern.android.data.BackendType
import app.sillytavern.android.data.ConnectionProfile
import app.sillytavern.android.data.ReasoningSettings
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.model.TextGenSamplerPreset
import app.sillytavern.android.prompt.Role
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    /** Text-completion sampler preset; Task 6 maps its fields into the request. */
    val sampler: TextGenSamplerPreset? = null,
    /** Functions the model may call (chat-style backends only). */
    val tools: List<ToolDef> = emptyList(),
)

/** One piece of a model response. */
sealed interface Chunk {
    data class Text(val text: String) : Chunk
    data class Reasoning(val text: String) : Chunk
    data class ToolCall(val id: String, val name: String, val arguments: String) : Chunk
}

/** Streams generated text as incremental deltas (a single delta when streaming is off). */
fun interface Backend {
    fun generate(req: GenerationRequest): Flow<String>
}

/** A backend that can also report reasoning and tool calls; [generate] keeps only the text. */
interface ChunkBackend : Backend {
    fun chunks(req: GenerationRequest): Flow<Chunk>
    override fun generate(req: GenerationRequest): Flow<String> =
        chunks(req).filterIsInstance<Chunk.Text>().map { it.text }
}

/** Per-call, stateful translator from one decoded JSON event to chunks. */
interface EventParser {
    fun parse(event: JsonObject): List<Chunk>
    /** Chunks held back until the stream ended (e.g. assembled tool calls). */
    fun finish(): List<Chunk> = emptyList()
    fun isDone(event: JsonObject): Boolean = false
}

enum class StreamFormat { SSE, NDJSON }

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
        BackendType.GOOGLE -> GeminiBackend(client)
        BackendType.OLLAMA -> OllamaBackend(client)
        BackendType.KOBOLD -> KoboldBackend(client)
        BackendType.NOVELAI -> NovelAiBackend(client)
        BackendType.HORDE -> HordeBackend(client)
    }
}

private val JSON_MEDIA = "application/json".toMediaType()

/** Shared HTTP plumbing (SSE or NDJSON). Subclasses supply the request and a parser for each JSON event. */
abstract class HttpBackend(private val client: OkHttpClient) : ChunkBackend {
    protected val json = Json { ignoreUnknownKeys = true }

    protected abstract fun buildRequest(req: GenerationRequest): Request
    protected abstract fun parser(req: GenerationRequest, streaming: Boolean): EventParser
    protected open val streamFormat: StreamFormat = StreamFormat.SSE
    protected open fun isStreaming(req: GenerationRequest): Boolean = req.profile.sampler.stream

    override fun chunks(req: GenerationRequest): Flow<Chunk> = flow {
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
            val streaming = isStreaming(req)
            val parser = parser(req, streaming)
            if (streaming) {
                val source = body.source()
                loop@ while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val payload = when (streamFormat) {
                        StreamFormat.SSE -> if (line.startsWith("data:")) line.removePrefix("data:").trim() else continue@loop
                        StreamFormat.NDJSON -> line.trim()
                    }
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val ev = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    ev["error"]?.let { throw BackendException(errorText(ev.toString())) }
                    parser.parse(ev).forEach { c -> if (c !is Chunk.Text || c.text.isNotEmpty()) emit(c) }
                    if (parser.isDone(ev)) break
                }
            } else {
                val ev = try {
                    json.parseToJsonElement(body.string()).jsonObject
                } catch (e: Exception) {
                    throw BackendException("Unreadable response: ${e.message}", e)
                }
                ev["error"]?.let { throw BackendException(errorText(ev.toString())) }
                parser.parse(ev).forEach { emit(it) }
            }
            parser.finish().forEach { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    protected fun post(url: String, body: JsonObject, headers: Map<String, String>): Request =
        Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(JSON_MEDIA)).build()

    protected fun url(base: String, path: String) = base.trimEnd('/') + path

    protected fun errorText(raw: String): String = runCatching {
        val o = json.parseToJsonElement(raw).jsonObject
        val e = o["error"] ?: o
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

private val presetDefaults = TextGenSamplerPreset()

/** Applies an ST sampler preset: basics override the profile's; advanced fields are sent only when non-default. */
private fun JsonObjectBuilder.putSamplerPreset(p: TextGenSamplerPreset) {
    val d = presetDefaults
    put("temperature", p.temp)
    put("top_p", p.topP)
    if (p.topK > 0) put("top_k", p.topK)
    if (p.minP > 0) put("min_p", p.minP)
    if (p.repPen != 1.0) put("repetition_penalty", p.repPen)
    if (p.freqPen != 0.0) put("frequency_penalty", p.freqPen)
    if (p.presencePen != 0.0) put("presence_penalty", p.presencePen)
    if (p.topA != d.topA) put("top_a", p.topA)
    if (p.tfs != d.tfs) put("tfs", p.tfs)
    if (p.typicalP != d.typicalP) put("typical_p", p.typicalP)
    if (p.repPenRange != d.repPenRange) put("repetition_penalty_range", p.repPenRange)
    if (p.dryMultiplier != d.dryMultiplier) {
        put("dry_multiplier", p.dryMultiplier)
        put("dry_base", p.dryBase)
        put("dry_allowed_length", p.dryAllowedLength)
        put("dry_penalty_last_n", p.dryPenaltyLastN)
        runCatching { Json.parseToJsonElement(p.drySequenceBreakers).jsonArray }.getOrNull()?.let { put("dry_sequence_breakers", it) }
    }
    if (p.mirostatMode > 0) { put("mirostat_mode", p.mirostatMode); put("mirostat_tau", p.mirostatTau); put("mirostat_eta", p.mirostatEta) }
    if (p.smoothingFactor != d.smoothingFactor) { put("smoothing_factor", p.smoothingFactor); put("smoothing_curve", p.smoothingCurve) }
    if (p.xtcProbability != d.xtcProbability) { put("xtc_probability", p.xtcProbability); put("xtc_threshold", p.xtcThreshold) }
    if (p.nsigma != d.nsigma) put("nsigma", p.nsigma)
    if (p.dynatemp) { put("dynatemp_low", p.minTemp); put("dynatemp_high", p.maxTemp); put("dynatemp_exponent", p.dynatempExponent) }
    if (p.banEosToken) put("ban_eos_token", true)
    if (!p.skipSpecialTokens) put("skip_special_tokens", false)
    if (p.seed >= 0) put("seed", p.seed)
    if (p.grammarString.isNotBlank()) put("grammar", p.grammarString)
    if (p.negativePrompt.isNotBlank()) put("negative_prompt", p.negativePrompt)
    if (p.guidanceScale != d.guidanceScale) put("guidance_scale", p.guidanceScale)
    p.bannedTokens.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        ?.let { put("banned_strings", JsonArray(it.map(::JsonPrimitive))) }
}

internal fun openAiMessage(m: PromptMessage): JsonObject = buildJsonObject {
    put("role", m.role.name.lowercase())
    put("content", m.content)
    if (m.role == Role.ASSISTANT && m.toolCalls.isNotEmpty()) {
        put("tool_calls", JsonArray(m.toolCalls.map {
            buildJsonObject {
                put("id", it.id); put("type", "function")
                put("function", buildJsonObject { put("name", it.name); put("arguments", it.arguments) })
            }
        }))
    }
    if (m.role == Role.TOOL) m.toolCallId?.let { put("tool_call_id", it) }
}

class OpenAiBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val provider = Providers.forProfile(req.profile)
        val reasonerOnly = provider.reasoningStyle == ReasoningStyle.DEEPSEEK && req.profile.model.contains("reasoner")
        val body = buildJsonObject {
            put("model", req.profile.model)
            put("messages", JsonArray(req.messages.map(::openAiMessage)))
            if (req.tools.isNotEmpty()) put("tools", JsonArray(req.tools.map {
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject { put("name", it.name); put("description", it.description); put("parameters", it.parameters) })
                }
            }))
            if (reasonerOnly) { // deepseek-reasoner rejects/ignores sampler parameters
                put("max_tokens", s.maxResponseTokens)
                if (req.stopSequences.isNotEmpty()) put("stop", JsonArray(req.stopSequences.map(::JsonPrimitive)))
            } else {
                putSamplers(req)
                if (s.frequencyPenalty != 0.0) put("frequency_penalty", s.frequencyPenalty)
                if (s.presencePenalty != 0.0) put("presence_penalty", s.presencePenalty)
                // Non-standard but widely accepted by OpenAI-compatible servers (OpenRouter, vLLM, ...).
                if (s.topK > 0) put("top_k", s.topK)
                if (s.minP > 0) put("min_p", s.minP)
                if (s.repetitionPenalty != 1.0) put("repetition_penalty", s.repetitionPenalty)
            }
            putReasoning(provider.reasoningStyle, req.profile.reasoning)
            put("stream", s.stream)
        }
        val headers = buildMap {
            putAll(provider.extraHeaders)
            req.apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return post(url(req.profile.baseUrl, "/chat/completions"), body, headers)
    }

    private fun JsonObjectBuilder.putReasoning(style: ReasoningStyle, r: ReasoningSettings) {
        val effort = when (r.effort) { "min" -> "minimal"; "max" -> "high"; "low", "medium", "high" -> r.effort; else -> null }
        when (style) {
            ReasoningStyle.OPENAI_EFFORT -> effort?.let { put("reasoning_effort", it) }
            ReasoningStyle.OPENROUTER -> when {
                r.budgetTokens > 0 -> put("reasoning", buildJsonObject { put("max_tokens", r.budgetTokens) })
                effort != null -> put("reasoning", buildJsonObject { put("effort", effort) })
            }
            ReasoningStyle.DEEPSEEK, ReasoningStyle.NONE -> {}
        }
    }

    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = object : EventParser {
        private val tools = ToolCallAssembler()

        override fun parse(event: JsonObject): List<Chunk> {
            val choice = (event["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
            val node = (if (streaming) choice["delta"] else choice["message"]) as? JsonObject ?: return emptyList()
            (node["tool_calls"] as? JsonArray)?.forEachIndexed { i, tc ->
                val o = tc as? JsonObject ?: return@forEachIndexed
                val fn = o["function"] as? JsonObject
                val idx = (o["index"] as? JsonPrimitive)?.intOrNull ?: i
                tools.add(idx, o["id"]?.strOrNull(), fn?.get("name")?.strOrNull(), fn?.get("arguments")?.let { it.strOrNull() ?: it.toString() })
            }
            return buildList {
                (node["reasoning_content"] ?: node["reasoning"])?.strOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { add(Chunk.Reasoning(it)) }
                node["content"]?.strOrNull()?.takeIf { it.isNotEmpty() }?.let { add(Chunk.Text(it)) }
            }
        }

        override fun finish(): List<Chunk> = tools.finish()
    }
}

class TextCompletionBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val body = buildJsonObject {
            if (req.profile.model.isNotBlank()) put("model", req.profile.model)
            put("prompt", req.textPrompt)
            putSamplers(req)
            if (req.sampler == null) { // a preset replaces the profile's basic samplers
                if (s.topK > 0) put("top_k", s.topK)
                if (s.minP > 0) put("min_p", s.minP)
                if (s.repetitionPenalty != 1.0) put("repetition_penalty", s.repetitionPenalty)
            }
            put("stream", s.stream)
            req.sampler?.let { putSamplerPreset(it) }
        }
        return post(url(req.profile.baseUrl, "/completions"), body,
            req.apiKey?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
    }

    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = object : EventParser {
        override fun parse(event: JsonObject): List<Chunk> =
            ((event["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text")?.strOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { listOf(Chunk.Text(it)) } ?: emptyList()
    }
}

class AnthropicBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val (system, turns) = toAnthropicTurns(req.messages)
        val s = req.profile.sampler
        val body = buildJsonObject {
            put("model", req.profile.model)
            if (system.isNotBlank()) put("system", system)
            put("messages", JsonArray(turns))
            if (req.tools.isNotEmpty()) put("tools", JsonArray(req.tools.map {
                buildJsonObject { put("name", it.name); put("description", it.description); put("input_schema", it.parameters) }
            }))
            val thinking = req.profile.reasoning.budgetTokens
            put("max_tokens", if (thinking > 0) maxOf(s.maxResponseTokens, thinking + 1) else s.maxResponseTokens)
            if (thinking > 0) {
                // Extended thinking forbids temperature/top_p/top_k overrides.
                put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", thinking) })
            } else {
                put("temperature", s.temperature.coerceIn(0.0, 1.0))
                if (s.topP < 1.0) put("top_p", s.topP)
                if (s.topK > 0) put("top_k", s.topK)
            }
            if (req.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(req.stopSequences.map(::JsonPrimitive)))
            put("stream", s.stream)
        }
        return post(url(req.profile.baseUrl, "/messages"), body, mapOf(
            "x-api-key" to (req.apiKey ?: ""),
            "anthropic-version" to "2023-06-01",
        ))
    }

    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = object : EventParser {
        private val tools = ToolCallAssembler()

        override fun parse(event: JsonObject): List<Chunk> {
            if (!streaming) {
                return ((event["content"] as? JsonArray) ?: return emptyList()).mapNotNull { b ->
                    val o = b as? JsonObject ?: return@mapNotNull null
                    when (o["type"]?.strOrNull()) {
                        "thinking" -> o["thinking"]?.strOrNull()?.let { Chunk.Reasoning(it) }
                        "text" -> o["text"]?.strOrNull()?.let { Chunk.Text(it) }
                        "tool_use" -> o["name"]?.strOrNull()?.let { Chunk.ToolCall(o["id"]?.strOrNull() ?: it, it, (o["input"] ?: JsonObject(emptyMap())).toString()) }
                        else -> null
                    }
                }
            }
            val index = (event["index"] as? JsonPrimitive)?.intOrNull ?: 0
            when (event["type"]?.strOrNull()) {
                "content_block_start" -> {
                    val cb = event["content_block"] as? JsonObject
                    if (cb?.get("type")?.strOrNull() == "tool_use") tools.add(index, cb["id"]?.strOrNull(), cb["name"]?.strOrNull(), null)
                    return emptyList()
                }
                "content_block_delta" -> {}
                else -> return emptyList()
            }
            val d = event["delta"] as? JsonObject ?: return emptyList()
            return when (d["type"]?.strOrNull()) {
                "thinking_delta" -> d["thinking"]?.strOrNull()?.let { listOf(Chunk.Reasoning(it)) }
                "input_json_delta" -> { tools.add(index, null, null, d["partial_json"]?.strOrNull() ?: ""); null }
                "text_delta", null -> d["text"]?.strOrNull()?.let { listOf(Chunk.Text(it)) }
                else -> null
            } ?: emptyList()
        }

        override fun finish(): List<Chunk> = tools.finish()

        override fun isDone(event: JsonObject) = event["type"]?.strOrNull() == "message_stop"
    }

    companion object {
        /**
         * Like [toAnthropicMessages] but supports tool calls/results: assistant tool calls become `tool_use` blocks and
         * TOOL messages become `tool_result` blocks inside a user turn.
         */
        fun toAnthropicTurns(messages: List<PromptMessage>): Pair<String, List<JsonObject>> {
            if (messages.none { it.role == Role.TOOL || it.toolCalls.isNotEmpty() }) {
                val (sys, turns) = toAnthropicMessages(messages)
                return sys to turns.map { buildJsonObject { put("role", it.first); put("content", it.second) } }
            }
            val leading = messages.takeWhile { it.role == Role.SYSTEM }
            val system = leading.joinToString("\n\n") { it.content }
            val turns = mutableListOf<Pair<String, MutableList<JsonObject>>>()
            fun add(role: String, block: JsonObject) {
                if (turns.isNotEmpty() && turns.last().first == role) turns.last().second += block else turns += role to mutableListOf(block)
            }
            fun textBlock(t: String) = buildJsonObject { put("type", "text"); put("text", t) }
            for (m in messages.drop(leading.size)) when {
                m.role == Role.TOOL -> add("user", buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", m.toolCallId ?: ""); put("content", m.content)
                })
                m.role == Role.ASSISTANT -> {
                    if (m.content.isNotBlank()) add("assistant", textBlock(m.content))
                    m.toolCalls.forEach { c ->
                        add("assistant", buildJsonObject {
                            put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", parseArgs(c.arguments))
                        })
                    }
                }
                else -> add("user", textBlock(m.content))
            }
            if (turns.isEmpty() || turns.first().first != "user") turns.add(0, "user" to mutableListOf(textBlock("[Start a new chat]")))
            return system to turns.map { (role, blocks) -> buildJsonObject { put("role", role); put("content", JsonArray(blocks)) } }
        }

        /**
         * Anthropic wants one top-level system string and strictly alternating user/assistant turns that start
         * with user. The leading system message becomes `system`; later system messages are sent as user turns.
         */
        fun toAnthropicMessages(messages: List<PromptMessage>): Pair<String, List<Pair<String, String>>> {
            val leading = messages.takeWhile { it.role == Role.SYSTEM }
            val system = leading.joinToString("\n\n") { it.content }
            val rest = messages.drop(leading.size)
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

/** String content of a JSON primitive, or null for non-primitives / JSON null. */
internal fun JsonElement.strOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
