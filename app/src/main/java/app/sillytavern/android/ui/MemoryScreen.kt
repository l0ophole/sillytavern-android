package app.sillytavern.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.memory.*
import coil.compose.AsyncImage

/** The sprite of the current expression, shown above the input bar. */
@Composable
fun SpritePanel(c: AppContainer, expression: String?, owner: String?) {
    val s by c.settings.settings.collectAsState()
    val e = s.memory.expressions
    if (!e.enabled || !e.showPanel || owner == null) return
    val sprite = remember(owner, expression, e.fallback) { c.sprites.resolve(owner, expression, e.fallback) } ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        AsyncImage(sprite.file, sprite.label, Modifier.height(e.panelHeight.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
fun MemoryScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val chat by c.chatController.state.collectAsState()
    val m = s.memory
    val ctx = LocalContext.current
    fun set(f: MemorySettings.() -> MemorySettings) = c.settings.update { it.copy(memory = it.memory.f()) }
    var message by remember { mutableStateOf<String?>(null) }
    val chatKey = chat.chat?.file?.nameWithoutExtension
    val characterKey = chat.group?.id ?: chat.character?.fileName
    Scaffold(topBar = { BackTopBar(tr("Memory, data bank & expressions"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            message?.let { Text(it, style = MaterialTheme.typography.labelMedium) }

            // ------------------------------------------------------------ embeddings
            Section(tr("Embeddings"))
            val em = m.embedder
            ChoiceRow(tr("Embedding source"), EmbedderKind.entries, em.kind, {
                when (it) { EmbedderKind.LOCAL_HASH -> tr("Built-in (word match)"); EmbedderKind.OPENAI -> tr("OpenAI-compatible"); EmbedderKind.OLLAMA -> tr("Ollama") }
            }) { v -> set { copy(embedder = em.copy(kind = v)) } }
            if (em.kind == EmbedderKind.LOCAL_HASH) Text(tr("The built-in embedder matches words, not meaning. Use a model server for better recall."), style = MaterialTheme.typography.labelSmall)
            else {
                Field(tr("Server URL"), em.url, { v -> set { copy(embedder = em.copy(url = v)) } })
                Field(tr("Model"), em.model, { v -> set { copy(embedder = em.copy(model = v)) } })
                KeyField(c, tr("API key (optional)"), MemorySettings.KEY_EMBEDDINGS)
            }
            TextButton(onClick = { c.memory.store.dropAll(); message = "All vectors deleted; they are rebuilt when needed" }) { Text(tr("Delete all vectors")) }

            // ------------------------------------------------------------ vector memory
            Section(tr("Chat memory"))
            val v = m.vector
            ToggleRow(tr("Recall older messages"), v.enabled) { x -> set { copy(vector = v.copy(enabled = x)) } }
            Field(tr("Chunks to recall"), v.retrieveCount.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(vector = v.copy(retrieveCount = n.coerceIn(1, 50))) } } }, number = true)
            Field(tr("Latest messages used as the query"), v.queryMessages.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(vector = v.copy(queryMessages = n.coerceIn(1, 20))) } } }, number = true)
            Field(tr("Keep out the newest N messages"), v.protectRecent.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(vector = v.copy(protectRecent = n.coerceIn(0, 200))) } } }, number = true)
            Field(tr("Score threshold (0-1)"), v.scoreThreshold.toString(), { t -> t.toDoubleOrNull()?.let { n -> set { copy(vector = v.copy(scoreThreshold = n.coerceIn(0.0, 1.0))) } } }, number = true)
            Field(tr("Chunk size (characters)"), v.chunkSize.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(vector = v.copy(chunkSize = n.coerceIn(100, 8000))) } } }, number = true)
            Field(tr("Insertion depth"), v.depth.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(vector = v.copy(depth = n.coerceIn(0, 100))) } } }, number = true)
            Field(tr("Template ({{text}})"), v.template, { t -> set { copy(vector = v.copy(template = t)) } }, singleLine = false)
            if (chatKey != null) TextButton(onClick = { c.memory.forgetChat(chatKey); message = "This chat's vectors were deleted" }) { Text(tr("Forget this chat's vectors")) }

            // ------------------------------------------------------------ data bank
            Section(tr("Data bank"))
            val d = m.dataBank
            ToggleRow(tr("Recall from documents"), d.enabled) { x -> set { copy(dataBank = d.copy(enabled = x)) } }
            Field(tr("Chunks to recall"), d.retrieveCount.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(dataBank = d.copy(retrieveCount = n.coerceIn(1, 50))) } } }, number = true)
            Field(tr("Score threshold (0-1)"), d.scoreThreshold.toString(), { t -> t.toDoubleOrNull()?.let { n -> set { copy(dataBank = d.copy(scoreThreshold = n.coerceIn(0.0, 1.0))) } } }, number = true)
            Field(tr("Chunk size (characters)"), d.chunkSize.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(dataBank = d.copy(chunkSize = n.coerceIn(100, 8000))) } } }, number = true)
            Field(tr("Chunk overlap (%)"), d.overlapPercent.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(dataBank = d.copy(overlapPercent = n.coerceIn(0, 50))) } } }, number = true)
            Field(tr("Insertion depth"), d.depth.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(dataBank = d.copy(depth = n.coerceIn(0, 100))) } } }, number = true)
            Field(tr("Template ({{text}})"), d.template, { t -> set { copy(dataBank = d.copy(template = t)) } }, singleLine = false)
            BankDocuments(c, characterKey, chatKey, onMessage = { message = it })

            // ------------------------------------------------------------ expressions
            Section(tr("Expressions"))
            val x = m.expressions
            ToggleRow(tr("Show character sprites"), x.enabled) { b -> set { copy(expressions = x.copy(enabled = b)) } }
            ChoiceRow(tr("Classifier"), ClassifierKind.entries, x.classifier, { if (it == ClassifierKind.KEYWORD) tr("Built-in (keywords)") else tr("Main model") }) { k -> set { copy(expressions = x.copy(classifier = k)) } }
            ToggleRow(tr("Classify every reply"), x.auto) { b -> set { copy(expressions = x.copy(auto = b)) } }
            ToggleRow(tr("Show the sprite panel in the chat"), x.showPanel) { b -> set { copy(expressions = x.copy(showPanel = b)) } }
            Field(tr("Fallback expression"), x.fallback, { t -> set { copy(expressions = x.copy(fallback = t.trim().lowercase())) } })
            Field(tr("Panel height (dp)"), x.panelHeight.toString(), { t -> t.toIntOrNull()?.let { n -> set { copy(expressions = x.copy(panelHeight = n.coerceIn(60, 600))) } } }, number = true)
            SpriteList(c, c.chatController.spriteOwner(), onMessage = { message = it })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BankDocuments(c: AppContainer, characterKey: String?, chatKey: String?, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val scopes = listOfNotNull(
        BankScopeKey(BankScope.GLOBAL),
        characterKey?.let { BankScopeKey(BankScope.CHARACTER, it) },
        chatKey?.let { BankScopeKey(BankScope.CHAT, it) },
    )
    var scope by remember(scopes) { mutableStateOf(scopes.first()) }
    var version by remember { mutableIntStateOf(0) }
    var writing by remember { mutableStateOf(false) }
    val docs = remember(scope, version) { c.memory.dataBank.list(scope) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        var added = 0
        uris.forEach { uri ->
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                c.memory.dataBank.add(scope, displayName(ctx, uri) ?: "document.txt", text); added++
            }
        }
        version++; onMessage("$added document(s) added")
    }
    ChoiceRow(tr("Documents of"), scopes, scope, { when (it.scope) { BankScope.GLOBAL -> tr("All chats"); BankScope.CHARACTER -> tr("This character"); BankScope.CHAT -> tr("This chat") } }) { scope = it }
    docs.forEach { doc ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${doc.name} (${doc.size / 1024 + 1} KB)", Modifier.weight(1f))
            TextButton(onClick = { c.memory.dataBank.remove(doc); version++ }) { Text(tr("Delete")) }
        }
    }
    Row {
        TextButton(onClick = { pick.launch("text/*") }) { Text(tr("Add files…")) }
        TextButton(onClick = { writing = true }) { Text(tr("Write note…")) }
        TextButton(onClick = { c.memory.dataBank.reindex(scope); onMessage("Documents will be re-indexed") }) { Text(tr("Re-index")) }
    }
    if (writing) {
        var name by remember { mutableStateOf("note") }
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { writing = false }, title = { Text(tr("New document")) },
            text = { Column { Field(tr("Name"), name, { name = it }); Field(tr("Text"), text, { text = it }, singleLine = false, minLines = 5) } },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { c.memory.dataBank.add(scope, "$name.txt", text); version++; writing = false }) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { writing = false }) { Text(tr("Cancel")) } })
    }
}

@Composable
private fun SpriteList(c: AppContainer, owner: String?, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    if (owner == null) { Text(tr("Open a chat to manage its character's sprites."), style = MaterialTheme.typography.labelSmall); return }
    var version by remember { mutableIntStateOf(0) }
    val sprites = remember(owner, version) { c.sprites.list(owner) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            runCatching {
                val name = displayName(ctx, uri) ?: "neutral.png"
                c.sprites.add(owner, name.substringBeforeLast('.'), name.substringAfterLast('.', "png"), ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
        version++; onMessage("Sprites added")
    }
    Text(tr("Sprites of") + " $owner", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(tr("Name each file after its expression, e.g. joy.png, anger.png, neutral.png."), style = MaterialTheme.typography.labelSmall)
    sprites.forEach { sp ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(sp.file, sp.label, Modifier.size(48.dp), contentScale = ContentScale.Crop)
            Text("  ${sp.file.name}", Modifier.weight(1f))
            TextButton(onClick = { c.sprites.remove(sp); version++ }) { Text(tr("Delete")) }
        }
    }
    TextButton(onClick = { pick.launch("image/*") }) { Text(tr("Add sprites…")) }
}
