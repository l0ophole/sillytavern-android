package app.sillytavern.android.chat

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.Chunk
import app.sillytavern.android.api.ChunkBackend
import app.sillytavern.android.api.EmptyToolRegistry
import app.sillytavern.android.api.ToolLoop
import app.sillytavern.android.api.ToolRegistry
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.api.usesTextPrompt
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.data.AppSettings
import app.sillytavern.android.data.BackendType
import app.sillytavern.android.macro.VariableStore
import kotlinx.serialization.json.JsonObject
import app.sillytavern.android.prompt.*
import app.sillytavern.android.worldinfo.*
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
    private val worlds: WorldBookRepository? = null,
    private val toolRegistry: ToolRegistry = EmptyToolRegistry,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit = {},
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

    /** Lorebook bound to the open chat (stored in the chat header, as SillyTavern does), or null. */
    fun chatBook(): String? = WorldInfoRunner.chatBookOf(chat)

    fun setChatBook(name: String?) {
        val cur = chat ?: return
        _state.update { it.copy(chat = WorldInfoRunner.withChatBook(cur, name)) }
        persist()
    }

    /** Variables for macros/commands: local ones live in the chat header, global ones in settings. */
    fun variableStore(): VariableStore = VariableStore(
        getLocal = { (chat?.header?.get("chat_metadata") as? JsonObject)?.get("variables") as? JsonObject ?: JsonObject(emptyMap()) },
        setLocal = { v ->
            _state.update { st ->
                val c = st.chat ?: return@update st
                val meta = c.header["chat_metadata"] as? JsonObject ?: JsonObject(emptyMap())
                st.copy(chat = c.copy(header = JsonObject(c.header + ("chat_metadata" to JsonObject(meta + ("variables" to v))))))
            }
            persist()
        },
        getGlobal = { settings().globalVariables },
        setGlobal = { g -> updateSettings { it.copy(globalVariables = g) } },
    )

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
            runGeneration(GenerationMode.NORMAL, idx, appendTo = false, trigger = "regenerate")
        }
    }

    fun continueLast() {
        if (_state.value.generating) return
        val last = messages.lastOrNull()?.takeIf { !it.isUser } ?: return
        runGeneration(GenerationMode.CONTINUE, messages.lastIndex, appendTo = true, trigger = "continue")
    }

    fun impersonate() {
        if (_state.value.generating || messages.isEmpty()) return
        runGeneration(GenerationMode.IMPERSONATE, null, appendTo = false, trigger = "impersonate")
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

    private fun runGeneration(mode: GenerationMode, targetIndex: Int?, appendTo: Boolean, trigger: String = "normal") {
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
        val textMode = profile.type.usesTextPrompt
        val input = PromptInput(
            card = character.card, persona = s.activePersona, preset = s.activePreset,
            history = history, maxContextTokens = profile.sampler.maxContextTokens,
            maxResponseTokens = profile.sampler.maxResponseTokens,
            counter = counterFor(profile.type), mode = mode,
            promptManager = s.activePromptManager,
            // System-prompt presets belong to text completion; chat completion uses the preset's own main prompt.
            systemPrompt = if (textMode) s.activeSystemPrompt else null,
            vars = variableStore(),
        )
        // Re-rolls of an existing reply must not advance sticky/cooldown timers.
        val dryRun = trigger != "normal"
        val worldInfoInput = worlds?.let { w ->
            val entries = WorldInfoSources.resolve(character.card, character.fileName, WorldInfoRunner.chatBookOf(chat), s, w)
            val (prompt, timed) = WorldInfoRunner.run(entries, history, input, s.worldInfo, trigger, WorldInfoRunner.timedOf(chat), dryRun)
            if (!dryRun && entries.isNotEmpty()) _state.update { st -> st.copy(chat = st.chat?.let { WorldInfoRunner.withTimed(it, timed) }) }
            input.copy(worldInfo = prompt)
        } ?: input
        val req = if (textMode) {
            val formatted = PromptBuilder.buildText(worldInfoInput, s.activeInstruct, s.activeContext)
            GenerationRequest(
                profile, apiKey(profile.id), emptyList(), formatted.text,
                stopSequences = (profile.sampler.stopSequences + formatted.stopStrings).distinct(),
                sampler = s.activeSamplerPreset,
            )
        } else GenerationRequest(profile, apiKey(profile.id), PromptBuilder.build(worldInfoInput), "")
        _state.update { it.copy(generating = true, error = null) }
        job = scope.launch {
            val acc = StringBuilder(prefix)
            val thinking = StringBuilder(if (appendTo && targetIndex != null) messages[targetIndex].reasoning.orEmpty() else "")
            var failure: String? = null
            // A fresh reply starts without the previous reply's reasoning (continue keeps it).
            if (targetIndex != null && !appendTo) setTargetReasoning(targetIndex, null)
            try {
                val backend = backendFor(profile.type)
                val stream: Flow<Chunk> = if (backend is ChunkBackend) ToolLoop.run(backend, req, toolRegistry)
                else backend.generate(req).map { Chunk.Text(it) }
                stream.collect { chunk ->
                    when (chunk) {
                        is Chunk.Text -> {
                            acc.append(chunk.text)
                            if (targetIndex != null) setTargetText(targetIndex, acc.toString())
                            else _state.update { it.copy(impersonation = acc.toString().trimStart()) }
                        }
                        is Chunk.Reasoning -> {
                            thinking.append(chunk.text)
                            if (targetIndex != null) setTargetReasoning(targetIndex, thinking.toString())
                        }
                        is Chunk.ToolCall -> {} // ToolLoop consumes tool calls; without a registry they are ignored
                    }
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

    private fun setTargetReasoning(index: Int, text: String?) = _state.update { st ->
        val c = st.chat ?: return@update st
        if (index !in c.messages.indices) return@update st
        st.copy(chat = c.copy(messages = c.messages.toMutableList().also { it[index] = it[index].withReasoning(text) }))
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
