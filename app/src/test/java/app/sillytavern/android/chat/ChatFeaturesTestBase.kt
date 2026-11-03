package app.sillytavern.android.chat

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.data.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope

/** Shared fixture for controller tests of set 7: live settings holder, recording backend. */
@OptIn(ExperimentalCoroutinesApi::class)
open class ChatFeaturesTestBase {
    val paths = StoragePaths(Files.createTempDirectory("cf").toFile()).ensure()
    val ann = StoredCharacter("Ann", CharacterCard(name = "Ann", firstMessage = "Hello"), File("Ann"))
    var cfg = AppSettings(connections = listOf(ConnectionProfile(name = "c")))
    val requests = mutableListOf<GenerationRequest>()
    var reply = "reply"
    /** Replies served first (in order) before falling back to [reply]. */
    val queuedReplies = ArrayDeque<String>()
    val backend = Backend { req -> requests += req; flow { emit(queuedReplies.removeFirstOrNull() ?: reply) } }

    /** Set before calling [controller] to enable vector memory / Data Bank. */
    var spriteRepo: app.sillytavern.android.memory.SpriteRepository? = null
    var memoryService: app.sillytavern.android.memory.MemoryService? = null

    fun TestScope.controller(): ChatController = ChatController(
        ChatRepository(paths), { backend }, { cfg }, { "key" }, this,
        worlds = app.sillytavern.android.worldinfo.WorldBookRepository(paths),
        updateSettings = { f -> cfg = f(cfg) },
        mediaRoot = paths.root,
        memory = memoryService,
        sprites = spriteRepo,
    )

    fun lastContents() = requests.last().messages.map { it.content }
}
