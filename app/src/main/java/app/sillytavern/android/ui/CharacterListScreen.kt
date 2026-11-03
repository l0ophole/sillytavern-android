package app.sillytavern.android.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import app.sillytavern.android.group.Group
import app.sillytavern.android.tags.*
import coil.compose.AsyncImage

fun Context.readUri(uri: Uri): ByteArray =
    contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")

/** A row in the mixed list: a character or a group. */
private sealed interface Entry {
    val key: String; val name: String; val fav: Boolean
    data class Char(val ch: StoredCharacter) : Entry {
        override val key = ch.fileName + ".png"; override val name = ch.card.name; override val fav = ch.card.fav
    }
    data class Grp(val g: Group) : Entry {
        override val key = g.id; override val name = g.name; override val fav = g.fav
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CharacterListScreen(
    c: AppContainer, onOpenChat: (String) -> Unit, onEdit: (String?) -> Unit, onSettings: () -> Unit,
    onOpenGroup: (String) -> Unit = {}, onEditGroup: (String?) -> Unit = {},
) {
    val ctx = LocalContext.current
    val settings by c.settings.settings.collectAsState()
    var chars by remember { mutableStateOf(emptyList<StoredCharacter>()) }
    var groups by remember { mutableStateOf(emptyList<Group>()) }
    var query by remember { mutableStateOf("") }
    var include by remember { mutableStateOf(emptySet<String>()) }
    var exclude by remember { mutableStateOf(emptySet<String>()) }
    var favOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<StoredCharacter?>(null) }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var bulkMenu by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf<String?>(null) } // "add" | "remove"
    var groupName by remember { mutableStateOf<String?>(null) }

    fun reload() {
        chars = c.characters.list(); groups = c.groups.list()
        // Characters never seen before get their card tags imported into the tag map (as SillyTavern does on import).
        val known = c.settings.settings.value.tagMap.keys
        chars.filter { it.card.tags.isNotEmpty() && (it.fileName + ".png") !in known }.forEach { ch ->
            c.settings.updateTags { TagOps.importCardTags(it, ch.fileName + ".png", ch.card.tags) }
        }
    }
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

    val state = settings.tagState
    val entries: List<Entry> = chars.map { Entry.Char(it) } + groups.map { Entry.Grp(it) }
    val byKey = entries.associateBy { it.key }
    val shown = TagFilter.apply(
        entries.map { e -> TagEntity(e.key, e.name, e.fav, (e as? Entry.Char)?.ch?.card?.tags.orEmpty()) },
        state, include, exclude, favOnly, query,
    ).mapNotNull { byKey[it.key] }.sortedWith(compareByDescending<Entry> { it.fav }.thenBy { it.name.lowercase() })
    selected = selected.filter { it in byKey }.toSet()

    fun toggleFav(e: Entry, fav: Boolean) {
        when (e) { is Entry.Char -> c.characters.setFav(e.ch.fileName, fav); is Entry.Grp -> c.groups.save(e.g.copy(fav = fav)) }
    }
    fun selectedEntries() = selected.mapNotNull(byKey::get)

    Scaffold(
        topBar = {
            if (selected.isEmpty()) TopAppBar(title = { Text("Characters") }, actions = {
                TextButton(onClick = { importer.launch(arrayOf("image/png", "application/json", "*/*")) }) { Text("Import") }
                TextButton(onClick = onSettings) { Text("Settings") }
            }) else TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = { TextButton(onClick = { selected = emptySet() }) { Text("✕") } },
                actions = {
                    TextButton(onClick = { selected = shown.map { it.key }.toSet() }) { Text("All") }
                    Box {
                        TextButton(onClick = { bulkMenu = true }) { Text("⋮") }
                        DropdownMenu(bulkMenu, { bulkMenu = false }) {
                            DropdownMenuItem({ Text("Favorite") }, { bulkMenu = false; selectedEntries().forEach { toggleFav(it, true) }; reload() })
                            DropdownMenuItem({ Text("Remove favorite") }, { bulkMenu = false; selectedEntries().forEach { toggleFav(it, false) }; reload() })
                            DropdownMenuItem({ Text("Add tag…") }, { bulkMenu = false; tagDialog = "add" })
                            DropdownMenuItem({ Text("Remove tag…") }, { bulkMenu = false; tagDialog = "remove" })
                            if (selectedEntries().any { it is Entry.Char })
                                DropdownMenuItem({ Text("Create group…") }, { bulkMenu = false; groupName = "" })
                            DropdownMenuItem({ Text("Delete…") }, { bulkMenu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = true }) { Text("+") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem({ Text("New character") }, { addMenu = false; onEdit(null) })
                    DropdownMenuItem({ Text("New group") }, { addMenu = false; onEditGroup(null) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Field("Search", query, { query = it })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(favOnly, { favOnly = !favOnly }, label = { Text("★ Favorites") })
                state.tags.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() })).forEach { t ->
                    val on = t.id in include; val off = t.id in exclude
                    Box(Modifier.combinedClickable(
                        onClick = {
                            // tap: off -> include; include/exclude -> off
                            if (on || off) { include = include - t.id; exclude = exclude - t.id } else include = include + t.id
                        },
                        onLongClick = { include = include - t.id; exclude = exclude + t.id },
                    )) {
                        FilterChip(on || off, onClick = {}, enabled = false, label = { Text((if (off) "not " else "") + t.label()) })
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(if (entries.isEmpty()) "No characters yet. Import a card (PNG/JSON) or tap +." else "No matches.", Modifier.padding(top = 24.dp))
            }
            LazyColumn {
                items(shown, key = { it.key }) { e ->
                    val isSel = e.key in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selected.isNotEmpty()) selected = if (isSel) selected - e.key else selected + e.key
                                    else when (e) { is Entry.Char -> onOpenChat(e.ch.fileName); is Entry.Grp -> onOpenGroup(e.g.id) }
                                },
                                onLongClick = { selected = if (isSel) selected - e.key else selected + e.key },
                            )
                            .then(if (isSel) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (e) {
                            is Entry.Char -> AsyncImage(e.ch.file, e.name, Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            is Entry.Grp -> Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) { Text("👥") }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleMedium)
                            val sub = when (e) {
                                is Entry.Char -> e.ch.card.description.ifBlank { e.ch.card.creatorNotes }
                                is Entry.Grp -> "Group · " + e.g.members.joinToString(", ") { a -> chars.firstOrNull { it.fileName + ".png" == a }?.card?.name ?: a.removeSuffix(".png") }
                            }
                            Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            val tagNames = TagOps.tagsOf(state, e.key).filter { !it.hiddenOnCard }.joinToString(" · ") { it.name }
                            if (tagNames.isNotEmpty()) Text(tagNames, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { toggleFav(e, !e.fav); reload() }) { Text(if (e.fav) "★" else "☆") }
                        Box {
                            TextButton(onClick = { menuFor = e }) { Text("⋮") }
                            DropdownMenu(menuFor == e, { menuFor = null }) {
                                DropdownMenuItem({ Text("Edit") }, { menuFor = null; when (e) { is Entry.Char -> onEdit(e.ch.fileName); is Entry.Grp -> onEditGroup(e.g.id) } })
                                if (e is Entry.Char) DropdownMenuItem({ Text("Export PNG") }, { menuFor = null; pendingExport = e.ch; exporter.launch("${e.ch.fileName}.png") })
                                DropdownMenuItem({ Text("Delete") }, { menuFor = null; selected = setOf(e.key); confirmDelete = true })
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmDelete) {
        val victims = selectedEntries()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${victims.size} item(s)?") },
            text = { Text("Character cards and groups (with their group chats) are removed. Character chats are kept on disk.") },
            confirmButton = {
                TextButton(onClick = {
                    victims.forEach { v ->
                        when (v) { is Entry.Char -> c.characters.delete(v.ch.fileName); is Entry.Grp -> c.groups.delete(v.g.id) }
                        c.settings.updateTags { TagOps.removeKey(it, v.key) }
                    }
                    selected = emptySet(); confirmDelete = false; reload()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    tagDialog?.let { mode ->
        val add = mode == "add"
        val pool = if (add) state.tags else state.tags.filter { t -> selected.any { k -> t.id in state.map[k].orEmpty() } }
        TagPickerDialog(if (add) "Add tag to ${selected.size}" else "Remove tag from ${selected.size}", pool, allowNew = add,
            onPick = { name ->
                c.settings.updateTags { st ->
                    if (add) TagOps.ensure(st, name)?.let { (n, t) -> selected.fold(n) { acc, k -> TagOps.assign(acc, k, t.id) } } ?: st
                    else st.tags.firstOrNull { it.name == name }?.let { t -> selected.fold(st) { acc, k -> TagOps.unassign(acc, k, t.id) } } ?: st
                }
                tagDialog = null
            }, onDismiss = { tagDialog = null })
    }
    groupName?.let { n ->
        AlertDialog(onDismissRequest = { groupName = null }, title = { Text("Create group") },
            text = { Field("Group name", n, { groupName = it }) },
            confirmButton = {
                TextButton(enabled = n.isNotBlank(), onClick = {
                    val members = selectedEntries().filterIsInstance<Entry.Char>().map { it.key }
                    c.groups.create(n.trim(), members)
                    groupName = null; selected = emptySet(); reload(); message = "Group created"
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { groupName = null }) { Text("Cancel") } })
    }
}
