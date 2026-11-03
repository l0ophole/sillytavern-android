package app.sillytavern.android.tools

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.prompt.TokenCounter

enum class TokenizerKind(val label: String) { OPENAI("OpenAI (o200k_base)"), APPROX("Estimate (~3.6 characters per token)") }

data class TextCounts(val tokens: Int, val characters: Int, val words: Int)
data class TokenPart(val label: String, val tokens: Int)

object TokenTools {
    fun count(text: String, counter: TokenCounter) = TextCounts(counter.count(text), text.length, Stats.words(text))

    /** Token cost of each card field that goes into a prompt (empty fields are left out). */
    fun cardParts(card: CharacterCard, counter: TokenCounter): List<TokenPart> = listOf(
        "Description" to card.description, "Personality" to card.personality, "Scenario" to card.scenario,
        "First message" to card.firstMessage, "Example messages" to card.messageExample,
        "Main prompt override" to card.systemPrompt, "Post-history instructions" to card.postHistoryInstructions,
    ).filter { it.second.isNotBlank() }.map { TokenPart(it.first, counter.count(it.second)) }

    /** Tokens of the chat lines that go into prompts (hidden and system lines excluded), as `Name: text`. */
    fun chatTokens(messages: List<ChatMessage>, counter: TokenCounter): Int =
        messages.filter { it.inPrompt }.sumOf { counter.count("${it.name}: ${it.text}") }

    /** The largest messages by tokens as (index, tokens), biggest first. */
    fun largestMessages(messages: List<ChatMessage>, counter: TokenCounter, limit: Int = 5): List<Pair<Int, Int>> =
        messages.mapIndexed { i, m -> i to counter.count(m.text) }.sortedByDescending { it.second }.take(limit)
}
