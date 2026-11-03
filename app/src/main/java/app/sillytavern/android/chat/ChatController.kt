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
import app.sillytavern.android.command.*
import app.sillytavern.android.macro.VariableStore
import app.sillytavern.android.quickreply.QuickReplyService
import app.sillytavern.android.regex.*
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
    /** Transient message from `/echo` for the UI to show once. */
    val notice: String? = null,
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
    /** Global + character-scoped regex scripts for the open chat. */
    private val regex: (app.sillytavern.android.card.CharacterCard?) -> List<RegexScript> = { emptyList() },
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
        fireAuto(QuickReplyService.Trigger.CHAT_CHANGE)
    }

    fun newChat() {
        val c = _state.value.character ?: return
        stop()
        _state.value = ChatUiState(character = c, chat = createChat(c, settings().activePersona.name))
        fireAuto(QuickReplyService.Trigger.NEW_CHAT)
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

    /** Set after construction (the service reads this controller's chat metadata). */
    var quickReplies: QuickReplyService? = null
    private var autoRunning = false

    private fun chatMeta(): JsonObject = chat?.header?.get("chat_metadata") as? JsonObject ?: JsonObject(emptyMap())

    private fun setChatMeta(key: String, value: kotlinx.serialization.json.JsonElement?) {
        _state.update { st ->
            val c = st.chat ?: return@update st
            val meta = JsonObject(chatMeta().toMutableMap().also { if (value == null) it.remove(key) else it[key] = value })
            st.copy(chat = c.copy(header = JsonObject(c.header + ("chat_metadata" to meta))))
        }
        persist()
    }

    /** Name of the Quick Reply set bound to this chat (chat_metadata.quickReply.set). */
    fun chatQuickReplySet(): String? =
        ((chatMeta()["quickReply"] as? JsonObject)?.get("set") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    fun setChatQuickReplySet(name: String?) = setChatMeta("quickReply",
        name?.let { JsonObject(mapOf("set" to kotlinx.serialization.json.JsonPrimitive(it))) })

    fun characterFileName(): String? = _state.value.character?.fileName

    fun automationIds(): Set<String> = buildSet {
        (chatMeta()["automationId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let(::add)
        ((_state.value.character?.card?.raw?.get("extensions") as? JsonObject)?.get("automation_id") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.takeIf { it.isNotBlank() }?.let(::add)
    }

    /** Runs Quick Replies flagged for [trigger]; slash scripts only, never re-entrant. */
    fun fireAuto(trigger: QuickReplyService.Trigger) {
        val svc = quickReplies ?: return
        if (autoRunning) return
        val list = svc.autoExecutable(trigger).filter { it.second.message.trimStart().startsWith("/") }
        if (list.isEmpty()) return
        autoRunning = true
        scope.launch {
            try {
                for ((set, qr) in list) {
                    val o = svc.run(set, qr, "", CommandExecutor(host), { Macros.expand(it, host.macroContext()) }, isAutoExecute = true)
                    o.error?.let { e -> _state.update { it.copy(error = e) } }
                }
            } finally { autoRunning = false }
        }
    }

    fun runStartupQuickReplies() = fireAuto(QuickReplyService.Trigger.STARTUP)

    fun clearError() = _state.update { it.copy(error = null) }
    fun consumeImpersonation() = _state.update { it.copy(impersonation = null) }

    private fun scripts(): List<RegexScript> = if (settings().regexEnabled) regex(_state.value.character?.card) else emptyList()

    private fun placementOf(m: ChatMessage) = if (m.isUser) Placement.USER_INPUT else Placement.AI_OUTPUT

    private fun regexed(text: String, placement: Int, params: RegexParams): String {
        val sc = scripts()
        if (sc.isEmpty() || text.isEmpty()) return text
        return RegexEngine.apply(text, placement, sc, params.copy(macros = { Macros.expand(it, host.macroContext()) }))
    }

    /** Text as shown in the chat bubble: markdown-only scripts applied, stored text untouched. */
    fun displayText(m: ChatMessage): String = regexed(m.text, placementOf(m), RegexParams(isMarkdown = true))

    /** Reasoning text as shown (REASONING placement). */
    fun displayReasoning(text: String): String = regexed(text, Placement.REASONING, RegexParams(isMarkdown = true))

    /** Entry point for the input box: `/commands` run through the command engine, anything else is sent as chat. */
    fun submit(text: String) {
        if (!CommandParser.isCommand(text)) { send(text); return }
        scope.launch {
            val r = CommandExecutor(host).runText(text.trim())
            if (r.error != null) _state.update { it.copy(error = r.error) }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private val host = ControllerHost()

    private inner class ControllerHost : CommandHost {
        override val variables get() = variableStore()
        override val quickReplies get() = this@ChatController.quickReplies
        override suspend fun runStoredScript(name: String, executor: CommandExecutor): String? = this@ChatController.quickReplies?.runStored(name, executor)
        override fun macroContext(): MacroContext {
            val s = settings(); val c = _state.value.character
            return MacroContext(
                user = s.activePersona.name, char = c?.card?.name.orEmpty(),
                description = c?.card?.description.orEmpty(), personality = c?.card?.personality.orEmpty(),
                scenario = c?.card?.scenario.orEmpty(), persona = s.activePersona.description,
                chat = ChatMacroView(messages), vars = variableStore(),
            )
        }
        override suspend fun echo(text: String, severity: String) { _state.update { it.copy(notice = text) } }
        override suspend fun sendUser(text: String) { if (text.isNotBlank()) mutate { it + ChatMessage.user(settings().activePersona.name, text.trim()) } }
        override suspend fun addSystemMessage(text: String) { mutate { it + ChatMessage.narrator(text) } }
        override suspend fun addMessageAs(name: String, text: String) { mutate { it + ChatMessage.assistant(name, text) } }
        override suspend fun generateReply() { waitIdle(); generateNew(); job?.join() }
        override suspend fun continueLast() { waitIdle(); this@ChatController.continueLast(); job?.join() }
        override suspend fun regenerate() { waitIdle(); this@ChatController.regenerate(); job?.join() }
        override suspend fun swipeBy(delta: Int) { if (messages.isNotEmpty()) swipe(messages.lastIndex, delta); job?.join() }
        override suspend fun impersonate(): String {
            waitIdle(); this@ChatController.impersonate(); job?.join()
            return _state.value.impersonation.orEmpty()
        }
        override suspend fun deleteMessages(range: IntRange) {
            mutate { l -> l.filterIndexed { i, _ -> i !in range } }
        }
        override suspend fun hideMessages(range: IntRange, hidden: Boolean) {
            mutate { l -> l.mapIndexed { i, m -> if (i in range) m.withHidden(hidden) else m } }
        }
        override suspend fun setInput(text: String) { _state.update { it.copy(impersonation = text) } }
        override fun messageCount() = messages.size
        override fun chatText(range: IntRange?, names: Boolean): String =
            messages.filterIndexed { i, _ -> range == null || i in range }
                .joinToString("\n") { if (names) "${it.name}: ${it.text}" else it.text }
        override suspend fun generateQuiet(prompt: String, useHistory: Boolean): String = quietGenerate(prompt, useHistory)
        override suspend fun newChat() { this@ChatController.newChat() }
        override suspend fun renameChat(name: String) {
            val cur = chat ?: return; val f = cur.file ?: return
            if (name.isBlank()) throw CommandError("Name required")
            persist()
            val nf = repo.rename(f, name)
            _state.update { st -> st.chat?.let { st.copy(chat = it.copy(file = nf)) } ?: st }
        }
        override fun chatName() = chat?.file?.nameWithoutExtension.orEmpty()
        override suspend fun setChatBook(name: String?) { this@ChatController.setChatBook(name) }
        override suspend fun switchPersona(name: String): Boolean {
            val p = settings().personas.firstOrNull { it.name.equals(name, true) } ?: return false
            updateSettings { it.copy(activePersonaId = p.id) }
            return true
        }
        private suspend fun waitIdle() { job?.join() }
    }

    /** One-off generation that is not added to the chat (used by /gen and /genraw). */
    private suspend fun quietGenerate(prompt: String, useHistory: Boolean): String {
        val character = _state.value.character ?: throw CommandError("This command needs an open chat")
        val s = settings()
        val profile = s.activeConnection ?: throw CommandError("No connection configured. Add one in Settings.")
        val persona = s.activePersona
        val history = (if (useHistory) messages.filter { it.inPrompt } else emptyList()) + ChatMessage.user(persona.name, prompt)
        val input = PromptInput(
            card = character.card, persona = persona, preset = s.activePreset, history = history,
            maxContextTokens = profile.sampler.maxContextTokens, maxResponseTokens = profile.sampler.maxResponseTokens,
            counter = counterFor(profile.type), promptManager = s.activePromptManager,
            systemPrompt = if (profile.type.usesTextPrompt) s.activeSystemPrompt else null, vars = variableStore(), regexScripts = scripts(),
        )
        val req = makeRequest(input, profile, s)
        val backend = backendFor(profile.type)
        val out = StringBuilder()
        val stream: Flow<Chunk> = if (backend is ChunkBackend) ToolLoop.run(backend, req, toolRegistry)
        else backend.generate(req).map { Chunk.Text(it) }
        stream.collect { if (it is Chunk.Text) out.append(it.text) }
        return out.toString().trim()
    }

    private fun makeRequest(input: PromptInput, profile: app.sillytavern.android.data.ConnectionProfile, s: AppSettings): GenerationRequest =
        if (profile.type.usesTextPrompt) {
            val formatted = PromptBuilder.buildText(input, s.activeInstruct, s.activeContext)
            GenerationRequest(
                profile, apiKey(profile.id), emptyList(), formatted.text,
                stopSequences = (profile.sampler.stopSequences + formatted.stopStrings).distinct(),
                sampler = s.activeSamplerPreset,
            )
        } else GenerationRequest(profile, apiKey(profile.id), PromptBuilder.build(input), "")

    fun send(text: String) {
        if (text.isBlank() || _state.value.generating) return
        val persona = settings().activePersona.name
        mutate { it + ChatMessage.user(persona, regexed(text.trim(), Placement.USER_INPUT, RegexParams()).ifBlank { text.trim() }) }
        fireAuto(QuickReplyService.Trigger.USER)
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
        mutate { it.toMutableList().also { l -> l.getOrNull(index)?.let { m -> l[index] = m.withText(regexed(text, placementOf(m), RegexParams(isEdit = true))) } } }
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
            vars = variableStore(), regexScripts = scripts(),
        )
        // Re-rolls of an existing reply must not advance sticky/cooldown timers.
        val dryRun = trigger != "normal"
        val worldInfoInput = worlds?.let { w ->
            val entries = WorldInfoSources.resolve(character.card, character.fileName, WorldInfoRunner.chatBookOf(chat), s, w)
            val (prompt, timed) = WorldInfoRunner.run(entries, history, input, s.worldInfo, trigger, WorldInfoRunner.timedOf(chat), dryRun)
            if (!dryRun && entries.isNotEmpty()) _state.update { st -> st.copy(chat = st.chat?.let { WorldInfoRunner.withTimed(it, timed) }) }
            input.copy(worldInfo = prompt)
        } ?: input
        val req = makeRequest(worldInfoInput, profile, s)
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
                        else setTargetText(targetIndex, regexed(messages[targetIndex].text.trimEnd(), Placement.AI_OUTPUT, RegexParams()))
                    }
                    persist()
                    _state.update { it.copy(generating = false, error = failure) }
                    if (failure == null && targetIndex != null && isActive) fireAuto(QuickReplyService.Trigger.AI)
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
