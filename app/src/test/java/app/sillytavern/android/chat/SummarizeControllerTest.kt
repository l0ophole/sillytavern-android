package app.sillytavern.android.chat

import app.sillytavern.android.command.CommandExecutor
import app.sillytavern.android.data.SummarySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SummarizeControllerTest : ChatFeaturesTestBase() {
    @Test fun summarizeStoresOnNewestMessageAndFeedsNextPrompt() = runTest {
        val c = controller(); c.open(ann)
        c.send("one"); advanceUntilIdle()
        reply = "They said hello."
        val out = c.summarizeNow()
        assertEquals("They said hello.", out)
        val last = c.state.value.chat!!.messages.last()
        assertEquals("They said hello.", (last.extra["memory"] as kotlinx.serialization.json.JsonPrimitive).content)
        // the summarize request carried the prompt as the final user message
        assertTrue(requests.last().messages.last().content.contains("Summarize the most important facts"))
        reply = "next"
        c.send("two"); advanceUntilIdle()
        assertTrue(lastContents().contains("[Summary: They said hello.]"))
    }

    @Test fun wordsMacroIsFilled() = runTest {
        cfg = cfg.copy(summary = SummarySettings(promptWords = 42))
        val c = controller(); c.open(ann); c.send("x"); advanceUntilIdle()
        c.summarizeNow()
        assertTrue(requests.last().messages.last().content.contains("42 words"))
    }

    @Test fun emptyModelOutputIsAnErrorAndStoresNothing() = runTest {
        val c = controller(); c.open(ann); c.send("x"); advanceUntilIdle()
        reply = "  "
        assertThrows(Exception::class.java) { runBlocking { c.summarizeNow() } }
        assertNull(Summary.latest(c.state.value.chat!!.messages))
    }

    @Test fun backgroundSummarizeReportsErrorsInState() = runTest {
        cfg = cfg.copy(connections = emptyList())
        val c = controller(); c.open(ann)
        c.summarize(); advanceUntilIdle()
        assertTrue(c.state.value.error!!.contains("connection"))
        assertFalse(c.state.value.summarizing)
    }

    @Test fun autoSummaryRunsAfterReplyWhenIntervalReached() = runTest {
        cfg = cfg.copy(summary = SummarySettings(auto = true, interval = 3))
        val c = controller(); c.open(ann)
        reply = "answer"
        c.send("a"); advanceUntilIdle() // Hello, a, answer = 3 lines -> due
        assertEquals(2, requests.size) // the reply and the automatic summary
        assertEquals("answer", c.summaryText()) // fake backend answers both with the same text
        assertFalse(c.state.value.summarizing)
    }

    @Test fun slashSummarizeAndGetSummary() = runTest {
        val c = controller(); c.open(ann); c.send("x"); advanceUntilIdle()
        reply = "S1"
        val ex = CommandExecutor(c.commandHost())
        assertEquals("S1", ex.runText("/summarize").pipe)
        assertEquals("S1", ex.runText("/getsummary").pipe)
        reply = "Of text"
        assertEquals("Of text", ex.runText("/summarize some long text").pipe)
        assertEquals("S1", c.summaryText()) // text mode does not touch the chat summary
    }

    @Test fun noAutoSummaryWhenFrozenOrOff() = runTest {
        cfg = cfg.copy(summary = SummarySettings(auto = true, interval = 1, frozen = true))
        val c = controller(); c.open(ann); c.send("a"); advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals("", c.summaryText())
    }

    private fun <T> runBlocking(f: suspend () -> T): T = kotlinx.coroutines.runBlocking { f() }
}
