package app.sillytavern.android.chat

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.data.AppSettings
import app.sillytavern.android.data.BackendType
import app.sillytavern.android.prompt.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatUiState(
    val character: StoredCharacter? = null,
    val chat: Chat? = null,
    val generating: Boolean = false,
    val error: String? = null,
    /** Text produced by impersonate, to place into the input box. */
    val impersonation: String? = null,
)

/**
 * Orchestrates one open chat: persistence, send / regenerate / swipe / continue / impersonate,
 * streaming into the last message. Pure Kotlin so it is unit-testable with a fake [Backend].
 */
class ChatController(
    private val repo: ChatRepository,
    private val backendFor: (BackendType) -> Backend,
    private val settings: () -> AppSettings,
    private val apiKey: (connectionId: String) -> String?,
    private val scope: CoroutineScope,
    private val counterFor: (BackendType) -> TokenCounter = { ApproxTokenCounter },
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private var job: Job? = null

    private val chat get() = _state.value.chat
    private val messages get() = chat?.messages.orEmpty()

    fun open(character: StoredCharacter, chatFile: File? = null) {
        stop()
        val s = settings()
        val loaded = when {
            chatFile != null -> repo.load(chatFile)
            else -> repo.list(character.fileName).firstOrNull()?.let(repo::load)
                ?: createChat(character, s.activePersona.name)
        }
        _state.value = ChatUiState(character = character, chat = loaded)
    }

    fun newChat() {
        val c = _state.value.character ?: return
        stop()
        _state.value = ChatUiState(character = c, chat = createChat(c, settings().activePersona.name))
    }

    /** New chat seeded with the first greeting; alternate greetings become swipes of it (as in SillyTavern). */
    private fun createChat(c: StoredCharacter, userName: String): Chat {
        val greetings = c.card.greetings
        val chat = repo.create(c.fileName, c.card.name, userName, greetings.firstOrNull())
        if (greetings.size < 2) return chat
        val first = chat.messages.first().copy(swipes = greetings, swipeId = 0)
        return chat.copy(messages = listOf(first)).also(repo::save)
    }

    /** Chat files for the open character, newest first. */
    fun chatFiles(): List<File> = _state.value.character?.let { repo.list(it.fileName) }.orEmpty()

    fun deleteChatFile(file: File) {
        repo.delete(file)
        if (file == chat?.file) {
            val c = _state.value.character ?: return
            open(c)
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun consumeImpersonation() = _state.update { it.copy(impersonation = null) }

    fun send(text: String) {
        if (text.isBlank() || _state.value.generating) return
        val persona = settings().activePersona.name
        mutate { it + ChatMessage.user(persona, text.trim()) }
        generateNew()
    }

    /** Regenerate the last reply as a new swipe, or generate one if the last message is the user's. */
    fun regenerate() {
        if (_state.value.generating) return
        val last = messages.lastOrNull() ?: return
        if (last.isUser) generateNew() else {
            val idx = messages.lastIndex
            mutate { it.toMutableList().also { l -> l[idx] = l[idx].addSwipe("") } }
            runGeneration(GenerationMode.NORMAL, idx, appendTo = false)
        }
    }

    fun continueLast() {
        if (_state.value.generating) return
        val last = messages.lastOrNull()?.takeIf { !it.isUser } ?: return
        runGeneration(GenerationMode.CONTINUE, messages.lastIndex, appendTo = true)
    }

    fun impersonate() {
        if (_state.value.generating || messages.isEmpty()) return
        runGeneration(GenerationMode.IMPERSONATE, null, appendTo = false)
    }

    fun swipe(index: Int, delta: Int) {
        if (_state.value.generating) return
        val m = messages.getOrNull(index) ?: return
        val target = m.swipeId + delta
        if (target < 0) return
        if (target >= m.swipes.size) { if (index == messages.lastIndex && !m.isUser) regenerate(); return }
        mutate { it.toMutableList().also { l -> l[index] = m.selectSwipe(target) } }
    }

    fun edit(index: Int, text: String) {
        if (_state.value.generating) return
        mutate { it.toMutableList().also { l -> l.getOrNull(index)?.let { m -> l[index] = m.withText(text) } } }
    }

    fun delete(index: Int) {
        if (_state.value.generating) return
        mutate { it.toMutableList().also { l -> if (index in l.indices) l.removeAt(index) } }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun generateNew() {
        val c = _state.value.character ?: return
        mutate { it + ChatMessage.assistant(c.card.name, "") }
        runGeneration(GenerationMode.NORMAL, messages.lastIndex, appendTo = false)
    }

    private fun runGeneration(mode: GenerationMode, targetIndex: Int?, appendTo: Boolean) {
        val character = _state.value.character ?: return
        val s = settings()
        val profile = s.activeConnection
        if (profile == null) {
            dropEmptyTarget(targetIndex)
            _state.update { it.copy(error = "No connection configured. Add one in Settings.") }
            return
        }
        val history = if (targetIndex != null && !appendTo) messages.take(targetIndex) else messages
        val prefix = if (appendTo && targetIndex != null) messages[targetIndex].text else ""
        val input = PromptInput(
            card = character.card, persona = s.activePersona, preset = s.activePreset,
            history = history, maxContextTokens = profile.sampler.maxContextTokens,
            maxResponseTokens = profile.sampler.maxResponseTokens,
            counter = counterFor(profile.type), mode = mode,
        )
        val prompt = PromptBuilder.build(input)
        val req = GenerationRequest(
            profile, apiKey(profile.id), prompt,
            PromptBuilder.toTextPrompt(prompt, s.activePersona.name, character.card.name, mode),
        )
        _state.update { it.copy(generating = true, error = null) }
        job = scope.launch {
            val acc = StringBuilder(prefix)
            var failure: String? = null
            try {
                backendFor(profile.type).generate(req).collect { delta ->
                    acc.append(delta)
                    if (targetIndex != null) setTargetText(targetIndex, acc.toString())
                    else _state.update { it.copy(impersonation = acc.toString().trimStart()) }
                }
            } catch (e: CancellationException) {
                // keep partial output
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                withContext(NonCancellable) {
                    if (targetIndex != null) {
                        if (messages.getOrNull(targetIndex)?.text.isNullOrEmpty()) dropEmptyTarget(targetIndex)
                        else setTargetText(targetIndex, messages[targetIndex].text.trimEnd())
                    }
                    persist()
                    _state.update { it.copy(generating = false, error = failure) }
                }
            }
        }
    }

    private fun setTargetText(index: Int, text: String) = _state.update { st ->
        val c = st.chat ?: return@update st
        if (index !in c.messages.indices) return@update st
        st.copy(chat = c.copy(messages = c.messages.toMutableList().also { it[index] = it[index].withText(text) }))
    }

    /** Remove an empty placeholder message (or an empty extra swipe) left by a failed/cancelled generation. */
    private fun dropEmptyTarget(index: Int?) {
        if (index == null) return
        val m = messages.getOrNull(index) ?: return
        if (m.text.isNotEmpty()) return
        mutate { l ->
            l.toMutableList().also {
                if (m.swipes.size > 1) it[index] = m.copy(swipes = m.swipes.dropLast(1)).selectSwipe(m.swipeId - 1)
                else it.removeAt(index)
            }
        }
    }

    private fun mutate(f: (List<ChatMessage>) -> List<ChatMessage>) {
        _state.update { st -> st.chat?.let { st.copy(chat = it.copy(messages = f(it.messages))) } ?: st }
        persist()
    }

    private fun persist() = chat?.takeIf { it.file != null }?.let(repo::save)
}
