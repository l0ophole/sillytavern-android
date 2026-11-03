package app.sillytavern.android.data

import app.sillytavern.android.prompt.model.*
import app.sillytavern.android.worldinfo.WorldInfoSettings
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Serializable
enum class BackendType { OPENAI, ANTHROPIC, TEXT_COMPLETION, GOOGLE, OLLAMA, KOBOLD, NOVELAI, HORDE }

/** Sampler settings shared by all backends; unused fields are ignored per backend. */
@Serializable
data class SamplerSettings(
    val temperature: Double = 1.0,
    val topP: Double = 1.0,
    val topK: Int = 0,
    val minP: Double = 0.0,
    val repetitionPenalty: Double = 1.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val maxContextTokens: Int = 8192,
    val maxResponseTokens: Int = 512,
    val stopSequences: List<String> = emptyList(),
    val stream: Boolean = true,
)

/** Reasoning/thinking request options. [effort] is auto|min|low|medium|high|max; "auto" sends nothing. */
@Serializable
data class ReasoningSettings(
    val effort: String = "auto",
    val budgetTokens: Int = 0,
)

@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New connection",
    val type: BackendType = BackendType.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val sampler: SamplerSettings = SamplerSettings(),
    val reasoning: ReasoningSettings = ReasoningSettings(),
    /** [app.sillytavern.android.api.Providers] id; "custom" for an unlisted OpenAI-compatible server. */
    val provider: String = "custom",
)

@Serializable
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "User",
    val description: String = "",
    /** File name inside `User Avatars/`, or empty. */
    val avatar: String = "",
    /** Description position: 0 in prompt, 2 top of Author's Note, 3 bottom of Author's Note, 4 at depth, 5 not sent. */
    val position: Int = 0,
    val depth: Int = 2,
    val role: Int = 0,
    val title: String = "",
    /** Lorebook (world book name) attached to the persona. */
    val lorebook: String = "",
    /** Character file names this persona is locked to (SillyTavern persona connections). */
    val characters: List<String> = emptyList(),
)

/** Defaults for new chats' Author's Note (SillyTavern `extension_settings.note`). */
@Serializable
data class NoteSettings(
    val defaultPosition: Int = 1,
    val defaultDepth: Int = 4,
    val defaultInterval: Int = 1,
    val defaultRole: Int = 0,
)

/** A character's own note; used when [useChara]. [position]: 0 replace, 1 before, 2 after the chat note. */
@Serializable
data class CharacterNote(val prompt: String = "", val useChara: Boolean = false, val position: Int = 0)

/** Summarize extension settings (main-API source only). [position]: 0 in prompt, 1 in chat at [depth], 2 before prompt, -1 not injected. */
@Serializable
data class SummarySettings(
    val prompt: String = app.sillytavern.android.chat.Summary.DEFAULT_PROMPT,
    val template: String = app.sillytavern.android.chat.Summary.DEFAULT_TEMPLATE,
    val position: Int = 0,
    val role: Int = 0,
    val depth: Int = 2,
    val promptWords: Int = 200,
    /** Summarize automatically after this many chat lines; only when [auto]. */
    val interval: Int = 10,
    val auto: Boolean = false,
    val frozen: Boolean = false,
)

@Serializable
data class PromptPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val mainPrompt: String =
        "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.",
    val jailbreak: String = "",
    val nsfwPrompt: String = "",
)

@Serializable
data class AppSettings(
    val connections: List<ConnectionProfile> = emptyList(),
    val activeConnectionId: String? = null,
    val personas: List<Persona> = listOf(Persona()),
    val activePersonaId: String? = null,
    /** Persona used for chats that are not locked (SillyTavern's default persona). */
    val defaultPersonaId: String? = null,
    /** Lock the chat to a persona whenever it is selected manually. */
    val personaAutoLock: Boolean = false,
    val presets: List<PromptPreset> = listOf(PromptPreset()),
    val activePresetId: String? = null,
    // --- Prompt engine (feature set 2); presets are identified by name, as in SillyTavern ---
    val promptManagerPresets: List<PromptManagerPreset> = listOf(DefaultPresets.promptManager()),
    val activePromptManagerName: String? = null,
    val instructEnabled: Boolean = false,
    val instructTemplates: List<InstructTemplate> = DefaultPresets.instructTemplates(),
    val activeInstructName: String? = null,
    val contextTemplates: List<ContextTemplate> = DefaultPresets.contextTemplates(),
    val activeContextName: String? = null,
    val systemPrompts: List<SystemPromptPreset> = DefaultPresets.systemPrompts(),
    val activeSystemPromptName: String? = null,
    val samplerPresets: List<TextGenSamplerPreset> = DefaultPresets.samplerPresets(),
    val activeSamplerPresetName: String? = null,
    // --- World Info (feature set 3) ---
    val worldInfo: WorldInfoSettings = WorldInfoSettings(),
    val globalWorldBooks: List<String> = emptyList(),
    /** Extra books per character file name (SillyTavern's `charLore`). */
    val characterExtraBooks: Map<String, List<String>> = emptyMap(),
    // --- Macros + commands (feature set 5) ---
    val globalVariables: Map<String, String> = emptyMap(),
    val regexEnabled: Boolean = true,
    val quickReplies: app.sillytavern.android.quickreply.QuickReplySettings = app.sillytavern.android.quickreply.QuickReplySettings(),
    // --- Groups + tags (feature set 6) ---
    val tags: List<app.sillytavern.android.tags.Tag> = emptyList(),
    @kotlinx.serialization.SerialName("tag_map") val tagMap: Map<String, List<String>> = emptyMap(),
    // --- Chat features (feature set 7) ---
    val note: NoteSettings = NoteSettings(),
    /** Character notes by character file name (SillyTavern `note.chara`). */
    val characterNotes: Map<String, CharacterNote> = emptyMap(),
    val summary: SummarySettings = SummarySettings(),
) {
    val tagState get() = app.sillytavern.android.tags.TagState(tags, tagMap)
    val activeConnection get() = connections.firstOrNull { it.id == activeConnectionId } ?: connections.firstOrNull()
    val activePersona get() = personas.firstOrNull { it.id == activePersonaId } ?: personas.first()
    val activePreset get() = presets.firstOrNull { it.id == activePresetId } ?: presets.firstOrNull() ?: PromptPreset()
    val activePromptManager get() = promptManagerPresets.firstOrNull { it.name == activePromptManagerName }
        ?: promptManagerPresets.firstOrNull() ?: DefaultPresets.promptManager()
    /** Null when instruct mode is off. */
    val activeInstruct get() = if (!instructEnabled) null
        else instructTemplates.firstOrNull { it.name == activeInstructName } ?: instructTemplates.firstOrNull()
    val activeContext get() = contextTemplates.firstOrNull { it.name == activeContextName }
        ?: contextTemplates.firstOrNull() ?: ContextTemplate()
    val activeSystemPrompt get() = systemPrompts.firstOrNull { it.name == activeSystemPromptName } ?: systemPrompts.firstOrNull()
    val activeSamplerPreset get() = samplerPresets.firstOrNull { it.name == activeSamplerPresetName }
        ?: samplerPresets.firstOrNull() ?: TextGenSamplerPreset()
}

/** JSON-file backed settings with an observable StateFlow. */
class SettingsRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        file.writeAtomic(json.encodeToString(AppSettings.serializer(), next))
        state.value = next
    }

    private fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        val text = file.readText()
        val loaded = runCatching { json.decodeFromString(AppSettings.serializer(), text) }.getOrDefault(AppSettings())
        // Settings written before the prompt engine only had MVP presets: carry their text over.
        val legacyOnly = runCatching {
            val o = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            o.containsKey("presets") && !o.containsKey("promptManagerPresets")
        }.getOrDefault(false)
        if (!legacyOnly || loaded.presets.isEmpty()) return loaded
        val migrated = loaded.presets.map(DefaultPresets::fromLegacy)
        return loaded.copy(
            promptManagerPresets = migrated,
            activePromptManagerName = loaded.presets.firstOrNull { it.id == loaded.activePresetId }?.name,
        )
    }
}
