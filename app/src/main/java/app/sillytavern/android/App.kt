package app.sillytavern.android

import app.sillytavern.android.appearance.BackgroundRepository
import app.sillytavern.android.media.*
import app.sillytavern.android.memory.*
import app.sillytavern.android.regex.RegexRepository
import app.sillytavern.android.quickreply.QuickReplyRepository
import app.sillytavern.android.quickreply.QuickReplyService
import android.app.Application
import app.sillytavern.android.api.Backends
import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.chat.ChatController
import app.sillytavern.android.chat.ChatRepository
import app.sillytavern.android.group.GroupRepository
import app.sillytavern.android.worldinfo.WorldBookRepository
import app.sillytavern.android.data.*
import app.sillytavern.android.prompt.ApproxTokenCounter
import app.sillytavern.android.prompt.TiktokenCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (Hilt is deferred until the object graph justifies it). */
class AppContainer(app: Application) {
    val paths = StoragePaths(app.filesDir.resolve("data")).ensure()
    val settings = SettingsRepository(paths.settingsFile)
    val secrets = SecretStore(paths.secretsFile, AesGcmCrypto.keystore())
    val characters = CharacterRepository(paths)
    val chats = ChatRepository(paths)
    val groups = GroupRepository(paths)
    val worlds = WorldBookRepository(paths)
    val regexScripts = RegexRepository(paths)
    val quickReplyRepo = QuickReplyRepository(paths)
    val backgrounds = BackgroundRepository(paths)
    val gallery = GalleryRepository(paths)
    val tts = TtsService(
        { settings.settings.value.media.tts }, { secrets.get(MediaSettings.KEY_TTS) },
        AndroidSpeechPlayer(app),
    )
    val stt = SttService(
        { settings.settings.value.media.stt }, { secrets.get(MediaSettings.KEY_STT) },
        AndroidVoiceInput(app),
    )
    val memory = MemoryService(
        paths, { settings.settings.value.memory }, { secrets.get(MemorySettings.KEY_EMBEDDINGS) },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tiktoken by lazy { TiktokenCounter() }

    val chatController: ChatController = ChatController(
        repo = chats,
        backendFor = { Backends.forType(it) },
        settings = { settings.settings.value },
        apiKey = { secrets.get("key:$it") },
        scope = scope,
        worlds = worlds,
        regex = { card -> regexScripts.global() + (card?.let(regexScripts::scopedOf) ?: emptyList()) },
        updateSettings = { f -> settings.update(f) },
        groups = groups, characterLookup = { characters.get(it) },
        counterFor = { if (it == BackendType.OPENAI) tiktoken else ApproxTokenCounter },
        mediaRoot = paths.root,
        mediaKey = { secrets.get(it) },
        memory = memory,
    )

    val quickReplies = QuickReplyService(
        quickReplyRepo, { settings.settings.value }, { f -> settings.update(f) },
        chatSet = { chatController.chatQuickReplySet() }, setChatSet = { chatController.setChatQuickReplySet(it) },
        characterKey = { chatController.characterFileName() }, automationIds = { chatController.automationIds() },
    ).also { chatController.quickReplies = it }

    init { chatController.tts = tts; chatController.runStartupQuickReplies() }
}

class SillyTavernApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
