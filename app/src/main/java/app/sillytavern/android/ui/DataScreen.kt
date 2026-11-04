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
import app.sillytavern.android.SillyTavernApp
import app.sillytavern.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

private fun kindLabel(k: MaidKind) = when (k) {
    MaidKind.TEMP_FILE -> "Leftover temporary files"
    MaidKind.ORPHAN_CHATS -> "Chats of deleted characters"
    MaidKind.UNUSED_AVATAR -> "Persona avatars no persona uses"
    MaidKind.EMPTY_FOLDER -> "Empty folders"
}

@Composable
fun DataScreen(c: AppContainer, onBack: () -> Unit, onRestored: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var backups by remember { mutableStateOf(c.autoBackup.list()) }
    var findings by remember { mutableStateOf<List<MaidFinding>?>(null) }
    var picked by remember { mutableStateOf(emptySet<MaidFinding>()) }

    fun edit(f: (DataSettings) -> DataSettings) = c.settings.update { it.copy(data = f(it.data)) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = try {
                val n = withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { c.backup.write(it) } }
                "Exported $n files."
            } catch (e: Exception) { "Export failed: ${e.message}" }
            busy = false
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val h = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { c.backup.inspect(it) } }.getOrNull() }
            if (h == null) message = "That file is not a backup made by this app." else pendingRestore = uri
        }
    }
    Scaffold(topBar = { BackTopBar(tr("Data & backups"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(tr("A backup is a zip of all characters, chats, worlds, settings and files of the current user. API keys are not included: they are tied to this device and have to be entered again after a restore on another one."), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Row {
                TextButton(enabled = !busy, onClick = { export.launch(BackupService.fileName(c.account.handle, System.currentTimeMillis())) }) { Text(tr("Export backup…")) }
                TextButton(enabled = !busy, onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(tr("Restore from file…")) }
            }

            SectionTitle(tr("Automatic backups"))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(tr("Back up automatically when the app starts"), Modifier.weight(1f))
                Switch(checked = s.data.autoBackup, onCheckedChange = { v -> edit { it.copy(autoBackup = v) } })
            }
            if (s.data.autoBackup) {
                NumberField(tr("Hours between backups"), s.data.intervalHours) { v -> edit { it.copy(intervalHours = v.coerceIn(1, 24 * 30)) } }
                NumberField(tr("Backups to keep"), s.data.keep) { v -> edit { it.copy(keep = v.coerceIn(1, 50)) } }
            }
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    message = try {
                        val f = withContext(Dispatchers.IO) { c.autoBackup.runNow(s.data.keep) }
                        backups = c.autoBackup.list(); "Saved ${f.name} (${humanSize(f.length())})"
                    } catch (e: Exception) { "Backup failed: ${e.message}" }
                    busy = false
                }
            }) { Text(tr("Back up now")) }
            backups.forEach { f ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodySmall)
                        Text(humanSize(f.length()), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = !busy, onClick = { pendingRestore = Uri.fromFile(f) }) { Text(tr("Restore")) }
                    TextButton(onClick = { f.delete(); backups = c.autoBackup.list() }) { Text(tr("Delete")) }
                }
            }

            SectionTitle(tr("Data maid"))
            Text(tr("Looks for leftovers. Nothing is deleted until you tick it and confirm."), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    val avatars = s.personas.map { it.avatar }.filter { it.isNotBlank() }.toSet()
                    val f = withContext(Dispatchers.IO) { c.dataMaid.scan(avatars) }
                    findings = f; picked = emptySet()
                    message = if (f.isEmpty()) "Nothing to clean up." else null
                }
            }) { Text(tr("Scan")) }
            findings?.groupBy { it.kind }?.forEach { (kind, list) ->
                Text("${tr(kindLabel(kind))} (${list.size}, ${humanSize(list.sumOf { it.bytes })})", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                list.forEach { f ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = f in picked, onCheckedChange = { on -> picked = if (on) picked + f else picked - f })
                        Column {
                            Text(f.path, style = MaterialTheme.typography.bodySmall)
                            if (f.bytes > 0) Text(humanSize(f.bytes), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (picked.isNotEmpty()) {
                var confirmClean by remember { mutableStateOf(false) }
                Button(onClick = { confirmClean = true }, modifier = Modifier.padding(top = 8.dp)) { Text("${tr("Delete")} ${picked.size}") }
                if (confirmClean) AlertDialog(
                    onDismissRequest = { confirmClean = false },
                    title = { Text(tr("Delete selected items?")) },
                    text = { Text(tr("This cannot be undone. Consider making a backup first.")) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClean = false
                            scope.launch {
                                val freed = withContext(Dispatchers.IO) { c.dataMaid.clean(picked.toList()) }
                                message = "Freed ${humanSize(freed)}."; findings = null; picked = emptySet()
                            }
                        }) { Text(tr("Delete")) }
                    },
                    dismissButton = { TextButton(onClick = { confirmClean = false }) { Text(tr("Cancel")) } },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(tr("Replace all data?")) },
            text = { Text(tr("Everything of this user is replaced by the backup. The current data is kept aside as \"data.before-restore\" until the next restore.")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { c.backup.restore(it) } }
                            (ctx.applicationContext as SillyTavernApp).reload()
                            onRestored()
                        } catch (e: Exception) { message = "Restore failed: ${e.message}" }
                        busy = false
                    }
                }) { Text(tr("Restore")) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
internal fun SectionTitle(text: String) = Text(text, Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text, onValueChange = { t -> text = t.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
