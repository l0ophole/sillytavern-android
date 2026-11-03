package app.sillytavern.android.tags

/** A list entry (character or group) as seen by the filter. */
data class TagEntity(val key: String, val name: String, val fav: Boolean, val cardTags: List<String> = emptyList())

object TagFilter {
    /**
     * Include = entity has all these tags; exclude = has none. Members of a CLOSED folder tag are hidden
     * unless that folder is among [include]. [query] matches the name or any tag name.
     */
    fun apply(entities: List<TagEntity>, s: TagState, include: Set<String>, exclude: Set<String>, favoritesOnly: Boolean, query: String): List<TagEntity> {
        val closed = s.tags.filter { it.folderType == "CLOSED" }.map { it.id }.toSet()
        val q = query.trim()
        return entities.filter { e ->
            val ids = s.map[e.key].orEmpty().toSet()
            val names = TagOps.tagsOf(s, e.key).map { it.name } + e.cardTags
            (!favoritesOnly || e.fav) &&
                ids.containsAll(include) && ids.none { it in exclude } &&
                (ids.intersect(closed) - include).isEmpty() &&
                (q.isEmpty() || e.name.contains(q, true) || names.any { it.contains(q, true) })
        }
    }
}
