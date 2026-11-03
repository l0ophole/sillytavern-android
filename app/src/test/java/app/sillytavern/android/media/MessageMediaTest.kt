package app.sillytavern.android.media

import app.sillytavern.android.api.AnthropicBackend
import app.sillytavern.android.api.GeminiBackend
import app.sillytavern.android.api.openAiMessage
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private val img = ChatMessage.user("Me", "look").withImage("user/images/Ann/a.png")

    @Test fun imageExtraRoundTrips() {
        val m = ChatMessage.fromJson(img.withImage("user/images/Ann/a.png", "a cat", inline = true).toJson())
        assertEquals("user/images/Ann/a.png", m.imagePath)
        assertEquals("a cat", m.imageTitle)
        assertTrue(m.isInlineImage)
        val cleared = m.withImage(null)
        assertNull(cleared.imagePath); assertNull(cleared.imageTitle); assertFalse(cleared.isInlineImage)
    }

    @Test fun fileAttachmentRoundTrips() {
        val m = ChatMessage.user("Me", "x").withFile(FileAttachment("user/files/n.txt", "n.txt", 12))
        assertEquals(FileAttachment("user/files/n.txt", "n.txt", 12), ChatMessage.fromJson(m.toJson()).fileAttachment)
    }

    @Test fun resolveRejectsPathsOutsideRoot() {
        val root = tmp.newFolder("data")
        File(root, "user/images").mkdirs(); File(root, "user/images/a.png").writeText("x")
        File(tmp.root, "secret.txt").writeText("s")
        assertNotNull(MessageMedia.resolve(root, "user/images/a.png"))
        assertNull(MessageMedia.resolve(root, "../secret.txt"))
        assertNull(MessageMedia.resolve(root, "user/../../secret.txt"))
        assertNull(MessageMedia.resolve(root, ""))
    }

    @Test fun dataUrlRoundTrips() {
        val u = MessageMedia.dataUrl("x.jpg", byteArrayOf(1, 2, 3))
        assertTrue(u.startsWith("data:image/jpeg;base64,"))
        val (mime, data) = MessageMedia.parseDataUrl(u)!!
        assertEquals("image/jpeg", mime)
        assertArrayEquals(byteArrayOf(1, 2, 3), java.util.Base64.getDecoder().decode(data))
        assertNull(MessageMedia.parseDataUrl("http://x"))
    }

    @Test fun decorateSendsMarkerOrCaption() {
        val send = MediaPromptContext(ImagePromptMode.SEND_IMAGE)
        assertEquals("⟦img:user/images/Ann/a.png⟧\nlook", MediaPrompt.decorate(img, "look", send))
        val cap = MediaPromptContext(ImagePromptMode.USE_CAPTION)
        assertEquals("look", MediaPrompt.decorate(img, "look", cap))
        assertEquals("[Image: a dog]\nlook", MediaPrompt.decorate(img.withCaption("a dog"), "look", cap))
    }

    @Test fun generatedImageUsesItsPromptAsCaption() {
        val gen = ChatMessage.assistant("Ann", "").withImage("user/images/Ann/g.png", "a red door", inline = true)
        assertEquals("[Image: a red door]", MediaPrompt.decorate(gen, "", MediaPromptContext(ImagePromptMode.SEND_IMAGE)))
    }

    @Test fun decorateInlinesFileText() {
        val m = ChatMessage.user("Me", "read").withFile(FileAttachment("f.txt", "f.txt", 3))
        val out = MediaPrompt.decorate(m, "read", MediaPromptContext(ImagePromptMode.SEND_IMAGE, fileText = { "abc" }))
        assertEquals("[File: f.txt]\nabc\n[End of file]\n\nread", out)
        assertEquals("read", MediaPrompt.decorate(m, "read", MediaPromptContext(ImagePromptMode.SEND_IMAGE)))
    }

    @Test fun resolveTurnsMarkersIntoImageParts() {
        val msgs = listOf(PromptMessage(Role.USER, MediaPrompt.marker("a.png") + "\nhi"), PromptMessage(Role.ASSISTANT, "ok"))
        val kept = MediaPrompt.resolve(msgs, true) { "data:image/png;base64,AAAA" }
        assertEquals("hi", kept[0].content); assertEquals(listOf("data:image/png;base64,AAAA"), kept[0].images)
        assertEquals(msgs[1], kept[1])
        val dropped = MediaPrompt.resolve(msgs, false) { error("no") }
        assertEquals("hi", dropped[0].content); assertTrue(dropped[0].images.isEmpty())
        assertTrue(MediaPrompt.resolve(msgs, true) { null }[0].images.isEmpty())
    }

    @Test fun openAiSerializesImageParts() {
        val o = openAiMessage(PromptMessage(Role.USER, "hi", images = listOf("data:image/png;base64,AA")))
        val parts = o["content"]!!.jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,AA", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("hi", openAiMessage(PromptMessage(Role.USER, "hi"))["content"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicSerializesImageBlocks() {
        val (_, turns) = AnthropicBackend.toAnthropicTurns(listOf(PromptMessage(Role.USER, "hi", images = listOf("data:image/jpeg;base64,QQ"))))
        val blocks = turns[0]["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        val src = blocks[1].jsonObject["source"]!!.jsonObject
        assertEquals("image/jpeg", src["media_type"]!!.jsonPrimitive.content)
        assertEquals("QQ", src["data"]!!.jsonPrimitive.content)
    }

    @Test fun geminiSerializesInlineData() {
        val (_, c) = GeminiBackend.toContents(listOf(PromptMessage(Role.USER, "hi", images = listOf("data:image/png;base64,ZZ"))))
        val parts = c[0]["parts"]!!.jsonArray
        assertEquals("hi", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("ZZ", parts[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }
}
