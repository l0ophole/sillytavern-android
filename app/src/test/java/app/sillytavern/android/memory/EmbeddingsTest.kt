package app.sillytavern.android.memory

import app.sillytavern.android.api.BackendException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HashingEmbedderTest {
    private val e = HashingEmbedder()
    private fun sim(a: String, b: String) = runBlocking { e.embed(listOf(a, b)) }.let { Vectors.dot(it[0], it[1]) }

    @Test fun vectorsAreNormalizedAndDeterministic() = runBlocking {
        val (a, b) = e.embed(listOf("The dragon sleeps in the mountain cave", "The dragon sleeps in the mountain cave"))
        assertEquals(1.0, Vectors.dot(a, a), 1e-5)
        assertArrayEquals(a, b, 0f)
    }

    @Test fun relatedTextsScoreHigherThanUnrelated() {
        val related = sim("We rode to the dragon mountain at dawn", "The dragon sleeps in the mountain cave")
        val unrelated = sim("We rode to the dragon mountain at dawn", "She bakes bread and sells pastries downtown")
        assertTrue("$related vs $unrelated", related > unrelated + 0.1)
    }

    @Test fun emptyTextGivesZeroVector() = runBlocking {
        assertEquals(0.0, Vectors.dot(e.embed(listOf(""))[0], e.embed(listOf("hello world"))[0]), 0.0)
    }
}

class RemoteEmbedderTest {
    private val server = MockWebServer()
    @After fun stop() = server.shutdown()

    @Test fun openAiRequestAndResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"index":1,"embedding":[0,2]},{"index":0,"embedding":[3,0]}]}"""))
        server.start()
        val e = RemoteEmbedder(EmbedderSettings(EmbedderKind.OPENAI, server.url("/v1").toString(), "m"), "key")
        val out = e.embed(listOf("a", "b"))
        val req = server.takeRequest()
        assertEquals("/v1/embeddings", req.path)
        assertEquals("Bearer key", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("m", body["model"]!!.jsonPrimitive.content)
        assertEquals(2, body["input"]!!.jsonArray.size)
        assertArrayEquals(floatArrayOf(1f, 0f), out[0], 1e-6f) // sorted by index and normalized
        assertArrayEquals(floatArrayOf(0f, 1f), out[1], 1e-6f)
    }

    @Test fun ollamaShape() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"embeddings":[[0,5]]}"""))
        server.start()
        val out = RemoteEmbedder(EmbedderSettings(EmbedderKind.OLLAMA, server.url("/").toString().trimEnd('/'), "nomic"), null).embed(listOf("x"))
        val req = server.takeRequest()
        assertEquals("/api/embed", req.path)
        assertNull(req.getHeader("Authorization"))
        assertArrayEquals(floatArrayOf(0f, 1f), out[0], 1e-6f)
    }

    @Test fun batchesLargeInputs() = runBlocking {
        server.start()
        repeat(2) { n ->
            val count = if (n == 0) RemoteEmbedder.BATCH else 3
            server.enqueue(MockResponse().setBody("""{"data":[${(0 until count).joinToString(",") { """{"index":$it,"embedding":[1]}""" }}]}"""))
        }
        val out = RemoteEmbedder(EmbedderSettings(EmbedderKind.OPENAI, server.url("/v1").toString(), "m"), null)
            .embed(List(RemoteEmbedder.BATCH + 3) { "t$it" })
        assertEquals(RemoteEmbedder.BATCH + 3, out.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun errorsBecomeBackendExceptions() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        server.start()
        val e = RemoteEmbedder(EmbedderSettings(EmbedderKind.OPENAI, server.url("/v1").toString(), "m"), null)
        val ex = assertThrows(BackendException::class.java) { runBlocking { e.embed(listOf("a")) } }
        assertTrue(ex.message!!.contains("401"))
        assertThrows(BackendException::class.java) { RemoteEmbedder.parse("""{"data":[{"index":0,"embedding":[1]}]}""", 2) }
        assertThrows(BackendException::class.java) { RemoteEmbedder.parse("not json", 1) }
    }
}

class TextChunkerTest {
    @Test fun shortTextIsOneChunk() = assertEquals(listOf("hi there"), TextChunker.chunk("  hi there \n", 100))

    @Test fun splitsOnSentencesWithinLimit() {
        val text = (1..20).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = TextChunker.chunk(text, 120)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 120 })
        assertTrue(chunks.joinToString(" ").contains("number 20"))
    }

    @Test fun overlapRepeatsTheTail() {
        val text = (1..10).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = TextChunker.chunk(text, 100, 40)
        assertTrue(chunks.size >= 2)
        val lastOfFirst = chunks[0].substringAfterLast("here. ")
        assertTrue(chunks[1].startsWith(lastOfFirst.take(10)))
    }

    @Test fun longWordsAreHardSplit() {
        val chunks = TextChunker.chunk("x".repeat(500), 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(500, chunks.sumOf { it.length })
    }

    @Test fun blankGivesNothing() = assertTrue(TextChunker.chunk("  \n ", 100).isEmpty())
}

class VectorStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun v(vararg x: Float) = Vectors.normalize(floatArrayOf(*x))

    @Test fun insertQueryAndPersist() {
        val dir = tmp.newFolder()
        val s = VectorStore(dir)
        s.insert("chat1", "e", listOf(VectorEntry("a", "apple", v(1f, 0f), "m1"), VectorEntry("b", "banana", v(0f, 1f)), VectorEntry("c", "apricot", v(1f, 0.2f))))
        val hits = s.query("chat1", "e", v(1f, 0f), 2, 0.0)
        assertEquals(listOf("a", "c"), hits.map { it.entry.id })
        assertEquals("m1", hits[0].entry.meta)
        // a new store instance reads the file back
        val again = VectorStore(dir)
        assertEquals(3, again.size("chat1"))
        assertEquals("banana", again.query("chat1", "e", v(0f, 1f), 1, 0.5).single().entry.text)
    }

    @Test fun thresholdFiltersAndTopKLimits() {
        val s = VectorStore(tmp.newFolder())
        s.insert("c", "e", listOf(VectorEntry("a", "a", v(1f, 0f)), VectorEntry("b", "b", v(0f, 1f))))
        assertEquals(1, s.query("c", "e", v(1f, 0f), 5, 0.5).size)
        assertEquals(1, s.query("c", "e", v(1f, 1f), 1, 0.0).size)
    }

    @Test fun otherEmbedderSeesNothingAndReplacesOnWrite() {
        val s = VectorStore(tmp.newFolder())
        s.insert("c", "e1", listOf(VectorEntry("a", "a", v(1f))))
        assertTrue(s.ids("c", "e2").isEmpty())
        assertTrue(s.query("c", "e2", v(1f), 5, 0.0).isEmpty())
        s.insert("c", "e2", listOf(VectorEntry("z", "z", v(1f))))
        assertEquals(setOf("z"), s.ids("c", "e2"))
        assertTrue(s.ids("c", "e1").isEmpty())
    }

    @Test fun removeAndDropAndOverwrite() {
        val dir = tmp.newFolder()
        val s = VectorStore(dir)
        s.insert("c", "e", listOf(VectorEntry("a", "old", v(1f)), VectorEntry("b", "b", v(1f))))
        s.insert("c", "e", listOf(VectorEntry("a", "new", v(1f))))
        assertEquals("new", s.query("c", "e", v(1f), 5, 0.0).first { it.entry.id == "a" }.entry.text)
        s.remove("c", listOf("b"))
        assertEquals(setOf("a"), VectorStore(dir).ids("c", "e"))
        s.drop("c")
        assertEquals(0, VectorStore(dir).size("c"))
    }

    @Test fun corruptFileIsTreatedAsEmpty() {
        val dir = tmp.newFolder()
        java.io.File(dir, "bad.vec").writeText("garbage")
        assertEquals(0, VectorStore(dir).size("bad"))
    }

    @Test fun collectionNamesAreSanitized() {
        val dir = tmp.newFolder()
        VectorStore(dir).insert("../evil/name", "e", listOf(VectorEntry("a", "a", v(1f))))
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir })
        assertEquals(1, dir.listFiles()!!.size)
    }
}
