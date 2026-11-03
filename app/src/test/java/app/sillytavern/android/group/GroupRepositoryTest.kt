package app.sillytavern.android.group

import app.sillytavern.android.chat.*
import app.sillytavern.android.data.StoragePaths
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroupRepositoryTest {
    private val paths = StoragePaths(Files.createTempDirectory("grp").toFile()).ensure()
    private val repo = GroupRepository(paths)

    @Test fun groupJsonRoundTripKeepsUnknownKeys() {
        val o = Json.parseToJsonElement("""{"id":"1","name":"G","members":["A.png"],"chat_id":"c","chats":["c"],"hideMutedSprites":true,"x":{"y":1}}""").jsonObject
        val back = Group.fromJson(Group.fromJson(o).toJson())
        assertEquals(JsonPrimitive(true), back.toJson()["hideMutedSprites"])
        assertEquals("A.png", back.members.single())
        assertTrue(back.toJson()["x"] is JsonObject)
    }

    @Test fun fromJsonDefaultsMissingFields() {
        val g = Group.fromJson(Json.parseToJsonElement("""{"id":"7"}""").jsonObject)
        assertEquals("7", g.chatId); assertEquals(listOf("7"), g.chats)
        assertEquals(ActivationStrategy.NATURAL, g.activationStrategy); assertEquals(5, g.autoModeDelay)
        assertTrue(g.members.isEmpty())
    }

    @Test fun createWritesFileAndFirstChat() {
        val g = repo.create("Crew", listOf("A.png", "B.png", "A.png"))
        assertEquals(listOf("A.png", "B.png"), g.members)
        assertEquals(g, repo.get(g.id)!!.copy(raw = g.raw))
        assertEquals(listOf(g.chatId), g.chats)
        assertEquals(1, repo.list().size)
    }

    @Test fun deleteRemovesGroupAndChats() {
        val g = repo.create("Crew", listOf("A.png"))
        val chats = ChatRepository(paths)
        chats.createGroupChat(repo.chatFile(g.chatId), listOf(ChatMessage.user("u", "hi")))
        assertTrue(repo.chatFile(g.chatId).exists())
        repo.delete(g.id)
        assertNull(repo.get(g.id)); assertFalse(repo.chatFile(g.chatId).exists())
    }

    @Test fun listSkipsCorruptFile() {
        repo.create("Ok", emptyList())
        java.io.File(paths.groups, "bad.json").writeText("{not json")
        assertEquals(listOf("Ok"), repo.list().map { it.name })
    }

    @Test fun groupChatFileCannotEscapeDir() {
        val f = repo.chatFile("../../x")
        assertEquals(paths.groupChats.canonicalPath, f.parentFile.canonicalPath)
    }

    @Test fun originalAvatarRoundTripsThroughMessageJson() {
        val m = ChatMessage.assistant("Alice", "hi").withOriginalAvatar("Alice.png")
        assertEquals("Alice.png", ChatMessage.fromJson(m.toJson()).originalAvatar)
        assertNull(ChatMessage.assistant("A", "x").originalAvatar)
    }

    @Test fun groupChatLoadsAsPlainChat() {
        val chats = ChatRepository(paths)
        val f = repo.chatFile("c1")
        chats.createGroupChat(f, listOf(ChatMessage.assistant("Alice", "hi").withOriginalAvatar("Alice.png")))
        val loaded = chats.load(f)
        assertEquals(1, loaded.messages.size); assertEquals("Alice.png", loaded.messages[0].originalAvatar)
    }

    @Test fun addChatMakesItCurrent() {
        val g = repo.create("Crew", emptyList())
        val g2 = repo.addChat(g.copy(chats = listOf(g.chatId)))
        assertEquals(2, g2.chats.size); assertEquals(g2.chats.last(), g2.chatId)
    }
}
