package app.sillytavern.android

import android.app.Application
import app.sillytavern.android.api.Backends
import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.chat.ChatController
import app.sillytavern.android.chat.ChatRepository
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
    val worlds = WorldBookRepository(paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tiktoken by lazy { TiktokenCounter() }

    val chatController = ChatController(
        repo = chats,
        backendFor = { Backends.forType(it) },
        settings = { settings.settings.value },
        apiKey = { secrets.get("key:$it") },
        scope = scope,
        worlds = worlds,
        counterFor = { if (it == BackendType.OPENAI) tiktoken else ApproxTokenCounter },
    )
}

class SillyTavernApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
