package app.sillytavern.android.media

import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.prompt.PromptMessage

/** What the prompt builders need to know about media. */
class MediaPromptContext(
    val mode: ImagePromptMode,
    /** Text of an attached file (path relative to the data root), or null when unreadable. */
    val fileText: (String) -> String? = { null },
)

/**
 * Image markers: the builders leave `⟦img:<path>⟧` in a message's text; [resolve] swaps them for image parts or
 * caption text once the final message list exists (so trimming and instruct formatting never see image bytes).
 */
object MediaPrompt {
    private const val OPEN = "⟦img:"
    private const val CLOSE = "⟧"
    private val marker = Regex(Regex.escape(OPEN) + "(.*?)" + Regex.escape(CLOSE))

    fun marker(path: String) = "$OPEN$path$CLOSE"

    /** Caption to use in place of an image: explicit caption, or the prompt that generated it. */
    fun captionOf(m: ChatMessage): String? = m.imageCaption ?: if (m.isInlineImage) m.imageTitle else null

    /** Adds the file text / image marker of [m] to its prompt [text]. */
    fun decorate(m: ChatMessage, text: String, ctx: MediaPromptContext): String {
        var out = text
        m.fileAttachment?.let { f ->
            ctx.fileText(f.path)?.takeIf { it.isNotBlank() }?.let { out = "[File: ${f.name}]\n$it\n[End of file]\n\n$out".trimEnd() }
        }
        val img = m.imagePath ?: return out
        val caption = captionOf(m)
        return when (ctx.mode) {
            ImagePromptMode.SEND_IMAGE -> if (m.isInlineImage && caption != null) "[Image: $caption]\n$out".trimEnd() else marker(img) + (if (out.isEmpty()) "" else "\n$out")
            ImagePromptMode.USE_CAPTION -> if (caption != null) "[Image: $caption]\n$out".trimEnd() else out
        }
    }

    /**
     * Replaces markers in [messages]: with image parts (data URLs from [load]) when [keepImages], otherwise they are
     * removed. Unreadable images are dropped. Only user/assistant messages carry images.
     */
    fun resolve(messages: List<PromptMessage>, keepImages: Boolean, load: (String) -> String?): List<PromptMessage> =
        messages.map { m ->
            if (!m.content.contains(OPEN)) return@map m
            val images = mutableListOf<String>()
            val text = marker.replace(m.content) { r ->
                if (keepImages) load(r.groupValues[1])?.let { images += it }
                ""
            }.trim()
            m.copy(content = text, images = m.images + images)
        }

    /** Paths of every image marked in [text]. */
    fun paths(text: String): List<String> = marker.findAll(text).map { it.groupValues[1] }.toList()
}
