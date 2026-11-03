package app.sillytavern.android.media

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.chat.ChatFeaturesTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImagePromptsTest {
    @Test fun modeWords() {
        assertEquals(ImageMode.CHARACTER, ImageMode.parse("you")); assertEquals(ImageMode.USER, ImageMode.parse(" Me "))
        assertEquals(ImageMode.RAW_LAST, ImageMode.parse("raw_last")); assertEquals(ImageMode.BACKGROUND, ImageMode.parse("bg"))
        assertNull(ImageMode.parse("a cat on a roof"))
    }

    @Test fun cleanUnwrapsModelOutput() {
        assertEquals("1girl, red hair, smiling", ImagePrompts.clean("\"[1girl, red hair,\n smiling.]\""))
        assertEquals("a, b", ImagePrompts.clean("a,, b,"))
    }

    @Test fun composeUsesPlaceholderOrPrepends() {
        assertEquals("masterpiece, a cat", ImagePrompts.compose("masterpiece,", "a cat"))
        assertEquals("(a cat:1.2), best quality", ImagePrompts.compose("({prompt}:1.2), best quality", "a cat"))
        assertEquals("a cat", ImagePrompts.compose("", "a cat"))
    }

    @Test fun everyModeButRawHasAnInstruction() {
        ImageMode.entries.filter { it != ImageMode.RAW_LAST }.forEach { assertNotNull(it.name, ImagePrompts.instruction(it)) }
        assertNull(ImagePrompts.instruction(ImageMode.RAW_LAST))
    }
}

class ImageGeneratorTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val b64 = java.util.Base64.getEncoder().encodeToString(png)
    private fun gen() = ImageGenerator(pollMs = 1, comfyTimeoutMs = 200)
    private fun root() = server.url("/").toString().trimEnd('/')

    @Test fun a1111PostsTxt2imgAndDecodes() {
        server.enqueue(MockResponse().setBody("""{"images":["$b64"]}"""))
        val s = ImageGenSettings(url = root(), model = "sd15", scheduler = "karras", seed = 5)
        val img = runBlocking { gen().generate(s, "a cat", "ugly", null) }
        assertArrayEquals(png, img.bytes)
        val r = server.takeRequest()
        assertEquals("/sdapi/v1/txt2img", r.path)
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("a cat", b["prompt"]!!.jsonPrimitive.content); assertEquals("ugly", b["negative_prompt"]!!.jsonPrimitive.content)
        assertEquals("karras", b["scheduler"]!!.jsonPrimitive.content); assertEquals(5, b["seed"]!!.jsonPrimitive.int)
        assertEquals("sd15", b["override_settings"]!!.jsonObject["sd_model_checkpoint"]!!.jsonPrimitive.content)
    }

    @Test fun a1111ErrorAndEmptyResponses() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(assertThrows(BackendException::class.java) { runBlocking { gen().generate(ImageGenSettings(url = root()), "x", "", null) } }.message!!.contains("500"))
        server.enqueue(MockResponse().setBody("""{"images":[]}"""))
        assertThrows(BackendException::class.java) { runBlocking { gen().generate(ImageGenSettings(url = root()), "x", "", null) } }
    }

    private val workflow = """{"3":{"inputs":{"text":"%prompt%","neg":"%negative_prompt%","seed":%seed%,"steps":%steps%,"w":%width%}}}"""

    @Test fun comfyWorkflowEscapesTextAndFillsNumbers() {
        val wf = gen().comfyWorkflow(ImageGenSettings(comfyWorkflow = workflow, steps = 30, width = 640, seed = 7), "say \"hi\"\nthere", "bad")
        val inputs = wf["3"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals("say \"hi\"\nthere", inputs["text"]!!.jsonPrimitive.content)
        assertEquals(7, inputs["seed"]!!.jsonPrimitive.int); assertEquals(30, inputs["steps"]!!.jsonPrimitive.int); assertEquals(640, inputs["w"]!!.jsonPrimitive.int)
    }

    @Test fun comfyWorkflowValidation() {
        assertThrows(BackendException::class.java) { gen().comfyWorkflow(ImageGenSettings(comfyWorkflow = ""), "a", "") }
        assertThrows(BackendException::class.java) { gen().comfyWorkflow(ImageGenSettings(comfyWorkflow = "{not json"), "a", "") }
    }

    @Test fun comfyQueuesPollsAndFetchesTheImage() {
        server.enqueue(MockResponse().setBody("""{"prompt_id":"abc"}"""))
        server.enqueue(MockResponse().setBody("{}")) // not finished yet
        server.enqueue(MockResponse().setBody("""{"abc":{"outputs":{"9":{"images":[{"filename":"out_1.png","subfolder":"sub","type":"output"}]}}}}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(png)))
        val img = runBlocking { gen().generate(ImageGenSettings(source = ImageSource.COMFY, url = root(), comfyWorkflow = workflow), "a cat", "", null) }
        assertArrayEquals(png, img.bytes); assertEquals("png", img.extension)
        assertEquals("/prompt", server.takeRequest().path)
        assertEquals("/history/abc", server.takeRequest().path); assertEquals("/history/abc", server.takeRequest().path)
        assertEquals("/view?filename=out_1.png&subfolder=sub&type=output", server.takeRequest().path)
    }

    @Test fun comfyTimesOut() {
        server.enqueue(MockResponse().setBody("""{"prompt_id":"abc"}"""))
        repeat(400) { server.enqueue(MockResponse().setBody("{}")) }
        assertTrue(assertThrows(BackendException::class.java) {
            runBlocking { gen().generate(ImageGenSettings(source = ImageSource.COMFY, url = root(), comfyWorkflow = workflow), "a", "", null) }
        }.message!!.contains("timed out"))
    }

    @Test fun novelAiReturnsTheZippedImage() {
        val zip = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("image_0.png")); z.write(png); z.closeEntry() } }.toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val s = ImageGenSettings(source = ImageSource.NOVELAI, novelUrl = root(), sampler = "Euler a", seed = 3)
        val img = runBlocking { gen().generate(s, "1girl", "lowres", "nai-key") }
        assertArrayEquals(png, img.bytes)
        val r = server.takeRequest()
        assertEquals("/ai/generate-image", r.path); assertEquals("Bearer nai-key", r.getHeader("Authorization"))
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("1girl", b["input"]!!.jsonPrimitive.content)
        assertEquals("k_euler", b["parameters"]!!.jsonObject["sampler"]!!.jsonPrimitive.content)
        assertEquals("lowres", b["parameters"]!!.jsonObject["negative_prompt"]!!.jsonPrimitive.content)
    }

    @Test fun novelAiNeedsAKeyAndAnImage() {
        assertThrows(BackendException::class.java) { runBlocking { gen().generate(ImageGenSettings(source = ImageSource.NOVELAI, novelUrl = root()), "x", "", null) } }
        assertThrows(BackendException::class.java) { ImageGenerator.unzipFirstImage(ByteArray(0)) }
    }

    @Test fun openAiDalle3AndGptImageBodies() {
        val d = gen().openAiBody(ImageGenSettings(source = ImageSource.OPENAI, width = 1024, height = 1024, openAiQuality = "hd"), "p")
        assertEquals("b64_json", d["response_format"]!!.jsonPrimitive.content); assertEquals("hd", d["quality"]!!.jsonPrimitive.content)
        assertEquals("1024x1024", d["size"]!!.jsonPrimitive.content)
        val g = gen().openAiBody(ImageGenSettings(openAiModel = "gpt-image-1"), "p")
        assertFalse(g.containsKey("response_format")); assertFalse(g.containsKey("quality"))
    }

    @Test fun openAiDecodesBase64OrFollowsUrl() {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$b64"}]}"""))
        val s = ImageGenSettings(source = ImageSource.OPENAI, openAiUrl = root() + "/v1")
        assertArrayEquals(png, runBlocking { gen().generate(s, "x", "", "k") }.bytes)
        assertEquals("/v1/images/generations", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"data":[{"url":"${root()}/img.png"}]}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(png)))
        assertArrayEquals(png, runBlocking { gen().generate(s, "x", "", "k") }.bytes)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImageControllerTest : ChatFeaturesTestBase() {
    class FakeImages : ImageBackend {
        val calls = mutableListOf<Triple<ImageGenSettings, String, String>>()
        override suspend fun generate(s: ImageGenSettings, prompt: String, negative: String, apiKey: String?): GeneratedImage {
            calls += Triple(s, prompt, negative); return GeneratedImage(byteArrayOf(1, 2, 3))
        }
    }
    private val images = FakeImages()
    private fun TestScopeCtl(scope: kotlinx.coroutines.test.TestScope) = with(this) {
        app.sillytavern.android.chat.ChatController(
            app.sillytavern.android.chat.ChatRepository(paths), { backend }, { cfg }, { "key" }, scope,
            mediaRoot = paths.root, imageBackend = images,
        )
    }

    @Test fun sceneAsksTheModelForAPromptAndAddsAnImageMessage() = runTest {
        cfg = cfg.copy(media = MediaSettings(imageGen = ImageGenSettings(prefix = "masterpiece,", negative = "ugly")))
        val c = TestScopeCtl(this); c.open(ann); c.send("hello"); advanceUntilIdle()
        reply = "\"a quiet room, soft light.\""
        val path = c.generateImage("scene")
        assertTrue(requests.last().messages.last().content.contains("Describe the current scene"))
        assertEquals("masterpiece, a quiet room, soft light", images.calls.single().second)
        assertEquals("ugly", images.calls.single().third)
        val m = c.state.value.chat!!.messages.last()
        assertEquals(path, m.imagePath); assertTrue(m.isInlineImage); assertTrue(m.isSystem)
        assertEquals("masterpiece, a quiet room, soft light", m.imageTitle)
        assertArrayEquals(byteArrayOf(1, 2, 3), java.io.File(paths.root, path).readBytes())
    }

    @Test fun freeTextIsUsedDirectlyAndQuietAddsNoMessage() = runTest {
        val c = TestScopeCtl(this); c.open(ann)
        val before = c.state.value.chat!!.messages.size
        c.generateImage("a red fox", quiet = true, negative = "blurry")
        assertEquals("a red fox", images.calls.single().second); assertEquals("blurry", images.calls.single().third)
        assertEquals(before, c.state.value.chat!!.messages.size)
        assertTrue(requests.isEmpty())
    }

    @Test fun rawLastUsesTheLastMessageWithoutTheModel() = runTest {
        val c = TestScopeCtl(this); c.open(ann); c.send("a castle on a hill"); advanceUntilIdle(); requests.clear()
        reply = "unused"
        c.generateImage("raw_last") // last message is the reply "reply"
        assertTrue(requests.isEmpty()); assertEquals("reply", images.calls.single().second)
    }

    @Test fun withoutLlmPromptTheCardIsUsed() = runTest {
        cfg = cfg.copy(media = MediaSettings(imageGen = ImageGenSettings(llmPrompt = false)))
        val c = TestScopeCtl(this); c.open(ann)
        c.generateImage("you")
        assertTrue(requests.isEmpty()); assertTrue(images.calls.single().second.startsWith("Ann"))
    }

    @Test fun nothingToDrawIsAnError() = runTest {
        val c = TestScopeCtl(this)
        c.open(ann.copy(card = ann.card.copy(firstMessage = "")))
        cfg = cfg.copy(media = MediaSettings(imageGen = ImageGenSettings(llmPrompt = false)))
        assertThrows(Exception::class.java) { runBlocking { c.generateImage("last") } }
    }
}
