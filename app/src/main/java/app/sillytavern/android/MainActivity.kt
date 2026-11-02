package app.sillytavern.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.sillytavern.android.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = (application as SillyTavernApp).container
        setContent { SillyTheme { AppNav(c) } }
    }
}

@Composable
fun SillyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
private fun AppNav(c: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "characters") {
        composable("characters") {
            CharacterListScreen(c,
                onOpenChat = { nav.navigate("chat/${android.net.Uri.encode(it)}") },
                onEdit = { nav.navigate("edit/${android.net.Uri.encode(it ?: "")}") },
                onSettings = { nav.navigate("settings") })
        }
        composable("chat/{file}", listOf(navArgument("file") { type = NavType.StringType })) {
            ChatScreen(c, it.arguments!!.getString("file")!!, onBack = { nav.popBackStack() },
                onEdit = { f -> nav.navigate("edit/${android.net.Uri.encode(f)}") })
        }
        composable("edit/{file}", listOf(navArgument("file") { type = NavType.StringType; defaultValue = "" })) {
            CharacterEditScreen(c, it.arguments?.getString("file").orEmpty().ifEmpty { null }, onDone = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(c, onBack = { nav.popBackStack() },
                onPromptManager = { nav.navigate("promptmanager") }, onPresets = { nav.navigate("presets") })
        }
        composable("promptmanager") { PromptManagerScreen(c, onBack = { nav.popBackStack() }) }
        composable("presets") { PresetsScreen(c, onBack = { nav.popBackStack() }) }
    }
}
