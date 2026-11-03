package app.sillytavern.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.tags.Tag
import app.sillytavern.android.tags.TagOps
import app.sillytavern.android.tags.updateTags

internal fun Tag.label() = when (folderType) { "OPEN" -> "📂 $name"; "CLOSED" -> "🔒 $name"; else -> name }

/** Pick an existing tag, or (when [allowNew]) type a new name. [onPick] receives the tag name. */
@Composable
fun TagPickerDialog(title: String, tags: List<Tag>, allowNew: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                if (allowNew) Field("New tag", text, { text = it })
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(tags, key = { it.id }) { t -> TextButton(onClick = { onPick(t.name) }) { Text(t.label()) } }
                }
                if (tags.isEmpty()) Text("No tags yet.")
            }
        },
        confirmButton = { if (allowNew) TextButton(enabled = text.isNotBlank(), onClick = { onPick(text.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tags of one entity (character avatar key / group id) as removable chips plus an add button. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntityTagEditor(c: AppContainer, key: String) {
    val s by c.settings.settings.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val mine = TagOps.tagsOf(s.tagState, key)
    Text("Tags", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        mine.forEach { t ->
            InputChip(selected = false, onClick = { c.settings.updateTags { TagOps.unassign(it, key, t.id) } }, label = { Text(t.label()) },
                trailingIcon = { Text("×") })
        }
        AssistChip(onClick = { picking = true }, label = { Text("+ tag") })
    }
    if (picking) TagPickerDialog("Add tag", s.tags.filter { t -> mine.none { it.id == t.id } }, true,
        onPick = { name -> c.settings.updateTags { st -> TagOps.ensure(st, name)?.let { (n, t) -> TagOps.assign(n, key, t.id) } ?: st }; picking = false },
        onDismiss = { picking = false })
}

@Composable
fun TagManagerScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Tag?>(null) }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    val uses = s.tagMap.values.flatten().groupingBy { it }.eachCount()
    Scaffold(topBar = { BackTopBar("Tags", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("New tag", newName, { newName = it }) }
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    c.settings.updateTags { st -> TagOps.ensure(st, newName)?.first ?: st }; newName = ""
                }) { Text("Add") }
            }
            if (s.tags.isEmpty()) Text("No tags yet. Tags from imported cards appear here.", Modifier.padding(top = 12.dp))
            LazyColumn {
                items(s.tags.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() })), key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${t.name}  (${uses[t.id] ?: 0})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { renaming = t }) { Text("Rename") }
                            TextButton(onClick = { deleting = t }) { Text("Delete") }
                        }
                        val types = listOf("NONE", "OPEN", "CLOSED")
                        Pick("Folder", listOf("Not a folder", "Open folder (show all)", "Closed folder (hide unless selected)"), types.indexOf(t.folderType).coerceAtLeast(0)) {
                            c.settings.updateTags { st -> TagOps.setFolder(st, t.id, types[it]) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    renaming?.let { t ->
        var text by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename tag") },
            text = { Field("Name", text, { text = it }) },
            confirmButton = { TextButton(onClick = { c.settings.updateTags { TagOps.rename(it, t.id, text) }; renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { t ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete tag ${t.name}?") },
            text = { Text("It is removed from every character and group.") },
            confirmButton = { TextButton(onClick = { c.settings.updateTags { TagOps.delete(it, t.id) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
