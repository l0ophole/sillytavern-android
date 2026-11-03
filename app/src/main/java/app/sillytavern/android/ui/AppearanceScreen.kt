package app.sillytavern.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.appearance.*

private const val SAMPLE = "**Alice** waves. *She smiles warmly.*\n\n\"Hello there!\" she says, <u>underlined</u> and ~~struck~~.\n\n> A quoted line\n\n- one\n- two\n\n`code` and a [link](https://example.org)"

@Composable
fun AppearanceScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val a = s.appearance
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var backgrounds by remember { mutableStateOf(c.backgrounds.list()) }
    fun set(f: AppearanceSettings.() -> AppearanceSettings) = c.settings.update { it.copy(appearance = it.appearance.f()) }

    val themeImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
            .mapCatching { ThemeIo.import(it, c.settings.settings.value.appearance) }
            .onSuccess { n -> c.settings.update { it.copy(appearance = ThemeIo.applied(it.appearance, n)) }; message = "Theme \"${n.themeName}\" applied" }
            .onFailure { message = "Import failed: ${it.message}" }
    }
    val themeExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(ThemeIo.export(a, a.themeName.ifBlank { "My theme" }).toByteArray()) } }
            .onSuccess { message = "Theme exported" }.onFailure { message = "Export failed: ${it.message}" }
    }
    val bgImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "background.png"
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .mapCatching { c.backgrounds.import(name, it).getOrThrow() }
            .onSuccess { f -> backgrounds = c.backgrounds.list(); set { copy(background = f) } }
            .onFailure { message = "Import failed: ${it.message}" }
    }

    Scaffold(topBar = { BackTopBar(tr("Appearance & language"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            val langs = Translator.languages
            Pick(tr("Language"), langs.map { it.second }, langs.indexOfFirst { it.first == a.language }.coerceAtLeast(0)) { set { copy(language = langs[it].first) } }
            Text("Only the main screens are translated; other screens stay in English.", style = MaterialTheme.typography.bodySmall)

            Heading("Theme")
            val modes = listOf("System", "Light", "Dark", "AMOLED black").map { tr(it) }
            Pick(tr("Mode"), modes, a.themeMode.ordinal) { set { copy(themeMode = ThemeMode.entries[it]) } }
            if (a.themeName.isNotBlank()) Text("Theme: ${a.themeName}", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { themeImport.launch(arrayOf("application/json", "*/*")) }) { Text(tr("Import ST theme…")) }
                TextButton(onClick = { themeExport.launch((a.themeName.ifBlank { "theme" }) + ".json") }) { Text(tr("Export theme…")) }
            }
            Text("Colors: #rrggbb or rgba(r, g, b, a). Empty = default.", style = MaterialTheme.typography.bodySmall)
            ColorField("Text", a.mainText) { set { copy(mainText = it) } }
            ColorField("Action text (*italics*)", a.italicsText) { set { copy(italicsText = it) } }
            ColorField("Quoted text (\"quotes\")", a.quoteText) { set { copy(quoteText = it) } }
            ColorField("Underlined text", a.underlineText) { set { copy(underlineText = it) } }
            ColorField("App background", a.surface) { set { copy(surface = it) } }
            ColorField("Chat background", a.chatBackground) { set { copy(chatBackground = it) } }
            ColorField("Your messages", a.userBubble) { set { copy(userBubble = it) } }
            ColorField("Character messages", a.botBubble) { set { copy(botBubble = it) } }
            TextButton(onClick = { set { AppearanceSettings(language = language, background = background, backgroundDim = backgroundDim) } }) { Text(tr("Reset theme")) }

            Heading("Chat")
            Pick(tr("Message style"), listOf("Flat", "Bubbles", "Document").map { tr(it) }, a.messageStyle.ordinal) { set { copy(messageStyle = MessageStyle.entries[it]) } }
            Pick(tr("Avatar style"), listOf("Round", "Portrait", "Square", "Rounded square").map { tr(it) }, a.avatarStyle.ordinal) { set { copy(avatarStyle = AvatarStyle.entries[it]) } }
            Text("Font size: ${"%.2f".format(a.fontScale)}×", style = MaterialTheme.typography.labelMedium)
            Slider(a.fontScale, { set { copy(fontScale = Math.round(it * 20) / 20f) } }, valueRange = 0.7f..1.6f)
            Text("Chat width on tablets: ${a.chatWidth}%", style = MaterialTheme.typography.labelMedium)
            Slider(a.chatWidth.toFloat(), { set { copy(chatWidth = it.toInt()) } }, valueRange = 25f..100f)
            SwitchRow(tr("Show timestamps"), a.showTimestamps) { set { copy(showTimestamps = it) } }
            SwitchRow(tr("Show message numbers"), a.showMessageNumbers) { set { copy(showMessageNumbers = it) } }
            SwitchRow(tr("Hide avatars"), a.hideAvatars) { set { copy(hideAvatars = it) } }
            SwitchRow(tr("Format messages (markdown)"), a.renderMarkdown) { set { copy(renderMarkdown = it) } }
            SwitchRow(tr("Allow basic HTML tags"), a.allowHtml) { set { copy(allowHtml = it) } }

            Heading("Preview")
            val colors = LocalChatColors.current
            Surface(shape = RoundedCornerShape(12.dp), color = colors.bot) { MarkdownText(SAMPLE, Modifier.padding(12.dp), a.renderMarkdown, a.allowHtml) }

            Heading("Background")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { set { copy(background = "") } }) { Text(if (a.background.isBlank()) "✓ " + tr("None") else tr("None")) }
                TextButton(onClick = { bgImport.launch(arrayOf("image/*")) }) { Text(tr("Add image…")) }
            }
            backgrounds.forEach { name ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    c.backgrounds.file(name)?.let { coil.compose.AsyncImage(it, null, Modifier.size(48.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                    TextButton(modifier = Modifier.weight(1f), onClick = { set { copy(background = name) } }) { Text((if (a.background == name) "✓ " else "") + name, maxLines = 1) }
                    TextButton(onClick = { c.backgrounds.delete(name); backgrounds = c.backgrounds.list(); if (a.background == name) set { copy(background = "") } }) { Text(tr("Delete")) }
                }
            }
            if (a.background.isNotBlank()) {
                Text("Dim: ${(a.backgroundDim * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(a.backgroundDim, { set { copy(backgroundDim = Math.round(it * 20) / 20f) } }, valueRange = 0f..0.9f)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Heading(text: String) = Text(tr(text), Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge)

@Composable
private fun ColorField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(value?.let(ThemeIo::formatColor).orEmpty()) }
    // Follow outside changes (theme import, reset) without rewriting what the user is typing.
    LaunchedEffect(value) { if ((if (text.isBlank()) null else ThemeIo.parseColor(text)) != value) text = value?.let(ThemeIo::formatColor).orEmpty() }
    val parsed = ThemeIo.parseColor(text)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(parsed?.let { Color(it) } ?: Color.Transparent, RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            text, { text = it; if (it.isBlank()) onChange(null) else ThemeIo.parseColor(it)?.let(onChange) },
            Modifier.weight(1f).padding(vertical = 2.dp), label = { Text(tr(label)) }, singleLine = true,
            isError = text.isNotBlank() && parsed == null,
        )
    }
}
