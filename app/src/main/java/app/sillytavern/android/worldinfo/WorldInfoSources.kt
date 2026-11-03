package app.sillytavern.android.worldinfo

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.data.AppSettings
import kotlinx.serialization.json.*

/** Collects the entries active for a character, in SillyTavern's priority order. */
object WorldInfoSources {
    fun resolve(
        card: CharacterCard, characterFileName: String, chatBook: String?, settings: AppSettings, repo: WorldBookRepository,
        personaBook: String? = null,
    ): List<ScanEntry> {
        fun entriesOf(book: WorldBook) = book.entries.map { ScanEntry(book.name, it) }.sortedByDescending { it.entry.order }
        fun load(name: String) = repo.load(name)?.let(::entriesOf).orEmpty()

        val chat = chatBook?.takeIf { it.isNotBlank() }
        val persona = personaBook?.takeIf { it.isNotBlank() && it != chat }
        val global = settings.globalWorldBooks.distinct().filter { it != chat && it != persona }
        val attached = ((card.raw["extensions"] as? JsonObject)?.get("world") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val charBooks = (listOfNotNull(attached) + settings.characterExtraBooks[characterFileName].orEmpty()).distinct()
            .filter { it !in global && it != chat && it != persona }

        val chatLore = chat?.let(::load).orEmpty()
        val globalLore = global.flatMap(::load)
        val charLore = charBooks.flatMap(::load) + embedded(card, attached)

        val mid = when (settings.worldInfo.characterStrategy) {
            0 -> (globalLore + charLore).sortedByDescending { it.entry.order }
            2 -> globalLore.sortedByDescending { it.entry.order } + charLore.sortedByDescending { it.entry.order }
            else -> charLore.sortedByDescending { it.entry.order } + globalLore.sortedByDescending { it.entry.order }
        }
        return (chatLore + persona?.let(::load).orEmpty() + mid).filter { passesCharacterFilter(it.entry, characterFileName, card.tags) }
    }

    /** A card's own `character_book` counts only when no world book is attached (importing it replaces it). */
    private fun embedded(card: CharacterCard, attached: String?): List<ScanEntry> {
        if (attached != null) return emptyList()
        val cb = card.raw["character_book"] as? JsonObject ?: return emptyList()
        val book = WorldJson.fromCharacterBook("${card.name} (embedded)", cb)
        return book.entries.map { ScanEntry(book.name, it) }.sortedByDescending { it.entry.order }
    }

    private fun passesCharacterFilter(e: WorldEntry, fileName: String, tags: List<String>): Boolean {
        if (e.characterFilterNames.isNotEmpty()) {
            val included = fileName in e.characterFilterNames
            if (if (e.characterFilterExclude) included else !included) return false
        }
        if (e.characterFilterTags.isNotEmpty()) {
            val has = tags.any { it in e.characterFilterTags }
            if (if (e.characterFilterExclude) has else !has) return false
        }
        return true
    }
}
