package app.sillytavern.android.prompt

import app.sillytavern.android.prompt.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PresetJsonTest {
    private fun res(name: String) = javaClass.getResource("/presets/$name")!!.readText()

    @Test fun instructChatMlDecodes() {
        val t = PresetJson.decode(InstructTemplate.serializer(), res("instruct-ChatML.json"))
        assertEquals("<|im_start|>user", t.inputSequence)
        assertEquals("force", t.namesBehavior)
        assertEquals("<|im_end|>\n", t.outputSuffix)
    }

    @Test fun contextChatMlDecodes() {
        val c = PresetJson.decode(ContextTemplate.serializer(), res("context-ChatML.json"))
        assertTrue(c.storyString.contains("{{#if description}}"))
    }

    @Test fun sysPromptDecodes() {
        val s = PresetJson.decode(SystemPromptPreset.serializer(), res("sysprompt-Roleplay-Simple.json"))
        assertEquals("Roleplay - Simple", s.name)
        assertTrue(s.content.startsWith("You're {{char}}"))
    }

    @Test fun openAiPresetPicksOrder100001() {
        val p = PresetJson.decodePromptManager(res("openai-Default.json"))
        assertEquals("main", p.order.first().identifier)
        assertTrue(p.order.any { it.identifier == "chatHistory" })
        assertTrue(p.prompts.first { it.identifier == "chatHistory" }.marker)
    }

    @Test fun textGenDecodes() {
        val s = PresetJson.decode(TextGenSamplerPreset.serializer(), res("textgen-Universal-Creative.json"))
        assertEquals(0.1, s.minP, 0.0)
        assertEquals(1.5, s.temp, 0.0)
    }

    @Test fun unknownKeysSurviveRoundTrip() {
        val original = Json.parseToJsonElement(res("openai-Default.json")).jsonObject
        val back = Json.parseToJsonElement(
            PresetJson.encodePromptManager(PresetJson.decodePromptManager(res("openai-Default.json")))
        ).jsonObject
        for ((k, v) in original) {
            assertTrue("missing $k", back.containsKey(k))
            if (k != "prompts") assertEquals("value of $k", v, back[k])
        }
        assertEquals(original["prompts"]!!.jsonArray.size, back["prompts"]!!.jsonArray.size)
        assertFalse(back.containsKey("order"))
    }

    @Test fun genericRoundTripKeepsExtras() {
        val text = res("instruct-ChatML.json").replace("\"name\"", "\"future_field\": 7, \"name\"")
        val back = Json.parseToJsonElement(
            PresetJson.encode(InstructTemplate.serializer(), PresetJson.decode(InstructTemplate.serializer(), text))
        ).jsonObject
        assertEquals(7, back["future_field"]!!.jsonPrimitive.int)
        assertFalse(back.containsKey("extra"))
    }
}
