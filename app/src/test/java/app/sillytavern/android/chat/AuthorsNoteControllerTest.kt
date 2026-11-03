package app.sillytavern.android.chat

import app.sillytavern.android.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorsNoteControllerTest : ChatFeaturesTestBase() {
    @Test fun noteIsInjectedAtDepthAndPersistedInHeader() = runTest {
        val c = controller(); c.open(ann)
        c.setNoteConfig(NoteConfig("Be brief.", interval = 1, depth = 0, position = 1, role = 0))
        c.send("hi"); advanceUntilIdle()
        assertEquals("Be brief.", lastContents().last())
        val saved = ChatRepository(paths).load(c.state.value.chat!!.file!!)
        assertEquals("Be brief.", ChatMeta.string(ChatMeta.of(saved), "note_prompt"))
    }

    @Test fun intervalSkipsUntilDue() = runTest {
        val c = controller(); c.open(ann)
        c.setNoteConfig(NoteConfig("N", interval = 2, depth = 0, position = 1, role = 0))
        c.send("one"); advanceUntilIdle()
        assertFalse(lastContents().contains("N"))
        c.send("two"); advanceUntilIdle()
        assertTrue(lastContents().contains("N"))
    }

    @Test fun characterNoteReplacesWhenEnabled() = runTest {
        val c = controller(); c.open(ann)
        c.setNoteConfig(NoteConfig("chat note", 1, 0, 1, 0))
        c.setCharacterNote(CharacterNote("char note", useChara = true, position = 0))
        c.send("hi"); advanceUntilIdle()
        assertEquals("char note", lastContents().last())
        assertEquals("char note", c.characterNote()!!.prompt)
    }

    @Test fun summaryIsInjectedFromEarlierMessage() = runTest {
        val c = controller(); c.open(ann)
        c.setSummary("They met at dawn.")
        c.send("go on"); advanceUntilIdle()
        assertTrue(lastContents().any { it == "[Summary: They met at dawn.]" })
    }
}
