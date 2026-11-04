package app.sillytavern.android.polish

import app.sillytavern.android.card.*
import app.sillytavern.android.chat.*
import app.sillytavern.android.data.StoragePaths
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PolishTest {
    private class FakeSink : GenerationSink {
        val log = ArrayList<String>()
        override fun running(characterName: String) { log += "run:$characterName" }
        override fun idle() { log += "idle" }
        override fun replyReady(characterName: String, preview: String, characterFile: String?, groupId: String?) { log += "reply:$characterName:$preview:$characterFile" }
    }

    private fun paths() = StoragePaths(File(Files.createTempDirectory("pl").toFile(), "data")).ensure()
    private val card = StoredCharacter("Ann", CharacterCard(name = "Ann"), File("Ann.png"))
    private fun msg(user: Boolean, text: String) = ChatMessage(if (user) "You" else "Ann", user, swipes = listOf(text))
    private fun state(gen: Boolean, vararg m: ChatMessage, error: String? = null) =
        ChatUiState(character = card, chat = Chat(null, kotlinx.serialization.json.JsonObject(emptyMap()), m.toList()), generating = gen, error = error)

    private fun watcher(sink: FakeSink, fg: Boolean, s: PolishSettings = PolishSettings()) =
        GenerationWatcher(kotlinx.coroutines.flow.emptyFlow(), { s }, { fg }, sink, previewLength = 10)

    @Test fun backgroundReplyNotifiesAndStopsService() {
        val sink = FakeSink(); val w = watcher(sink, fg = false)
        w.onState(state(true, msg(true, "hi")))
        w.onState(state(false, msg(true, "hi"), msg(false, "Hello   there, friend!")))
        assertEquals(listOf("run:Ann", "idle", "reply:Ann:Hello ther…:Ann"), sink.log)
    }

    @Test fun foregroundReplyDoesNotNotify() {
        val sink = FakeSink(); val w = watcher(sink, fg = true)
        w.onState(state(true, msg(true, "hi")))
        w.onState(state(false, msg(true, "hi"), msg(false, "ok")))
        assertEquals(listOf("run:Ann", "idle"), sink.log)
    }

    @Test fun failureOrNoNewMessageDoesNotNotify() {
        val sink = FakeSink(); val w = watcher(sink, fg = false)
        w.onState(state(true, msg(true, "hi")))
        w.onState(state(false, msg(true, "hi"), error = "boom"))
        w.onState(state(true, msg(true, "hi")))
        w.onState(state(false, msg(true, "hi")))
        assertEquals(listOf("run:Ann", "idle", "run:Ann", "idle"), sink.log)
    }

    @Test fun settingsSwitchOffServiceAndNotification() {
        val sink = FakeSink(); val w = watcher(sink, fg = false, PolishSettings(keepAlive = false, notifyReplies = false))
        w.onState(state(true, msg(true, "hi")))
        w.onState(state(false, msg(true, "hi"), msg(false, "ok")))
        assertEquals(listOf("idle"), sink.log)
    }

    @Test fun powerPolicy() {
        assertTrue(PowerPolicy.pauseAuto(true, PolishSettings()))
        assertFalse(PowerPolicy.pauseAuto(false, PolishSettings()))
        assertFalse(PowerPolicy.postponeBackup(true, PolishSettings(respectBatterySaver = false)))
    }

    @Test fun recentChatPicksNewestOfExistingCharacters() {
        val p = paths(); val chars = CharacterRepository(p); val chats = ChatRepository(p)
        val a = chars.save(null, CharacterCard(name = "Ann")); val b = chars.save(null, CharacterCard(name = "Bob"))
        val ca = chats.create(a.fileName, "Ann", "You", "Hello from Ann"); val cb = chats.create(b.fileName, "Bob", "You", "Hi from Bob")
        ca.file!!.setLastModified(1_000_000); cb.file!!.setLastModified(2_000_000)
        File(p.chats, "ghost").mkdirs(); File(p.chats, "ghost/g.jsonl").writeText("{}").also { File(p.chats, "ghost/g.jsonl").setLastModified(3_000_000) }
        val r = RecentChatFinder.find(p, chars, chats)!!
        assertEquals("Bob", r.characterName); assertEquals("Hi from Bob", r.lastLine)
        assertNull(RecentChatFinder.find(paths(), CharacterRepository(paths()), chats))
    }

    private fun importer(p: StoragePaths) = ShareImporter(CharacterRepository(p), ChatRepository(p), { "You" })

    @Test fun classifyByContent() {
        val imp = importer(paths())
        val png = CardCodec.toPng(CharacterCard(name = "Zed"), PngChunks.BLANK)
        assertEquals(ShareKind.CARD, imp.classify(png))
        assertEquals(ShareKind.CARD, imp.classify("""{"spec":"chara_card_v2","data":{"name":"Z"}}""".toByteArray()))
        assertEquals(ShareKind.CHAT, imp.classify("""{"user_name":"You","character_name":"Z","chat_metadata":{}}""".toByteArray()))
        assertEquals(ShareKind.UNKNOWN, imp.classify("plain text".toByteArray()))
        assertEquals(ShareKind.UNKNOWN, imp.classify(PngChunks.BLANK))
    }

    @Test fun sharedCardAndChatAreImported() {
        val p = paths(); val imp = importer(p)
        val png = CardCodec.toPng(CharacterCard(name = "Zed"), PngChunks.BLANK)
        val chat = """{"user_name":"You","character_name":"Zed","chat_metadata":{}}""" + "\n" +
            """{"name":"Zed","is_user":false,"send_date":"x","mes":"Hello","swipes":["Hello"],"swipe_id":0}""" + "\n"
        val orphan = chat.replace("Zed", "Nobody")
        val r = imp.import(listOf(SharedItem("z.png", png), SharedItem("c.jsonl", chat.toByteArray()), SharedItem("o.jsonl", orphan.toByteArray()), SharedItem("t.txt", "hi".toByteArray())))
        assertEquals(1, r.characters.size); assertEquals(1, r.chats); assertEquals(2, r.notes.size)
        val files = ChatRepository(p).list(r.characters.single())
        assertEquals(1, files.size)
        assertEquals("Hello", ChatRepository(p).load(files.single()).messages.single().text)
        assertTrue(r.summary().contains("Imported 1 character(s), 1 chat(s)"))
    }
}
