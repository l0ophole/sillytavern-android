package app.sillytavern.android.chat

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.data.*
import app.sillytavern.android.group.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatControllerTest {
    private val paths = StoragePaths(Files.createTempDirectory("gc").toFile()).ensure()
    private val groupRepo = GroupRepository(paths)
    private val alice = StoredCharacter("Alice", CharacterCard(name = "Alice", description = "Alice is tall.", firstMessage = "Hi from Alice"), File("a"))
    private val bob = StoredCharacter("Bob", CharacterCard(name = "Bob", description = "Bob is short."), File("b"))
    private val chars = mapOf("Alice" to alice, "Bob" to bob)
    private val settings = AppSettings(connections = listOf(ConnectionProfile(name = "c")))
    private val requests = mutableListOf<GenerationRequest>()
    private var n = 0
    private val fake = Backend { req -> requests += req; val k = ++n; flow { emit("reply$k") } }

    private fun TestScope.controller(backend: Backend = fake) = ChatController(
        ChatRepository(paths), { backend }, { settings }, { "key" }, this,
        groups = groupRepo, characterLookup = { chars[it] }, random = kotlin.random.Random(1),
    )

    private fun group(strategy: Int = ActivationStrategy.LIST, mode: Int = GroupGenerationMode.SWAP, members: List<String> = listOf("Alice.png", "Bob.png"), muted: List<String> = emptyList()) =
        groupRepo.create("Crew", members).let { g -> g.copy(activationStrategy = strategy, generationMode = mode, disabledMembers = muted).also(groupRepo::save) }

    @Test fun groupSendGeneratesActivatedMembersInOrder() = runTest {
        val c = controller(); c.openGroup(group())
        c.send("hello all"); advanceUntilIdle()
        val m = c.state.value.chat!!.messages
        assertEquals(listOf("Alice", "User", "Alice", "Bob"), m.map { it.name })
        assertEquals(listOf("Hi from Alice", "hello all", "reply1", "reply2"), m.map { it.text })
        assertEquals(listOf("Alice.png", "Bob.png"), m.drop(2).map { it.originalAvatar })
        assertFalse(c.state.value.generating)
    }

    @Test fun manualStrategyDoesNotGenerateOnUserInput() = runTest {
        val c = controller(); c.openGroup(group(ActivationStrategy.MANUAL))
        c.send("hello"); advanceUntilIdle()
        assertEquals(0, requests.size)
        assertEquals("hello", c.state.value.chat!!.messages.last().text)
    }

    @Test fun allMutedSendsUserMessageOnly() = runTest {
        val c = controller(); c.openGroup(group(muted = listOf("Alice.png", "Bob.png")))
        c.send("anyone?"); advanceUntilIdle()
        assertEquals(0, requests.size)
        assertEquals("anyone?", c.state.value.chat!!.messages.last().text)
        assertNotNull(c.state.value.notice)
    }

    @Test fun speakForcesMutedMember() = runTest {
        val c = controller(); c.openGroup(group(muted = listOf("Bob.png")))
        c.speak("Bob.png"); advanceUntilIdle()
        val last = c.state.value.chat!!.messages.last()
        assertEquals("Bob", last.name); assertEquals("reply1", last.text); assertEquals("Bob.png", last.originalAvatar)
    }

    @Test fun regenerateUsesMessageSpeaker() = runTest {
        val c = controller(); c.openGroup(group())
        c.speak("Bob.png"); advanceUntilIdle()
        c.regenerate(); advanceUntilIdle()
        val last = c.state.value.chat!!.messages.last()
        assertEquals("Bob", last.name); assertEquals(2, last.swipes.size)
        assertTrue(requests.last().messages.any { it.content.contains("Bob is short.") })
    }

    @Test fun regenerateOfRemovedMemberErrorsAndKeepsMessage() = runTest {
        val c = controller(); val g = group(); c.openGroup(g)
        c.speak("Bob.png"); advanceUntilIdle()
        c.updateGroup { it.copy(members = listOf("Alice.png")) }
        val before = c.state.value.chat!!.messages
        c.regenerate(); advanceUntilIdle()
        assertNotNull(c.state.value.error)
        assertEquals(before, c.state.value.chat!!.messages)
    }

    @Test fun promptHasMergedCardInAppendMode() = runTest {
        val c = controller(); c.openGroup(group(mode = GroupGenerationMode.APPEND))
        c.speak("Bob.png"); advanceUntilIdle()
        val text = requests.last().messages.joinToString("\n") { it.content }
        assertTrue(text, text.contains("Alice is tall.") && text.contains("Bob is short."))
    }

    @Test fun swapModeOnlyHasSpeakersCard() = runTest {
        val c = controller(); c.openGroup(group())
        c.speak("Bob.png"); advanceUntilIdle()
        val text = requests.last().messages.joinToString("\n") { it.content }
        assertTrue(text.contains("Bob is short.")); assertFalse(text.contains("Alice is tall."))
    }

    @Test fun promptHasSpeakerNamesPrefix() = runTest {
        val c = controller(); c.openGroup(group())
        c.speak("Bob.png"); advanceUntilIdle()
        assertTrue(requests.last().messages.any { it.content == "Alice: Hi from Alice" })
    }

    @Test fun errorStopsBatch() = runTest {
        val c = controller(Backend { flow { throw BackendException("HTTP 500") } }); c.openGroup(group())
        c.send("hi"); advanceUntilIdle()
        assertEquals("HTTP 500", c.state.value.error)
        assertFalse(c.state.value.generating)
        assertEquals(2, c.state.value.chat!!.messages.size) // greeting + user
    }

    @Test fun stopCancelsBatch() = runTest {
        val slow = Backend { flow { emit("a"); delay(100_000); emit("b") } }
        val c = controller(slow); c.openGroup(group())
        c.send("hi"); runCurrent()
        assertTrue(c.state.value.generating)
        c.stop(); advanceUntilIdle()
        assertFalse(c.state.value.generating)
        assertEquals(3, c.state.value.chat!!.messages.size) // greeting, user, partial Alice; Bob never starts
    }

    @Test fun newGroupChatCreatesFileAndSwitches() = runTest {
        val c = controller(); val g = group(); c.openGroup(g)
        val old = c.state.value.chat!!.file
        c.newChat()
        assertNotEquals(old, c.state.value.chat!!.file)
        assertEquals(2, c.chatFiles().size)
        assertEquals(c.state.value.group!!.chatId, c.state.value.chat!!.file!!.nameWithoutExtension)
    }

    @Test fun openGroupWithMissingMemberDoesNotThrow() = runTest {
        val c = controller(); c.openGroup(group(members = listOf("Ghost.png", "Bob.png")))
        assertEquals(listOf("Bob"), c.state.value.members.map { it.card.name })
        c.send("hi"); advanceUntilIdle()
        assertEquals("Bob", c.state.value.chat!!.messages.last().name)
    }

    @Test fun mutedMemberIsSkippedByList() = runTest {
        val c = controller(); c.openGroup(group(muted = listOf("Alice.png")))
        c.send("hi"); advanceUntilIdle()
        assertEquals(listOf("Bob"), c.state.value.chat!!.messages.filter { !it.isUser && it.originalAvatar != null && it.text.startsWith("reply") }.map { it.name })
    }
}
