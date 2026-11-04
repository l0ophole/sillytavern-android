package app.sillytavern.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import app.sillytavern.android.polish.Routes
import app.sillytavern.android.polish.ShareImporter
import app.sillytavern.android.polish.SharedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.sillytavern.android.ui.*

class MainActivity : ComponentActivity() {
    /** A chat route requested by a notification or the widget, consumed by the nav host. */
    private val route = mutableStateOf<String?>(null)
    /** A share intent waiting for the lock screen to be passed. */
    private val pendingShare = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent?) {
        Routes.routeFor(intent)?.let { route.value = it }
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) pendingShare.value = intent
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }

    private fun importShared(c: AppContainer, i: Intent) {
        val uris = sharedUris(i)
        if (uris.isEmpty()) { Toast.makeText(this, "Nothing to import", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val notes = ArrayList<String>()
                val items = uris.mapNotNull { u ->
                    runCatching {
                        val bytes = contentResolver.openInputStream(u)!!.use { it.readBytes() }
                        SharedItem(u.lastPathSegment ?: "file", bytes)
                    }.onFailure { notes += "${u.lastPathSegment}: ${it.message}" }.getOrNull()
                }
                val r = ShareImporter(c.characters, c.chats, { c.settings.settings.value.activePersona.name }).import(items)
                r.copy(notes = r.notes + notes)
            }
            Toast.makeText(this@MainActivity, result.summary(), Toast.LENGTH_LONG).show()
            if (result.imported > 0) route.value = "characters"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) accept(intent)
        val app = application as SillyTavernApp
        val c = app.container
        setContent {
            val s by c.settings.settings.collectAsState()
            var unlocked by remember { mutableStateOf(app.isUnlocked()) }
            SillyTheme(s.appearance, rememberTranslator(s.appearance.language)) {
                if (unlocked) {
                    LaunchedEffect(pendingShare.value) { pendingShare.value?.let { importShared(c, it); pendingShare.value = null } }
                    AppNav(c, route, onRebuild = { recreate() })
                }
                else LockScreen(c.account.name, app.accounts.list().filter { it.handle != c.account.handle && !it.locked },
                    onUnlock = { code -> withContext(Dispatchers.Default) { app.unlock(code) }.also { if (it) unlocked = true } },
                    onSwitch = { app.switchTo(it.handle); recreate() })
            }
        }
    }
}

@Composable
private fun AppNav(c: AppContainer, route: androidx.compose.runtime.MutableState<String?>, onRebuild: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(route.value) {
        val r = route.value ?: return@LaunchedEffect
        route.value = null
        // Going to "characters" rebuilds the list so freshly imported cards show up.
        if (r == "characters") nav.navigate(r) { popUpTo("characters") { inclusive = true } } else nav.navigate(r) { launchSingleTop = true }
    }
    NavHost(nav, startDestination = "characters") {
        composable("characters") {
            CharacterListScreen(c,
                onOpenChat = { nav.navigate("chat/${android.net.Uri.encode(it)}") },
                onEdit = { nav.navigate("edit/${android.net.Uri.encode(it ?: "")}") },
                onSettings = { nav.navigate("settings") },
                onOpenGroup = { nav.navigate("groupchat/${android.net.Uri.encode(it)}") },
                onEditGroup = { nav.navigate("groupedit/${android.net.Uri.encode(it ?: "")}") })
        }
        composable("chat/{file}", listOf(navArgument("file") { type = NavType.StringType })) {
            ChatScreen(c, it.arguments!!.getString("file")!!, onBack = { nav.popBackStack() },
                onEdit = { f -> nav.navigate("edit/${android.net.Uri.encode(f)}") },
                onGallery = { f -> nav.navigate("gallery/${android.net.Uri.encode(f)}") })
        }
        composable("groupchat/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
            ChatScreen(c, "", groupId = it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, onEdit = {},
                onEditGroup = { id -> nav.navigate("groupedit/${android.net.Uri.encode(id)}") })
        }
        composable("groupedit/{id}", listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) {
            GroupEditScreen(c, it.arguments?.getString("id").orEmpty().ifEmpty { null }, onDone = { nav.popBackStack() })
        }
        composable("tags") { TagManagerScreen(c, onBack = { nav.popBackStack() }) }
        composable("edit/{file}", listOf(navArgument("file") { type = NavType.StringType; defaultValue = "" })) {
            CharacterEditScreen(c, it.arguments?.getString("file").orEmpty().ifEmpty { null }, onDone = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(c, onBack = { nav.popBackStack() },
                onPromptManager = { nav.navigate("promptmanager") }, onPresets = { nav.navigate("presets") },
                onWorlds = { nav.navigate("worlds") },
                onQuickReplies = { nav.navigate("quickreplies") }, onRegex = { nav.navigate("regex") },
                onTags = { nav.navigate("tags") }, onAppearance = { nav.navigate("appearance") },
                onMedia = { nav.navigate("media") }, onGallery = { nav.navigate("gallery") }, onMemory = { nav.navigate("memory") }, onTools = { nav.navigate(it) })
        }
        composable("data") { DataScreen(c, onBack = { nav.popBackStack() }, onRestored = onRebuild) }
        composable("accounts") { AccountsScreen(c, onBack = { nav.popBackStack() }, onSwitched = onRebuild) }
        composable("polish") { PolishScreen(c, onBack = { nav.popBackStack() }) }
        composable("extensions") { ExtensionsScreen(c, onBack = { nav.popBackStack() }) }
        composable("tools") { ToolsScreen(c, onBack = { nav.popBackStack() }) }
        composable("content") { ContentScreen(c, onBack = { nav.popBackStack() }) }
        composable("tokens") { TokenScreen(c, onBack = { nav.popBackStack() }) }
        composable("stats") { StatsScreen(c, onBack = { nav.popBackStack() }) }
        composable("memory") { MemoryScreen(c, onBack = { nav.popBackStack() }) }
        composable("media") { MediaSettingsScreen(c, onBack = { nav.popBackStack() }) }
        composable("gallery") { GalleryFoldersScreen(c, onOpen = { nav.navigate("gallery/${android.net.Uri.encode(it)}") }, onBack = { nav.popBackStack() }) }
        composable("gallery/{folder}", listOf(navArgument("folder") { type = NavType.StringType })) {
            GalleryScreen(c, it.arguments!!.getString("folder")!!, onBack = { nav.popBackStack() })
        }
        composable("appearance") { AppearanceScreen(c, onBack = { nav.popBackStack() }) }
        composable("promptmanager") { PromptManagerScreen(c, onBack = { nav.popBackStack() }) }
        composable("worlds") { WorldInfoScreen(c, onOpenBook = { nav.navigate("worlds/${android.net.Uri.encode(it)}") }, onBack = { nav.popBackStack() }) }
        composable("worlds/{name}", listOf(navArgument("name") { type = NavType.StringType })) {
            WorldBookScreen(c, it.arguments!!.getString("name")!!, onBack = { nav.popBackStack() })
        }
        composable("quickreplies") { QuickReplyScreen(c, onOpenSet = { nav.navigate("quickreplies/${android.net.Uri.encode(it)}") }, onBack = { nav.popBackStack() }) }
        composable("quickreplies/{name}", listOf(navArgument("name") { type = NavType.StringType })) {
            QuickReplySetScreen(c, it.arguments!!.getString("name")!!, onBack = { nav.popBackStack() })
        }
        composable("regex") { RegexScreen(c, onBack = { nav.popBackStack() }) }
        composable("presets") { PresetsScreen(c, onBack = { nav.popBackStack() }) }
    }
}
