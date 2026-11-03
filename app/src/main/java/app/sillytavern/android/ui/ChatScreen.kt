package app.sillytavern.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import app.sillytavern.android.media.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import app.sillytavern.android.AppContainer
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.quickreply.QuickReplyService
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(c: AppContainer, characterFile: String, onBack: () -> Unit, onEdit: (String) -> Unit, groupId: String? = null, onEditGroup: (String) -> Unit = {}, onGallery: (String) -> Unit = {}) {
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
    var showNote by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showPersona by remember { mutableStateOf(false) }
    var showQrSet by remember { mutableStateOf(false) }
    var showImageGen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) readScaledImage(ctx, uri, c.settings.settings.value.media.attachments.maxImageSide)?.let { (bytes, name) -> ctl.attachImage(bytes, name) }
    }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(bytes.size <= 1_000_000) { "Text files up to 1 MB can be attached" }
            ctl.attachTextFile(bytes.toString(Charsets.UTF_8), displayName(ctx, uri) ?: "file.txt")
        }.onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "Could not read the file") } }
    }
    var attachMenu by remember { mutableStateOf(false) }
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
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); ctl.clearError() } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); ctl.clearNotice() } }

    val look = LocalAppearance.current
    val chatColors = LocalChatColors.current
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val columnDp = app.sillytavern.android.appearance.AppearanceSettings.chatColumnDp(screenDp, look.chatWidth).dp
    val bgFile = remember(look.background) { c.backgrounds.file(look.background) }
    Box(Modifier.fillMaxSize()) {
    if (bgFile != null) {
        coil.compose.AsyncImage(bgFile, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = look.backgroundDim.coerceIn(0f, 0.9f))))
    }
    Scaffold(
        containerColor = if (bgFile != null) androidx.compose.ui.graphics.Color.Transparent else chatColors.chatBackground ?: MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.group?.name ?: state.character?.card?.name ?: "") },
                navigationIcon = { TextButton(onClick = onBack) { Text(tr("‹ Back")) } },
                actions = {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(tr("New chat")) }, { menu = false; ctl.newChat() })
                            DropdownMenuItem({ Text(tr("Chats…")) }, { menu = false; showChats = true })
                            if (ctl.mainChatName() != null) DropdownMenuItem({ Text(tr("Back to main chat")) }, { menu = false; ctl.backToMain() })
                            DropdownMenuItem({ Text(tr("Regenerate")) }, { menu = false; ctl.regenerate() })
                            DropdownMenuItem({ Text(tr("Continue")) }, { menu = false; ctl.continueLast() })
                            DropdownMenuItem({ Text(tr("Impersonate")) }, { menu = false; ctl.impersonate() })
                            DropdownMenuItem({ Text(tr("Author's Note…")) }, { menu = false; showNote = true })
                            DropdownMenuItem({ Text(tr("Summary…")) }, { menu = false; showSummary = true })
                            DropdownMenuItem({ Text(tr("Persona…") + " (${appSettings.activePersona.name})") }, { menu = false; showPersona = true })
                            DropdownMenuItem({ Text(tr("Chat lorebook…")) }, { menu = false; showBook = true })
                            DropdownMenuItem({ Text(tr("Chat quick reply set…")) }, { menu = false; showQrSet = true })
                            DropdownMenuItem({ Text(tr("Generate image…")) }, { menu = false; showImageGen = true })
                            DropdownMenuItem({ Text(tr("Gallery")) }, { menu = false; onGallery(ctl.imageFolder()) })
                            DropdownMenuItem({ Text(tr("Stop speaking")) }, { menu = false; ctl.stopSpeaking() })
                            val grp = state.group
                            if (grp != null) {
                                DropdownMenuItem({ Text(tr(if (state.autoMode) "Auto mode: turn off" else "Auto mode: turn on")) }, { menu = false; ctl.setAutoMode(!state.autoMode) })
                                DropdownMenuItem({ Text(tr("Edit group")) }, { menu = false; onEditGroup(grp.id) })
                            } else DropdownMenuItem({ Text(tr("Edit character")) }, { menu = false; onEdit(characterFile) })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
          Column(Modifier.widthIn(max = columnDp)) {
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
            SpritePanel(c, state.expression, ctl.spriteOwner())
            state.group?.let { g -> MemberStrip(g, state.members, state.generating, state.groupQueue, onSpeak = ctl::speak, onMute = ctl::muteMember) }
            if (state.pendingImage != null || state.pendingFile != null || state.mediaBusy) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                state.pendingImage?.let { p -> app.sillytavern.android.media.MessageMedia.resolve(c.paths.root, p)?.let { f -> coil.compose.AsyncImage(f, null, Modifier.size(48.dp)) } }
                state.pendingFile?.let { Text("📎 ${it.name}", style = MaterialTheme.typography.labelMedium) }
                if (state.mediaBusy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  " + tr("Working…"), style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.weight(1f))
                if (state.pendingImage != null || state.pendingFile != null) TextButton(onClick = { ctl.clearAttachments() }) { Text(tr("Remove")) }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    TextButton(onClick = { attachMenu = true }) { Text("＋") }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem({ Text(tr("Image…")) }, { attachMenu = false; pickImage.launch("image/*") })
                        DropdownMenuItem({ Text(tr("Text file…")) }, { attachMenu = false; pickFile.launch("text/*") })
                    }
                }
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(tr("Message")) }, maxLines = 6)
                if (appSettings.media.stt.enabled) MicButton(c, onText = { t ->
                    if (appSettings.media.stt.autoSend) ctl.submit(t) else input = if (input.isBlank()) t else input.trimEnd() + " " + t
                }, onError = { m -> scope.launch { snackbar.showSnackbar(m) } })
                Spacer(Modifier.width(4.dp))
                if (state.generating) Button(onClick = { ctl.stop() }) { Text(tr("Stop")) }
                else Button(enabled = input.isNotBlank() || state.pendingImage != null || state.pendingFile != null, onClick = { ctl.submit(input); input = "" }) { Text(tr("Send")) }
            }
          }
          }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = columnDp).fillMaxSize().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(if (look.messageStyle == app.sillytavern.android.appearance.MessageStyle.DOCUMENT) 14.dp else 8.dp)) {
            itemsIndexed(messages) { i, m ->
                val shown = remember(m.text, m.isUser, appSettings.regexEnabled, state.character) { ctl.displayText(m) }
                val shownReasoning = remember(m.reasoning, appSettings.regexEnabled, state.character) { m.reasoning?.let(ctl::displayReasoning) }
                val avatar = remember(m.name, m.isUser, m.originalAvatar, state.character, state.group, appSettings.personas, appSettings.activePersonaId) {
                    when {
                        m.isSystem -> null
                        m.isUser -> (appSettings.personas.firstOrNull { it.name == m.name } ?: appSettings.activePersona).avatar
                            .takeIf { it.isNotBlank() }?.let { File(c.paths.userAvatars, it) }
                        state.group != null -> m.originalAvatar?.let { File(c.paths.characters, it) }
                        else -> state.character?.file
                    }?.takeIf { it.exists() }
                }
                MessageBubble(
                    m, index = i, avatar = avatar, look = look, colors = chatColors, shown = shown, shownReasoning = shownReasoning, isLast = i == messages.lastIndex, generating = state.generating && i == messages.lastIndex,
                    onLongPress = { actionsFor = i }, onSwipe = { d -> ctl.swipe(i, d) },
                    onOpenCheckpoint = { ctl.openCheckpoint(i) },
                    media = {
                        m.imagePath?.let { MessageImage(c, it, app.sillytavern.android.media.MediaPrompt.captionOf(m)) }
                        m.fileAttachment?.let { Text("📎 ${it.name}", style = MaterialTheme.typography.labelMedium) }
                    },
                )
            }
        }
        }
    }
    }

    actionsFor?.let { i ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(tr("Message")) },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    TextButton(onClick = { editing = i; actionsFor = null }) { Text(tr("Edit")) }
                    if (messages[i].text.isNotBlank()) TextButton(onClick = { ctl.speakMessage(i); actionsFor = null }) { Text(tr("Speak")) }
                    if (messages[i].imagePath != null) TextButton(onClick = {
                        val idx = i; actionsFor = null
                        scope.launch { try { ctl.captionMessage(idx) } catch (e: Exception) { snackbar.showSnackbar("Caption failed: ${e.message}") } }
                    }) { Text(tr(if (messages[i].imageCaption != null) "Re-caption image" else "Caption image")) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(messages[i].text)); actionsFor = null }) { Text(tr("Copy")) }
                    TextButton(onClick = { ctl.setHidden(i, !messages[i].isHidden); actionsFor = null }) {
                        Text(tr(if (messages[i].isHidden) "Unhide (include in prompt)" else "Hide (exclude from prompt)"))
                    }
                    TextButton(onClick = { ctl.createBranch(i, messages[i].swipeId.takeIf { messages[i].swipes.size > 1 }); actionsFor = null }) { Text(tr("Branch from here")) }
                    TextButton(onClick = { checkpointFor = i; actionsFor = null }) { Text(tr(if (messages[i].bookmarkLink != null) "Replace checkpoint…" else "Create checkpoint…")) }
                    if (messages[i].bookmarkLink != null) TextButton(onClick = { ctl.openCheckpoint(i); actionsFor = null }) { Text(tr("Open checkpoint")) }
                    TextButton(onClick = { ctl.delete(i); actionsFor = null }) { Text(tr("Delete")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text(tr("Close")) } },
        )
    }
    if (showImageGen) GenerateImageDialog({ showImageGen = false }) { ctl.generateImageAsync(it) }
    checkpointFor?.let { i ->
        var name by remember(i) { mutableStateOf(messages.getOrNull(i)?.bookmarkLink.orEmpty()) }
        AlertDialog(
            onDismissRequest = { checkpointFor = null }, title = { Text("Create checkpoint") },
            text = { Field("Name (empty = automatic)", name, { name = it }) },
            confirmButton = { TextButton(onClick = { ctl.createCheckpoint(i, name.ifBlank { null }); checkpointFor = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { checkpointFor = null }) { Text(tr("Cancel")) } },
        )
    }
    editing?.let { i ->
        var text by remember(i) { mutableStateOf(messages.getOrNull(i)?.text ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit message") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = { TextButton(onClick = { ctl.edit(i, text); editing = null }) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }
    if (showQrSet) {
        val names = remember { c.quickReplyRepo.list() }
        var sel by remember { mutableStateOf(ctl.chatQuickReplySet()) }
        AlertDialog(onDismissRequest = { showQrSet = false }, title = { Text("Quick reply set for this chat") },
            text = { Pick("Set", listOf("(none)") + names, if (sel == null) 0 else names.indexOf(sel) + 1) { sel = if (it == 0) null else names[it - 1] } },
            confirmButton = { TextButton(onClick = { ctl.setChatQuickReplySet(sel); showQrSet = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showQrSet = false }) { Text(tr("Cancel")) } })
    }
    if (showNote) AuthorsNoteDialog(c) { showNote = false }
    if (showSummary) SummaryDialog(c) { showSummary = false }
    if (showPersona) PersonaLockDialog(c) { showPersona = false }
    if (showChats) ChatListDialog(c, onDismiss = { showChats = false })
    if (showBook) {
        val names = remember { c.worlds.list() }
        val current = ctl.chatBook()
        AlertDialog(
            onDismissRequest = { showBook = false }, title = { Text("Lorebook for this chat") },
            text = { Pick("Book", listOf("(none)") + names, if (current == null) 0 else names.indexOf(current) + 1) { ctl.setChatBook(if (it == 0) null else names[it - 1]); showBook = false } },
            confirmButton = { TextButton(onClick = { showBook = false }) { Text(tr("Close")) } },
        )
    }
}

@Composable
private fun AvatarImage(file: File?, name: String, style: app.sillytavern.android.appearance.AvatarStyle) {
    val shape = when (style) {
        app.sillytavern.android.appearance.AvatarStyle.ROUND -> androidx.compose.foundation.shape.CircleShape
        app.sillytavern.android.appearance.AvatarStyle.RECTANGULAR -> RoundedCornerShape(4.dp)
        app.sillytavern.android.appearance.AvatarStyle.SQUARE -> RoundedCornerShape(2.dp)
        app.sillytavern.android.appearance.AvatarStyle.ROUNDED -> RoundedCornerShape(10.dp)
    }
    val mod = if (style == app.sillytavern.android.appearance.AvatarStyle.RECTANGULAR) Modifier.size(36.dp, 54.dp) else Modifier.size(40.dp)
    if (file != null) coil.compose.AsyncImage(file, name, mod.clip(shape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Surface(mod, shape = shape, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase()) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: ChatMessage, index: Int, avatar: File?, look: app.sillytavern.android.appearance.AppearanceSettings, colors: ChatColors,
    shown: String, shownReasoning: String?, isLast: Boolean, generating: Boolean, onLongPress: () -> Unit, onSwipe: (Int) -> Unit, onOpenCheckpoint: () -> Unit,
    media: @Composable ColumnScope.() -> Unit = {},
) {
    val style = look.messageStyle
    val color = when { m.isNarrator -> colors.narrator; m.isUser -> colors.user; else -> colors.bot }
    val document = style == app.sillytavern.android.appearance.MessageStyle.DOCUMENT
    val bubbles = style == app.sillytavern.android.appearance.MessageStyle.BUBBLES
    val showAvatar = !look.hideAvatars && !document && !m.isSystem
    val container = if (document) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(if (bubbles) 0.94f else 1f).background(color, RoundedCornerShape(if (bubbles) 14.dp else 6.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = if (bubbles && m.isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(container.combinedClickable(onClick = {}, onLongClick = onLongPress).padding(if (document) 0.dp else 10.dp)) {
            if (showAvatar) { AvatarImage(avatar, m.name, look.avatarStyle); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.weight(1f, fill = false))
                    if (look.showMessageNumbers) Text("  #$index", style = MaterialTheme.typography.labelSmall, color = colors.italics)
                    if (look.showTimestamps) Text("  " + app.sillytavern.android.appearance.SendDate.format(m.sendDate), style = MaterialTheme.typography.labelSmall, color = colors.italics)
                    Spacer(Modifier.weight(1f))
                    m.bookmarkLink?.let { TextButton(onClick = onOpenCheckpoint, contentPadding = PaddingValues(0.dp)) { Text("⚑ ${it.take(24)}", style = MaterialTheme.typography.labelSmall, maxLines = 1) } }
                }
                shownReasoning?.let { r ->
                    var open by remember(m.sendDate) { mutableStateOf(false) }
                    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (open) "▾ " + tr("Thinking") else "▸ " + tr("Thinking") + "…", style = MaterialTheme.typography.labelMedium)
                    }
                    if (open) Text(r, style = MaterialTheme.typography.bodySmall, color = colors.italics, modifier = Modifier.padding(bottom = 6.dp))
                }
                media()
                if (m.text.isEmpty() && generating) Text("…", color = colors.text)
                else if (m.text.isEmpty() && m.imagePath != null) {}
                else MarkdownText(shown + if (m.isHidden) "  (" + tr("hidden") + ")" else "", render = look.renderMarkdown, allowHtml = look.allowHtml)
                if (document) HorizontalDivider(Modifier.padding(top = 10.dp), color = colors.italics.copy(alpha = 0.25f))
                if (isLast && !m.isUser && (m.swipes.size > 1 || !generating)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = m.swipeId > 0 && !generating, onClick = { onSwipe(-1) }) { Text("◀") }
                        Text("${m.swipeId + 1}/${m.swipes.size}", style = MaterialTheme.typography.labelSmall, color = colors.text)
                        TextButton(enabled = !generating, onClick = { onSwipe(1) }) { Text("▶") }
                    }
                }
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
                                    DropdownMenuItem({ Text(tr("Delete")) }, { rowMenu = false; ctl.deleteChatFile(f); files = ctl.chatFiles() })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
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
