package app.sillytavern.android.ui

import android.net.Uri
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
import app.sillytavern.android.AppContainer
import app.sillytavern.android.extensions.*
import kotlinx.coroutines.launch

@Composable
fun ExtensionsScreen(c: AppContainer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsState()
    val status by c.extensionHost.status.collectAsState()
    val logs by c.extensionHost.logs.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<InstalledExtension?>(null) }
    var removing by remember { mutableStateOf<InstalledExtension?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val installed = remember(version, status) { c.extensionRepo.list() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val e = ctx.contentResolver.openInputStream(uri)!!.use { c.extensionManager.install(it) }
                version++
                "Installed ${e.manifest.name} ${e.manifest.version}. Turn it on to run it."
            } catch (e: Exception) { "Install failed: ${e.message}" }
        }
    }

    Scaffold(topBar = { BackTopBar(tr("Extensions"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(tr("Extensions are small JavaScript programs that run in a sandbox. They can add slash commands and macros, put text in the prompt and react to chat events, but only with the permissions you confirm. Unmodified SillyTavern browser extensions do not run here."), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { pick.launch("application/zip") }) { Text(tr("Install from a zip file")) }
            if (installed.isEmpty()) Text(tr("No extensions installed."), Modifier.padding(vertical = 8.dp))
            installed.forEach { e ->
                val m = e.manifest
                val st = status[e.id]
                val on = e.id in s.extensions.enabled
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${m.name}  ${m.version}", style = MaterialTheme.typography.titleMedium)
                                if (m.author.isNotBlank()) Text(m.author, style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(checked = on, onCheckedChange = { want ->
                                if (!want) scope.launch { c.extensionManager.disable(e.id); version++ }
                                else if (m.granted.isEmpty()) scope.launch { c.extensionManager.enable(e.id); version++ }
                                else confirm = e
                            })
                        }
                        if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodySmall)
                        Text(when (st?.state) {
                            ExtensionState.RUNNING -> tr("Running")
                            ExtensionState.FAILED -> tr("Stopped after an error") + (st.error?.let { ": $it" } ?: "")
                            else -> tr("Off")
                        }, style = MaterialTheme.typography.labelMedium)
                        Row {
                            TextButton(onClick = { expanded = if (expanded == e.id) null else e.id }) { Text(if (expanded == e.id) tr("Hide details") else tr("Details")) }
                            TextButton(onClick = { removing = e }) { Text(tr("Remove")) }
                        }
                        if (expanded == e.id) {
                            Text(tr("Permissions") + ": " + (m.granted.joinToString { it.label }.ifBlank { tr("none") }), style = MaterialTheme.typography.bodySmall)
                            val lines = logs[e.id].orEmpty()
                            Text(tr("Log"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                            if (lines.isEmpty()) Text(tr("Nothing logged."), style = MaterialTheme.typography.bodySmall)
                            lines.takeLast(30).forEach { Text("[${it.level}] ${it.text}", style = MaterialTheme.typography.bodySmall) }
                            if (lines.isNotEmpty()) TextButton(onClick = { c.extensionHost.clearLog(e.id) }) { Text(tr("Clear log")) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("Allow ${e.manifest.name}?")) },
            text = { Column { Text(tr("This extension asks to:")); e.manifest.granted.forEach { Text("• " + it.label) } } },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { c.extensionManager.enable(e.id); version++ } }) { Text(tr("Allow and turn on")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Cancel")) } },
        )
    }
    removing?.let { e ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr("Remove ${e.manifest.name}?")) },
            text = { Text(tr("Its files and stored data are deleted.")) },
            confirmButton = { TextButton(onClick = { removing = null; scope.launch { c.extensionManager.remove(e.id); version++ } }) { Text(tr("Remove")) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("Cancel")) } },
        )
    }
}
