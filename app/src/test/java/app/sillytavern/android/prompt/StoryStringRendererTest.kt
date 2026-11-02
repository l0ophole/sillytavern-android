package app.sillytavern.android.prompt

import app.sillytavern.android.prompt.model.ContextTemplate
import app.sillytavern.android.prompt.model.PresetJson
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryStringRendererTest {
    @Test fun ifBlockOmittedWhenBlank() =
        assertEquals("x", StoryStringRenderer.render("{{#if a}}A{{a}}\n{{/if}}x", mapOf("a" to " ")))

    @Test fun ifBlockKeptWhenSet() =
        assertEquals("A:1\nx", StoryStringRenderer.render("{{#if a}}A:{{a}}\n{{/if}}x", mapOf("a" to "1")))

    @Test fun trimEatsSurroundingNewlines() =
        assertEquals("ab", StoryStringRenderer.render("a\n\n{{trim}}\n\nb", emptyMap()))

    @Test fun unknownVarIsEmpty() = assertEquals("[]", StoryStringRenderer.render("[{{nope}}]", emptyMap()))

    @Test fun nestedIfs() {
        val t = "{{#if a}}1{{#if b}}2{{/if}}3{{/if}}"
        assertEquals("123", StoryStringRenderer.render(t, mapOf("a" to "x", "b" to "y")))
        assertEquals("13", StoryStringRenderer.render(t, mapOf("a" to "x")))
        assertEquals("", StoryStringRenderer.render(t, mapOf("b" to "y")))
    }

    @Test fun unbalancedTagsDoNotThrow() {
        assertEquals("a{{/if}}b", StoryStringRenderer.render("a{{/if}}b", emptyMap()))
        assertEquals("a{{#if x}}b", StoryStringRenderer.render("a{{#if x}}b", mapOf("x" to "1")))
    }

    @Test fun chatMlDefaultStoryString() {
        val c = PresetJson.decode(ContextTemplate.serializer(), javaClass.getResource("/presets/context-ChatML.json")!!.readText())
        assertEquals("D\nP", StoryStringRenderer.render(c.storyString, mapOf("description" to "D", "persona" to "P")))
    }
}
