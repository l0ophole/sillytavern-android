package app.sillytavern.android.media

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.api.usesTextPrompt
import app.sillytavern.android.data.ConnectionProfile
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import kotlinx.coroutines.flow.toList

/** Image captioning through the active (vision-capable) chat connection. */
object Captioner {
    /** Text-completion backends have no image input. */
    fun supported(profile: ConnectionProfile) = !profile.type.usesTextPrompt

    fun request(profile: ConnectionProfile, apiKey: String?, prompt: String, imageDataUrl: String): GenerationRequest =
        GenerationRequest(
            profile.copy(sampler = profile.sampler.copy(stream = false, maxResponseTokens = profile.sampler.maxResponseTokens.coerceAtLeast(300))),
            apiKey,
            listOf(PromptMessage(Role.USER, prompt, images = listOf(imageDataUrl))),
            "",
            stopSequences = emptyList(),
        )

    suspend fun caption(backend: Backend, req: GenerationRequest): String =
        backend.generate(req).toList().joinToString("").trim()
}
