package app.sillytavern.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.tools.*
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ToolsScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val t = s.tools
    fun set(f: ToolsSettings.() -> ToolsSettings) = c.settings.update { it.copy(tools = it.tools.f()) }
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { BackTopBar(tr("Translation & web search"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            testResult?.let { Text(it, style = MaterialTheme.typography.labelMedium) }

            Section(tr("Translation"))
            val tr0 = t.translate
            fun setT(f: TranslateSettings.() -> TranslateSettings) = set { copy(translate = translate.f()) }
            ChoiceRow(tr("Auto translate"), TranslateMode.entries, tr0.mode, {
                when (it) { TranslateMode.OFF -> tr("Off"); TranslateMode.RESPONSES -> tr("Replies"); TranslateMode.INPUTS -> tr("Your messages"); TranslateMode.BOTH -> tr("Both") }
            }) { v -> setT { copy(mode = v) } }
            ChoiceRow(tr("Provider"), TranslateProvider.entries, tr0.provider, {
                when (it) { TranslateProvider.GOOGLE -> "Google (free endpoint)"; TranslateProvider.LIBRETRANSLATE -> "LibreTranslate"; TranslateProvider.DEEPL -> "DeepL"; TranslateProvider.LLM -> tr("Main model") }
            }) { v -> setT { copy(provider = v) } }
            Field(tr("Show replies in (language code)"), tr0.target, { v -> setT { copy(target = v.trim()) } })
            Field(tr("Model reads and writes (language code)"), tr0.internal, { v -> setT { copy(internal = v.trim()) } })
            if (tr0.provider == TranslateProvider.LIBRETRANSLATE) Field(tr("LibreTranslate URL"), tr0.libreUrl, { v -> setT { copy(libreUrl = v) } })
            if (tr0.provider == TranslateProvider.DEEPL) Field(tr("DeepL API URL"), tr0.deeplUrl, { v -> setT { copy(deeplUrl = v) } })
            if (tr0.provider == TranslateProvider.LIBRETRANSLATE || tr0.provider == TranslateProvider.DEEPL) KeyField(c, tr("API key"), ToolsSettings.KEY_TRANSLATE)
            Text(tr("Translations are only shown; the model keeps reading the original text. Long-press a message to translate it by hand."), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = {
                testResult = "Translating…"
                scope.launch { testResult = try { "“Hello, how are you?” → " + c.translator.translate("Hello, how are you?", tr0.target) } catch (e: Exception) { "Translation failed: ${e.message}" } }
            }, enabled = tr0.provider != TranslateProvider.LLM) { Text(tr("Test translation")) }

            Section(tr("Web search"))
            val w = t.search
            fun setW(f: SearchSettings.() -> SearchSettings) = set { copy(search = search.f()) }
            ToggleRow(tr("Search the web before replying"), w.enabled) { x -> setW { copy(enabled = x) } }
            ChoiceRow(tr("Provider"), SearchProvider.entries, w.provider, {
                when (it) { SearchProvider.SEARXNG -> "SearXNG"; SearchProvider.BRAVE -> "Brave Search"; SearchProvider.TAVILY -> "Tavily"; SearchProvider.SERPER -> "Serper (Google)" }
            }) { v -> setW { copy(provider = v) } }
            if (w.provider == SearchProvider.SEARXNG) Field(tr("SearXNG URL"), w.searxUrl, { v -> setW { copy(searxUrl = v) } })
            else KeyField(c, tr("API key"), ToolsSettings.KEY_SEARCH)
            Field(tr("Results"), w.resultCount.toString(), { v -> v.toIntOrNull()?.let { n -> setW { copy(resultCount = n.coerceIn(1, 20)) } } }, number = true)
            Field(tr("Only search when the message contains (comma separated, empty = always)"), w.triggers, { v -> setW { copy(triggers = v) } })
            Field(tr("Snippet length (characters)"), w.snippetLength.toString(), { v -> v.toIntOrNull()?.let { n -> setW { copy(snippetLength = n.coerceIn(40, 2000)) } } }, number = true)
            Field(tr("Insertion depth"), w.depth.toString(), { v -> v.toIntOrNull()?.let { n -> setW { copy(depth = n.coerceIn(0, 100)) } } }, number = true)
            Field(tr("Template ({{query}}, {{text}})"), w.template, { v -> setW { copy(template = v) } }, singleLine = false)
            TextButton(onClick = {
                testResult = "Searching…"
                scope.launch { testResult = try { c.webSearch.search("SillyTavern") } catch (e: Exception) { "Search failed: ${e.message}" } }
            }) { Text(tr("Test search")) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ContentScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<AssetEntry>()) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    Scaffold(topBar = { BackTopBar(tr("Content manager"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            message?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            Section(tr("Default content"))
            Text(tr("The character, world and themes that come with SillyTavern."), style = MaterialTheme.typography.bodySmall)
            key(refresh) {
                c.defaultContent.items.forEach { item ->
                    val installed = c.defaultContent.isInstalled(item)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${item.title} · ${tr(item.type.label)}", Modifier.weight(1f))
                        if (installed && item.type == AssetType.THEME) TextButton(onClick = {
                            val text = c.assetInstaller.themeText(item.title)
                            runCatching { app.sillytavern.android.appearance.ThemeIo.import(text!!, s.appearance) }
                                .onSuccess { n -> c.settings.update { it.copy(appearance = app.sillytavern.android.appearance.ThemeIo.applied(it.appearance, n)) }; message = "Theme \"${item.title}\" applied" }
                                .onFailure { message = "Could not apply: ${it.message}" }
                        }) { Text(tr("Apply")) }
                        else if (installed) Text(tr("Installed"), style = MaterialTheme.typography.labelMedium)
                        else TextButton(onClick = {
                            message = runCatching { c.defaultContent.install(item); "${item.title} installed" }.getOrElse { "Install failed: ${it.message}" }
                            refresh++
                        }) { Text(tr("Install")) }
                    }
                }
            }
            TextButton(onClick = { message = runCatching { "${c.defaultContent.installAll()} item(s) installed" }.getOrElse { "Install failed: ${it.message}" }; refresh++ }) { Text(tr("Install all")) }

            Section(tr("Asset catalog"))
            Text(tr("Address of a JSON list of backgrounds, characters, worlds and themes to download."), style = MaterialTheme.typography.bodySmall)
            Field(tr("Catalog URL"), s.tools.catalogUrl, { v -> c.settings.update { it.copy(tools = it.tools.copy(catalogUrl = v.trim())) } })
            Button(enabled = !busy && s.tools.catalogUrl.isNotBlank(), onClick = {
                busy = true; message = "Loading…"
                scope.launch {
                    try { entries = c.assetInstaller.fetchCatalog(s.tools.catalogUrl); message = "${entries.size} asset(s) listed" }
                    catch (e: Exception) { message = "Catalog failed: ${e.message}" }
                    busy = false
                }
            }) { Text(tr("Load catalog")) }
            entries.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name)
                        Text((e.type?.label ?: e.rawType.ifBlank { "?" }) + if (e.description.isNotBlank()) " · ${e.description}" else "", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = e.installable && !busy, onClick = {
                        busy = true; message = "Downloading ${e.name}…"
                        scope.launch {
                            message = try { "Installed: ${c.assetInstaller.install(e)}" } catch (ex: Exception) { "Install failed: ${ex.message}" }
                            busy = false
                        }
                    }) { Text(tr(if (e.type == null) "Not supported" else "Install")) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun TokenScreen(c: AppContainer, onBack: () -> Unit) {
    val chat by c.chatController.state.collectAsState()
    var kind by remember { mutableStateOf(TokenizerKind.OPENAI) }
    var text by remember { mutableStateOf("") }
    val counter = remember(kind) { c.tokenizer(kind) }
    val counts = remember(text, kind) { TokenTools.count(text, counter) }
    Scaffold(topBar = { BackTopBar(tr("Token counter"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            ChoiceRow(tr("Tokenizer"), TokenizerKind.entries, kind, { it.label }) { kind = it }
            Field(tr("Text"), text, { text = it }, singleLine = false, minLines = 5)
            Text("${counts.tokens} ${tr("tokens")} · ${counts.characters} ${tr("characters")} · ${counts.words} ${tr("words")}", style = MaterialTheme.typography.titleMedium)

            val card = chat.character?.card
            if (card != null) {
                Section(card.name)
                val parts = remember(card, kind) { TokenTools.cardParts(card, counter) }
                parts.forEach { Text("${it.label}: ${it.tokens}") }
                Text("${tr("Card total")}: ${parts.sumOf { it.tokens }}", style = MaterialTheme.typography.titleSmall)
                chat.chat?.messages?.let { msgs ->
                    val total = remember(msgs.size, msgs.lastOrNull()?.text, kind) { TokenTools.chatTokens(msgs, counter) }
                    Text("${tr("Chat")}: $total ${tr("tokens in")} ${msgs.count { it.inPrompt }} ${tr("messages")}")
                }
            } else Text(tr("Open a chat to see the token cost of its character and messages."), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatsScreen(c: AppContainer, onBack: () -> Unit) {
    var report by remember { mutableStateOf<StatsReport?>(null) }
    LaunchedEffect(Unit) { report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Stats.collect(c.paths, c.chats, c.characters) } }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
    fun line(s: UsageStats) = buildString {
        append("${s.chats} ${"chats"} · ${s.messages} messages (you ${s.userMessages}, character ${s.characterMessages})\n")
        append("Words: you ${s.userWords} (avg ${s.avgUserWords}), character ${s.characterWords} (avg ${s.avgCharacterWords})")
        if (s.extraSwipes > 0) append("\nExtra swipes: ${s.extraSwipes}")
        if (s.first != null && s.last != null) append("\n${fmt.format(s.first)} – ${fmt.format(s.last)}")
    }
    Scaffold(topBar = { BackTopBar(tr("Statistics"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            val r = report
            if (r == null) Text(tr("Counting…"), Modifier.padding(top = 16.dp))
            else if (r.rows.isEmpty()) Text(tr("No chats yet."), Modifier.padding(top = 16.dp))
            else {
                Section(tr("Total"))
                Text(line(r.total))
                r.rows.forEach { row ->
                    Text(row.name, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
                    Text(line(row.stats), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
