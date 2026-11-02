package app.sillytavern.android.data

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Serializable
enum class BackendType { OPENAI, ANTHROPIC, TEXT_COMPLETION }

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

@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New connection",
    val type: BackendType = BackendType.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val sampler: SamplerSettings = SamplerSettings(),
)

@Serializable
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "User",
    val description: String = "",
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
    val presets: List<PromptPreset> = listOf(PromptPreset()),
    val activePresetId: String? = null,
) {
    val activeConnection get() = connections.firstOrNull { it.id == activeConnectionId } ?: connections.firstOrNull()
    val activePersona get() = personas.firstOrNull { it.id == activePersonaId } ?: personas.first()
    val activePreset get() = presets.firstOrNull { it.id == activePresetId } ?: presets.first()
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

    private fun load(): AppSettings =
        if (file.exists()) {
            runCatching { json.decodeFromString(AppSettings.serializer(), file.readText()) }
                .getOrDefault(AppSettings())
        } else AppSettings()
}
