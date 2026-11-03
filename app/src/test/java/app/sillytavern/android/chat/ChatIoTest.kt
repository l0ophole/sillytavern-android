package app.sillytavern.android.chat

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.group.GroupRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatIoTest : ChatFeaturesTestBase() {
    private val repo = ChatRepository(StoragePathsHolder.paths)
    private fun imp(text: String) = ChatIo.import(text, "Me", "Ann", repo)

    @Test fun oobaPairsBecomeUserAndCharMessages() {
        val chats = imp("""{"data_visible":[["hi","hello"],["","only char"],["only user",""]]}""").getOrThrow()
        val m = chats.single().messages
        assertEquals(listOf("Me:hi", "Ann:hello", "Ann:only char", "Me:only user"), m.map { it.name + ":" + it.text })
        assertEquals(listOf(true, false, false, true), m.map { it.isUser })
        assertEquals("unused", chats.single().header["user_name"].toString().trim('"'))
    }

    @Test fun agnaiUsesUserIdToTellSides() {
        val m = imp("""{"messages":[{"userId":"u1","msg":"yo"},{"msg":"hey"}]}""").getOrThrow().single().messages
        assertEquals(listOf("Me:yo", "Ann:hey"), m.map { it.name + ":" + it.text })
    }

    @Test fun caiToolsGivesOneChatPerHistory() {
        val txt = """{"histories":{"histories":[{"msgs":[{"src":{"is_human":true},"text":"a"},{"src":{"is_human":false},"text":"b"}]},{"msgs":[{"src":{"is_human":false},"text":"c"}]}]}}"""
        val chats = imp(txt).getOrThrow()
        assertEquals(2, chats.size)
        assertEquals(listOf("a", "b"), chats[0].messages.map { it.text }); assertEquals(listOf("c"), chats[1].messages.map { it.text })
    }

    @Test fun koboldLiteUsesSavedNames() {
        val txt = """{"savedsettings":{"chatname":"Kim","chatopponent":"Bot||${'$'}||x"},"prompt":"{{[OUTPUT]}} Welcome","actions":["{{[INPUT]}} hi","{{[OUTPUT]}} there"]}"""
        val m = imp(txt).getOrThrow().single().messages
        assertEquals(listOf("Bot:Welcome", "Kim:hi", "Bot:there"), m.map { it.name + ":" + it.text })
    }

    @Test fun stJsonlRoundTripsThroughImport() {
        val src = Chat(null, kotlinx.serialization.json.buildJsonObject {
            put("user_name", "Me"); put("character_name", "Ann"); put("chat_metadata", kotlinx.serialization.json.buildJsonObject { put("note_prompt", "n") })
        }, listOf(ChatMessage.assistant("Ann", "Hello").addSwipe("Hi"), ChatMessage.user("Me", "yo")))
        val back = imp(repo.serialize(src)).getOrThrow().single()
        assertEquals(listOf("Hi", "yo"), back.messages.map { it.text })
        assertEquals(2, back.messages[0].swipes.size)
        assertEquals("n", ChatMeta.string(ChatMeta.of(back), "note_prompt"))
    }

    @Test fun chubSwipeObjectsAreFlattened() {
        val txt = """{"user_name":"Me","character_name":"Ann"}
{"name":"Ann","is_user":false,"mes":"a","swipes":[{"message":"a"},{"message":"b"}],"swipe_id":1}"""
        val m = imp(txt).getOrThrow().single().messages.single()
        assertEquals(listOf("a", "b"), m.swipes); assertEquals("b", m.text)
    }

    @Test fun garbageIsRejectedWithoutThrowing() {
        assertTrue(imp("not json at all").isFailure)
        assertTrue(imp("").isFailure)
        assertTrue(imp("""{"foo":1}""").isFailure)
        assertTrue(imp("""{"user_name":"Me"}""").isFailure) // header only, no messages
    }

    @Test fun exportTextSkipsSystemAndUsesDisplayText() {
        val m = listOf(ChatMessage.user("Me", "hi\r\nthere"), ChatMessage.narrator("secret"), ChatMessage.assistant("Ann", "ok").withExtra("display_text", kotlinx.serialization.json.JsonPrimitive("OK!")))
        assertEquals("Me: hi\nthere\n\nAnn: OK!\n\n", ChatIo.exportText(m))
    }

    @Test fun controllerImportCreatesFileAndExportsBack() = runTest {
        val c = controller(); c.open(ann)
        val f = c.importChat("""{"data_visible":[["q","a"]]}""").getOrThrow()
        assertTrue(f.name.endsWith("imported.jsonl")); assertEquals(paths.chatDir("Ann"), f.parentFile)
        c.openChatFile(f)
        assertEquals(listOf("q", "a"), c.state.value.chat!!.messages.map { it.text })
        assertEquals("Me: q\n\nAnn: a\n\n".replace("Me", cfg.activePersona.name), c.exportChat(f, true))
        assertTrue(c.exportChat(f, false).lines().first().contains("user_name"))
        assertTrue(c.importChat("junk").isFailure)
    }

    @Test fun groupImportRegistersChat() = runTest {
        val groups = GroupRepository(paths)
        val g = groups.create("Crew", listOf("Ann.png"))
        val c = app.sillytavern.android.chat.ChatController(ChatRepository(paths), { backend }, { cfg }, { "key" }, this, groups = groups, characterLookup = { ann.takeIf { _ -> it == "Ann" } })
        c.openGroup(g)
        val f = c.importChat("""{"messages":[{"msg":"x"}]}""").getOrThrow()
        assertTrue(f.nameWithoutExtension in groups.get(g.id)!!.chats)
        assertEquals(paths.groupChats, f.parentFile)
    }
}

/** A standalone paths instance for pure-IO tests that do not need a controller. */
private object StoragePathsHolder {
    val paths = app.sillytavern.android.data.StoragePaths(Files.createTempDirectory("io").toFile()).ensure()
}
