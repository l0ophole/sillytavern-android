package app.sillytavern.android.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.sillytavern.android.AppContainer
import app.sillytavern.android.polish.ContinueWidget

/** Background generation, notifications, widget and battery options (feature set 14). */
@Composable
fun PolishScreen(c: AppContainer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by c.settings.settings.collectAsState()
    val p = s.polish
    var note by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) note = "Notifications are blocked: replies will still finish in the background, but no alert is shown."
    }
    fun needsPermission() = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    Scaffold(topBar = { BackTopBar(tr("Android"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Keep a reply running when you leave the app, get told when it is done, and go easy on the battery."), style = MaterialTheme.typography.bodySmall)
            ToggleRow(tr("Keep generating in the background"), p.keepAlive) { v -> c.settings.update { it.copy(polish = it.polish.copy(keepAlive = v)) } }
            Text(tr("Shows an ongoing notification while a reply is being written so Android does not stop the app."), style = MaterialTheme.typography.labelSmall)
            ToggleRow(tr("Notify when a reply is ready"), p.notifyReplies) { v ->
                c.settings.update { it.copy(polish = it.polish.copy(notifyReplies = v)) }
                if (v && needsPermission()) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ToggleRow(tr("Respect Battery Saver"), p.respectBatterySaver) { v -> c.settings.update { it.copy(polish = it.polish.copy(respectBatterySaver = v)) } }
            Text(tr("While Battery Saver is on, group auto-mode stops and automatic backups wait."), style = MaterialTheme.typography.labelSmall)
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }

            SectionTitle(tr("Home screen"))
            val manager = remember { AppWidgetManager.getInstance(ctx) }
            if (Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
                OutlinedButton(onClick = { manager.requestPinAppWidget(ComponentName(ctx, ContinueWidget::class.java), null, null) }) { Text(tr("Add \"Continue chat\" widget")) }
            } else Text(tr("Add the \"Continue chat\" widget from your launcher's widget list."), style = MaterialTheme.typography.bodySmall)

            SectionTitle(tr("Sharing into the app"))
            Text(tr("Share a character card (PNG or JSON) or a chat file from another app and choose SillyTavern to import it. A chat is placed under the character named in the file when exactly one character has that name."), style = MaterialTheme.typography.bodySmall)
        }
    }
}
