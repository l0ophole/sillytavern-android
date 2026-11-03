package app.sillytavern.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.quickreply.QuickReplyService
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(c: AppContainer, characterFile: String, onBack: () -> Unit, onEdit: (String) -> Unit, groupId: String? = null, onEditGroup: (String) -> Unit = {}) {
    val ctl = c.chatController
    val state by ctl.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var actionsFor by remember { mutableStateOf<Int?>(null) }
    var checkpointFor by remember { mutableStateOf<Int?>(null) }
    var showChats by remember { mutableStateOf(false) }
    var showBook by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showPersona by remember { mutableStateOf(false) }
    var showQrSet by remember { mutableStateOf(false) }
    val appSettings by c.settings.settings.collectAsState()
    val bar = remember(appSettings, state.chat?.file, state.character) { c.quickReplies.bar() }

    LaunchedEffect(characterFile, groupId) {
        if (groupId != null) { if (state.group?.id != groupId) c.groups.get(groupId)?.let { ctl.openGroup(it) } }
        else if (state.group != null || state.character?.fileName != characterFile) c.characters.get(characterFile)?.let { ctl.open(it) }
    }
    DisposableEffect(Unit) { onDispose { /* generation continues while app process lives; see worklog */ } }
    val messages = state.chat?.messages.orEmpty()
    val tailLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, tailLen) { if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }
    LaunchedEffect(state.impersonation, state.generating) {
        val imp = state.impersonation
        if (imp != null) { input = imp; if (!state.generating) ctl.consumeImpersonation() }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); ctl.clearError() } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); ctl.clearNotice() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.group?.name ?: state.character?.card?.name ?: "") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                actions = {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("New chat") }, { menu = false; ctl.newChat() })
                            DropdownMenuItem({ Text("Chats…") }, { menu = false; showChats = true })
                            if (ctl.mainChatName() != null) DropdownMenuItem({ Text("Back to main chat") }, { menu = false; ctl.backToMain() })
                            DropdownMenuItem({ Text("Regenerate") }, { menu = false; ctl.regenerate() })
                            DropdownMenuItem({ Text("Continue") }, { menu = false; ctl.continueLast() })
                            DropdownMenuItem({ Text("Impersonate") }, { menu = false; ctl.impersonate() })
                            DropdownMenuItem({ Text("Persona… (${appSettings.activePersona.name})") }, { menu = false; showPersona = true })
                            DropdownMenuItem({ Text("Chat lorebook…") }, { menu = false; showBook = true })
                            DropdownMenuItem({ Text("Chat quick reply set…") }, { menu = false; showQrSet = true })
                            val grp = state.group
                            if (grp != null) {
                                DropdownMenuItem({ Text(if (state.autoMode) "Auto mode: turn off" else "Auto mode: turn on") }, { menu = false; ctl.setAutoMode(!state.autoMode) })
                                DropdownMenuItem({ Text("Edit group") }, { menu = false; onEditGroup(grp.id) })
                            } else DropdownMenuItem({ Text("Edit character") }, { menu = false; onEdit(characterFile) })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
          Column {
            if (bar.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(bar.size) { idx ->
                    val (set, qr) = bar[idx]
                    AssistChip(onClick = {
                        scope.launch {
                            val o = ctl.pressQuickReply(set, qr, input)
                            o.error?.let { snackbar.showSnackbar(it) }
                            o.inputText?.let { input = it }
                            o.textToSend?.let { input = ""; ctl.submit(it) }
                        }
                    }, label = { Text(qr.label.ifBlank { "?" }, maxLines = 1) })
                }
            }
            state.group?.let { g -> MemberStrip(g, state.members, state.generating, state.groupQueue, onSpeak = ctl::speak, onMute = ctl::muteMember) }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 6)
                Spacer(Modifier.width(8.dp))
                if (state.generating) Button(onClick = { ctl.stop() }) { Text("Stop") }
                else Button(enabled = input.isNotBlank(), onClick = { ctl.submit(input); input = "" }) { Text("Send") }
            }
          }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(messages) { i, m ->
                val shown = remember(m.text, m.isUser, appSettings.regexEnabled, state.character) { ctl.displayText(m) }
                val shownReasoning = remember(m.reasoning, appSettings.regexEnabled, state.character) { m.reasoning?.let(ctl::displayReasoning) }
                MessageBubble(
                    m, shown = shown, shownReasoning = shownReasoning, isLast = i == messages.lastIndex, generating = state.generating && i == messages.lastIndex,
                    onLongPress = { actionsFor = i }, onSwipe = { d -> ctl.swipe(i, d) },
                    onOpenCheckpoint = { ctl.openCheckpoint(i) },
                )
            }
        }
    }

    actionsFor?.let { i ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text("Message") },
            text = {
                Column {
                    TextButton(onClick = { editing = i; actionsFor = null }) { Text("Edit") }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(messages[i].text)); actionsFor = null }) { Text("Copy") }
                    TextButton(onClick = { ctl.setHidden(i, !messages[i].isHidden); actionsFor = null }) {
                        Text(if (messages[i].isHidden) "Unhide (include in prompt)" else "Hide (exclude from prompt)")
                    }
                    TextButton(onClick = { ctl.createBranch(i, messages[i].swipeId.takeIf { messages[i].swipes.size > 1 }); actionsFor = null }) { Text("Branch from here") }
                    TextButton(onClick = { checkpointFor = i; actionsFor = null }) { Text(if (messages[i].bookmarkLink != null) "Replace checkpoint…" else "Create checkpoint…") }
                    if (messages[i].bookmarkLink != null) TextButton(onClick = { ctl.openCheckpoint(i); actionsFor = null }) { Text("Open checkpoint") }
                    TextButton(onClick = { ctl.delete(i); actionsFor = null }) { Text("Delete") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text("Close") } },
        )
    }
    checkpointFor?.let { i ->
        var name by remember(i) { mutableStateOf(messages.getOrNull(i)?.bookmarkLink.orEmpty()) }
        AlertDialog(
            onDismissRequest = { checkpointFor = null }, title = { Text("Create checkpoint") },
            text = { Field("Name (empty = automatic)", name, { name = it }) },
            confirmButton = { TextButton(onClick = { ctl.createCheckpoint(i, name.ifBlank { null }); checkpointFor = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { checkpointFor = null }) { Text("Cancel") } },
        )
    }
    editing?.let { i ->
        var text by remember(i) { mutableStateOf(messages.getOrNull(i)?.text ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit message") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = { TextButton(onClick = { ctl.edit(i, text); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (showQrSet) {
        val names = remember { c.quickReplyRepo.list() }
        var sel by remember { mutableStateOf(ctl.chatQuickReplySet()) }
        AlertDialog(onDismissRequest = { showQrSet = false }, title = { Text("Quick reply set for this chat") },
            text = { Pick("Set", listOf("(none)") + names, if (sel == null) 0 else names.indexOf(sel) + 1) { sel = if (it == 0) null else names[it - 1] } },
            confirmButton = { TextButton(onClick = { ctl.setChatQuickReplySet(sel); showQrSet = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showQrSet = false }) { Text("Cancel") } })
    }
    if (showPersona) PersonaLockDialog(c) { showPersona = false }
    if (showChats) ChatListDialog(c, onDismiss = { showChats = false })
    if (showBook) {
        val names = remember { c.worlds.list() }
        val current = ctl.chatBook()
        AlertDialog(
            onDismissRequest = { showBook = false }, title = { Text("Lorebook for this chat") },
            text = { Pick("Book", listOf("(none)") + names, if (current == null) 0 else names.indexOf(current) + 1) { ctl.setChatBook(if (it == 0) null else names[it - 1]); showBook = false } },
            confirmButton = { TextButton(onClick = { showBook = false }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: ChatMessage, shown: String, shownReasoning: String?, isLast: Boolean, generating: Boolean, onLongPress: () -> Unit, onSwipe: (Int) -> Unit, onOpenCheckpoint: () -> Unit) {
    val color = when { m.isNarrator -> MaterialTheme.colorScheme.tertiaryContainer; m.isUser -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant }
    Column(
        Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongPress).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            m.bookmarkLink?.let { TextButton(onClick = onOpenCheckpoint, contentPadding = PaddingValues(0.dp)) { Text("⚑ ${it.take(24)}", style = MaterialTheme.typography.labelSmall, maxLines = 1) } }
        }
        shownReasoning?.let { r ->
            var open by remember(m.sendDate) { mutableStateOf(false) }
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
                Text(if (open) "▾ Thinking" else "▸ Thinking…", style = MaterialTheme.typography.labelMedium)
            }
            if (open) Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(if (m.text.isEmpty() && generating) "…" else shown + if (m.isHidden) "  (hidden)" else "", style = MaterialTheme.typography.bodyLarge)
        if (isLast && !m.isUser && (m.swipes.size > 1 || !generating)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = m.swipeId > 0 && !generating, onClick = { onSwipe(-1) }) { Text("◀") }
                Text("${m.swipeId + 1}/${m.swipes.size}", style = MaterialTheme.typography.labelSmall)
                TextButton(enabled = !generating, onClick = { onSwipe(1) }) { Text("▶") }
            }
        }
    }
}

@Composable
private fun ChatListDialog(c: AppContainer, onDismiss: () -> Unit) {
    val ctl = c.chatController
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(ctl.chatFiles()) }
    val current = ctl.state.collectAsState().value.chat?.file
    var message by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<File, Boolean>?>(null) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
        text.onFailure { message = "Could not read the file: ${it.message}" }
        text.onSuccess { t ->
            ctl.importChat(t).onSuccess { f -> files = ctl.chatFiles(); ctl.openChatFile(f); onDismiss() }
                .onFailure { message = "Import failed: ${it.message}" }
        }
    }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val (file, asText) = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val data = ctl.exportChat(file, asText)
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data.toByteArray(Charsets.UTF_8)) }
        }.onFailure { message = "Export failed: ${it.message}" }.onSuccess { message = "Exported" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chats") },
        text = {
            Column {
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    itemsIndexed(files) { _, f: File ->
                        var rowMenu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(modifier = Modifier.weight(1f), onClick = { ctl.openChatFile(f); onDismiss() }) {
                                Text(f.nameWithoutExtension + if (f == current) "  ✓" else "", maxLines = 2)
                            }
                            Box {
                                TextButton(onClick = { rowMenu = true }) { Text("⋮") }
                                DropdownMenu(rowMenu, { rowMenu = false }) {
                                    DropdownMenuItem({ Text("Export as JSONL") }, { rowMenu = false; pendingExport = f to false; exporter.launch(f.nameWithoutExtension + ".jsonl") })
                                    DropdownMenuItem({ Text("Export as text") }, { rowMenu = false; pendingExport = f to true; exporter.launch(f.nameWithoutExtension + ".txt") })
                                    DropdownMenuItem({ Text("Delete") }, { rowMenu = false; ctl.deleteChatFile(f); files = ctl.chatFiles() })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import…") } },
    )
}

/** Group members as chips: tap = that member speaks, long-press = mute / unmute. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberStrip(
    g: app.sillytavern.android.group.Group, members: List<app.sillytavern.android.card.StoredCharacter>, generating: Boolean,
    queue: List<String>, onSpeak: (String) -> Unit, onMute: (String, Boolean) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(g.members.size) { idx ->
            val a = g.members[idx]
            val ch = members.firstOrNull { it.fileName + ".png" == a }
            val muted = g.isMuted(a)
            val label = (if (muted) "🔇 " else if (a in queue) "▶ " else "") + (ch?.card?.name ?: "${a.removeSuffix(".png")} (missing)")
            Surface(
                shape = RoundedCornerShape(16.dp), tonalElevation = if (muted) 0.dp else 3.dp,
                color = if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.combinedClickable(enabled = ch != null, onClick = { if (!generating) onSpeak(a) }, onLongClick = { onMute(a, !muted) }),
            ) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
    }
}
