package app.sillytavern.android.data

import app.sillytavern.android.prompt.model.PresetJson
import app.sillytavern.android.prompt.model.PromptManagerPreset
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SettingsMigrationTest {
    private fun res(n: String) = javaClass.getResource("/presets/$n")!!.readText()

    @Test fun legacySettingsMigrates() {
        val f = Files.createTempFile("settings", ".json").toFile()
        f.writeText("""{"presets":[{"id":"p1","name":"Mine","mainPrompt":"Be {{char}}","jailbreak":"JB","nsfwPrompt":"AUX"}],"activePresetId":"p1"}""")
        val s = SettingsRepository(f).settings.value
        val pm = s.activePromptManager
        assertEquals("Mine", pm.name)
        assertEquals("Be {{char}}", pm.prompts.first { it.identifier == "main" }.content)
        assertEquals("JB", pm.prompts.first { it.identifier == "jailbreak" }.content)
        assertEquals("AUX", pm.prompts.first { it.identifier == "nsfw" }.content)
    }

    @Test fun freshSettingsDoNotMigrateOverNewPresets() {
        val f = Files.createTempFile("settings", ".json").toFile()
        val repo = SettingsRepository(f)
        repo.update { it.copy(promptManagerPresets = listOf(PromptManagerPreset(name = "Custom"))) }
        assertEquals("Custom", SettingsRepository(f).settings.value.activePromptManager.name)
    }

    @Test fun defaultPromptManagerMatchesStOrder() {
        val st = PresetJson.decodePromptManager(res("openai-Default.json"))
        // ST's global order (character_id 100001) lists personaDescription; ours must match identifier-for-identifier on enabled flags.
        assertEquals(st.order.map { it.identifier to it.enabled }.toSet(), DefaultPresets.promptManager().order.map { it.identifier to it.enabled }.toSet())
    }

    @Test fun bundledPresetsLoad() {
        assertTrue(DefaultPresets.instructTemplates().any { it.name == "ChatML" })
        assertTrue(DefaultPresets.contextTemplates().isNotEmpty())
        assertTrue(DefaultPresets.systemPrompts().isNotEmpty())
        assertTrue(DefaultPresets.samplerPresets().any { it.name == "Universal-Creative" })
    }

    @Test fun oldSettingsGetWorldInfoDefaults() {
        val f = Files.createTempFile("settings", ".json").toFile()
        f.writeText("""{"activeInstructName":"x"}""")
        val s = SettingsRepository(f).settings.value
        assertEquals(25, s.worldInfo.budget); assertEquals(2, s.worldInfo.depth); assertTrue(s.globalWorldBooks.isEmpty())
    }
}
