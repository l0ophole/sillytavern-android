package app.sillytavern.android.media

import app.sillytavern.android.data.StoragePaths
import java.io.File

data class GalleryImage(val path: String, val file: File, val name: String, val modified: Long)

/** The per-character image folders under `user/images/` (SillyTavern's gallery extension). */
class GalleryRepository(private val paths: StoragePaths) {
    private val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif")
    private fun isImage(f: File) = f.isFile && f.extension.lowercase() in imageExt
    private fun dir(folder: String) = File(paths.userImages, StoragePaths.sanitizeFileName(folder))

    /** Folder names that hold at least one image, with their image counts. */
    fun folders(): List<Pair<String, Int>> =
        paths.userImages.listFiles { f -> f.isDirectory }.orEmpty()
            .map { it.name to it.listFiles().orEmpty().count(::isImage) }
            .filter { it.second > 0 }.sortedBy { it.first.lowercase() }

    /** Newest first. */
    fun list(folder: String): List<GalleryImage> =
        dir(folder).listFiles().orEmpty().filter(::isImage)
            .map { GalleryImage("user/images/${it.parentFile!!.name}/${it.name}", it, it.name, it.lastModified()) }
            .sortedByDescending { it.modified }

    /** Copies [bytes] into [folder] under a free name; returns the stored path. */
    fun add(folder: String, fileName: String, bytes: ByteArray): GalleryImage {
        val d = dir(folder).also { it.mkdirs() }
        val safe = StoragePaths.sanitizeFileName(fileName)
        val base = safe.substringBeforeLast('.'); val ext = safe.substringAfterLast('.', "png").let { if (it.lowercase() in imageExt) it else "png" }
        var f = File(d, "$base.$ext"); var n = 1
        while (f.exists()) f = File(d, "${base}_${n++}.$ext")
        f.writeBytes(bytes)
        return GalleryImage("user/images/${d.name}/${f.name}", f, f.name, f.lastModified())
    }

    /** Deletes the image at a stored [path]; false when it is outside the images folder or missing. */
    fun delete(path: String): Boolean {
        val f = MessageMedia.resolve(paths.root, path) ?: return false
        val inImages = f.canonicalPath.startsWith(paths.userImages.canonicalPath + File.separator)
        return inImages && f.isFile && f.delete()
    }
}
