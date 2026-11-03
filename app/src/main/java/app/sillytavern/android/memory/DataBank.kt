package app.sillytavern.android.memory

import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.prompt.ExtensionPrompt
import java.io.File

enum class BankScope { GLOBAL, CHARACTER, CHAT }

/** Where a document is kept: [key] is the character file name / group id / chat file name (empty for global). */
data class BankScopeKey(val scope: BankScope, val key: String = "") {
    val collection get() = when (scope) {
        BankScope.GLOBAL -> "bank_global"
        BankScope.CHARACTER -> "bank_char_$key"
        BankScope.CHAT -> "bank_chat_$key"
    }
}

data class BankDocument(val scope: BankScopeKey, val name: String, val file: File, val size: Long)

/** Text documents the model can recall from (SillyTavern's Data Bank): `user/data-bank/<scope>/<key>/<name>.txt`. */
class DataBank(private val root: File, private val store: VectorStore) {
    private fun dir(k: BankScopeKey) = when (k.scope) {
        BankScope.GLOBAL -> File(root, "global")
        BankScope.CHARACTER -> File(root, "character/" + StoragePaths.sanitizeFileName(k.key))
        BankScope.CHAT -> File(root, "chat/" + StoragePaths.sanitizeFileName(k.key))
    }

    private val extensions = setOf("txt", "md", "markdown", "text")

    fun list(k: BankScopeKey): List<BankDocument> =
        dir(k).listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in extensions }
            .sortedBy { it.name.lowercase() }.map { BankDocument(k, it.name, it, it.length()) }

    /** Saves a document under a free name; returns it. */
    fun add(k: BankScopeKey, fileName: String, text: String): BankDocument {
        val d = dir(k).also { it.mkdirs() }
        val safe = StoragePaths.sanitizeFileName(fileName)
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").lowercase().takeIf { it in extensions } ?: "txt"
        var f = File(d, "$base.$ext"); var n = 1
        while (f.exists()) f = File(d, "${base}_${n++}.$ext")
        f.writeText(text)
        return BankDocument(k, f.name, f, f.length())
    }

    fun read(doc: BankDocument): String = doc.file.readText()

    fun remove(doc: BankDocument): Boolean {
        val ok = doc.file.delete()
        store.remove(doc.scope.collection, store.idsAnyEmbedder(doc.scope.collection).filter { it.startsWith(docKey(doc.name) + "|") })
        return ok
    }

    /** Drops every vector of a scope so it is rebuilt on next use (after changing the embedder or chunk size). */
    fun reindex(k: BankScopeKey) = store.drop(k.collection)

    private fun docKey(name: String) = name.replace("|", "_")

    /** Embeds the chunks that are not stored yet and drops those of changed or deleted documents. */
    suspend fun sync(k: BankScopeKey, s: DataBankSettings, embedder: Embedder) {
        val docs = list(k)
        val overlap = s.chunkSize * s.overlapPercent.coerceIn(0, 50) / 100
        val wanted = LinkedHashMap<String, Pair<String, String>>() // id -> (doc, text)
        for (d in docs) {
            val text = d.file.readText()
            val hash = (text.hashCode().toUInt().toString(16)) + "-" + s.chunkSize + "-" + overlap
            TextChunker.chunk(text, s.chunkSize, overlap).forEachIndexed { i, c -> wanted["${docKey(d.name)}|$hash|$i"] = d.name to c }
        }
        val have = store.ids(k.collection, embedder.id)
        val stale = have.filter { it !in wanted }
        if (stale.isNotEmpty()) store.remove(k.collection, stale)
        val missing = wanted.filterKeys { it !in have }
        if (missing.isEmpty()) return
        val entries = missing.entries.toList()
        val vectors = embedder.embed(entries.map { it.value.second })
        store.insert(k.collection, embedder.id, entries.mapIndexed { i, e -> VectorEntry(e.key, e.value.second, vectors[i], e.value.first) })
    }

    /** Best chunks over [scopes] for [query], highest score first. */
    suspend fun retrieve(scopes: List<BankScopeKey>, query: String, s: DataBankSettings, embedder: Embedder): List<VectorHit> {
        if (query.isBlank()) return emptyList()
        val active = scopes.filter { list(it).isNotEmpty() }
        if (active.isEmpty()) return emptyList()
        for (k in active) sync(k, s, embedder)
        val q = embedder.embed(listOf(query)).single()
        return active.flatMap { store.query(it.collection, embedder.id, q, s.retrieveCount, s.scoreThreshold) }
            .sortedByDescending { it.score }.take(s.retrieveCount)
    }

    suspend fun prompt(scopes: List<BankScopeKey>, history: List<ChatMessage>, s: DataBankSettings, embedder: Embedder, queryMessages: Int = 2): ExtensionPrompt? {
        if (!s.enabled) return null
        val query = history.filter { !it.isSystem && !it.isHidden && it.text.isNotBlank() }.takeLast(queryMessages.coerceAtLeast(1)).joinToString("\n") { it.text }
        val hits = retrieve(scopes, query, s, embedder)
        if (hits.isEmpty()) return null
        return ExtensionPrompt("databank", s.template.replace("{{text}}", hits.joinToString("\n\n") { it.entry.text }), s.position, s.depth, s.role)
    }
}
