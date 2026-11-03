package app.sillytavern.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.tags.TagOps
import app.sillytavern.android.tags.updateTags
import app.sillytavern.android.worldinfo.WorldJson
import kotlinx.serialization.json.*
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream

/** Decode an image, downscale to at most 512x768, and re-encode as PNG for use as an avatar. */
private fun toAvatarPng(bytes: ByteArray): ByteArray {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var sample = 1
    while (opts.outWidth / sample > 1024 || opts.outHeight / sample > 1536) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Not an image")
    val scale = minOf(512f / bmp.width, 768f / bmp.height, 1f)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(c: AppContainer, fileName: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val existing = remember(fileName) { fileName?.let { c.characters.get(it) } }
    val base = existing?.card ?: CharacterCard(name = "")
    var name by remember { mutableStateOf(base.name) }
    var description by remember { mutableStateOf(base.description) }
    var personality by remember { mutableStateOf(base.personality) }
    var scenario by remember { mutableStateOf(base.scenario) }
    var first by remember { mutableStateOf(base.firstMessage) }
    var alts by remember { mutableStateOf(base.alternateGreetings.joinToString("\n---\n")) }
    var example by remember { mutableStateOf(base.messageExample) }
    var sys by remember { mutableStateOf(base.systemPrompt) }
    var post by remember { mutableStateOf(base.postHistoryInstructions) }
    var notes by remember { mutableStateOf(base.creatorNotes) }
    var tags by remember { mutableStateOf(base.tags.joinToString(", ")) }
    val attachedWorld = ((base.raw["extensions"] as? JsonObject)?.get("world") as? JsonPrimitive)?.contentOrNull.orEmpty()
    var world by remember { mutableStateOf(attachedWorld) }
    var worldNames by remember { mutableStateOf(c.worlds.list()) }
    var scoped by remember { mutableStateOf(c.regexScripts.scopedOf(base)) }
    val qrNames = remember { c.quickReplyRepo.list() }
    var qrSet by remember { mutableStateOf(existing?.fileName?.let { c.settings.settings.value.quickReplies.characterSets[it] }) }
    var fav by remember { mutableStateOf(base.fav) }
    var talk by remember { mutableStateOf(base.talkativeness.toFloat()) }
    var avatar by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching { avatar = toAvatarPng(ctx.readUri(uri)) }.onFailure { error = "Avatar: ${it.message}" }
    }

    Scaffold(topBar = {
        BackTopBar(if (existing == null) "New character" else "Edit ${base.name}", onDone, actions = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val card = base.copy(
                    name = name.trim(), description = description, personality = personality, scenario = scenario,
                    firstMessage = first, messageExample = example, systemPrompt = sys, postHistoryInstructions = post,
                    creatorNotes = notes,
                    alternateGreetings = alts.split(Regex("\\n---\\n")).map { it.trim() }.filter { it.isNotEmpty() },
                    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    raw = withWorld(base.raw, world),
                ).withFav(fav).withTalkativeness(talk.toDouble()).let { cd -> c.regexScripts.withScoped(cd, scoped) }
                runCatching { c.characters.save(existing?.fileName, card, avatar) }
                    .onSuccess { saved ->
                        if (existing == null) c.settings.updateTags { TagOps.importCardTags(it, saved.fileName + ".png", card.tags) }
                        c.settings.update { st ->
                            val m = st.quickReplies.characterSets - saved.fileName
                            st.copy(quickReplies = st.quickReplies.copy(characterSets = if (qrSet == null) m else m + (saved.fileName to qrSet!!)))
                        }
                        onDone()
                    }.onFailure { error = "Save failed: ${it.message}" }
            }) { Text("Save") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val model: Any? = avatar ?: existing?.file
                if (model != null) AsyncImage(model, null, Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Choose avatar") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Field("Name", name, { name = it })
            Field("Description", description, { description = it }, singleLine = false, minLines = 4)
            Field("Personality", personality, { personality = it }, singleLine = false, minLines = 2)
            Field("Scenario", scenario, { scenario = it }, singleLine = false, minLines = 2)
            Field("First message", first, { first = it }, singleLine = false, minLines = 3)
            Field("Alternate greetings (separate with a line containing ---)", alts, { alts = it }, singleLine = false, minLines = 3)
            Field("Example dialogue", example, { example = it }, singleLine = false, minLines = 3)
            Field("System prompt override", sys, { sys = it }, singleLine = false, minLines = 2)
            Field("Post-history instructions", post, { post = it }, singleLine = false, minLines = 2)
            Field("Creator notes", notes, { notes = it }, singleLine = false, minLines = 2)
            if (existing == null) Field("Tags (comma separated)", tags, { tags = it })
            else EntityTagEditor(c, existing.fileName + ".png")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Favorite", Modifier.weight(1f)); Switch(fav, { fav = it })
            }
            Text("Talkativeness in group chats: ${"%.2f".format(talk)}", style = MaterialTheme.typography.labelLarge)
            Slider(talk, { talk = it }, valueRange = 0f..1f)
            Pick("Lorebook", listOf("(none)") + worldNames, if (world.isBlank()) 0 else worldNames.indexOf(world) + 1) { world = if (it == 0) "" else worldNames[it - 1] }
            (base.raw["character_book"] as? JsonObject)?.let { cb ->
                OutlinedButton(onClick = {
                    val n = (cb["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: "${name.ifBlank { "Character" }} lore"
                    c.worlds.save(WorldJson.fromCharacterBook(n, cb)); worldNames = c.worlds.list(); world = n
                }) { Text("Import embedded lorebook as a world book") }
            }
            Pick("Quick reply set", listOf("(none)") + qrNames, if (qrSet == null) 0 else qrNames.indexOf(qrSet) + 1) { qrSet = if (it == 0) null else qrNames[it - 1] }
            Text("Regex scripts (this character)", style = MaterialTheme.typography.titleMedium)
            RegexListEditor(scoped, { scoped = it })
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun withWorld(raw: JsonObject, world: String): JsonObject {
    val ext = JsonObject((raw["extensions"] as? JsonObject).orEmpty().toMutableMap().also {
        if (world.isBlank()) it.remove("world") else it["world"] = JsonPrimitive(world)
    })
    return JsonObject(raw + ("extensions" to ext))
}

private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())
