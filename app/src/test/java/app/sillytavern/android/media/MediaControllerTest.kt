package app.sillytavern.android.media

import app.sillytavern.android.chat.ChatFeaturesTestBase
import app.sillytavern.android.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControllerTest : ChatFeaturesTestBase() {
    private val png = byteArrayOf(1, 2, 3, 4)

    @Test fun attachedImageIsSentWithTheMessageAndStored() = runTest {
        val c = controller(); c.open(ann)
        c.attachImage(png, "pic.png")
        assertNotNull(c.state.value.pendingImage)
        c.send("what is this"); advanceUntilIdle()
        assertNull(c.state.value.pendingImage)
        val user = c.state.value.chat!!.messages.first { it.isUser }
        assertEquals("user/images/Ann/pic.png", user.imagePath)
        assertArrayEquals(png, java.io.File(paths.root, user.imagePath!!).readBytes())
        val sent = requests.last().messages.first { it.images.isNotEmpty() }
        assertEquals("what is this", sent.content)
        assertTrue(sent.images[0].startsWith("data:image/png;base64,"))
    }

    @Test fun imageOnlyMessageCanBeSent() = runTest {
        val c = controller(); c.open(ann)
        c.attachImage(png, "p.png"); c.send(""); advanceUntilIdle()
        assertEquals(1, requests.size)
        assertTrue(c.state.value.chat!!.messages.any { it.isUser && it.imagePath != null })
    }

    @Test fun captionModeSendsTextInsteadOfImage() = runTest {
        cfg = cfg.copy(media = MediaSettings(attachments = AttachmentSettings(mode = ImagePromptMode.USE_CAPTION)))
        val c = controller(); c.open(ann)
        c.attachImage(png, "p.png"); c.send("hi"); advanceUntilIdle()
        reply = "a small cat"
        runBlocking { c.captionMessage(c.state.value.chat!!.messages.indexOfFirst { it.isUser }) }
        reply = "ok"
        c.send("again"); advanceUntilIdle()
        val prompt = requests.last().messages
        assertTrue(prompt.none { it.images.isNotEmpty() })
        assertTrue(prompt.any { it.content.contains("[Image: a small cat]") })
    }

    @Test fun captionRequestCarriesTheImageAndStoresTheResult() = runTest {
        val c = controller(); c.open(ann)
        c.attachImage(png, "p.png"); c.send("hi"); advanceUntilIdle()
        reply = "  a red barn  "
        val idx = c.state.value.chat!!.messages.indexOfFirst { it.isUser }
        assertEquals("a red barn", runBlocking { c.captionMessage(idx) })
        val req = requests.last()
        assertEquals("What's in this image?", req.messages.single().content)
        assertEquals(1, req.messages.single().images.size)
        assertEquals("a red barn", c.state.value.chat!!.messages[idx].imageCaption)
    }

    @Test fun autoCaptionRunsBeforeTheReply() = runTest {
        cfg = cfg.copy(media = MediaSettings(attachments = AttachmentSettings(autoCaption = true, mode = ImagePromptMode.USE_CAPTION)))
        val c = controller(); c.open(ann)
        reply = "a lighthouse"
        c.attachImage(png, "p.png"); c.send("look"); advanceUntilIdle()
        assertEquals(2, requests.size)
        assertTrue(requests[1].messages.any { it.content.contains("[Image: a lighthouse]") })
    }

    @Test fun captioningNeedsAnImage() = runTest {
        val c = controller(); c.open(ann); c.send("plain"); advanceUntilIdle()
        assertThrows(Exception::class.java) { runBlocking { c.captionMessage(1) } }
    }

    @Test fun textFileAttachmentIsInlinedIntoThePrompt() = runTest {
        val c = controller(); c.open(ann)
        c.attachTextFile("notes about cats", "notes.txt"); c.send("summarize"); advanceUntilIdle()
        assertTrue(requests.last().messages.any { it.content.contains("[File: notes.txt]\nnotes about cats") })
    }

    @Test fun textBackendsGetCaptionsNotMarkers() = runTest {
        cfg = cfg.copy(connections = listOf(ConnectionProfile(name = "t", type = BackendType.TEXT_COMPLETION)))
        val c = controller(); c.open(ann)
        c.attachImage(png, "p.png"); c.send("hi"); advanceUntilIdle()
        assertFalse(requests.last().textPrompt.contains("⟦"))
    }
}
