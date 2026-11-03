package app.sillytavern.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.media.*

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }

@Composable
internal fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(name(selected)) }
            DropdownMenu(open, { open = false }) { options.forEach { o -> DropdownMenuItem({ Text(name(o)) }, { open = false; onSelect(o) }) } }
        }
    }
}

/** An API-key field that writes straight to the encrypted secret store. */
@Composable
internal fun KeyField(c: AppContainer, label: String, key: String) {
    var v by remember { mutableStateOf(c.secrets.get(key).orEmpty()) }
    Field(label, v, { v = it; if (it.isBlank()) c.secrets.remove(key) else c.secrets.set(key, it) }, secret = true)
}

@Composable
internal fun Section(title: String) = Text(title, Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge)

@Composable
fun MediaSettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val m = s.media
    fun set(f: MediaSettings.() -> MediaSettings) = c.settings.update { it.copy(media = it.media.f()) }
    fun num(t: String) = t.toDoubleOrNull()
    Scaffold(topBar = { BackTopBar(tr("Speech, images & attachments"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            // ------------------------------------------------------------ attachments
            Section(tr("Attachments"))
            val a = m.attachments
            ChoiceRow(tr("Send images to the model"), ImagePromptMode.entries, a.mode, { if (it == ImagePromptMode.SEND_IMAGE) tr("As image") else tr("As caption text") }) { v -> set { copy(attachments = a.copy(mode = v)) } }
            Text(tr("Text-completion connections always use captions."), style = MaterialTheme.typography.labelSmall)
            ToggleRow(tr("Caption new images automatically"), a.autoCaption) { v -> set { copy(attachments = a.copy(autoCaption = v)) } }
            Field(tr("Caption prompt"), a.captionPrompt, { v -> set { copy(attachments = a.copy(captionPrompt = v)) } }, singleLine = false)
            Field(tr("Scale images to (px, 0 = original)"), a.maxImageSide.toString(), { v -> v.toIntOrNull()?.let { n -> set { copy(attachments = a.copy(maxImageSide = n.coerceAtLeast(0))) } } }, number = true)

            // ------------------------------------------------------------ TTS
            Section(tr("Text to speech"))
            val t = m.tts
            ToggleRow(tr("Enabled"), t.enabled) { v -> set { copy(tts = t.copy(enabled = v)) } }
            ToggleRow(tr("Read new replies aloud"), t.autoPlay) { v -> set { copy(tts = t.copy(autoPlay = v)) } }
            ChoiceRow(tr("Voice source"), TtsProvider.entries, t.provider, { when (it) { TtsProvider.SYSTEM -> tr("Device voice"); TtsProvider.OPENAI -> "OpenAI-compatible"; TtsProvider.ELEVENLABS -> "ElevenLabs" } }) { v -> set { copy(tts = t.copy(provider = v)) } }
            ChoiceRow(tr("Read"), NarrateMode.entries, t.narrate, { when (it) { NarrateMode.ALL -> tr("Everything"); NarrateMode.QUOTED -> tr("Only quoted speech"); NarrateMode.NON_QUOTED -> tr("Only narration") } }) { v -> set { copy(tts = t.copy(narrate = v)) } }
            ToggleRow(tr("Read *actions*"), t.speakActions) { v -> set { copy(tts = t.copy(speakActions = v)) } }
            ToggleRow(tr("Skip code blocks"), t.skipCodeBlocks) { v -> set { copy(tts = t.copy(skipCodeBlocks = v)) } }
            Field(tr("Speed"), t.rate.toString(), { v -> num(v)?.let { n -> set { copy(tts = t.copy(rate = n.coerceIn(0.25, 4.0))) } } }, number = true)
            if (t.provider == TtsProvider.SYSTEM) Field(tr("Pitch"), t.pitch.toString(), { v -> num(v)?.let { n -> set { copy(tts = t.copy(pitch = n.coerceIn(0.25, 4.0))) } } }, number = true)
            Field(tr("Default voice"), t.defaultVoice, { v -> set { copy(tts = t.copy(defaultVoice = v.trim())) } })
            if (t.provider == TtsProvider.SYSTEM) {
                val voices = remember { c.tts.voices() }
                if (voices.isNotEmpty()) Text(tr("Installed voices") + ": " + voices.take(12).joinToString(", ") + if (voices.size > 12) "…" else "", style = MaterialTheme.typography.labelSmall)
            }
            var voiceText by remember(t.voiceMap) { mutableStateOf(TtsText.formatVoiceMap(t.voiceMap)) }
            Field(tr("Voice per character (Name=voice, one per line)"), voiceText, { v -> voiceText = v; set { copy(tts = t.copy(voiceMap = TtsText.parseVoiceMap(v))) } }, singleLine = false, minLines = 2)
            if (t.provider == TtsProvider.OPENAI) {
                Field(tr("Server URL"), t.openAiUrl, { v -> set { copy(tts = t.copy(openAiUrl = v.trim())) } })
                Field(tr("Model"), t.openAiModel, { v -> set { copy(tts = t.copy(openAiModel = v.trim())) } })
                KeyField(c, tr("API key"), MediaSettings.KEY_TTS)
            }
            if (t.provider == TtsProvider.ELEVENLABS) {
                Field(tr("Server URL"), t.elevenUrl, { v -> set { copy(tts = t.copy(elevenUrl = v.trim())) } })
                Field(tr("Model"), t.elevenModel, { v -> set { copy(tts = t.copy(elevenModel = v.trim())) } })
                KeyField(c, tr("API key"), MediaSettings.KEY_TTS)
            }

            // ------------------------------------------------------------ STT
            Section(tr("Speech to text"))
            val st = m.stt
            ToggleRow(tr("Show microphone button"), st.enabled) { v -> set { copy(stt = st.copy(enabled = v)) } }
            ChoiceRow(tr("Recognizer"), SttProvider.entries, st.provider, { if (it == SttProvider.SYSTEM) tr("Device") else "Whisper API" }) { v -> set { copy(stt = st.copy(provider = v)) } }
            ToggleRow(tr("Send right after speaking"), st.autoSend) { v -> set { copy(stt = st.copy(autoSend = v)) } }
            Field(tr("Language (e.g. en-US, empty = automatic)"), st.language, { v -> set { copy(stt = st.copy(language = v.trim())) } })
            if (st.provider == SttProvider.WHISPER) {
                Field(tr("Server URL"), st.whisperUrl, { v -> set { copy(stt = st.copy(whisperUrl = v.trim())) } })
                Field(tr("Model"), st.whisperModel, { v -> set { copy(stt = st.copy(whisperModel = v.trim())) } })
                KeyField(c, tr("API key"), MediaSettings.KEY_STT)
                Text(tr("Tap the microphone to start recording and tap again to transcribe."), style = MaterialTheme.typography.labelSmall)
            }

            // ------------------------------------------------------------ image generation
            Section(tr("Image generation"))
            val g = m.imageGen
            ChoiceRow(tr("Source"), ImageSource.entries, g.source, { when (it) { ImageSource.A1111 -> "AUTOMATIC1111 / Forge"; ImageSource.COMFY -> "ComfyUI"; ImageSource.NOVELAI -> "NovelAI"; ImageSource.OPENAI -> "OpenAI-compatible" } }) { v -> set { copy(imageGen = g.copy(source = v)) } }
            when (g.source) {
                ImageSource.A1111, ImageSource.COMFY -> Field(tr("Server URL"), g.url, { v -> set { copy(imageGen = g.copy(url = v.trim())) } })
                ImageSource.NOVELAI -> { Field(tr("Server URL"), g.novelUrl, { v -> set { copy(imageGen = g.copy(novelUrl = v.trim())) } }); Field(tr("Model"), g.novelModel, { v -> set { copy(imageGen = g.copy(novelModel = v.trim())) } }) }
                ImageSource.OPENAI -> { Field(tr("Server URL"), g.openAiUrl, { v -> set { copy(imageGen = g.copy(openAiUrl = v.trim())) } }); Field(tr("Model"), g.openAiModel, { v -> set { copy(imageGen = g.copy(openAiModel = v.trim())) } }); Field(tr("Quality"), g.openAiQuality, { v -> set { copy(imageGen = g.copy(openAiQuality = v.trim())) } }) }
            }
            if (g.source == ImageSource.NOVELAI || g.source == ImageSource.OPENAI) KeyField(c, tr("API key"), MediaSettings.KEY_IMAGE)
            if (g.source == ImageSource.A1111 || g.source == ImageSource.COMFY) Field(tr("Checkpoint (empty = server default)"), g.model, { v -> set { copy(imageGen = g.copy(model = v.trim())) } })
            if (g.source != ImageSource.OPENAI) {
                Field(tr("Sampler"), g.sampler, { v -> set { copy(imageGen = g.copy(sampler = v.trim())) } })
                if (g.source != ImageSource.NOVELAI) Field(tr("Scheduler"), g.scheduler, { v -> set { copy(imageGen = g.copy(scheduler = v.trim())) } })
                Field(tr("Steps"), g.steps.toString(), { v -> v.toIntOrNull()?.let { n -> set { copy(imageGen = g.copy(steps = n.coerceIn(1, 150))) } } }, number = true)
                Field(tr("CFG scale"), g.cfg.toString(), { v -> num(v)?.let { n -> set { copy(imageGen = g.copy(cfg = n)) } } }, number = true)
                Field(tr("Seed (-1 = random)"), g.seed.toString(), { v -> v.toLongOrNull()?.let { n -> set { copy(imageGen = g.copy(seed = n)) } } }, number = true)
            }
            Field(tr("Width"), g.width.toString(), { v -> v.toIntOrNull()?.let { n -> set { copy(imageGen = g.copy(width = n.coerceIn(64, 4096))) } } }, number = true)
            Field(tr("Height"), g.height.toString(), { v -> v.toIntOrNull()?.let { n -> set { copy(imageGen = g.copy(height = n.coerceIn(64, 4096))) } } }, number = true)
            Field(tr("Prompt prefix ({prompt} marks where the text goes)"), g.prefix, { v -> set { copy(imageGen = g.copy(prefix = v)) } }, singleLine = false)
            if (g.source != ImageSource.OPENAI) Field(tr("Negative prompt"), g.negative, { v -> set { copy(imageGen = g.copy(negative = v)) } }, singleLine = false)
            ToggleRow(tr("Let the main model write the image prompt"), g.llmPrompt) { v -> set { copy(imageGen = g.copy(llmPrompt = v)) } }
            if (g.source == ImageSource.COMFY) {
                Field(tr("Workflow (API format JSON with %prompt%, %negative_prompt%, %seed%, %steps%, %scale%, %width%, %height%, %model%, %sampler%, %scheduler%)"),
                    g.comfyWorkflow, { v -> set { copy(imageGen = g.copy(comfyWorkflow = v)) } }, singleLine = false, minLines = 6)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
