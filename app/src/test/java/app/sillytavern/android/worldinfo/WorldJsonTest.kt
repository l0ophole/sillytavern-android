package app.sillytavern.android.worldinfo

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorldJsonTest {
    private fun fixture() = javaClass.getResource("/worldinfo/Eldoria.json")!!.readText()

    @Test fun eldoriaDecodes() {
        val raw = Json.parseToJsonElement(fixture()).jsonObject["entries"]!!.jsonObject
        val book = WorldJson.decode("Eldoria", fixture())
        assertEquals(raw.size, book.entries.size)
        assertTrue(book.entries.first().key.isNotEmpty())
        assertTrue(book.entries.first().content.isNotBlank())
    }

    @Test fun unknownKeysSurviveRoundTrip() {
        val text = """{"entries":{"0":{"uid":0,"key":["a"],"content":"x","mystery":{"k":1}}},"topExtra":5}"""
        val out = Json.parseToJsonElement(WorldJson.encode(WorldJson.decode("b", text))).jsonObject
        assertEquals(JsonPrimitive(5), out["topExtra"])
        assertEquals(1, out["entries"]!!.jsonObject["0"]!!.jsonObject["mystery"]!!.jsonObject["k"]!!.jsonPrimitive.int)
    }

    @Test fun fixtureKeysSurviveRoundTrip() {
        val orig = Json.parseToJsonElement(fixture()).jsonObject["entries"]!!.jsonObject
        val out = Json.parseToJsonElement(WorldJson.encode(WorldJson.decode("E", fixture()))).jsonObject["entries"]!!.jsonObject
        for ((uid, e) in orig) for ((k, v) in e.jsonObject) {
            if (v is JsonNull || k == "delayUntilRecursion") continue // bool->number is intentional
            assertEquals("entry $uid key $k", v, out[uid]!!.jsonObject[k])
        }
    }

    @Test fun entriesArrayAccepted() {
        val b = WorldJson.decode("b", """{"entries":[{"uid":3,"key":["a"],"content":"x"}]}""")
        assertEquals(3, b.entries.single().uid)
    }

    @Test fun delayUntilRecursionBoolAndNumber() {
        fun d(v: String) = WorldJson.decode("b", """{"entries":{"0":{"delayUntilRecursion":$v}}}""").entries.single().delayUntilRecursion
        assertEquals(1, d("true")); assertEquals(0, d("false")); assertEquals(3, d("3"))
    }

    @Test fun missingFieldsGetDefaults() {
        val e = WorldJson.decode("b", """{"entries":{"0":{"key":["a"],"content":"x"}}}""").entries.single()
        assertEquals(100, e.order); assertEquals(4, e.depth); assertEquals(100, e.probability)
        assertTrue(e.selective); assertEquals(0, e.position)
    }

    @Test fun characterBookConverts() {
        val cb = Json.parseToJsonElement("""{"entries":[{"keys":["a"],"content":"c","insertion_order":7,"enabled":false,"position":"after_char"}]}""").jsonObject
        val e = WorldJson.fromCharacterBook("n", cb).entries.single()
        assertEquals(listOf("a"), e.key); assertEquals(7, e.order); assertTrue(e.disable); assertEquals(1, e.position)
    }

    @Test fun characterBookRoundTrip() {
        val cb = Json.parseToJsonElement("""{"entries":[{"keys":["a"],"content":"c","insertion_order":7,"enabled":false}]}""").jsonObject
        val back = WorldJson.toCharacterBook(WorldJson.fromCharacterBook("n", cb))
        val e = back["entries"]!!.jsonArray.single().jsonObject
        assertEquals("a", e["keys"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("c", e["content"]!!.jsonPrimitive.content)
        assertEquals(7, e["insertion_order"]!!.jsonPrimitive.int)
        assertFalse(e["enabled"]!!.jsonPrimitive.boolean)
    }
}
