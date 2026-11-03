package app.sillytavern.android.memory

import app.sillytavern.android.data.StoragePaths
import java.io.*

class VectorEntry(val id: String, val text: String, val vector: FloatArray, val meta: String = "")

class VectorHit(val entry: VectorEntry, val score: Double)

/**
 * Collections of embedded texts, one binary file each (`user/vectors/<name>.vec`), searched by brute-force cosine.
 * A collection remembers which embedder produced it; asking with another embedder sees it as empty and the next write replaces it.
 */
class VectorStore(private val dir: File) {
    private class Bucket(var embedderId: String, val entries: LinkedHashMap<String, VectorEntry>)
    private val cache = HashMap<String, Bucket>()

    private fun file(name: String) = File(dir, StoragePaths.sanitizeFileName(name) + ".vec")

    @Synchronized
    private fun load(name: String): Bucket = cache.getOrPut(name) {
        val f = file(name)
        runCatching { if (f.exists()) read(f) else null }.getOrNull() ?: Bucket("", LinkedHashMap())
    }

    /** Ids stored for [name] by [embedderId]. */
    @Synchronized
    fun ids(name: String, embedderId: String): Set<String> =
        load(name).takeIf { it.embedderId == embedderId }?.entries?.keys?.toSet().orEmpty()

    /** Ids stored for [name] whatever embedder produced them. */
    @Synchronized
    fun idsAnyEmbedder(name: String): Set<String> = load(name).entries.keys.toSet()

    @Synchronized
    fun size(name: String): Int = load(name).entries.size

    @Synchronized
    fun insert(name: String, embedderId: String, entries: List<VectorEntry>) {
        if (entries.isEmpty()) return
        val c = load(name)
        if (c.embedderId != embedderId) { c.entries.clear(); c.embedderId = embedderId }
        for (e in entries) c.entries[e.id] = e
        write(name, c)
    }

    @Synchronized
    fun remove(name: String, ids: Collection<String>) {
        val c = load(name)
        val removed = ids.count { c.entries.remove(it) != null }
        if (removed > 0) write(name, c)
    }

    @Synchronized
    fun drop(name: String) { cache.remove(name); file(name).delete() }

    @Synchronized
    fun query(name: String, embedderId: String, vector: FloatArray, topK: Int, threshold: Double): List<VectorHit> {
        val c = load(name).takeIf { it.embedderId == embedderId } ?: return emptyList()
        return c.entries.values.map { VectorHit(it, Vectors.dot(vector, it.vector)) }
            .filter { it.score >= threshold }.sortedByDescending { it.score }.take(topK)
    }

    private fun write(name: String, c: Bucket) {
        dir.mkdirs()
        val f = file(name)
        val tmp = File(dir, f.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
            o.writeInt(MAGIC); o.writeUTF(c.embedderId); o.writeInt(c.entries.size)
            for (e in c.entries.values) {
                writeStr(o, e.id); writeStr(o, e.text); writeStr(o, e.meta)
                o.writeInt(e.vector.size); for (x in e.vector) o.writeFloat(x)
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun read(f: File): Bucket = DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
        require(i.readInt() == MAGIC)
        val c = Bucket(i.readUTF(), LinkedHashMap())
        repeat(i.readInt()) {
            val id = readStr(i); val text = readStr(i); val meta = readStr(i)
            val v = FloatArray(i.readInt()) { i.readFloat() }
            c.entries[id] = VectorEntry(id, text, v, meta)
        }
        c
    }

    private fun writeStr(o: DataOutputStream, s: String) { val b = s.toByteArray(); o.writeInt(b.size); o.write(b) }
    private fun readStr(i: DataInputStream): String { val b = ByteArray(i.readInt()); i.readFully(b); return String(b) }

    private companion object { const val MAGIC = 0x53545653 }
}
