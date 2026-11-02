package app.sillytavern.android.worldinfo

fun WorldBook.addEntry(): WorldBook {
    val uid = (entries.maxOfOrNull { it.uid } ?: -1) + 1
    return copy(entries = entries + WorldEntry(uid = uid, displayIndex = uid))
}

fun WorldBook.updateEntry(e: WorldEntry) = copy(entries = entries.map { if (it.uid == e.uid) e else it })

fun WorldBook.deleteEntry(uid: Int) = copy(entries = entries.filter { it.uid != uid })

fun WorldBook.duplicateEntry(uid: Int): WorldBook {
    val i = entries.indexOfFirst { it.uid == uid }
    if (i < 0) return this
    val next = (entries.maxOf { it.uid }) + 1
    return copy(entries = entries.toMutableList().also { it.add(i + 1, entries[i].copy(uid = next, displayIndex = next)) })
}

fun WorldBook.moveEntry(uid: Int, delta: Int): WorldBook {
    val i = entries.indexOfFirst { it.uid == uid }
    if (i < 0) return this
    val j = (i + delta).coerceIn(0, entries.lastIndex)
    return copy(entries = entries.toMutableList().also { val e = it.removeAt(i); it.add(j, e) })
}

private val regexKeyAhead = Regex("^/.+?/[gimsuy]*(?=\\s*(?:,|$))", RegexOption.DOT_MATCHES_ALL)

/** Splits a comma-separated key list; commas inside a `/regex/flags` key are kept. */
fun parseKeys(text: String): List<String> {
    val out = mutableListOf<String>()
    var rest = text
    while (rest.isNotEmpty()) {
        rest = rest.trimStart()
        if (rest.isEmpty()) break
        val m = if (rest.startsWith("/")) regexKeyAhead.find(rest) else null
        val token = m?.value ?: rest.substringBefore(',')
        if (token.isBlank()) { rest = rest.drop(token.length + 1); continue }
        out += token.trim()
        rest = rest.drop(token.length).trimStart().removePrefix(",")
    }
    return out
}

fun formatKeys(keys: List<String>) = keys.joinToString(", ")
