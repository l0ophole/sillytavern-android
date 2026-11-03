package app.sillytavern.android.chat

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatBranchingTest {
    private val msgs = listOf(ChatMessage.user("Me", "a"), ChatMessage.assistant("A", "b").addSwipe("b2"), ChatMessage.user("Me", "c"))

    @Test fun namesStripExistingSuffixes() {
        assertEquals("Ann - 2026 - Branch #3", ChatBranching.branchName("Ann - 2026 - Branch #1", 3))
        assertEquals("Ann - Branch #1", ChatBranching.branchName("Branch #4 - Ann", 1))
        assertEquals("Ann - Checkpoint #2", ChatBranching.checkpointName("Ann - Checkpoint #7", 2))
        assertEquals("Ann - Checkpoint #1", ChatBranching.checkpointName("Checkpoint #7 - Ann", 1))
    }

    @Test fun uniqueNameSkipsExisting() {
        val taken = setOf("Ann - Branch #1", "Ann - Branch #2")
        assertEquals("Ann - Branch #3", ChatBranching.uniqueName("Ann", ChatBranching::branchName) { it in taken })
        assertNull(ChatBranching.uniqueName("Ann", ChatBranching::branchName) { true })
    }

    @Test fun snapshotKeepsThroughIndex() {
        assertEquals(listOf("a", "b2"), ChatBranching.snapshot(msgs, 1)!!.map { it.text })
        assertNull(ChatBranching.snapshot(msgs, 3)); assertNull(ChatBranching.snapshot(msgs, -1))
    }

    @Test fun snapshotSelectsSwipe() {
        assertEquals("b", ChatBranching.snapshot(msgs, 1, swipeId = 0)!!.last().text)
        assertNull(ChatBranching.snapshot(msgs, 1, swipeId = 5))
    }

    @Test fun childHeaderKeepsMetadataAndSetsMain() {
        val h = buildJsonObject { put("user_name", "Me"); put("chat_metadata", buildJsonObject { put("note_prompt", "n") }) }
        val c = ChatBranching.childHeader(h, "Parent")
        assertEquals("Me", c["user_name"]!!.jsonPrimitive.content)
        val meta = c["chat_metadata"]!!.jsonObject
        assertEquals("n", meta["note_prompt"]!!.jsonPrimitive.content)
        assertEquals("Parent", meta["main_chat"]!!.jsonPrimitive.content)
    }

    @Test fun linksAreRecordedOnMessages() {
        val m = ChatBranching.withBranch(ChatBranching.withBranch(msgs[0], "x"), "y")
        assertEquals(listOf("x", "y"), m.branches)
        val c = ChatBranching.withCheckpoint(ChatBranching.withCheckpoint(msgs[0], "old"), "new")
        assertEquals("new", c.bookmarkLink)
        assertEquals(listOf(0 to "new"), ChatBranching.checkpoints(listOf(c, msgs[1])))
    }
}
