package app.sillytavern.android.chat

import app.sillytavern.android.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonaLockTest : ChatFeaturesTestBase() {
    private val a = Persona(id = "a", name = "Alice", description = "A desc")
    private val b = Persona(id = "b", name = "Bob", description = "B desc", characters = listOf("Ann"))
    private val d = Persona(id = "d", name = "Dee")

    private fun s(default: String? = null, active: String? = "a") =
        AppSettings(personas = listOf(a, b, d), activePersonaId = active, defaultPersonaId = default)

    @Test fun chatLockWinsOverCharacterAndDefault() = assertEquals("d", PersonaLock.resolve(s("a"), "d", "Ann"))
    @Test fun characterLockWinsOverDefault() = assertEquals("b", PersonaLock.resolve(s("d"), null, "Ann"))
    @Test fun defaultUsedWhenNothingLocked() = assertEquals("d", PersonaLock.resolve(s("d"), null, "Zed"))
    @Test fun nothingAppliesKeepsCurrent() = assertNull(PersonaLock.resolve(s(), null, "Zed"))
    @Test fun deletedPersonasAreIgnored() {
        assertEquals("b", PersonaLock.resolve(s("gone"), "gone", "Ann"))
        assertNull(PersonaLock.resolve(s("gone"), "gone", "Zed"))
    }

    @Test fun lockCharacterMovesTheConnection() {
        val out = PersonaLock.lockCharacter(listOf(a, b, d), "d", "Ann")
        assertEquals(listOf(emptyList<String>(), emptyList(), listOf("Ann")), out.map { it.characters })
        assertTrue(PersonaLock.unlockCharacter(out, "Ann").all { it.characters.isEmpty() })
    }

    @Test fun chatOpensWithLockedPersona() = runTest {
        cfg = cfg.copy(personas = listOf(a, d), activePersonaId = "a")
        val c = controller(); c.open(ann)
        c.selectPersona("d"); c.lockPersona(chatLock = true)
        assertTrue(c.isChatLocked())
        val file = c.state.value.chat!!.file!!
        cfg = cfg.copy(activePersonaId = "a")
        val c2 = controller(); c2.open(ann, file)
        assertEquals("d", cfg.activePersonaId)
        c2.unlockPersona(chatLock = true)
        assertFalse(ChatMeta.of(c2.state.value.chat).containsKey("persona"))
    }

    @Test fun characterLockSwitchesOnOpen() = runTest {
        cfg = cfg.copy(personas = listOf(a, b), activePersonaId = "a")
        val c = controller(); c.open(ann)
        assertEquals("b", cfg.activePersonaId) // b is connected to Ann
        assertTrue(c.isCharacterLocked())
        c.unlockPersona(chatLock = false)
        assertFalse(c.isCharacterLocked())
    }

    @Test fun autoLockLocksOnManualSelect() = runTest {
        cfg = cfg.copy(personas = listOf(a, d), activePersonaId = "a", personaAutoLock = true)
        val c = controller(); c.open(ann)
        c.selectPersona("d")
        assertEquals(JsonPrimitive("d"), ChatMeta.of(c.state.value.chat)["persona"])
    }

    @Test fun personaDepthDescriptionIsInjected() = runTest {
        cfg = cfg.copy(personas = listOf(a.copy(position = 4, depth = 0, role = 0)), activePersonaId = "a")
        val c = controller(); c.open(ann); c.send("hi"); advanceUntilIdle()
        assertEquals("A desc", lastContents().last())
        assertFalse(lastContents().dropLast(1).contains("A desc"))
    }

    @Test fun personaPositionNoneDropsDescription() = runTest {
        cfg = cfg.copy(personas = listOf(a.copy(position = 5)), activePersonaId = "a")
        val c = controller(); c.open(ann); c.send("hi"); advanceUntilIdle()
        assertFalse(lastContents().any { it.contains("A desc") })
    }

    @Test fun personaInPromptByDefault() = runTest {
        cfg = cfg.copy(personas = listOf(a), activePersonaId = "a")
        val c = controller(); c.open(ann); c.send("hi"); advanceUntilIdle()
        assertTrue(lastContents().any { it.contains("A desc") })
    }

    @Test fun personaTopOfAuthorsNote() = runTest {
        cfg = cfg.copy(personas = listOf(a.copy(position = 2)), activePersonaId = "a")
        val c = controller(); c.open(ann)
        c.setNoteConfig(NoteConfig("NOTE", 1, 0, 1, 0))
        c.send("hi"); advanceUntilIdle()
        assertEquals("A desc\nNOTE", lastContents().last())
    }
}
