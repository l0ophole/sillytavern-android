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
import app.sillytavern.android.group.*
import app.sillytavern.android.data.AppSettings
import app.sillytavern.android.data.CharacterNote
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
import kotlin.random.Random

data class ChatUiState(
    val character: StoredCharacter? = null,
    val chat: Chat? = null,
    val generating: Boolean = false,
    val error: String? = null,
    /** Text produced by impersonate, to place into the input box. */
    val impersonation: String? = null,
    /** Transient message from `/echo` for the UI to show once. */
    val notice: String? = null,
    /** Group chat: the open group, its resolvable members (group order) and the avatars still queued to speak. */
    val group: Group? = null,
    val members: List<StoredCharacter> = emptyList(),
    val groupQueue: List<String> = emptyList(),
    val autoMode: Boolean = false,
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
    private val groups: GroupRepository? = null,
    /** Looks a character up by file name (group members are stored as `<fileName>.png`). */
    private val characterLookup: (String) -> StoredCharacter? = { null },
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private var job: Job? = null

    private val chat get() = _state.value.chat
    private val messages get() = chat?.messages.orEmpty()

    fun open(character: StoredCharacter, chatFile: File? = null) {
        stop(); stopAuto()
        val existing = (chatFile ?: repo.list(character.fileName).firstOrNull())?.let(repo::load)
        applyPersonaFor(existing, character.fileName)
        val loaded = existing ?: createChat(character, settings().activePersona.name)
        _state.value = ChatUiState(character = character, chat = loaded)
        fireAuto(QuickReplyService.Trigger.CHAT_CHANGE)
    }

    fun newChat() {
        group?.let { g -> groups?.let { openGroup(it.addChat(g)) }; return }
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
    fun chatFiles(): List<File> {
        val g = group
        if (g != null) return g.chats.map { groups!!.chatFile(it) }.filter { it.exists() }
        return _state.value.character?.let { repo.list(it.fileName) }.orEmpty()
    }

    /** Opens another chat of the current character or group. */
    fun openChatFile(file: File) {
        val g = group
        if (g != null) openGroup(g, file.nameWithoutExtension) else _state.value.character?.let { open(it, file) }
    }

    fun deleteChatFile(file: File) {
        val g = group
        if (g != null) {
            val id = file.nameWithoutExtension
            file.delete()
            val left = g.chats.filter { it != id }
            val next = g.copy(chats = left, chatId = if (g.chatId == id) left.lastOrNull() ?: id else g.chatId)
            groups?.save(next)
            if (g.chatId == id) { if (left.isEmpty()) groups?.let { openGroup(it.addChat(next.copy(chats = emptyList()))) } else openGroup(next) }
            else _state.update { it.copy(group = next) }
            return
        }
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

    /** Runs a button press; the UI then sends [QuickReplyOutcome.textToSend] or fills the input with [QuickReplyOutcome.inputText]. */
    suspend fun pressQuickReply(set: app.sillytavern.android.quickreply.QuickReplySet, qr: app.sillytavern.android.quickreply.QuickReply, currentInput: String): app.sillytavern.android.quickreply.QuickReplyOutcome {
        val svc = quickReplies ?: return app.sillytavern.android.quickreply.QuickReplyOutcome(null, null, null)
        return svc.run(set, qr, currentInput, CommandExecutor(host), { Macros.expand(it, host.macroContext()) })
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

    /** The command host of this controller (tests run slash commands against it). */
    fun commandHost(): CommandHost = host

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
                summary = Summary.latest(messages)?.second.orEmpty(),
            )
        }
        override suspend fun echo(text: String, severity: String) { _state.update { it.copy(notice = text) } }
        override suspend fun sendUser(text: String) { if (text.isNotBlank()) mutate { it + ChatMessage.user(settings().activePersona.name, text.trim()) } }
        override suspend fun addSystemMessage(text: String) { mutate { it + ChatMessage.narrator(text) } }
        override suspend fun addMessageAs(name: String, text: String) { mutate { it + ChatMessage.assistant(name, text) } }
        override suspend fun generateReply() { waitIdle(); newReply(isUserInput = false); groupJob?.join(); job?.join() }
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
        override suspend fun createBranch(mesId: Int): String? = this@ChatController.createBranch(mesId)
        override suspend fun createCheckpoint(mesId: Int, name: String?): String? = this@ChatController.createCheckpoint(mesId, name)
        override suspend fun openCheckpoint(mesId: Int): Boolean = this@ChatController.openCheckpoint(mesId)
        override suspend fun backToMain(): Boolean = this@ChatController.backToMain()
        override fun mainChatName(): String? = this@ChatController.mainChatName()
        override fun checkpointOf(mesId: Int): String? = messages.getOrNull(mesId)?.bookmarkLink
        override fun checkpoints(): List<Pair<Int, String>> = ChatBranching.checkpoints(messages)
        override suspend fun switchPersona(name: String): Boolean {
            val p = settings().personas.firstOrNull { it.name.equals(name, true) } ?: return false
            selectPersona(p.id)
            return true
        }
        override suspend fun groupMembers(): List<GroupMemberInfo>? = group?.let { g ->
            g.members.map { a -> GroupMemberInfo(memberByAvatar(a)?.card?.name ?: a.removeSuffix(".png"), a, !g.isMuted(a)) }
        }
        override suspend fun groupSetEnabled(avatar: String, enabled: Boolean) { muteMember(avatar, !enabled) }
        override suspend fun groupAdd(characterName: String): Boolean {
            val c = characterLookup(characterName.removeSuffix(".png")) ?: return false
            updateGroup { g -> if (avatarOf(c) in g.members) g else g.copy(members = g.members + avatarOf(c)) }
            return true
        }
        override suspend fun groupRemove(avatar: String) { updateGroup { g -> g.copy(members = g.members - avatar, disabledMembers = g.disabledMembers - avatar) } }
        override suspend fun groupMove(avatar: String, delta: Int) = updateGroup { g -> g.copy(members = moved(g.members, avatar, delta)) }
        override suspend fun groupSpeak(avatar: String) { waitIdle(); speak(avatar); groupJob?.join() }
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
            extensionPrompts = extensionPromptsFor(history, character),
        )
        val req = makeRequest(input, profile, s)
        val backend = backendFor(profile.type)
        val out = StringBuilder()
        val stream: Flow<Chunk> = if (backend is ChunkBackend) ToolLoop.run(backend, req, toolRegistry)
        else backend.generate(req).map { Chunk.Text(it) }
        stream.collect { if (it is Chunk.Text) out.append(it.text) }
        return out.toString().trim()
    }

    // ---------------------------------------------------------------- import / export

    /** Imports [text] (ST JSONL, Ooba, Agnai, CAI Tools or Kobold Lite) as new chat(s) of the open character / group; returns the last file. */
    fun importChat(text: String): Result<File> = runCatching {
        val dir = chatDirectory() ?: error("Open a character or group first")
        val charName = group?.name ?: _state.value.character?.card?.name ?: error("Open a character or group first")
        val imported = ChatIo.import(text, settings().activePersona.name, charName, repo).getOrThrow()
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd@HH'h'mm'm'ss's'"))
        var last: File? = null
        for ((i, c) in imported.withIndex()) {
            val base = (if (group != null) "$stamp imported" else "${app.sillytavern.android.data.StoragePaths.sanitizeFileName(charName)} - $stamp imported") + if (i > 0) " ${i + 1}" else ""
            var file = File(dir, "$base.jsonl"); var n = 1
            while (file.exists()) file = File(dir, "$base ${n++}.jsonl")
            repo.save(c.copy(file = file))
            group?.let { g -> updateGroup { it.copy(chats = it.chats + file.nameWithoutExtension) } }
            last = file
        }
        last ?: error("Nothing to import")
    }

    /** Chat file contents as SillyTavern JSONL, or plain `Name: text` blocks when [asText]. */
    fun exportChat(file: File, asText: Boolean): String {
        val c = repo.load(file)
        return if (asText) ChatIo.exportText(c.messages) else repo.serialize(c)
    }

    // ---------------------------------------------------------------- hide, branches, checkpoints

    fun setHidden(index: Int, hidden: Boolean) {
        if (_state.value.generating) return
        mutate { l -> l.mapIndexed { i, m -> if (i == index) m.withHidden(hidden) else m } }
    }

    fun currentChatName(): String = chat?.file?.nameWithoutExtension.orEmpty()

    /** Name of the chat this one was branched from (checkpoint / branch chats), or null. */
    fun mainChatName(): String? = ChatMeta.string(chatMeta(), ChatMeta.MAIN_CHAT)?.takeIf { it.isNotBlank() }

    private fun chatDirectory(): File? = chat?.file?.parentFile

    private fun chatFileNamed(name: String): File? =
        chatDirectory()?.let { File(it, app.sillytavern.android.data.StoragePaths.sanitizeFileName(name) + ".jsonl") }

    /** Writes [messages] to a new chat named [name] next to the open one, registering it with the group if needed. */
    private fun writeDerivedChat(name: String, messages: List<ChatMessage>): File? {
        val cur = chat ?: return null
        val file = chatFileNamed(name) ?: return null
        repo.save(Chat(file, ChatBranching.childHeader(cur.header, currentChatName()), messages))
        group?.let { g -> if (file.nameWithoutExtension !in g.chats) updateGroup { it.copy(chats = it.chats + file.nameWithoutExtension) } }
        return file
    }

    /** Copies the chat up to [mesId] into a new "Branch #n" chat and opens it. Returns the new chat name, or null with an error shown. */
    fun createBranch(mesId: Int, swipeId: Int? = null, open: Boolean = true): String? {
        val cur = chat ?: return null
        if (_state.value.generating) return null
        val snap = ChatBranching.snapshot(cur.messages, mesId, swipeId)
        if (snap == null) { _state.update { it.copy(error = "Branch creation failed: invalid message or swipe") }; return null }
        val name = ChatBranching.uniqueName(currentChatName(), ChatBranching::branchName) { chatFileNamed(it)?.exists() == true }
        if (name == null) { _state.update { it.copy(error = "Could not generate a unique branch name") }; return null }
        val file = writeDerivedChat(name, snap) ?: return null
        mutate { l -> l.mapIndexed { i, m -> if (i == mesId) ChatBranching.withBranch(m, name) else m } }
        if (open) openChatFile(file)
        return name
    }

    /** Saves a checkpoint chat of everything up to [mesId] and links it from that message. Stays in the current chat. */
    fun createCheckpoint(mesId: Int, name: String? = null): String? {
        val cur = chat ?: return null
        if (_state.value.generating) return null
        val snap = ChatBranching.snapshot(cur.messages, mesId)
        if (snap == null) { _state.update { it.copy(error = "Checkpoint creation failed: invalid message") }; return null }
        val finalName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: ChatBranching.uniqueName(currentChatName(), ChatBranching::checkpointName) { chatFileNamed(it)?.exists() == true }
        if (finalName == null) { _state.update { it.copy(error = "Could not generate a unique checkpoint name") }; return null }
        writeDerivedChat(finalName, snap) ?: return null
        mutate { l -> l.mapIndexed { i, m -> if (i == mesId) ChatBranching.withCheckpoint(m, finalName) else m } }
        return finalName
    }

    /** Opens the checkpoint chat linked from message [mesId]; false when there is none. */
    fun openCheckpoint(mesId: Int): Boolean {
        val name = messages.getOrNull(mesId)?.bookmarkLink ?: return false
        return openChatNamed(name)
    }

    /** Back to the chat the open one was derived from. */
    fun backToMain(): Boolean = mainChatName()?.let(::openChatNamed) ?: false

    private fun openChatNamed(name: String): Boolean {
        val f = chatFileNamed(name)?.takeIf { it.exists() }
        if (f == null) { _state.update { it.copy(error = "Chat \"$name\" no longer exists") }; return false }
        openChatFile(f)
        return true
    }

    // ---------------------------------------------------------------- personas

    /** Switches the active persona to the chat's lock, the character's lock or the default persona (if any applies). */
    private fun applyPersonaFor(chat: Chat?, characterFile: String?) {
        val target = PersonaLock.resolve(settings(), chat?.let { ChatMeta.string(ChatMeta.of(it), ChatMeta.PERSONA) }, characterFile) ?: return
        if (target != settings().activePersona.id) updateSettings { it.copy(activePersonaId = target) }
    }

    /** Selects a persona by hand; with auto-lock on, the open chat is locked to it. */
    fun selectPersona(id: String) {
        if (settings().personas.none { it.id == id }) return
        updateSettings { it.copy(activePersonaId = id) }
        if (settings().personaAutoLock && chat != null) lockPersona(chatLock = true)
    }

    /** Locks the active persona to the open chat, or to the open character ([chatLock] false). */
    fun lockPersona(chatLock: Boolean) {
        val p = settings().activePersona
        if (chatLock) {
            val cur = chat ?: return
            _state.update { it.copy(chat = ChatMeta.with(cur, ChatMeta.PERSONA, kotlinx.serialization.json.JsonPrimitive(p.id))) }
            persist()
        } else {
            val f = if (group == null) _state.value.character?.fileName ?: return else return
            updateSettings { it.copy(personas = PersonaLock.lockCharacter(it.personas, p.id, f)) }
        }
    }

    fun unlockPersona(chatLock: Boolean) {
        if (chatLock) {
            val cur = chat ?: return
            _state.update { it.copy(chat = ChatMeta.with(cur, ChatMeta.PERSONA, null)) }
            persist()
        } else {
            val f = if (group == null) _state.value.character?.fileName ?: return else return
            updateSettings { it.copy(personas = PersonaLock.unlockCharacter(it.personas, f)) }
        }
    }

    fun isChatLocked(): Boolean = ChatMeta.string(chatMeta(), ChatMeta.PERSONA) == settings().activePersona.id
    fun isCharacterLocked(): Boolean = group == null && _state.value.character?.fileName?.let { it in settings().activePersona.characters } == true

    // ---------------------------------------------------------------- author's note + summary

    /** Prompts contributed by the Author's Note and the chat summary for a generation over [history]. */
    private fun extensionPromptsFor(history: List<ChatMessage>, character: StoredCharacter?): List<ExtensionPrompt> {
        val s = settings()
        val persona = s.activePersona
        val note = AuthorsNote.prompt(
            AuthorsNote.config(chatMeta(), s.note), history,
            // SillyTavern applies the character note to single-character chats only.
            if (group == null) character?.let { s.characterNotes[it.fileName] } else null,
            persona.description, persona.position,
        )
        val depthPersona = persona.description.takeIf { it.isNotBlank() && persona.position == 4 }
            ?.let { ExtensionPrompt("persona", it, ExtensionPrompt.IN_CHAT, persona.depth, persona.role) }
        return listOfNotNull(Summary.extensionPrompt(history, s.summary), depthPersona, note)
    }

    /** Author's Note of the open chat (chat metadata, with global defaults). */
    fun noteConfig(): NoteConfig = AuthorsNote.config(chatMeta(), settings().note)

    fun setNoteConfig(c: NoteConfig) {
        val cur = chat ?: return
        _state.update { it.copy(chat = AuthorsNote.withConfig(cur, c)) }
        persist()
    }

    /** The open character's own note (single-character chats). */
    fun characterNote(): CharacterNote? = _state.value.character?.fileName?.let { settings().characterNotes[it] }

    fun setCharacterNote(n: CharacterNote) {
        val f = _state.value.character?.fileName ?: return
        updateSettings { st -> st.copy(characterNotes = if (n.prompt.isEmpty() && !n.useChara) st.characterNotes - f else st.characterNotes + (f to n)) }
    }

    /** Latest summary text of the open chat. */
    fun summaryText(): String = Summary.latest(messages)?.second.orEmpty()

    /** Edits the current summary in place (or puts a first one on the newest message); blank clears it. Older summaries stay. */
    fun setSummary(text: String) {
        if (messages.isEmpty()) return
        val at = Summary.latest(messages)?.first ?: messages.lastIndex
        mutate { l -> l.toMutableList().also { it[at] = Summary.withMemory(it[at], text) } }
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
        newReply(isUserInput = true, input = text)
    }

    /** A fresh reply: one message from the open character, or a batch from the members the group activates. */
    private fun newReply(isUserInput: Boolean, input: String = "") {
        if (group == null) generateNew() else groupReply(isUserInput, input)
    }

    /** Regenerate the last reply as a new swipe, or generate one if the last message is the user's. */
    fun regenerate() {
        if (_state.value.generating) return
        val last = messages.lastOrNull() ?: return
        if (last.isUser) newReply(isUserInput = false) else {
            val sp = lastSpeaker(); if (group != null && sp == null) return
            val idx = messages.lastIndex
            mutate { it.toMutableList().also { l -> l[idx] = l[idx].addSwipe("") } }
            runGeneration(GenerationMode.NORMAL, idx, appendTo = false, trigger = "regenerate", speaker = sp)
        }
    }

    fun continueLast() {
        if (_state.value.generating) return
        val last = messages.lastOrNull()?.takeIf { !it.isUser } ?: return
        val sp = lastSpeaker(); if (group != null && sp == null) return
        runGeneration(GenerationMode.CONTINUE, messages.lastIndex, appendTo = true, trigger = "continue", speaker = sp)
    }

    fun impersonate() {
        if (_state.value.generating || messages.isEmpty()) return
        val sp = if (group == null) null else GroupActivation.impersonate(memberInfos(onlyEnabled = false), random).firstOrNull()?.let(::memberByAvatar)
        if (group != null && sp == null) { _state.update { it.copy(error = "This group has no members") }; return }
        runGeneration(GenerationMode.IMPERSONATE, null, appendTo = false, trigger = "impersonate", speaker = sp)
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
        batch = false
        groupJob?.cancel(); groupJob = null
        job?.cancel()
        job = null
    }

    private fun generateNew(speaker: StoredCharacter? = null) {
        val c = speaker ?: _state.value.character ?: return
        val msg = ChatMessage.assistant(c.card.name, "").let { if (group != null) it.withOriginalAvatar(avatarOf(c)) else it }
        mutate { it + msg }
        runGeneration(GenerationMode.NORMAL, messages.lastIndex, appendTo = false, speaker = speaker)
    }

    private fun runGeneration(mode: GenerationMode, targetIndex: Int?, appendTo: Boolean, trigger: String = "normal", speaker: StoredCharacter? = null) {
        val character = speaker ?: _state.value.character ?: return
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
            card = cardFor(character), persona = s.activePersona, preset = s.activePreset,
            history = history, maxContextTokens = profile.sampler.maxContextTokens,
            maxResponseTokens = profile.sampler.maxResponseTokens,
            counter = counterFor(profile.type), mode = mode,
            promptManager = s.activePromptManager,
            // System-prompt presets belong to text completion; chat completion uses the preset's own main prompt.
            systemPrompt = if (textMode) s.activeSystemPrompt else null,
            vars = variableStore(), regexScripts = scripts(), groupNames = group != null,
            extensionPrompts = extensionPromptsFor(history, character),
        )
        // Re-rolls of an existing reply must not advance sticky/cooldown timers.
        val dryRun = trigger != "normal"
        val worldInfoInput = worlds?.let { w ->
            val entries = WorldInfoSources.resolve(character.card, character.fileName, WorldInfoRunner.chatBookOf(chat), s, w, s.activePersona.lorebook)
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
                    _state.update { it.copy(generating = batch, error = failure) }
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

    // ---------------------------------------------------------------- group chats

    private var groupJob: Job? = null
    private var autoJob: Job? = null
    /** True while a group batch is running, so `generating` does not flicker between members. */
    private var batch = false

    private val group get() = _state.value.group
    private fun avatarOf(c: StoredCharacter) = c.fileName + ".png"
    private fun memberByAvatar(a: String) = _state.value.members.firstOrNull { avatarOf(it) == a }

    private fun memberInfos(onlyEnabled: Boolean): List<Member> {
        val g = group ?: return emptyList()
        return g.members.mapNotNull(::memberByAvatar).filter { !onlyEnabled || !g.isMuted(avatarOf(it)) }
            .map { Member(avatarOf(it), it.card.name, it.card.talkativeness) }
    }

    /** The speaker's card, with the other members' fields merged in for APPEND generation modes. */
    private fun cardFor(speaker: StoredCharacter): app.sillytavern.android.card.CharacterCard {
        val g = group ?: return speaker.card
        val cards = g.members.mapNotNull { a -> memberByAvatar(a)?.let { GroupCardMember(a, it.card.name, it.card) } }
        val user = settings().activePersona.name
        val merged = GroupCards.merge(g, cards, avatarOf(speaker)) { t, name -> Macros.expand(t, MacroContext(user = user, char = name)) }
            ?: return speaker.card
        return speaker.card.copy(description = merged.description, personality = merged.personality, scenario = merged.scenario, messageExample = merged.mesExamples)
    }

    /** Author of the last message (group) or the open character; reports an error when the author left the group. */
    private fun lastSpeaker(): StoredCharacter? {
        if (group == null) return null
        val a = GroupActivation.swipe(memberInfos(onlyEnabled = false), messages).firstOrNull()
        val m = a?.let(::memberByAvatar)
        if (m == null) _state.update { it.copy(error = "The member who wrote this message is no longer in the group. Add them back to continue.") }
        return m
    }

    private fun moved(list: List<String>, item: String, delta: Int): List<String> {
        val i = list.indexOf(item)
        val j = i + delta
        if (i < 0 || j !in list.indices) return list
        return list.toMutableList().also { it[i] = it[j]; it[j] = item }
    }

    /** Reorders a member in the open group (UI up/down buttons). */
    fun moveMember(avatar: String, delta: Int) = updateGroup { g -> g.copy(members = moved(g.members, avatar, delta)) }

    private fun resolveMembers(g: Group) = g.members.mapNotNull { characterLookup(it.removeSuffix(".png")) }

    fun openGroup(group: Group, chatId: String? = null) {
        stop(); stopAuto()
        val gr = groups ?: return
        val g = if (chatId != null && chatId != group.chatId) group.copy(chatId = chatId, chats = (group.chats + chatId).distinct()).also(gr::save) else group
        val members = resolveMembers(g)
        val file = gr.chatFile(g.chatId)
        val existing = if (file.exists()) repo.load(file) else null
        applyPersonaFor(existing, null)
        val loaded = existing ?: repo.createGroupChat(file, groupGreetings(g, members))
        _state.value = ChatUiState(character = members.firstOrNull(), chat = loaded, group = g, members = members)
        fireAuto(QuickReplyService.Trigger.CHAT_CHANGE)
    }

    /** New group chats start with each enabled member's first greeting (alternates become swipes). */
    private fun groupGreetings(g: Group, members: List<StoredCharacter>): List<ChatMessage> =
        members.filter { !g.isMuted(avatarOf(it)) }.mapNotNull { m ->
            val gr = m.card.greetings
            gr.firstOrNull()?.let { ChatMessage.assistant(m.card.name, it).withOriginalAvatar(avatarOf(m)).copy(swipes = gr) }
        }

    /** Applies [f] to the open group, saves it and refreshes the resolved member list. */
    fun updateGroup(f: (Group) -> Group) {
        val g = group ?: return
        val n = f(g)
        groups?.save(n)
        _state.update { it.copy(group = n, members = resolveMembers(n)) }
    }

    fun muteMember(avatar: String, muted: Boolean) = updateGroup { g ->
        g.copy(disabledMembers = if (muted) (g.disabledMembers + avatar).distinct() else g.disabledMembers - avatar)
    }

    /** Makes one member speak now, even a muted one. */
    fun speak(avatar: String) {
        if (_state.value.generating) return
        if (memberByAvatar(avatar) == null) { _state.update { it.copy(error = "That member is missing from the group") }; return }
        startBatch(listOf(avatar))
    }

    private fun groupReply(isUserInput: Boolean, input: String) {
        val g = group ?: return
        val enabled = memberInfos(onlyEnabled = true)
        val last = messages.lastOrNull()
        val text = if (isUserInput) input else last?.takeIf { !it.isSystem }?.text.orEmpty()
        val picked = when (g.activationStrategy) {
            ActivationStrategy.LIST -> GroupActivation.list(enabled)
            ActivationStrategy.POOLED -> GroupActivation.pooled(enabled, messages, last, isUserInput, random)
            ActivationStrategy.MANUAL -> if (isUserInput) emptyList() else GroupActivation.manual(enabled, random)
            else -> GroupActivation.natural(enabled, text, last, g.allowSelfResponses, isUserInput, random)
        }
        if (picked.isEmpty()) {
            if (enabled.isEmpty()) _state.update { it.copy(notice = "All group members are muted or missing; nobody can reply.") }
            return
        }
        startBatch(picked)
    }

    /** Generates one reply per avatar, in order; stops at the first error or on [stop]. */
    private fun startBatch(avatars: List<String>) {
        val speakers = avatars.mapNotNull(::memberByAvatar)
        if (speakers.isEmpty()) return
        batch = true
        _state.update { it.copy(generating = true, error = null, groupQueue = speakers.map(::avatarOf)) }
        groupJob = scope.launch {
            try {
                for ((i, sp) in speakers.withIndex()) {
                    _state.update { it.copy(groupQueue = speakers.drop(i).map(::avatarOf)) }
                    generateNew(sp)
                    job?.join()
                    if (_state.value.error != null) break
                }
            } finally {
                withContext(NonCancellable) {
                    batch = false
                    _state.update { it.copy(generating = job?.isActive == true, groupQueue = emptyList()) }
                }
            }
        }
    }

    /** Auto mode: while on, an idle group chat gets a new reply every `autoModeDelay` seconds. */
    fun setAutoMode(on: Boolean) {
        stopAuto()
        if (!on || group == null) return
        _state.update { it.copy(autoMode = true) }
        autoJob = scope.launch {
            while (isActive) {
                delay((group?.autoModeDelay ?: 5).coerceAtLeast(1) * 1000L)
                if (!_state.value.generating && messages.isNotEmpty()) groupReply(isUserInput = false, input = "")
            }
        }
    }

    private fun stopAuto() {
        autoJob?.cancel(); autoJob = null
        if (_state.value.autoMode) _state.update { it.copy(autoMode = false) }
    }

    private fun persist() = chat?.takeIf { it.file != null }?.let(repo::save)
}
