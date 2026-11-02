package app.sillytavern.android.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PresetIoTest {
    private fun res(n: String) = javaClass.getResource("/presets/$n")!!.readText()
    private fun repo() = SettingsRepository(Files.createTempFile("s", ".json").toFile())

    private val fixtures = listOf(
        Triple(PresetKind.INSTRUCT, "instruct-ChatML.json", "ChatML"),
        Triple(PresetKind.CONTEXT, "context-ChatML.json", "ChatML"),
        Triple(PresetKind.SYSTEM_PROMPT, "sysprompt-Roleplay-Simple.json", "Roleplay - Simple"),
        Triple(PresetKind.PROMPT_MANAGER, "openai-Default.json", "openai-Default"),
        Triple(PresetKind.SAMPLER, "textgen-Universal-Creative.json", "textgen-Universal-Creative"),
    )

    @Test fun importCreatesPresetWithFilesNameOrFileName() {
        for ((kind, file, expected) in fixtures) {
            val r = repo()
            val name = PresetIo.importPreset(r, kind, file, res(file)).getOrThrow()
            assertEquals(kind.name, expected, name)
        }
    }

    @Test fun exportThenImportRoundTrips() {
        for ((kind, file, _) in fixtures) {
            val a = repo()
            val name = PresetIo.importPreset(a, kind, file, res(file)).getOrThrow()
            val text = PresetIo.exportPreset(a.settings.value, kind, name)!!
            val b = repo()
            assertEquals(name, PresetIo.importPreset(b, kind, "$name.json", text).getOrThrow())
            assertEquals(kind.name, text, PresetIo.exportPreset(b.settings.value, kind, name))
        }
    }

    @Test fun malformedFileFailsAndLeavesSettingsUnchanged() {
        val r = repo(); val before = r.settings.value
        val res = PresetIo.importPreset(r, PresetKind.INSTRUCT, "bad.json", "{not json")
        assertTrue(res.isFailure)
        assertEquals(before, r.settings.value)
    }

    @Test fun wrongKindIsRejected() {
        val r = repo(); val before = r.settings.value
        assertTrue(PresetIo.importPreset(r, PresetKind.SAMPLER, "x.json", res("instruct-ChatML.json")).isFailure)
        assertEquals(before, r.settings.value)
    }

    @Test fun importReplacesSameNameInsteadOfDuplicating() {
        val r = repo()
        PresetIo.importPreset(r, PresetKind.INSTRUCT, "a.json", res("instruct-ChatML.json"))
        PresetIo.importPreset(r, PresetKind.INSTRUCT, "a.json", res("instruct-ChatML.json"))
        assertEquals(1, r.settings.value.instructTemplates.count { it.name == "ChatML" })
    }
}
