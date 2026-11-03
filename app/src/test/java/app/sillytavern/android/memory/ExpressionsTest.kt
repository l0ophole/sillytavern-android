package app.sillytavern.android.memory

import app.sillytavern.android.chat.ChatFeaturesTestBase
import app.sillytavern.android.command.CommandError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeywordClassifierTest {
    private fun c(t: String) = KeywordClassifier.classifyNow(t)

    @Test fun picksTheDominantEmotion() {
        assertEquals("anger", c("I'm so furious, I hate this! *snarls*"))
        assertEquals("sadness", c("I feel so sad and lonely, tears in my eyes"))
        assertEquals("gratitude", c("Thank you so much, I really appreciate it."))
        assertEquals("fear", c("*trembles* I'm scared... please, help me!"))
        assertEquals("surprise", c("Whoa! I can't believe it, what a shock!"))
        assertEquals("embarrassment", c("*blushes* Oh, this is so awkward, I'm flustered."))
    }

    @Test fun emojiCount() = assertEquals("amusement", c("that was great 😂"))

    @Test fun nothingMatchedIsNeutral() = assertEquals("neutral", c("The meeting is at three o'clock in the main hall."))

    @Test fun labelsAreRestrictedToTheAvailableOnes() {
        assertEquals("joy", KeywordClassifier.classifyNow("I'm so happy and angry", listOf("joy", "neutral")))
        assertNull(KeywordClassifier.classifyNow("nothing special here", listOf("joy")))
    }

    @Test fun ties() {
        assertEquals("anger", c("sorry, I'm angry")) // one remorse word, one anger word: the later mention wins
    }
}

class LlmClassifierTest {
    @Test fun parsesTheFirstLabelMentioned() = runBlocking {
        val labels = listOf("joy", "sadness", "neutral")
        assertEquals("sadness", LlmClassifier { "Sadness." }.classify("x", labels))
        assertEquals("joy", LlmClassifier { "I think joy, not sadness" }.classify("x", labels))
        assertNull(LlmClassifier { "I cannot say" }.classify("x", labels))
        assertNull(ExpressionLabels.parse("unjoyful", labels))
    }

    @Test fun promptListsLabelsAndText() {
        val p = LlmClassifier.prompt("Hello!", listOf("joy", "fear"))
        assertTrue(p.contains("joy, fear")); assertTrue(p.endsWith("Hello!"))
    }
}

class SpriteRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun repo() = SpriteRepository(tmp.newFolder())

    @Test fun addListAndLabels() {
        val r = repo()
        r.add("Ann", "Joy", "png", byteArrayOf(1)); r.add("Ann", "joy", "png", byteArrayOf(2)); r.add("Ann", "neutral", "bad", byteArrayOf(3))
        assertEquals(listOf("joy", "neutral"), r.labels("Ann"))
        assertEquals(3, r.list("Ann").size)
        assertTrue(r.list("Bob").isEmpty())
        assertTrue(r.list("Ann").any { it.file.name == "joy-2.png" })
        assertTrue(r.list("Ann").any { it.file.name == "neutral.png" })
    }

    @Test fun resolveFallsBackToFallbackThenNeutral() {
        val r = repo()
        r.add("Ann", "neutral", "png", byteArrayOf(1)); r.add("Ann", "joy", "png", byteArrayOf(1))
        assertEquals("joy", r.resolve("Ann", "joy", "neutral")!!.label)
        assertEquals("joy", r.resolve("Ann", "anger", "joy")!!.label)
        assertEquals("neutral", r.resolve("Ann", "anger", "missing")!!.label)
        assertNull(r.resolve("Bob", "joy", "neutral"))
        val noneOfThem = repo().also { it.add("Z", "love", "png", byteArrayOf(1)) }
        assertNull(noneOfThem.resolve("Z", "anger", "neutral"))
    }

    @Test fun removeStaysInsideTheRoot() {
        val r = repo()
        val s = r.add("Ann", "joy", "png", byteArrayOf(1))
        assertTrue(r.remove(s))
        val outside = tmp.newFile("x.png")
        assertFalse(r.remove(Sprite("x", outside)))
        assertTrue(outside.exists())
    }

    @Test fun characterNamesCannotEscape() {
        val r = repo()
        r.add("../../evil", "joy", "png", byteArrayOf(1))
        assertEquals(listOf("joy"), r.labels("../../evil"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExpressionControllerTest : ChatFeaturesTestBase() {
    private fun enable(kind: ClassifierKind = ClassifierKind.KEYWORD, auto: Boolean = true) {
        cfg = cfg.copy(memory = MemorySettings(expressions = ExpressionSettings(enabled = true, classifier = kind, auto = auto)))
    }

    @Test fun replyIsClassifiedAfterGeneration() = runTest {
        enable(); reply = "I'm so happy to see you, I could cry with joy!"
        val c = controller(); c.open(ann); advanceUntilIdle()
        assertEquals("neutral", c.state.value.expression) // the greeting is classified on open, without a model call
        c.send("hi"); advanceUntilIdle()
        assertEquals("joy", c.state.value.expression)
    }

    @Test fun disabledOrManualDoesNotClassify() = runTest {
        reply = "I'm furious!"
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("hi"); advanceUntilIdle()
        assertNull(c.state.value.expression)
        enable(auto = false)
        c.send("again"); advanceUntilIdle()
        assertEquals(null, c.state.value.expression)
    }

    @Test fun llmClassifierUsesTheBackendWithoutTouchingTheChat() = runTest {
        enable(ClassifierKind.LLM)
        queuedReplies += listOf("a long and happy story", "fear")
        val c = controller()
        c.open(ann); advanceUntilIdle(); c.send("hi"); advanceUntilIdle()
        assertEquals("fear", c.state.value.expression)
        assertEquals(listOf("hi", "a long and happy story"), c.state.value.chat!!.messages.drop(1).map { it.text })
    }

    @Test fun spriteLabelsAndSetting() = runTest {
        enable()
        val sprites = SpriteRepository(paths.sprites).also { it.add("Ann", "joy", "png", byteArrayOf(1)); it.add("Ann", "anger", "png", byteArrayOf(1)) }
        spriteRepo = sprites
        val c = controller(); c.open(ann); advanceUntilIdle()
        assertEquals(listOf("anger", "joy"), c.expressionLabelsFor())
        assertTrue(c.chooseExpression("Anger"))
        assertEquals("anger", c.state.value.expression)
        assertFalse(c.chooseExpression("love"))
        assertEquals(ExpressionLabels.DEFAULT, controllerWithoutSprites().expressionLabelsFor())
    }

    @Test fun slashCommands() = runTest {
        enable()
        spriteRepo = SpriteRepository(paths.sprites).also { it.add("Ann", "joy", "png", byteArrayOf(1)); it.add("Ann", "fear", "png", byteArrayOf(1)) }
        val c = controller(); c.open(ann); advanceUntilIdle()
        val ex = app.sillytavern.android.command.CommandExecutor(c.commandHost())
        assertEquals("fear,joy", ex.runText("/expression-list").pipe)
        assertEquals("fear", ex.runText("/sprite fear").pipe)
        assertEquals("fear", c.state.value.expression)
        assertEquals("joy", ex.runText("/emote Joy").pipe)
        assertTrue(ex.runText("/sprite love").error?.contains("Available: fear, joy") == true)
        assertEquals("fear", ex.runText("/expression-classify I am so scared, help me!").pipe)
        assertEquals("neutral", ex.runText("/expression-classify I am furious").pipe) // anger has no sprite here: fallback
    }

    private fun kotlinx.coroutines.test.TestScope.controllerWithoutSprites(): app.sillytavern.android.chat.ChatController {
        spriteRepo = null; return controller().also { it.open(ann) }
    }
}
