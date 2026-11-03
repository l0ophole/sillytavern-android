package app.sillytavern.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.sillytavern.android.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = (application as SillyTavernApp).container
        setContent {
            val s by c.settings.settings.collectAsState()
            SillyTheme(s.appearance, rememberTranslator(s.appearance.language)) { AppNav(c) }
        }
    }
}

@Composable
private fun AppNav(c: AppContainer) {
    val nav = rememberNavController()
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
                onMedia = { nav.navigate("media") }, onGallery = { nav.navigate("gallery") }, onMemory = { nav.navigate("memory") })
        }
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
