package app.sillytavern.android.chat

import app.sillytavern.android.data.AppSettings
import app.sillytavern.android.data.Persona

/** Which persona a chat should use: chat lock, then character lock, then the default persona. */
object PersonaLock {
    /** The persona id to switch to when a chat opens, or null to keep the current persona. Missing personas are ignored. */
    fun resolve(s: AppSettings, chatLockId: String?, characterFile: String?): String? {
        fun exists(id: String?) = id != null && s.personas.any { it.id == id }
        if (exists(chatLockId)) return chatLockId
        if (characterFile != null) s.personas.firstOrNull { characterFile in it.characters }?.let { return it.id }
        if (exists(s.defaultPersonaId)) return s.defaultPersonaId
        return null
    }

    /** Locks [characterFile] to [personaId] (and removes it from every other persona). */
    fun lockCharacter(personas: List<Persona>, personaId: String, characterFile: String): List<Persona> =
        personas.map { p ->
            when {
                p.id == personaId -> p.copy(characters = (p.characters + characterFile).distinct())
                characterFile in p.characters -> p.copy(characters = p.characters - characterFile)
                else -> p
            }
        }

    fun unlockCharacter(personas: List<Persona>, characterFile: String): List<Persona> =
        personas.map { if (characterFile in it.characters) it.copy(characters = it.characters - characterFile) else it }
}
