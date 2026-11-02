package app.sillytavern.android.api

import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/** A function the model may call; [parameters] is a JSON-schema object. */
data class ToolDef(val name: String, val description: String, val parameters: JsonObject)

interface ToolRegistry {
    fun definitions(): List<ToolDef>
    /** Runs the call and returns the result text sent back to the model. */
    suspend fun execute(call: Chunk.ToolCall): String
}

object EmptyToolRegistry : ToolRegistry {
    override fun definitions() = emptyList<ToolDef>()
    override suspend fun execute(call: Chunk.ToolCall) = ""
}

/** Request → tool calls → registry → follow-up request, until the model answers without calling tools. */
object ToolLoop {
    fun run(backend: ChunkBackend, req: GenerationRequest, registry: ToolRegistry, maxRounds: Int = 5): Flow<Chunk> = flow {
        val tools = registry.definitions()
        if (tools.isEmpty()) { emitAll(backend.chunks(req)); return@flow }
        var cur = req.copy(tools = tools)
        for (round in 1..maxRounds) {
            val calls = mutableListOf<Chunk.ToolCall>()
            val text = StringBuilder()
            backend.chunks(cur).collect { c ->
                when (c) {
                    is Chunk.ToolCall -> calls += c
                    is Chunk.Text -> { text.append(c.text); emit(c) }
                    is Chunk.Reasoning -> emit(c)
                }
            }
            if (calls.isEmpty() || round == maxRounds) return@flow
            val results = calls.map { call ->
                val out = try { registry.execute(call) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message ?: e.javaClass.simpleName}" }
                PromptMessage(Role.TOOL, out, toolCallId = call.id, toolName = call.name)
            }
            cur = cur.copy(messages = cur.messages + PromptMessage(Role.ASSISTANT, text.toString(), toolCalls = calls) + results)
        }
    }
}

/** Accumulates streamed tool-call fragments by index. */
internal class ToolCallAssembler {
    private class Acc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, Acc>()
    fun add(index: Int, id: String?, name: String?, args: String?) {
        val a = calls.getOrPut(index) { Acc() }
        if (!id.isNullOrEmpty()) a.id = id
        if (!name.isNullOrEmpty()) a.name = name
        if (args != null) a.args.append(args)
    }
    fun finish(): List<Chunk.ToolCall> = calls.entries.filter { it.value.name.isNotEmpty() }.map { (i, a) ->
        Chunk.ToolCall(a.id.ifEmpty { "call_$i" }, a.name, a.args.toString().ifBlank { "{}" })
    }
}

internal fun parseArgs(s: String): JsonElement = runCatching { Json.parseToJsonElement(s.ifBlank { "{}" }) }.getOrDefault(JsonObject(emptyMap()))

/** Gemini's schema dialect rejects some JSON-schema keywords. */
internal fun stripSchema(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.filterKeys { it != "\$schema" && it != "additionalProperties" }.mapValues { stripSchema(it.value) })
    is JsonArray -> JsonArray(e.map(::stripSchema))
    else -> e
}
