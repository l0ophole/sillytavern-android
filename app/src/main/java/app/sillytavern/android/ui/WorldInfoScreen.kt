package app.sillytavern.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.worldinfo.*

@Composable
internal fun Pick(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 2.dp)) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: ${options.getOrElse(selected) { "?" }}") }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, { open = false; onSelect(i) }) }
        }
    }
}

@Composable
private fun Tri(label: String, v: Boolean?, onChange: (Boolean?) -> Unit) =
    Pick(label, listOf("Default", "Yes", "No"), when (v) { null -> 0; true -> 1; false -> 2 }) { onChange(listOf(null, true, false)[it]) }

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, onChange); Spacer(Modifier.width(8.dp)); Text(label, Modifier.weight(1f))
    }

@Composable
private fun NumField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    Field(label, text, { text = it; onChange(it.trim().toIntOrNull()) }, number = true)
}

private val positions = listOf("Before character defs", "After character defs", "Author's note top", "Author's note bottom", "At depth", "Example messages top", "Example messages bottom", "Outlet")
private val logics = listOf("AND ANY", "NOT ALL", "NOT ANY", "AND ALL")
private val roles = listOf("System", "User", "Assistant")

/** Book list, import/export, global activation, and global World Info settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldInfoScreen(c: AppContainer, onOpenBook: (String) -> Unit, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val ctx = LocalContext.current
    var names by remember { mutableStateOf(c.worlds.list()) }
    var message by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var exportName by remember { mutableStateOf("") }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) message = runCatching { c.worlds.import(uri.lastPathSegment.orEmpty(), String(ctx.readUri(uri))).getOrThrow() }
            .fold({ names = c.worlds.list(); "Imported \"$it\"" }, { "Import failed: ${it.message}" })
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val text = c.worlds.export(exportName)
        if (uri != null && text != null) message = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
            .fold({ "Exported" }, { "Export failed: ${it.message}" })
    }
    val wi = s.worldInfo
    fun set(f: (WorldInfoSettings) -> WorldInfoSettings) = c.settings.update { it.copy(worldInfo = f(it.worldInfo)) }

    Scaffold(topBar = { BackTopBar("World Info", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lorebooks", style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import") }
                    TextButton(onClick = { newName = "" }) { Text("New") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (names.isEmpty()) Text("No lorebooks yet. Import one (a world file or a character card with an embedded book) or create a new one.")
            names.forEach { n ->
                val on = n in s.globalWorldBooks
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(on, { v -> c.settings.update { st -> st.copy(globalWorldBooks = if (v) (st.globalWorldBooks + n).distinct() else st.globalWorldBooks - n) } })
                    Spacer(Modifier.width(8.dp))
                    Text(n, Modifier.weight(1f).clickable { onOpenBook(n) }, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { onOpenBook(n) }) { Text("Edit") }
                    TextButton(onClick = { exportName = n; exporter.launch("$n.json") }) { Text("Export") }
                    TextButton(onClick = { deleting = n }) { Text("Delete") }
                }
            }
            Text("Switch on = active for every chat (global). Character and chat books are attached in their own screens.", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(16.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            NumField("Scan depth (messages)", wi.depth) { v -> v?.let { set { s -> s.copy(depth = it.coerceAtLeast(0)) } } }
            NumField("Budget (% of context)", wi.budget) { v -> v?.let { set { s -> s.copy(budget = it.coerceIn(1, 100)) } } }
            NumField("Budget cap (tokens, 0 = none)", wi.budgetCap) { v -> v?.let { set { s -> s.copy(budgetCap = it.coerceAtLeast(0)) } } }
            NumField("Min activations (0 = off)", wi.minActivations) { v -> v?.let { set { s -> s.copy(minActivations = it.coerceAtLeast(0)) } } }
            NumField("Max depth for min activations (0 = unlimited)", wi.minActivationsDepthMax) { v -> v?.let { set { s -> s.copy(minActivationsDepthMax = it.coerceAtLeast(0)) } } }
            NumField("Max recursion steps (0 = unlimited)", wi.maxRecursionSteps) { v -> v?.let { set { s -> s.copy(maxRecursionSteps = it.coerceAtLeast(0)) } } }
            Pick("Insertion strategy", listOf("Sorted evenly", "Character lore first", "Global lore first"), wi.characterStrategy) { v -> set { it.copy(characterStrategy = v) } }
            SwitchRow("Include names in scan text", wi.includeNames) { v -> set { it.copy(includeNames = v) } }
            SwitchRow("Recursive scan", wi.recursive) { v -> set { it.copy(recursive = v) } }
            SwitchRow("Case-sensitive keys", wi.caseSensitive) { v -> set { it.copy(caseSensitive = v) } }
            SwitchRow("Match whole words", wi.matchWholeWords) { v -> set { it.copy(matchWholeWords = v) } }
            SwitchRow("Use inclusion-group scoring", wi.useGroupScoring) { v -> set { it.copy(useGroupScoring = v) } }
            SwitchRow("Alert when budget is reached", wi.overflowAlert) { v -> set { it.copy(overflowAlert = v) } }
            Spacer(Modifier.height(32.dp))
        }
    }

    newName?.let { n ->
        AlertDialog(onDismissRequest = { newName = null }, title = { Text("New lorebook") },
            text = { Field("Name", n, { newName = it }) },
            confirmButton = {
                TextButton(enabled = n.isNotBlank() && n.trim() !in names, onClick = {
                    c.worlds.save(WorldBook(n.trim())); names = c.worlds.list(); newName = null; onOpenBook(n.trim())
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newName = null }) { Text("Cancel") } })
    }
    deleting?.let { n ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"$n\"?") },
            confirmButton = {
                TextButton(onClick = {
                    c.worlds.delete(n); names = c.worlds.list(); deleting = null
                    c.settings.update { st -> st.copy(globalWorldBooks = st.globalWorldBooks - n) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

/** Entry list of one book with an editor dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookScreen(c: AppContainer, name: String, onBack: () -> Unit) {
    var book by remember(name) { mutableStateOf(c.worlds.load(name) ?: WorldBook(name)) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<WorldEntry?>(null) }
    fun change(f: (WorldBook) -> WorldBook) { book = f(book); c.worlds.save(book) }
    val shown = book.entries.filter {
        query.isBlank() || it.comment.contains(query, true) || it.key.any { k -> k.contains(query, true) } || it.content.contains(query, true)
    }
    Scaffold(
        topBar = { BackTopBar(name, onBack, actions = { TextButton(onClick = { change { it.addEntry() }; editing = book.entries.last() }) { Text("Add entry") } }) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Field("Search", query, { query = it })
            Text("${book.entries.size} entries", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.uid }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { editing = e }, verticalAlignment = Alignment.CenterVertically) {
                        Switch(!e.disable, { on -> change { it.updateEntry(e.copy(disable = !on)) } })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.comment.ifBlank { formatKeys(e.key).ifBlank { "(no keys)" } }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                            Text(
                                (if (e.constant) "constant · " else "") + positions[e.position.coerceIn(0, 7)] + " · order ${e.order}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            )
                        }
                        TextButton(onClick = { change { it.moveEntry(e.uid, -1) } }) { Text("↑") }
                        TextButton(onClick = { change { it.moveEntry(e.uid, 1) } }) { Text("↓") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    editing?.let { e0 ->
        EntryDialog(
            e0, onDismiss = { editing = null },
            onSave = { e -> change { it.updateEntry(e) }; editing = null },
            onDuplicate = { change { it.duplicateEntry(e0.uid) }; editing = null },
            onDelete = { change { it.deleteEntry(e0.uid) }; editing = null },
        )
    }
}

@Composable
private fun EntryDialog(e0: WorldEntry, onDismiss: () -> Unit, onSave: (WorldEntry) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var e by remember(e0.uid) { mutableStateOf(e0) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Entry ${e.uid}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Title / comment", e.comment, { e = e.copy(comment = it) })
                Field("Content", e.content, { e = e.copy(content = it) }, singleLine = false, minLines = 4)
                SwitchRow("Constant (always active)", e.constant) { e = e.copy(constant = it) }
                Field("Primary keys (comma separated, /regex/i allowed)", formatKeys(e.key), { e = e.copy(key = parseKeys(it)) }, singleLine = false)
                Field("Secondary keys", formatKeys(e.keysecondary), { e = e.copy(keysecondary = parseKeys(it)) }, singleLine = false)
                Pick("Secondary logic", logics, e.selectiveLogic) { e = e.copy(selectiveLogic = it) }
                Pick("Position", positions, e.position) { e = e.copy(position = it) }
                if (e.position == WiPosition.AT_DEPTH) {
                    NumField("Depth", e.depth) { v -> e = e.copy(depth = v ?: DEFAULT_DEPTH) }
                    Pick("Role", roles, e.role) { e = e.copy(role = it) }
                }
                if (e.position == WiPosition.OUTLET) Field("Outlet name", e.outletName, { e = e.copy(outletName = it) })
                NumField("Order", e.order) { v -> e = e.copy(order = v ?: 100) }
                SwitchRow("Use probability", e.useProbability) { e = e.copy(useProbability = it) }
                if (e.useProbability) NumField("Probability %", e.probability) { v -> e = e.copy(probability = (v ?: 100).coerceIn(0, 100)) }
                NumField("Scan depth override (blank = default)", e.scanDepth) { e = e.copy(scanDepth = it) }
                Tri("Case-sensitive", e.caseSensitive) { e = e.copy(caseSensitive = it) }
                Tri("Match whole words", e.matchWholeWords) { e = e.copy(matchWholeWords = it) }
                Tri("Group scoring", e.useGroupScoring) { e = e.copy(useGroupScoring = it) }
                Field("Inclusion group(s)", e.group, { e = e.copy(group = it) })
                SwitchRow("Group override (always wins)", e.groupOverride) { e = e.copy(groupOverride = it) }
                NumField("Group weight", e.groupWeight) { v -> e = e.copy(groupWeight = v ?: DEFAULT_WEIGHT) }
                SwitchRow("Non-recursable (not triggered by other entries)", e.excludeRecursion) { e = e.copy(excludeRecursion = it) }
                SwitchRow("Prevent further recursion", e.preventRecursion) { e = e.copy(preventRecursion = it) }
                NumField("Delay until recursion level (0 = off)", e.delayUntilRecursion) { v -> e = e.copy(delayUntilRecursion = (v ?: 0).coerceAtLeast(0)) }
                SwitchRow("Ignore token budget", e.ignoreBudget) { e = e.copy(ignoreBudget = it) }
                NumField("Sticky (messages, blank = off)", e.sticky) { e = e.copy(sticky = it) }
                NumField("Cooldown (messages)", e.cooldown) { e = e.copy(cooldown = it) }
                NumField("Delay (until chat has N messages)", e.delay) { e = e.copy(delay = it) }
                Field("Only for generation types (normal, continue, impersonate, swipe, regenerate, quiet)", e.triggers.joinToString(", "),
                    { e = e.copy(triggers = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) })
                Text("Also scan:", style = MaterialTheme.typography.labelLarge)
                SwitchRow("Persona description", e.matchPersonaDescription) { e = e.copy(matchPersonaDescription = it) }
                SwitchRow("Character description", e.matchCharacterDescription) { e = e.copy(matchCharacterDescription = it) }
                SwitchRow("Character personality", e.matchCharacterPersonality) { e = e.copy(matchCharacterPersonality = it) }
                SwitchRow("Character depth prompt", e.matchCharacterDepthPrompt) { e = e.copy(matchCharacterDepthPrompt = it) }
                SwitchRow("Scenario", e.matchScenario) { e = e.copy(matchScenario = it) }
                SwitchRow("Creator's notes", e.matchCreatorNotes) { e = e.copy(matchCreatorNotes = it) }
                Field("Character filter: file names", e.characterFilterNames.joinToString(", "),
                    { e = e.copy(characterFilterNames = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) })
                Field("Character filter: tags", e.characterFilterTags.joinToString(", "),
                    { e = e.copy(characterFilterTags = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) })
                SwitchRow("Character filter is an exclusion list", e.characterFilterExclude) { e = e.copy(characterFilterExclude = it) }
                Row { TextButton(onClick = onDuplicate) { Text("Duplicate") }; TextButton(onClick = onDelete) { Text("Delete") } }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(e) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
