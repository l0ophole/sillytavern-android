package app.sillytavern.android.tags

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A tag as SillyTavern stores it in settings.json. [folderType] is OPEN, CLOSED or NONE. */
@Serializable
data class Tag(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    @SerialName("folder_type") val folderType: String = "NONE",
    val color: String = "",
    val color2: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("create_date") val createDate: Long = 0,
    @SerialName("is_hidden_on_character_card") val hiddenOnCard: Boolean = false,
)

/** Tags plus the entity-key -> tag-ids map (key = character avatar file name or group id). */
data class TagState(val tags: List<Tag> = emptyList(), val map: Map<String, List<String>> = emptyMap())

/** Pure operations on [TagState]; every function returns a new state. */
object TagOps {
    /** Existing tag with this name (case-insensitive) or a new one; null for a blank name. */
    fun ensure(s: TagState, name: String): Pair<TagState, Tag>? {
        val n = name.trim()
        if (n.isEmpty()) return null
        s.tags.firstOrNull { it.name.equals(n, true) }?.let { return s to it }
        val tag = Tag(name = n, sortOrder = (s.tags.maxOfOrNull { it.sortOrder } ?: 0) + 1, createDate = System.currentTimeMillis())
        return s.copy(tags = s.tags + tag) to tag
    }

    fun assign(s: TagState, key: String, tagId: String): TagState {
        val cur = s.map[key].orEmpty()
        return if (tagId in cur) s else s.copy(map = s.map + (key to cur + tagId))
    }

    fun unassign(s: TagState, key: String, tagId: String) = s.copy(map = s.map + (key to s.map[key].orEmpty().filter { it != tagId }))

    /** Renames a tag; unchanged when blank or when another tag already has that name. */
    fun rename(s: TagState, tagId: String, newName: String): TagState {
        val n = newName.trim()
        if (n.isEmpty() || s.tags.any { it.id != tagId && it.name.equals(n, true) }) return s
        return s.copy(tags = s.tags.map { if (it.id == tagId) it.copy(name = n) else it })
    }

    fun delete(s: TagState, tagId: String) =
        TagState(s.tags.filter { it.id != tagId }, s.map.mapValues { (_, v) -> v.filter { it != tagId } })

    fun setFolder(s: TagState, tagId: String, folderType: String) =
        s.copy(tags = s.tags.map { if (it.id == tagId) it.copy(folderType = folderType) else it })

    fun update(s: TagState, tagId: String, f: (Tag) -> Tag) =
        s.copy(tags = s.tags.map { if (it.id == tagId) f(it) else it })

    fun tagsOf(s: TagState, key: String): List<Tag> {
        val byId = s.tags.associateBy { it.id }
        return s.map[key].orEmpty().mapNotNull(byId::get).sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
    }

    /** Creates missing tags named in a card and assigns them to [key]. */
    fun importCardTags(s: TagState, key: String, cardTags: List<String>): TagState =
        cardTags.fold(s) { acc, name ->
            val (next, tag) = ensure(acc, name) ?: return@fold acc
            assign(next, key, tag.id)
        }

    fun moveKey(s: TagState, oldKey: String, newKey: String): TagState {
        val v = s.map[oldKey] ?: return s
        return s.copy(map = s.map - oldKey + (newKey to v))
    }

    fun removeKey(s: TagState, key: String) = s.copy(map = s.map - key)
}

/** Applies a [TagState] transformation to the persisted settings. */
fun app.sillytavern.android.data.SettingsRepository.updateTags(f: (TagState) -> TagState) =
    update { s -> f(s.tagState).let { n -> s.copy(tags = n.tags, tagMap = n.map) } }
