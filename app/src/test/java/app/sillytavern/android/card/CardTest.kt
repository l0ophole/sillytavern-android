package app.sillytavern.android.card

import app.sillytavern.android.data.StoragePaths
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CardTest {
    private val v1 = """{"name":"Ann","description":"d","personality":"p","scenario":"s","first_mes":"Hi","mes_example":"ex"}"""
    private val v2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Bo","description":"dd","first_mes":"Yo",
        "alternate_greetings":["A","B"],"tags":["t"],"character_book":{"entries":[]},"extensions":{"x":1}}}"""

    @Test fun parsesV1() = assertEquals("Hi", CardCodec.parse(v1).firstMessage)

    @Test fun parsesV2AndGreetings() {
        val c = CardCodec.parse(v2)
        assertEquals(listOf("Yo", "A", "B"), c.greetings)
        assertEquals(listOf("t"), c.tags)
    }

    @Test fun rejectsNonCards() {
        assertThrows(Exception::class.java) { CardCodec.parse("""{"foo":1}""") }
    }

    @Test fun pngRoundTripPreservesUnknownFields() {
        val png = CardCodec.toPng(CardCodec.parse(v2), PngChunks.BLANK)
        assertTrue(PngChunks.isPng(png))
        val back = CardCodec.fromBytes(png)
        assertEquals("Bo", back.name)
        assertNotNull(back.raw["character_book"])
        assertEquals(1, back.raw["extensions"]!!.jsonObject["x"]!!.jsonPrimitive.int)
    }

    @Test fun rewritingDoesNotDuplicateTextChunks() {
        val once = CardCodec.toPng(CardCodec.parse(v1), PngChunks.BLANK)
        val twice = CardCodec.toPng(CardCodec.fromBytes(once), once)
        assertEquals(2, PngChunks.read(twice).count { it.type == "tEXt" })
    }

    @Test fun ccv3PreferredOverChara() {
        val enc = java.util.Base64.getEncoder()
        val png = PngChunks.withText(PngChunks.BLANK, mapOf(
            "chara" to enc.encodeToString(v1.toByteArray()),
            "ccv3" to enc.encodeToString(v2.toByteArray())))
        assertEquals("Bo", CardCodec.fromBytes(png).name)
    }

    @Test fun repositoryImportListEditDelete() {
        val repo = CharacterRepository(StoragePaths(Files.createTempDirectory("c").toFile()).ensure())
        val a = repo.import(v1.toByteArray())
        val b = repo.import(v1.toByteArray())
        assertNotEquals(a.fileName, b.fileName)
        assertEquals(2, repo.list().size)
        repo.save(a.fileName, a.card.copy(description = "new"))
        assertEquals("new", repo.get(a.fileName)!!.card.description)
        assertTrue(repo.exportJson(a.fileName).contains("chara_card_v2"))
        repo.delete(a.fileName)
        assertEquals(1, repo.list().size)
    }
}
