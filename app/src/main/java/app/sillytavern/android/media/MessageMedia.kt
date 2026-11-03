package app.sillytavern.android.media

import app.sillytavern.android.chat.ChatMessage
import java.io.File
import kotlinx.serialization.json.*

/** An attached text file (`extra.file`). */
data class FileAttachment(val path: String, val name: String, val size: Long)

/**
 * SillyTavern keeps attachments in a message's `extra`: `image` (path), `inline_image` (generated, not attached),
 * `title` (the prompt that made it), `image_swipes`, `file` ({url,size,name}). Paths are relative to the data root
 * (`user/images/<folder>/<file>`).
 */
val ChatMessage.imagePath: String? get() = (extra["image"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
val ChatMessage.isInlineImage: Boolean get() = extra["inline_image"]?.jsonPrimitive?.booleanOrNull == true
/** Prompt text that produced a generated image. */
val ChatMessage.imageTitle: String? get() = (extra["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
val ChatMessage.imageCaption: String? get() = (extra["image_caption"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
val ChatMessage.imageSwipes: List<String> get() = (extra["image_swipes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

val ChatMessage.fileAttachment: FileAttachment? get() {
    val o = extra["file"] as? JsonObject ?: return null
    val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return null
    return FileAttachment(url, (o["name"] as? JsonPrimitive)?.contentOrNull ?: File(url).name, (o["size"] as? JsonPrimitive)?.longOrNull ?: 0)
}

fun ChatMessage.withImage(path: String?, title: String? = null, inline: Boolean = false): ChatMessage {
    var m = withExtra("image", path?.let(::JsonPrimitive))
    m = m.withExtra("title", if (path != null) title?.let(::JsonPrimitive) else null)
    m = m.withExtra("inline_image", if (path != null && inline) JsonPrimitive(true) else null)
    if (path == null) m = m.withExtra("image_caption", null)
    return m
}

fun ChatMessage.withCaption(caption: String?): ChatMessage = withExtra("image_caption", caption?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))

fun ChatMessage.withFile(f: FileAttachment?): ChatMessage = withExtra("file", f?.let {
    buildJsonObject { put("url", it.path); put("name", it.name); put("size", it.size) }
})

object MessageMedia {
    /**
     * Resolves a stored relative path under [root]. Returns null when it escapes [root] (a crafted chat file must not
     * make the app read arbitrary files).
     */
    fun resolve(root: File, relative: String): File? {
        val f = File(root, relative)
        val base = root.canonicalFile
        val c = runCatching { f.canonicalFile }.getOrNull() ?: return null
        return c.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    fun dataUrl(name: String, bytes: ByteArray): String =
        "data:${mimeOf(name)};base64," + java.util.Base64.getEncoder().encodeToString(bytes)

    /** Parses `data:<mime>;base64,<payload>`. */
    fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',').takeIf { it > 0 } ?: return null
        val head = url.substring(5, comma)
        if (!head.endsWith(";base64")) return null
        return head.removeSuffix(";base64") to url.substring(comma + 1)
    }

    /** Folder name for a character's / group's images. */
    fun folderFor(name: String) = app.sillytavern.android.data.StoragePaths.sanitizeFileName(name)
}
