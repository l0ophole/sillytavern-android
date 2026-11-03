package app.sillytavern.android.chat

import app.sillytavern.android.data.SummarySettings
import app.sillytavern.android.prompt.ExtensionPrompt
import kotlinx.serialization.json.*

/** Chat summary (SillyTavern's Summarize extension): the text lives in `extra.memory` of the message it was made at. */
object Summary {
    const val DEFAULT_PROMPT = "Ignore previous instructions. Summarize the most important facts and events in the story so far. " +
        "If a summary already exists in your memory, use that as a base and expand with new facts. " +
        "Limit the summary to {{words}} words or less. Your response should include nothing but the summary."
    const val DEFAULT_TEMPLATE = "[Summary: {{summary}}]"

    private fun ChatMessage.memory(): String? = (extra["memory"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Index and text of the newest message carrying a summary. */
    fun latest(messages: List<ChatMessage>): Pair<Int, String>? {
        for (i in messages.indices.reversed()) messages[i].memory()?.let { return i to it }
        return null
    }

    fun withMemory(m: ChatMessage, text: String?): ChatMessage = m.copy(extra = buildJsonObject {
        m.extra.forEach { (k, v) -> if (k != "memory") put(k, v) }
        if (!text.isNullOrBlank()) put("memory", text.trim())
    })

    fun format(summary: String, template: String): String {
        val s = summary.trim()
        if (s.isEmpty()) return ""
        return if (template.isBlank()) "Summary: $s" else template.replace("{{summary}}", s)
    }

    fun prompt(s: SummarySettings): String = s.prompt.ifBlank { DEFAULT_PROMPT }.replace("{{words}}", s.promptWords.toString())

    fun extensionPrompt(history: List<ChatMessage>, s: SummarySettings): ExtensionPrompt? {
        if (s.position < 0) return null
        val text = format(latest(history)?.second.orEmpty(), s.template)
        if (text.isBlank()) return null
        return ExtensionPrompt("summary", text, s.position, s.depth, s.role)
    }

    /** Automatic summary is due when enough chat lines (not system) followed the newest summary. */
    fun due(messages: List<ChatMessage>, s: SummarySettings): Boolean {
        if (!s.auto || s.frozen || s.interval <= 0 || messages.isEmpty()) return false
        val from = (latest(messages)?.first ?: -1) + 1
        return messages.drop(from).count { !it.isSystem } >= s.interval
    }
}
