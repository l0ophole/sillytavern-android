package app.sillytavern.android.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What an extension script is allowed to do; each `st.*` call checks the permission it names. */
enum class Permission(val key: String, val label: String) {
    NETWORK("network", "Make HTTP requests"),
    GENERATE("generate", "Ask the model for text"),
    CHAT("chat", "Add or send chat messages"),
    PROMPT("prompt", "Add text to the prompt"),
    COMMANDS("commands", "Register and run slash commands"),
    MACROS("macros", "Register macros");

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key.trim().lowercase() } }
}

@Serializable
data class ExtensionManifest(
    val id: String,
    val name: String = id,
    val version: String = "0.0.0",
    val description: String = "",
    val author: String = "",
    val script: String = "index.js",
    val permissions: List<String> = emptyList(),
) {
    /** Known permissions; unknown keys are reported by [validate] instead of being silently ignored. */
    val granted: Set<Permission> get() = permissions.mapNotNull { Permission.of(it) }.toSet()

    fun validate(): String? = when {
        !ID.matches(id) -> "The id must be 1-64 characters of a-z, 0-9, '.', '_' or '-'"
        script.isBlank() || script.startsWith("/") || script.split('/', '\\').any { it == ".." || it.isEmpty() } -> "The script path is not valid"
        !script.endsWith(".js") -> "The script must be a .js file"
        permissions.any { Permission.of(it) == null } -> "Unknown permission: " + permissions.first { Permission.of(it) == null }
        else -> null
    }

    companion object {
        val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        const val FILE = "manifest.json"

        fun parse(text: String): ExtensionManifest {
            val m = try { json.decodeFromString(serializer(), text.trimStart(0xFEFF.toChar())) }
            catch (e: Exception) { throw ExtensionException("manifest.json is not valid: ${e.message?.lineSequence()?.first()}") }
            m.validate()?.let { throw ExtensionException(it) }
            return m
        }
    }
}

class ExtensionException(message: String) : Exception(message)

@Serializable
data class ExtensionSettings(
    /** Ids of enabled extensions. */
    val enabled: Set<String> = emptySet(),
    /** Permissions the user confirmed per extension id (a script never gets more than manifest ∩ confirmed). */
    val confirmed: Map<String, Set<String>> = emptyMap(),
)
