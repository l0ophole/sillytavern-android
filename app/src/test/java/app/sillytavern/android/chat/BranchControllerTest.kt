package app.sillytavern.android.chat

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.command.CommandExecutor
import app.sillytavern.android.group.GroupRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BranchControllerTest : ChatFeaturesTestBase() {
    private fun TestScope.talk(c: ChatController) { c.open(ann); c.send("one"); advanceUntilIdle(); c.send("two"); advanceUntilIdle() }

    @Test fun branchCopiesUpToMessageAndOpensIt() = runTest {
        val c = controller(); talk(c)
        val parent = c.currentChatName()
        val name = c.createBranch(2)!!
        assertEquals("$parent - Branch #1", name)
        assertEquals(name, c.currentChatName())
        assertEquals(listOf("Hello", "one", "reply"), c.state.value.chat!!.messages.map { it.text })
        assertEquals(parent, c.mainChatName())
        c.backToMain()
        assertEquals(parent, c.currentChatName())
        assertEquals(5, c.state.value.chat!!.messages.size)
        assertEquals(listOf(name), c.state.value.chat!!.messages[2].branches)
        assertEquals(listOf(parent + " - Branch #1.jsonl"), ChatRepository(paths).list("Ann").map { it.name }.filter { "Branch" in it })
    }

    @Test fun secondBranchGetsNextNumber() = runTest {
        val c = controller(); talk(c)
        val first = c.createBranch(1)!!; c.backToMain()
        val second = c.createBranch(1)!!
        assertTrue(first.endsWith("#1")); assertTrue(second.endsWith("#2"))
    }

    @Test fun checkpointStaysAndLinksThenOpens() = runTest {
        val c = controller(); talk(c)
        val parent = c.currentChatName()
        val name = c.createCheckpoint(1)!!
        assertEquals("$parent - Checkpoint #1", name)
        assertEquals(parent, c.currentChatName())
        assertEquals(name, c.state.value.chat!!.messages[1].bookmarkLink)
        assertTrue(c.openCheckpoint(1))
        assertEquals(name, c.currentChatName())
        assertEquals(2, c.state.value.chat!!.messages.size)
        assertEquals(parent, c.mainChatName())
    }

    @Test fun namedCheckpointReplacesPreviousFile() = runTest {
        val c = controller(); talk(c)
        c.createCheckpoint(1, "Save A"); c.createCheckpoint(3, "Save A")
        c.openCheckpoint(3)
        assertEquals(4, c.state.value.chat!!.messages.size)
    }

    @Test fun invalidIndexReportsError() = runTest {
        val c = controller(); talk(c)
        assertNull(c.createBranch(99))
        assertNotNull(c.state.value.error)
        assertFalse(c.openCheckpoint(0))
    }

    @Test fun openingMissingCheckpointFileReportsError() = runTest {
        val c = controller(); talk(c)
        val name = c.createCheckpoint(1)!!
        File(c.state.value.chat!!.file!!.parentFile, "$name.jsonl").delete()
        assertFalse(c.openCheckpoint(1))
        assertTrue(c.state.value.error!!.contains("no longer exists"))
    }

    @Test fun branchNameCannotEscapeChatDirectory() = runTest {
        val c = controller(); talk(c)
        c.createCheckpoint(1, "../../evil")
        assertTrue(File(paths.chatDir("Ann"), "______evil.jsonl").exists() || paths.chatDir("Ann").listFiles()!!.any { "evil" in it.name })
        assertFalse(File(paths.root.parentFile, "evil.jsonl").exists())
    }

    @Test fun hideAndUnhideMessage() = runTest {
        val c = controller(); talk(c)
        c.setHidden(1, true)
        assertTrue(c.state.value.chat!!.messages[1].isHidden)
        c.send("three"); advanceUntilIdle()
        assertFalse(lastContents().contains("one"))
        c.setHidden(1, false)
        assertFalse(c.state.value.chat!!.messages[1].isHidden)
        assertTrue(c.state.value.chat!!.messages[1].inPrompt)
    }

    @Test fun commandsBranchAndCheckpoint() = runTest {
        val c = controller(); talk(c)
        val ex = CommandExecutor(c.commandHost())
        val cp = ex.runText("/checkpoint-create mes=1 Save").pipe
        assertEquals("Save", cp)
        assertEquals("Save", ex.runText("/checkpoint-get 1").pipe)
        assertEquals("1", ex.runText("/checkpoint-list").pipe)
        val parent = c.currentChatName()
        ex.runText("/checkpoint-go 1")
        assertEquals("Save", c.currentChatName())
        assertEquals(parent, ex.runText("/checkpoint-parent").pipe)
        ex.runText("/checkpoint-exit")
        assertEquals(parent, c.currentChatName())
        assertNotNull(ex.runText("/checkpoint-go 0").error)
    }

    @Test fun groupBranchRegistersChatWithGroup() = runTest {
        val groups = GroupRepository(paths)
        val bob = StoredCharacter("Bob", CharacterCard(name = "Bob", firstMessage = "yo"), File("Bob"))
        val g = groups.create("Crew", listOf("Ann.png", "Bob.png"))
        val c = ChatController(ChatRepository(paths), { backend }, { cfg }, { "key" }, this, groups = groups,
            characterLookup = { mapOf("Ann" to ann, "Bob" to bob)[it] })
        c.openGroup(g)
        val name = c.createCheckpoint(0, "Cp")!!
        assertTrue(name in groups.get(g.id)!!.chats)
        assertTrue(c.openCheckpoint(0))
        assertEquals("Cp", c.currentChatName())
        assertEquals(g.chatId.let { it }, c.mainChatName())
        c.backToMain()
        assertEquals(g.chatId, c.currentChatName())
    }
}
