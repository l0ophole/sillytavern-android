package app.sillytavern.android.tools

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.Backends
import app.sillytavern.android.appearance.BackgroundRepository
import app.sillytavern.android.appearance.ThemeIo
import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.data.writeAtomic
import app.sillytavern.android.worldinfo.WorldBookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

enum class AssetType(val label: String) {
    BACKGROUND("Background"), CHARACTER("Character"), WORLD("World / lorebook"), THEME("Theme");

    companion object {
        /** Maps a catalog type name; null for kinds this app has no place for (ambient, bgm, live2d, vrm, …). */
        fun of(name: String?): AssetType? = when (name?.trim()?.lowercase()) {
            "background", "backgrounds" -> BACKGROUND
            "character", "characters", "card" -> CHARACTER
            "world", "worlds", "lorebook", "worldinfo", "world_info" -> WORLD
            "theme", "themes" -> THEME
            else -> null
        }
    }
}

data class AssetEntry(val type: AssetType?, val rawType: String, val name: String, val url: String, val description: String = "") {
    /** File name the download is stored under. */
    val fileName: String get() = (url.substringBefore('?').substringAfterLast('/').takeIf { it.contains('.') } ?: name).let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    val installable get() = type != null && url.toHttpUrlOrNull() != null
}

object AssetCatalog {
    /**
     * Reads a catalog: a JSON array of entries, `{assets:[…]}`, or SillyTavern's `{category:[…]}` object. An entry has `url` and
     * `type` (or takes the category name), and `name`/`id`/`filename` for display. Entries without a URL are dropped.
     */
    fun parse(text: String): List<AssetEntry> {
        val root = runCatching { Json.parseToJsonElement(text.trimStart(0xFEFF.toChar())) }.getOrNull() ?: throw BackendException("The catalog is not valid JSON")
        fun entries(arr: JsonArray, category: String?) = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val url = str("url") ?: return@mapNotNull null
            val raw = str("type") ?: category ?: ""
            AssetEntry(AssetType.of(raw), raw, str("name") ?: str("id") ?: str("filename") ?: url.substringAfterLast('/'), url, str("description").orEmpty())
        }
        return when (root) {
            is JsonArray -> entries(root, null)
            is JsonObject -> when {
                root["assets"] is JsonArray -> entries(root["assets"] as JsonArray, null)
                else -> root.flatMap { (k, v) -> (v as? JsonArray)?.let { entries(it, k) }.orEmpty() }
            }
            else -> throw BackendException("The catalog has no asset list")
        }
    }
}

/** Downloads catalog entries into the matching data folders. */
class AssetInstaller(
    private val paths: StoragePaths,
    private val characters: CharacterRepository,
    private val worlds: WorldBookRepository,
    private val backgrounds: BackgroundRepository,
    private val client: OkHttpClient = Backends.defaultClient,
) {
    suspend fun fetchCatalog(url: String): List<AssetEntry> {
        val text = download(url, MAX_CATALOG).toString(Charsets.UTF_8)
        return AssetCatalog.parse(text)
    }

    /** Downloads [entry] and stores it; returns what was installed (a name for the UI). */
    suspend fun install(entry: AssetEntry): String {
        val type = entry.type ?: throw BackendException("Assets of type \"${entry.rawType}\" are not supported")
        return store(type, entry.fileName, download(entry.url, MAX_ASSET))
    }

    /** Stores downloaded or bundled [bytes] as [type]. */
    fun store(type: AssetType, fileName: String, bytes: ByteArray): String = when (type) {
        AssetType.BACKGROUND -> backgrounds.import(fileName, bytes).getOrThrow()
        AssetType.CHARACTER -> characters.import(bytes).card.name
        AssetType.WORLD -> worlds.import(fileName, bytes.toString(Charsets.UTF_8)).getOrThrow()
        AssetType.THEME -> {
            val text = bytes.toString(Charsets.UTF_8)
            val name = ThemeIo.nameOf(text)?.takeIf { it.isNotBlank() } ?: throw BackendException("Not a theme file")
            File(paths.themes, StoragePaths.sanitizeFileName(name) + ".json").also { paths.themes.mkdirs() }.writeAtomic(text)
            name
        }
    }

    /** Names of the themes stored in the themes folder. */
    fun themes(): List<String> = (paths.themes.listFiles { f -> f.extension == "json" } ?: emptyArray()).map { it.nameWithoutExtension }.sortedBy { it.lowercase() }
    fun themeText(name: String): String? = File(paths.themes, StoragePaths.sanitizeFileName(name) + ".json").takeIf { it.isFile }?.readText()

    private suspend fun download(url: String, max: Long): ByteArray = withContext(Dispatchers.IO) {
        val http = url.toHttpUrlOrNull() ?: throw BackendException("Not a valid http(s) address")
        try {
            client.newCall(Request.Builder().url(http).build()).execute().use { r ->
                if (!r.isSuccessful) throw BackendException("Download failed: HTTP ${r.code}")
                val body = r.body ?: throw BackendException("Empty download")
                if (body.contentLength() > max) throw BackendException("The file is larger than ${max / 1_000_000} MB")
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > max) throw BackendException("The file is larger than ${max / 1_000_000} MB")
                    }
                }
                out.toByteArray()
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }
    }

    companion object {
        const val MAX_ASSET = 25_000_000L
        const val MAX_CATALOG = 2_000_000L
    }
}
