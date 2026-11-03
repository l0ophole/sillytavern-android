package app.sillytavern.android.appearance

import app.sillytavern.android.data.StoragePaths
import java.io.File

/** Chat background images in the `backgrounds` folder (same folder name as SillyTavern). */
class BackgroundRepository(private val paths: StoragePaths) {
    private val exts = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun list(): List<String> =
        (paths.backgrounds.listFiles { f -> f.isFile && f.extension.lowercase() in exts } ?: emptyArray()).map { it.name }.sortedBy { it.lowercase() }

    fun file(name: String): File? {
        if (name.isBlank()) return null
        val f = File(paths.backgrounds, StoragePaths.sanitizeFileName(name))
        return f.takeIf { it.isFile && it.parentFile == paths.backgrounds }
    }

    /** Stores [bytes] under a safe, unique name derived from [desiredName]. Fails for non-image extensions and empty data. */
    fun import(desiredName: String, bytes: ByteArray): Result<String> = runCatching {
        require(bytes.isNotEmpty()) { "The file is empty" }
        val clean = StoragePaths.sanitizeFileName(desiredName.substringAfterLast('/'))
        val ext = clean.substringAfterLast('.', "").lowercase()
        require(ext in exts) { "Not an image file (png, jpg, webp, gif)" }
        val base = clean.dropLast(ext.length + 1).ifEmpty { "background" }
        var n = 1
        var target = File(paths.backgrounds, "$base.$ext")
        while (target.exists()) { n++; target = File(paths.backgrounds, "$base ($n).$ext") }
        paths.backgrounds.mkdirs()
        target.writeBytes(bytes)
        target.name
    }

    fun delete(name: String): Boolean = file(name)?.delete() ?: false
}
