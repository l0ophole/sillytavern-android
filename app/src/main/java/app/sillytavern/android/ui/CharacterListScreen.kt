package app.sillytavern.android.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.card.StoredCharacter
import coil.compose.AsyncImage

fun Context.readUri(uri: Uri): ByteArray =
    contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CharacterListScreen(c: AppContainer, onOpenChat: (String) -> Unit, onEdit: (String?) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var chars by remember { mutableStateOf(emptyList<StoredCharacter>()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<StoredCharacter?>(null) }
    var menuFor by remember { mutableStateOf<StoredCharacter?>(null) }
    var confirmDelete by remember { mutableStateOf<StoredCharacter?>(null) }
    fun reload() { chars = c.characters.list() }
    LaunchedEffect(Unit) { reload() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var ok = 0
        uris.forEach { u -> runCatching { c.characters.import(ctx.readUri(u)) }.onSuccess { ok++ }.onFailure { message = "Import failed: ${it.message}" } }
        if (ok > 0) message = "Imported $ok character(s)"
        reload()
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val ch = pendingExport
        if (uri != null && ch != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(c.characters.exportPng(ch.fileName)) }
        }.onFailure { message = "Export failed: ${it.message}" }
        pendingExport = null
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Characters") }, actions = {
                TextButton(onClick = { importer.launch(arrayOf("image/png", "application/json", "*/*")) }) { Text("Import") }
                TextButton(onClick = onSettings) { Text("Settings") }
            })
        },
        floatingActionButton = { FloatingActionButton(onClick = { onEdit(null) }) { Text("+") } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Field("Search", query, { query = it })
            val shown = chars.filter { query.isBlank() || it.card.name.contains(query, true) || it.card.tags.any { t -> t.contains(query, true) } }
            if (shown.isEmpty()) {
                Text(if (chars.isEmpty()) "No characters yet. Import a card (PNG/JSON) or tap +." else "No matches.",
                    Modifier.padding(top = 24.dp))
            }
            LazyColumn {
                items(shown, key = { it.fileName }) { ch ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenChat(ch.fileName) }, onLongClick = { menuFor = ch })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(ch.file, ch.card.name, Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.card.name, style = MaterialTheme.typography.titleMedium)
                            Text(ch.card.description.ifBlank { ch.card.creatorNotes }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            TextButton(onClick = { menuFor = ch }) { Text("⋮") }
                            DropdownMenu(menuFor == ch, { menuFor = null }) {
                                DropdownMenuItem({ Text("Edit") }, { menuFor = null; onEdit(ch.fileName) })
                                DropdownMenuItem({ Text("Export PNG") }, { menuFor = null; pendingExport = ch; exporter.launch("${ch.fileName}.png") })
                                DropdownMenuItem({ Text("Delete") }, { menuFor = null; confirmDelete = ch })
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    confirmDelete?.let { ch ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${ch.card.name}?") },
            text = { Text("The character card is removed. Chats are kept on disk.") },
            confirmButton = { TextButton(onClick = { c.characters.delete(ch.fileName); confirmDelete = null; reload() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
