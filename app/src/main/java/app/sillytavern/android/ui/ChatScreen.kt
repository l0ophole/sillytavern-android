package app.sillytavern.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(c: AppContainer, characterFile: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val ctl = c.chatController
    val state by ctl.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var actionsFor by remember { mutableStateOf<Int?>(null) }
    var showChats by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(characterFile) {
        if (state.character?.fileName != characterFile) c.characters.get(characterFile)?.let { ctl.open(it) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.character?.card?.name ?: "") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                actions = {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("New chat") }, { menu = false; ctl.newChat() })
                            DropdownMenuItem({ Text("Chats…") }, { menu = false; showChats = true })
                            DropdownMenuItem({ Text("Regenerate") }, { menu = false; ctl.regenerate() })
                            DropdownMenuItem({ Text("Continue") }, { menu = false; ctl.continueLast() })
                            DropdownMenuItem({ Text("Impersonate") }, { menu = false; ctl.impersonate() })
                            DropdownMenuItem({ Text("Edit character") }, { menu = false; onEdit(characterFile) })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 6)
                Spacer(Modifier.width(8.dp))
                if (state.generating) Button(onClick = { ctl.stop() }) { Text("Stop") }
                else Button(enabled = input.isNotBlank(), onClick = { ctl.send(input); input = "" }) { Text("Send") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(messages) { i, m ->
                MessageBubble(
                    m, isLast = i == messages.lastIndex, generating = state.generating && i == messages.lastIndex,
                    onLongPress = { actionsFor = i }, onSwipe = { d -> ctl.swipe(i, d) },
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
                    TextButton(onClick = { ctl.delete(i); actionsFor = null }) { Text("Delete") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text("Close") } },
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
    if (showChats) ChatListDialog(c, onDismiss = { showChats = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: ChatMessage, isLast: Boolean, generating: Boolean, onLongPress: () -> Unit, onSwipe: (Int) -> Unit) {
    val color = if (m.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongPress).padding(12.dp),
    ) {
        Text(m.name, style = MaterialTheme.typography.labelMedium)
        Text(if (m.text.isEmpty() && generating) "…" else m.text, style = MaterialTheme.typography.bodyLarge)
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
    var files by remember { mutableStateOf(ctl.chatFiles()) }
    val current = ctl.state.value.chat?.file
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chats") },
        text = {
            LazyColumn {
                itemsIndexed(files) { _, f: File ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(modifier = Modifier.weight(1f), onClick = { ctl.state.value.character?.let { ch -> ctl.open(ch, f) }; onDismiss() }) {
                            Text(f.nameWithoutExtension + if (f == current) "  ✓" else "", maxLines = 2)
                        }
                        TextButton(onClick = { ctl.deleteChatFile(f); files = ctl.chatFiles() }) { Text("🗑") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
