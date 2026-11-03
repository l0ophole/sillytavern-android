package app.sillytavern.android.chat

import app.sillytavern.android.data.SummarySettings
import org.junit.Assert.*
import org.junit.Test

class SummaryTest {
    private fun msgs(n: Int) = List(n) { if (it % 2 == 0) ChatMessage.user("Me", "u$it") else ChatMessage.assistant("A", "a$it") }

    @Test fun latestIsNewestWithMemory() {
        val l = msgs(6).toMutableList()
        l[1] = Summary.withMemory(l[1], "old"); l[3] = Summary.withMemory(l[3], "new")
        assertEquals(3 to "new", Summary.latest(l))
        assertNull(Summary.latest(msgs(3)))
    }

    @Test fun regeneratingAnEarlierMessageDoesNotSeeALaterSummary() {
        val l = msgs(6).toMutableList().also { it[4] = Summary.withMemory(it[4], "later") }
        assertNull(Summary.latest(l.take(3)))
    }

    @Test fun withMemoryBlankRemoves() {
        val m = Summary.withMemory(Summary.withMemory(ChatMessage.user("Me", "x"), "s"), "")
        assertFalse(m.extra.containsKey("memory"))
    }

    @Test fun formatUsesTemplate() {
        assertEquals("[Summary: abc]", Summary.format(" abc ", Summary.DEFAULT_TEMPLATE))
        assertEquals("Summary: abc", Summary.format("abc", ""))
        assertEquals("", Summary.format("  ", Summary.DEFAULT_TEMPLATE))
    }

    @Test fun promptReplacesWords() {
        assertTrue(Summary.prompt(SummarySettings(promptWords = 120)).contains("120 words"))
    }

    @Test fun extensionPromptCarriesSettings() {
        val l = msgs(3).toMutableList().also { it[1] = Summary.withMemory(it[1], "S") }
        val e = Summary.extensionPrompt(l, SummarySettings(position = 1, depth = 5, role = 2))!!
        assertEquals(listOf("[Summary: S]", 1, 5, 2), listOf(e.content, e.position, e.depth, e.role))
        assertNull(Summary.extensionPrompt(msgs(3), SummarySettings()))
    }

    @Test fun dueRules() {
        val s = SummarySettings(auto = true, interval = 4)
        assertFalse(Summary.due(msgs(3), s)); assertTrue(Summary.due(msgs(4), s))
        assertFalse(Summary.due(msgs(10), s.copy(auto = false)))
        assertFalse(Summary.due(msgs(10), s.copy(frozen = true)))
        assertFalse(Summary.due(msgs(10), s.copy(interval = 0)))
        val l = msgs(8).toMutableList().also { it[5] = Summary.withMemory(it[5], "x") }
        assertFalse(Summary.due(l, s)) // only 2 lines after the summary
        assertTrue(Summary.due(l + msgs(2), s))
    }

    @Test fun systemLinesDoNotCount() {
        val l = msgs(2) + List(5) { ChatMessage.narrator("sys$it") }
        assertFalse(Summary.due(l, SummarySettings(auto = true, interval = 4)))
    }
}
