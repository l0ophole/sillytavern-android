package app.sillytavern.android.data

import java.io.File

/** On-disk layout, mirroring SillyTavern's per-user data directory names for import/export parity. */
class StoragePaths(val root: File) {
    val characters = File(root, "characters")
    val chats = File(root, "chats")
    val userAvatars = File(root, "User Avatars")
    val presets = File(root, "OpenAI Settings")
    val settingsFile = File(root, "settings.json")
    val secretsFile = File(root, "secrets.enc.json")

    fun ensure(): StoragePaths {
        listOf(root, characters, chats, userAvatars, presets).forEach { it.mkdirs() }
        return this
    }

    fun chatDir(characterFileName: String) = File(chats, sanitizeFileName(characterFileName))

    companion object {
        /** Strip path separators and other characters unsafe in file names. */
        fun sanitizeFileName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.')
            return cleaned.ifEmpty { "unnamed" }.take(120)
        }
    }
}
