package app.sillytavern.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.quickreply.*

/** Set list: global activation, import/export, create, delete. */
@Composable
fun QuickReplyScreen(c: AppContainer, onOpenSet: (String) -> Unit, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val ctx = LocalContext.current
    val repo = c.quickReplyRepo
    var names by remember { mutableStateOf(repo.list()) }
    var message by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var exportName by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) message = runCatching { repo.importJson(String(ctx.readUri(uri))) }
            .fold({ names = repo.list(); "Imported \"${it.name}\"" }, { "Import failed: ${it.message}" })
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val set = repo.load(exportName)
        if (uri != null && set != null) message = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(repo.exportJson(set).toByteArray()) } }
            .fold({ "Exported" }, { "Export failed: ${it.message}" })
    }
    val q = s.quickReplies
    Scaffold(topBar = { BackTopBar("Quick Replies", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SwitchRow("Show global sets", q.globalEnabled) { v -> c.settings.update { it.copy(quickReplies = it.quickReplies.copy(globalEnabled = v)) } }
            SwitchRow("Show chat and character sets", q.chatEnabled) { v -> c.settings.update { it.copy(quickReplies = it.quickReplies.copy(chatEnabled = v)) } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sets", style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import") }
                    TextButton(onClick = { newName = "" }) { Text("New") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (names.isEmpty()) Text("No sets yet.")
            names.forEach { n ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(n in q.globalSets, { v -> c.quickReplies.setGlobal(n, v) })
                    Spacer(Modifier.width(8.dp))
                    Text(n, Modifier.weight(1f).clickable { onOpenSet(n) }, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { onOpenSet(n) }) { Text("Edit") }
                    TextButton(onClick = { exportName = n; exporter.launch("$n.json") }) { Text("Export") }
                    TextButton(onClick = { deleting = n }) { Text("Delete") }
                }
            }
            Text("Switch on = shown in every chat. A set can also be chosen for one chat (chat menu) or one character (character editor).", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }
    newName?.let { n ->
        AlertDialog(onDismissRequest = { newName = null }, title = { Text("New set") },
            text = { Field("Name", n, { newName = it }) },
            confirmButton = {
                TextButton(enabled = n.isNotBlank() && n.trim() !in names, onClick = {
                    repo.save(QuickReplySet(name = n.trim())); names = repo.list(); newName = null; onOpenSet(n.trim())
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newName = null }) { Text("Cancel") } })
    }
    deleting?.let { n ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"$n\"?") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(n); names = repo.list(); deleting = null
                    c.quickReplies.setGlobal(n, false)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

/** Editor for one set: flags plus its Quick Replies. Saves on every change. */
@Composable
fun QuickReplySetScreen(c: AppContainer, name: String, onBack: () -> Unit) {
    val repo = c.quickReplyRepo
    var set by remember { mutableStateOf(repo.load(name) ?: QuickReplySet(name = name)) }
    var editing by remember { mutableStateOf<Int?>(null) }
    fun update(f: (QuickReplySet) -> QuickReplySet) { set = f(set); repo.save(set) }
    Scaffold(topBar = { BackTopBar(name, onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SwitchRow("Don't send automatically (fill the input box)", set.disableSend) { v -> update { it.copy(disableSend = v) } }
            SwitchRow("Add the input box text to the message", set.injectInput) { v -> update { it.copy(injectInput = v) } }
            SwitchRow("…place the message before the input text", set.placeBeforeInput) { v -> update { it.copy(placeBeforeInput = v) } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Quick Replies", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { update { it.withAdded(QuickReply(label = "New", message = "")) }; editing = set.qrList.lastIndex }) { Text("Add") }
            }
            set.qrList.forEachIndexed { i, qr ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(qr.label.ifBlank { "(no label)" } + if (qr.isHidden) "  (hidden)" else "", Modifier.weight(1f).clickable { editing = i })
                    TextButton(onClick = { editing = i }) { Text("Edit") }
                    TextButton(onClick = { update { it.copy(qrList = it.qrList.toMutableList().also { l -> l.removeAt(i) }) } }) { Text("Delete") }
                }
            }
            Text("A message starting with / runs as a command script. {{input}} inserts the input box text.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }
    editing?.let { i ->
        val qr = set.qrList.getOrNull(i)
        if (qr == null) editing = null else QrDialog(qr, onDismiss = { editing = null }) { upd ->
            update { it.copy(qrList = it.qrList.toMutableList().also { l -> l[i] = upd }) }; editing = null
        }
    }
}

@Composable
private fun QrDialog(qr: QuickReply, onDismiss: () -> Unit, onSave: (QuickReply) -> Unit) {
    var label by remember { mutableStateOf(qr.label) }
    var title by remember { mutableStateOf(qr.title) }
    var msg by remember { mutableStateOf(qr.message) }
    var hidden by remember { mutableStateOf(qr.isHidden) }
    var startup by remember { mutableStateOf(qr.executeOnStartup) }
    var user by remember { mutableStateOf(qr.executeOnUser) }
    var ai by remember { mutableStateOf(qr.executeOnAi) }
    var change by remember { mutableStateOf(qr.executeOnChatChange) }
    var newChat by remember { mutableStateOf(qr.executeOnNewChat) }
    var auto by remember { mutableStateOf(qr.automationId) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Quick Reply") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Label", label, { label = it })
                Field("Tooltip", title, { title = it })
                Field("Message / script", msg, { msg = it }, singleLine = false, minLines = 4)
                SwitchRow("Hidden (not in the bar)", hidden) { hidden = it }
                Text("Run automatically", style = MaterialTheme.typography.labelLarge)
                SwitchRow("On app start", startup) { startup = it }
                SwitchRow("After a user message", user) { user = it }
                SwitchRow("After an AI reply", ai) { ai = it }
                SwitchRow("When the chat changes", change) { change = it }
                SwitchRow("In a new chat", newChat) { newChat = it }
                Field("Automation ID (optional)", auto, { auto = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(qr.copy(label = label, title = title, message = msg, isHidden = hidden, executeOnStartup = startup, executeOnUser = user,
                    executeOnAi = ai, executeOnChatChange = change, executeOnNewChat = newChat, automationId = auto))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
