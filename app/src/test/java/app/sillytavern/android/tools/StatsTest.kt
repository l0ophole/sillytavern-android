package app.sillytavern.android.tools

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.chat.ChatRepository
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.prompt.ApproxTokenCounter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class StatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun at(m: ChatMessage, iso: String) = m.copy(sendDate = iso)

    @Test fun countsMessagesWordsAndSwipes() {
        val a = at(ChatMessage.assistant("Ann", "one two three").addSwipe("alt"), "2024-01-02T10:00:00Z")
        val msgs = listOf(at(ChatMessage.user("Me", "hi there"), "2024-01-01T10:00:00Z"), a, ChatMessage.narrator("sys line"))
        val s = Stats.ofMessages(msgs)
        assertEquals(1, s.userMessages); assertEquals(1, s.characterMessages)
        assertEquals(2, s.userWords); assertEquals(1, s.extraSwipes)
        assertEquals(Instant.parse("2024-01-01T10:00:00Z"), s.first)
        assertEquals(Instant.parse("2024-01-02T10:00:00Z"), s.last)
        assertEquals(1, s.chats)
    }

    @Test fun nonIsoDatesAreIgnored() {
        val s = Stats.ofMessages(listOf(at(ChatMessage.user("Me", "x"), "June 3, 2024 4:05pm")))
        assertEquals(1, s.userMessages); assertNull(s.first)
    }

    @Test fun collectsAcrossCharactersAndGroupChats() {
        val paths = StoragePaths(tmp.newFolder()).ensure()
        val chars = CharacterRepository(paths); val chats = ChatRepository(paths)
        val ann = chars.save(null, CharacterCard(name = "Ann Lee"))
        val c1 = chats.create(ann.fileName, "Ann Lee", "Me", "Hello friend")
        chats.save(c1.copy(messages = c1.messages + ChatMessage.user("Me", "hey there ann")))
        chats.create(ann.fileName, "Ann Lee", "Me", null).let { chats.save(it.copy(messages = listOf(ChatMessage.user("Me", "solo"))) ) }
        chats.createGroupChat(File(paths.groupChats, "g.jsonl"), listOf(ChatMessage.user("Me", "group hello")))
        File(paths.chats, "ghost").mkdirs()
        File(paths.chatDir(ann.fileName), "broken.jsonl").writeText("not json\n")

        val r = Stats.collect(paths, chats, chars)
        assertEquals(listOf("Ann Lee", "Group chats"), r.rows.map { it.name })
        val row = r.rows[0].stats
        assertEquals(2, row.chats); assertEquals(2, row.userMessages); assertEquals(1, row.characterMessages)
        assertTrue(r.rows[1].isGroup)
        assertEquals(3, r.total.chats); assertEquals(3, r.total.userMessages)
        assertEquals(2, r.total.avgUserWords) // 3 + 1 + 2 words over 3 messages
    }

    @Test fun cardPartsAndChatTokens() {
        val card = CharacterCard(name = "A", description = "x".repeat(36), firstMessage = "y".repeat(72))
        val parts = TokenTools.cardParts(card, ApproxTokenCounter)
        assertEquals(listOf("Description" to 10, "First message" to 20), parts.map { it.label to it.tokens })
        val msgs = listOf(ChatMessage.user("Me", "a".repeat(36)), ChatMessage.user("Me", "hidden").withHidden(true), ChatMessage.assistant("Ann", "b".repeat(36)))
        assertEquals(ApproxTokenCounter.count("Me: " + "a".repeat(36)) + ApproxTokenCounter.count("Ann: " + "b".repeat(36)), TokenTools.chatTokens(msgs, ApproxTokenCounter))
        assertEquals(listOf(0, 2), TokenTools.largestMessages(msgs, ApproxTokenCounter, 2).map { it.first }.sorted())
        assertEquals(TextCounts(2, 7, 2), TokenTools.count("ab cdef", ApproxTokenCounter))
    }
}
