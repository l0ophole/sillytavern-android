package app.sillytavern.android.extensions

import app.sillytavern.android.data.AppSettings
import java.io.InputStream

/** The operations behind the Extensions screen: install, enable (with confirmed permissions), disable, remove. */
class ExtensionManager(
    private val repo: ExtensionRepository,
    private val host: ExtensionHost,
    private val settings: () -> AppSettings,
    private val update: ((AppSettings) -> AppSettings) -> Unit,
) {
    private fun edit(f: (ExtensionSettings) -> ExtensionSettings) = update { it.copy(extensions = f(it.extensions)) }

    /** Installs a zip. A reinstall stops the extension and withdraws its approval: it has to be enabled (and its permissions confirmed) again. */
    suspend fun install(zip: InputStream): InstalledExtension {
        val ext = repo.installZip(zip)
        edit { it.copy(enabled = it.enabled - ext.id, confirmed = it.confirmed - ext.id) }
        host.sync(reload = setOf(ext.id))
        return ext
    }

    /** Turns an extension on; the caller has shown its permissions, and [ExtensionSettings.confirmed] records what the user accepted. */
    suspend fun enable(id: String) {
        val ext = repo.get(id) ?: throw ExtensionException("$id is not installed")
        edit { it.copy(enabled = it.enabled + id, confirmed = it.confirmed + (id to ext.manifest.granted.map { p -> p.key }.toSet())) }
        host.sync(reload = setOf(id))
    }

    suspend fun disable(id: String) {
        edit { it.copy(enabled = it.enabled - id) }
        host.sync()
    }

    suspend fun remove(id: String) {
        edit { it.copy(enabled = it.enabled - id, confirmed = it.confirmed - id) }
        host.sync()
        repo.remove(id)
        host.clearLog(id)
        host.sync()
    }

    fun isEnabled(id: String) = id in settings().extensions.enabled
}
