package app.sillytavern.android.tools

import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.worldinfo.WorldBookRepository

/** Reads files bundled with the app (Android assets); a plain function so tests can supply files. */
fun interface BundledFiles {
    fun read(path: String): ByteArray
}

class DefaultItem(val type: AssetType, val title: String, val path: String)

/** The content SillyTavern ships for first-time users: the Seraphina card, the Eldoria world and the stock themes. */
class DefaultContent(
    private val files: BundledFiles,
    private val installer: AssetInstaller,
    private val characters: CharacterRepository,
    private val worlds: WorldBookRepository,
) {
    val items: List<DefaultItem> = listOf(
        DefaultItem(AssetType.CHARACTER, "Seraphina", "default-content/default_Seraphina.png"),
        DefaultItem(AssetType.WORLD, "Eldoria", "default-content/Eldoria.json"),
    ) + listOf("Azure", "Cappuccino", "Celestial Macaron", "Dark Lite", "Dark V 1.0").map { DefaultItem(AssetType.THEME, it, "default-content/themes/$it.json") }

    fun isInstalled(item: DefaultItem): Boolean = when (item.type) {
        AssetType.CHARACTER -> characters.list().any { it.card.name == item.title }
        AssetType.WORLD -> item.title in worlds.list()
        AssetType.THEME -> item.title in installer.themes()
        AssetType.BACKGROUND -> false
    }

    /** Installs the item unless it is already there; returns true when something was written. */
    fun install(item: DefaultItem): Boolean {
        if (isInstalled(item)) return false
        installer.store(item.type, item.path.substringAfterLast('/'), files.read(item.path))
        return true
    }

    /** Installs the character and world (not the themes); used for an empty library. */
    fun installStarter(): Int = items.filter { it.type != AssetType.THEME }.count { install(it) }

    fun installAll(): Int = items.count { install(it) }
}
