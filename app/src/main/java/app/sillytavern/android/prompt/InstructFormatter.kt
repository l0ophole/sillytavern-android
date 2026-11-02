package app.sillytavern.android.prompt

import app.sillytavern.android.prompt.model.ContextTemplate
import app.sillytavern.android.prompt.model.InstructTemplate

/**
 * Inputs for text-completion formatting. [history] roles follow PromptBuilder's convention
 * (in IMPERSONATE mode roles are flipped relative to who really spoke); SYSTEM entries are allowed anywhere.
 */
class TextPromptParts(
    val storyVars: Map<String, String>,
    val exampleBlocks: List<String> = emptyList(),
    val history: List<PromptMessage>,
    val postHistory: String = "",
)

data class FormattedPrompt(val text: String, val stopStrings: List<String>)

/** Turns prompt parts into a single text-completion prompt using an instruct template (or plain `Name: text` lines). */
object InstructFormatter {
    private enum class Side { USER, CHAR, SYSTEM }
    private class Turn(val side: Side, val text: String)

    fun format(
        parts: TextPromptParts, instruct: InstructTemplate?, context: ContextTemplate,
        userName: String, charName: String, mode: GenerationMode,
    ): FormattedPrompt {
        val impersonate = mode == GenerationMode.IMPERSONATE
        val vars = parts.storyVars + mapOf("char" to charName, "user" to userName)
        val story = StoryStringRenderer.render(context.storyString, vars).trim()

        fun macro(s: String) = if (instruct?.macro == false) s else s.replace("{{char}}", charName).replace("{{user}}", userName)

        val turns = buildList {
            parts.history.forEach { m ->
                val side = when (m.role) {
                    Role.SYSTEM -> Side.SYSTEM
                    // Undo PromptBuilder's role flip so names/sequences reflect who actually speaks.
                    Role.USER, Role.TOOL -> if (impersonate) Side.CHAR else Side.USER
                    Role.ASSISTANT -> if (impersonate) Side.USER else Side.CHAR
                }
                add(Turn(side, m.content))
            }
            if (parts.postHistory.isNotBlank()) add(Turn(Side.SYSTEM, parts.postHistory))
        }
        fun nameOf(s: Side) = if (s == Side.USER) userName else charName

        val sb = StringBuilder()
        // --- header: story string, examples, chat start ---
        val skipExamples = instruct?.skipExamples == true
        if (instruct == null) {
            if (story.isNotBlank()) sb.append(story).append("\n\n")
        } else if (story.isNotBlank()) {
            sb.append(joinSeq(macro(instruct.storyStringPrefix), story, instruct.wrap)).append(macro(instruct.storyStringSuffix))
        }
        if (!skipExamples) parts.exampleBlocks.filter { it.isNotBlank() }.forEach { ex ->
            if (context.exampleSeparator.isNotBlank()) sb.append(context.exampleSeparator).append('\n')
            sb.append(ex.trim()).append('\n')
        }
        if (context.chatStart.isNotBlank()) sb.append(context.chatStart).append('\n')

        val firstUser = turns.indexOfFirst { it.side == Side.USER }
        val lastUser = turns.indexOfLast { it.side == Side.USER }
        val firstChar = turns.indexOfFirst { it.side == Side.CHAR }
        val continuing = mode == GenerationMode.CONTINUE && turns.lastOrNull()?.side == Side.CHAR

        turns.forEachIndexed { idx, t ->
            if (instruct == null) {
                when (t.side) {
                    Side.SYSTEM -> sb.append(t.text).append("\n\n")
                    else -> sb.append(nameOf(t.side)).append(": ").append(t.text).append('\n')
                }
                return@forEachIndexed
            }
            val showName = t.side != Side.SYSTEM && instruct.namesBehavior != "none"
            val prefix = macro(when (t.side) {
                Side.USER -> when {
                    idx == firstUser && instruct.firstInputSequence.isNotEmpty() -> instruct.firstInputSequence
                    idx == lastUser && instruct.lastInputSequence.isNotEmpty() -> instruct.lastInputSequence
                    else -> instruct.inputSequence
                }
                Side.CHAR -> if (idx == firstChar && instruct.firstOutputSequence.isNotEmpty()) instruct.firstOutputSequence else instruct.outputSequence
                Side.SYSTEM -> if (instruct.systemSameAsUser) instruct.inputSequence else instruct.systemSequence
            })
            val suffix = macro(when (t.side) {
                Side.USER -> instruct.inputSuffix
                Side.CHAR -> instruct.outputSuffix
                Side.SYSTEM -> if (instruct.systemSameAsUser) instruct.inputSuffix else instruct.systemSuffix
            })
            val open = continuing && idx == turns.lastIndex
            val body = (if (showName) "${nameOf(t.side)}: " else "") + t.text + (if (open) "" else suffix)
            sb.append(joinSeq(prefix, body, instruct.wrap))
        }

        // --- open the model's turn ---
        if (!continuing) {
            if (instruct == null) {
                sb.append(if (impersonate) userName else charName).append(':')
            } else {
                val seq = macro(
                    if (impersonate) instruct.inputSequence.ifEmpty { "" }
                    else instruct.lastOutputSequence.ifEmpty { instruct.outputSequence },
                )
                val namePart = if (instruct.namesBehavior != "none") "${if (impersonate) userName else charName}:" else ""
                sb.append(joinSeq(seq, namePart, instruct.wrap))
                if (namePart.isEmpty() && seq.isNotEmpty() && instruct.wrap) sb.append('\n')
            }
        }
        return FormattedPrompt(sb.toString(), stopStrings(instruct, context, userName, charName, impersonate, ::macro))
    }

    private fun joinSeq(prefix: String, body: String, wrap: Boolean) =
        if (prefix.isNotEmpty() && body.isNotEmpty() && wrap) "$prefix\n$body" else prefix + body

    private fun stopStrings(
        instruct: InstructTemplate?, context: ContextTemplate, user: String, char: String,
        impersonate: Boolean, macro: (String) -> String,
    ): List<String> {
        val out = LinkedHashSet<String>()
        if (instruct != null) {
            if (instruct.stopSequence.isNotBlank()) out += macro(instruct.stopSequence)
            if (instruct.sequencesAsStopStrings) listOf(
                instruct.inputSequence, instruct.outputSequence, instruct.systemSequence, instruct.firstOutputSequence,
                instruct.lastOutputSequence, instruct.firstInputSequence, instruct.lastInputSequence, instruct.lastSystemSequence,
            ).map { macro(it).trim() }.filter { it.isNotEmpty() }.forEach { out += it }
        }
        if (context.namesAsStopStrings) out += "\n${if (impersonate) char else user}:"
        return out.filter { it.isNotBlank() }
    }
}
