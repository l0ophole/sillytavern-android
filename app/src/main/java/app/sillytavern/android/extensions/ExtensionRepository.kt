package app.sillytavern.android.extensions

import app.sillytavern.android.data.writeAtomic
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class InstalledExtension(val manifest: ExtensionManifest, val dir: File) {
    val id get() = manifest.id
    fun scriptFile() = File(dir, manifest.script)
}

/** Extensions live in `<root>/<id>/` with `manifest.json`, the script, and `storage.json` written by the script. */
class ExtensionRepository(val root: File) {
    fun list(): List<InstalledExtension> = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { load(it) }

    fun get(id: String): InstalledExtension? = if (ExtensionManifest.ID.matches(id)) load(File(root, id)) else null

    private fun load(dir: File): InstalledExtension? {
        val mf = File(dir, ExtensionManifest.FILE).takeIf { it.isFile } ?: return null
        val m = runCatching { ExtensionManifest.parse(mf.readText()) }.getOrNull() ?: return null
        return if (m.id == dir.name) InstalledExtension(m, dir) else null
    }

    /**
     * Installs a zip: `manifest.json` at the top level or inside a single top folder. An existing extension with the same id is replaced
     * (its storage is kept). Entries leaving the folder, too many files, or too much data are rejected before anything is written.
     */
    fun installZip(input: InputStream): InstalledExtension {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." }) throw ExtensionException("The zip contains an unsafe path: ${e.name}")
                if (files.size >= MAX_FILES) throw ExtensionException("The zip has too many files")
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_FILE_BYTES) throw ExtensionException("${e.name} is larger than ${MAX_FILE_BYTES / 1024 / 1024} MB")
                }
                val bytes = buf.toByteArray()
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw ExtensionException("The zip is larger than ${MAX_TOTAL_BYTES / 1024 / 1024} MB")
                files[name] = bytes
            }
        }
        val manifestPath = files.keys.filter { it == ExtensionManifest.FILE || it.count { c -> c == '/' } == 1 && it.endsWith("/" + ExtensionManifest.FILE) }
            .minByOrNull { it.length } ?: throw ExtensionException("The zip has no manifest.json")
        val prefix = manifestPath.removeSuffix(ExtensionManifest.FILE)
        val manifest = ExtensionManifest.parse(files.getValue(manifestPath).toString(Charsets.UTF_8))
        if (!files.containsKey(prefix + manifest.script)) throw ExtensionException("The script ${manifest.script} is missing from the zip")
        val dir = File(root, manifest.id)
        val storage = File(dir, STORAGE).takeIf { it.isFile }?.readBytes()
        dir.deleteRecursively()
        dir.mkdirs()
        for ((name, bytes) in files) {
            if (!name.startsWith(prefix)) continue
            val rel = name.removePrefix(prefix)
            if (rel == STORAGE) continue
            val target = File(dir, rel)
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw ExtensionException("The zip contains an unsafe path: $name")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        storage?.let { File(dir, STORAGE).writeBytes(it) }
        return InstalledExtension(manifest, dir)
    }

    fun remove(id: String): Boolean = get(id)?.dir?.deleteRecursively() ?: false

    // ---- per-extension storage: one JSON object of string values

    fun readStorage(id: String): Map<String, String> {
        val f = File(root, "$id/$STORAGE").takeIf { it.isFile } ?: return emptyMap()
        val o = runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull() ?: return emptyMap()
        return o.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }

    fun writeStorage(id: String, values: Map<String, String>) {
        val dir = File(root, id).takeIf { it.isDirectory } ?: return
        val text = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
        if (text.length > MAX_STORAGE_CHARS) throw ExtensionException("Extension storage is full")
        File(dir, STORAGE).writeAtomic(text)
    }

    companion object {
        const val STORAGE = "storage.json"
        const val MAX_FILES = 200
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
        const val MAX_STORAGE_CHARS = 512 * 1024
    }
}
