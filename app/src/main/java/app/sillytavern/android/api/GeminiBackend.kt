package app.sillytavern.android.api

import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Google Gemini (Generative Language API, `generateContent` / `streamGenerateContent`). */
class GeminiBackend(client: OkHttpClient) : HttpBackend(client) {
    override fun buildRequest(req: GenerationRequest): Request {
        val s = req.profile.sampler
        val r = req.profile.reasoning
        val (system, contents) = toContents(req.messages)
        val budget = when {
            r.budgetTokens > 0 -> r.budgetTokens
            else -> when (r.effort) { "min" -> 512; "low" -> 1024; "medium" -> 8192; "high" -> 24576; "max" -> 32768; else -> 0 }
        }
        val body = buildJsonObject {
            system?.let { put("systemInstruction", buildJsonObject { put("parts", JsonArray(listOf(buildJsonObject { put("text", it) }))) }) }
            put("contents", JsonArray(contents))
            put("generationConfig", buildJsonObject {
                put("temperature", s.temperature)
                put("topP", s.topP)
                if (s.topK > 0) put("topK", s.topK)
                put("maxOutputTokens", s.maxResponseTokens)
                if (req.stopSequences.isNotEmpty()) put("stopSequences", JsonArray(req.stopSequences.take(5).map(::JsonPrimitive)))
                if (budget > 0) put("thinkingConfig", buildJsonObject { put("includeThoughts", true); put("thinkingBudget", budget) })
            })
            put("safetySettings", JsonArray(SAFETY_CATEGORIES.map {
                buildJsonObject { put("category", it); put("threshold", "BLOCK_NONE") }
            }))
        }
        val streaming = isStreaming(req)
        val path = "/models/${req.profile.model}:" + if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        return post(url(req.profile.baseUrl, path), body, mapOf("x-goog-api-key" to (req.apiKey ?: "")))
    }

    override fun parser(req: GenerationRequest, streaming: Boolean): EventParser = object : EventParser {
        override fun parse(event: JsonObject): List<Chunk> {
            (event["promptFeedback"] as? JsonObject)?.get("blockReason")?.strOrNull()?.let {
                throw BackendException("Blocked: $it")
            }
            val cand = (event["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
            val parts = (cand["content"] as? JsonObject)?.get("parts") as? JsonArray
            val out = mutableListOf<Chunk>()
            parts?.forEachIndexed { i, p ->
                val o = p as? JsonObject ?: return@forEachIndexed
                val fc = o["functionCall"] as? JsonObject
                if (fc != null) {
                    val name = fc["name"]?.strOrNull() ?: return@forEachIndexed
                    out += Chunk.ToolCall("$name-$i", name, (fc["args"] ?: JsonObject(emptyMap())).toString())
                    return@forEachIndexed
                }
                val text = o["text"]?.strOrNull() ?: return@forEachIndexed
                val thought = (o["thought"] as? JsonPrimitive)?.booleanOrNull == true
                out += if (thought) Chunk.Reasoning(text) else Chunk.Text(text)
            }
            if (out.isEmpty() && cand["finishReason"]?.strOrNull() in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST")) {
                throw BackendException("Blocked: ${cand["finishReason"]!!.strOrNull()}")
            }
            return out
        }
    }

    companion object {
        val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT", "HARM_CATEGORY_CIVIC_INTEGRITY",
        )

        /** Leading system messages → `systemInstruction`; assistant → `model`; same-role neighbours merged; starts with `user`. */
        fun toContents(messages: List<PromptMessage>): Pair<String?, List<JsonObject>> {
            val leading = messages.takeWhile { it.role == Role.SYSTEM }
            val system = leading.joinToString("\n\n") { it.content }.takeIf { it.isNotBlank() }
            val turns = mutableListOf<Pair<String, String>>()
            for (m in messages.drop(leading.size)) {
                val role = if (m.role == Role.ASSISTANT) "model" else "user"
                if (turns.isNotEmpty() && turns.last().first == role) turns[turns.lastIndex] = role to (turns.last().second + "\n\n" + m.content)
                else turns += role to m.content
            }
            if (turns.isEmpty() || turns.first().first != "user") turns.add(0, "user" to "[Start a new chat]")
            return system to turns.map { (role, text) ->
                buildJsonObject {
                    put("role", role)
                    put("parts", JsonArray(listOf(buildJsonObject { put("text", text) })))
                }
            }
        }
    }
}
