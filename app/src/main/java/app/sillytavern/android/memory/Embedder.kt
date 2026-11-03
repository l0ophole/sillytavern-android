package app.sillytavern.android.memory

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.Backends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.sqrt

/** Turns texts into unit-length vectors. Vectors from different embedders (different [id]) are never compared. */
interface Embedder {
    val id: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

object Vectors {
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val n = sqrt(sum)
        return if (n == 0.0) v else FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    /** Dot product; equals the cosine for normalized vectors. */
    fun dot(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

/**
 * Built-in embedder with no model: words, word pairs and character trigrams are hashed into a fixed number
 * of buckets. It finds lexical overlap (names, places, objects), not meaning, but works offline and instantly.
 */
class HashingEmbedder(private val dim: Int = 512) : Embedder {
    override val id = "hash-$dim"

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::vector)

    fun vector(text: String): FloatArray {
        val v = FloatArray(dim)
        val words = tokens(text)
        fun add(feature: String, weight: Float) {
            val h = fnv(feature)
            val idx = (h and 0x7fffffff).toInt() % dim
            v[idx] += if ((h shr 40) and 1L == 0L) weight else -weight
        }
        for ((i, w) in words.withIndex()) {
            add("w:$w", 1f)
            if (i + 1 < words.size) add("b:$w ${words[i + 1]}", 0.5f)
            if (w.length > 4) for (j in 0..w.length - 3) add("t:${w.substring(j, j + 3)}", 0.25f)
        }
        return Vectors.normalize(v)
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }

    private fun fnv(s: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (c in s) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return h
    }

    private companion object {
        val STOP = setOf("the", "and", "you", "for", "that", "with", "this", "was", "are", "but", "not", "have", "has", "had", "his", "her", "she", "him", "its", "they", "them", "what", "when", "where", "who", "how", "from", "will", "would", "can", "could", "just", "like", "your", "then", "than", "into", "out", "all", "got", "get", "did", "does")
    }
}

/** OpenAI-compatible `/embeddings` or Ollama's native `/api/embed`. */
class RemoteEmbedder(
    private val settings: EmbedderSettings,
    private val apiKey: String?,
    private val client: OkHttpClient = Backends.defaultClient,
) : Embedder {
    override val id = "${settings.kind.name.lowercase()}:${settings.model}"

    fun request(inputs: List<String>): Request {
        val base = settings.url.trimEnd('/')
        val ollama = settings.kind == EmbedderKind.OLLAMA
        val body = buildJsonObject {
            put("model", settings.model)
            put("input", JsonArray(inputs.map(::JsonPrimitive)))
        }.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder().url(if (ollama) "$base/api/embed" else "$base/embeddings")
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .post(body).build()
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val out = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(BATCH)) out += post(batch)
        return out
    }

    private suspend fun post(batch: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request(batch)).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw BackendException("Embeddings HTTP ${r.code}: ${text.take(300)}")
                parse(text, batch.size)
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }
    }

    companion object {
        const val BATCH = 32

        /** Reads either response shape: `{data:[{index, embedding}]}` or `{embeddings:[[...]]}`. */
        fun parse(raw: String, expected: Int): List<FloatArray> {
            val o = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: throw BackendException("Unreadable embeddings response")
            fun floats(e: JsonElement) = (e as JsonArray).map { it.jsonPrimitive.float }.toFloatArray()
            val vectors = try {
                when {
                    o["data"] is JsonArray -> (o["data"] as JsonArray).map { it.jsonObject }
                        .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: 0 }.map { floats(it["embedding"]!!) }
                    o["embeddings"] is JsonArray -> (o["embeddings"] as JsonArray).map(::floats)
                    else -> throw BackendException("No embeddings in response")
                }
            } catch (e: BackendException) { throw e } catch (e: Exception) { throw BackendException("Unreadable embeddings response") }
            if (vectors.size != expected) throw BackendException("Expected $expected embeddings, got ${vectors.size}")
            return vectors.map(Vectors::normalize)
        }
    }
}
