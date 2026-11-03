package app.sillytavern.android.memory

import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.prompt.ExtensionPrompt

/** Chat memory: embeds the older messages of a chat and recalls the ones closest to the latest messages. */
class VectorMemory(private val store: VectorStore) {
    private class Wanted(val id: String, val index: Int, val text: String)

    private fun eligible(m: ChatMessage) = !m.isSystem && !m.isHidden && m.text.isNotBlank()

    /** Chunks of every indexable message older than the protected window. */
    private fun wanted(history: List<ChatMessage>, s: VectorSettings): List<Wanted> {
        val limit = history.size - s.protectRecent.coerceAtLeast(0)
        val out = ArrayList<Wanted>()
        for (i in 0 until limit.coerceAtLeast(0)) {
            val m = history[i]
            if (!eligible(m)) continue
            val hash = m.text.hashCode().toUInt().toString(16)
            TextChunker.chunk(m.text, s.chunkSize).forEachIndexed { n, c -> out += Wanted("$i:$hash:$n", i, "${m.name}: $c") }
        }
        return out
    }

    /** Embeds what is missing and drops entries of edited or deleted messages (only within [history]). */
    suspend fun sync(collection: String, history: List<ChatMessage>, s: VectorSettings, embedder: Embedder) {
        val wanted = wanted(history, s)
        val have = store.ids(collection, embedder.id)
        val keep = wanted.mapTo(HashSet()) { it.id }
        val window = history.size - s.protectRecent.coerceAtLeast(0)
        val stale = have.filter { id -> id !in keep && (id.substringBefore(':').toIntOrNull() ?: Int.MAX_VALUE) < window }
        if (stale.isNotEmpty()) store.remove(collection, stale)
        val missing = wanted.filter { it.id !in have }
        if (missing.isEmpty()) return
        val vectors = embedder.embed(missing.map { it.text })
        store.insert(collection, embedder.id, missing.mapIndexed { i, w -> VectorEntry(w.id, w.text, vectors[i], w.index.toString()) })
    }

    /** The recalled lines in chat order, or null when nothing relevant was found. */
    suspend fun recall(collection: String, history: List<ChatMessage>, s: VectorSettings, embedder: Embedder): String? {
        val query = history.filter(::eligible).takeLast(s.queryMessages.coerceAtLeast(1)).joinToString("\n") { it.text }
        if (query.isBlank()) return null
        val limit = history.size - s.protectRecent.coerceAtLeast(0)
        val q = embedder.embed(listOf(query)).single()
        val hits = store.query(collection, embedder.id, q, s.retrieveCount * 4, s.scoreThreshold)
            .filter { (it.entry.meta.toIntOrNull() ?: Int.MAX_VALUE) < limit }
            .take(s.retrieveCount)
            .sortedWith(compareBy({ it.entry.meta.toIntOrNull() ?: 0 }, { it.entry.id }))
        if (hits.isEmpty()) return null
        return hits.joinToString("\n") { it.entry.text }
    }

    suspend fun prompt(collection: String, history: List<ChatMessage>, s: VectorSettings, embedder: Embedder): ExtensionPrompt? {
        if (!s.enabled) return null
        sync(collection, history, s, embedder)
        val text = recall(collection, history, s, embedder) ?: return null
        return ExtensionPrompt("vectors", s.template.replace("{{text}}", text), s.position, s.depth, s.role)
    }

    fun clear(collection: String) = store.drop(collection)
}
