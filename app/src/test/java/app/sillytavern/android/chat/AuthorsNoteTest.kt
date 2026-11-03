package app.sillytavern.android.chat

import app.sillytavern.android.data.CharacterNote
import app.sillytavern.android.data.NoteSettings
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AuthorsNoteTest {
    private fun cfg(prompt: String = "note", interval: Int = 1) = NoteConfig(prompt, interval, 4, 1, 0)
    private fun users(n: Int) = List(n) { ChatMessage.user("Me", "m$it") }

    @Test fun intervalOneAlwaysDue() { assertTrue(AuthorsNote.due(0, 1)); assertTrue(AuthorsNote.due(7, 1)) }
    @Test fun intervalZeroNeverDue() { assertFalse(AuthorsNote.due(0, 0)); assertFalse(AuthorsNote.due(5, 0)) }
    @Test fun intervalThreeDueOnEveryThirdUserMessage() {
        assertEquals(listOf(3, 6, 9), (0..10).filter { AuthorsNote.due(it, 3) })
    }

    @Test fun promptNullWhenNotDueOrBlank() {
        assertNull(AuthorsNote.prompt(cfg(interval = 3), users(2), null))
        assertNotNull(AuthorsNote.prompt(cfg(interval = 3), users(3), null))
        assertNull(AuthorsNote.prompt(cfg(prompt = "  "), users(1), null))
    }

    @Test fun onlyUserMessagesCount() {
        val h = users(2) + ChatMessage.assistant("A", "x") + ChatMessage.assistant("A", "y")
        assertNull(AuthorsNote.prompt(cfg(interval = 3), h, null))
    }

    @Test fun characterNotePositions() {
        fun p(pos: Int) = AuthorsNote.prompt(cfg(), users(1), CharacterNote("CHAR", true, pos))!!.content
        assertEquals("CHAR", p(0)); assertEquals("CHAR\nnote", p(1)); assertEquals("note\nCHAR", p(2))
        assertEquals("note", AuthorsNote.prompt(cfg(), users(1), CharacterNote("CHAR", false, 0))!!.content)
    }

    @Test fun characterNoteUsedEvenWhenChatNoteBlank() {
        assertEquals("CHAR", AuthorsNote.prompt(cfg(prompt = ""), users(1), CharacterNote("CHAR", true, 0))!!.content)
    }

    @Test fun personaTopAndBottomMerge() {
        assertEquals("P\nnote", AuthorsNote.prompt(cfg(), users(1), null, "P", 2)!!.content)
        assertEquals("note\nP", AuthorsNote.prompt(cfg(), users(1), null, "P", 3)!!.content)
        assertEquals("note", AuthorsNote.prompt(cfg(), users(1), null, "P", 0)!!.content)
    }

    @Test fun personaPositionNotAppliedWhenNoteNotDue() {
        assertNull(AuthorsNote.prompt(cfg(prompt = "", interval = 5), users(1), null, "P", 2))
    }

    @Test fun promptCarriesPositionDepthRole() {
        val e = AuthorsNote.prompt(NoteConfig("n", 1, 7, 2, 1), users(1), null)!!
        assertEquals(listOf(2, 7, 1), listOf(e.position, e.depth, e.role))
    }

    @Test fun configReadsMetadataWithDefaults() {
        val d = NoteSettings(defaultPosition = 0, defaultDepth = 9, defaultInterval = 2, defaultRole = 2)
        assertEquals(NoteConfig("", 2, 9, 0, 2), AuthorsNote.config(JsonObject(emptyMap()), d))
        val meta = buildJsonObject { put("note_prompt", "hi"); put("note_interval", 5); put("note_depth", "3") }
        assertEquals(NoteConfig("hi", 5, 3, 0, 2), AuthorsNote.config(meta, d))
    }

    @Test fun withConfigRoundTrips() {
        val chat = Chat(null, buildJsonObject { put("chat_metadata", buildJsonObject { put("x", 1) }) }, emptyList())
        val c = NoteConfig("hello", 4, 2, 1, 2)
        val saved = AuthorsNote.withConfig(chat, c)
        assertEquals(c, AuthorsNote.config(ChatMeta.of(saved), NoteSettings()))
        assertEquals(1, ChatMeta.int(ChatMeta.of(saved), "x"))
    }
}
