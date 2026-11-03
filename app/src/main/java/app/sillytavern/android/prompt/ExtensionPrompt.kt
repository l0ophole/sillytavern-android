package app.sillytavern.android.prompt

/**
 * A prompt contributed by a feature (Author's Note, summary, persona at depth), SillyTavern's "extension prompt".
 * [position]: 0 = in prompt (after the main prompt), 1 = in chat at [depth], 2 = before the main prompt. [role]: 0 system, 1 user, 2 assistant.
 */
data class ExtensionPrompt(
    val id: String,
    val content: String,
    val position: Int = IN_PROMPT,
    val depth: Int = 0,
    val role: Int = 0,
) {
    companion object {
        const val IN_PROMPT = 0
        const val IN_CHAT = 1
        const val BEFORE_PROMPT = 2
    }
}
