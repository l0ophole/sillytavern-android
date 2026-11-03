package app.sillytavern.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.sillytavern.android.AppContainer
import app.sillytavern.android.media.ImageMode
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.launch

/** Reads an image from [uri] and scales its longest side to [maxSide] (0 = keep). Returns JPEG/PNG bytes and a file name. */
fun readScaledImage(ctx: Context, uri: Uri, maxSide: Int): Pair<ByteArray, String>? = runCatching {
    val name = displayName(ctx, uri) ?: "image.png"
    val raw = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    if (maxSide <= 0) return@runCatching raw to name
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(raw, 0, raw.size, it) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= maxSide || longest <= 0) return@runCatching raw to name // already small enough: keep the original bytes (and animation)
    var sample = 1
    while (longest / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching raw to name
    val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
    val png = name.endsWith(".png", true)
    val bytes = ByteArrayOutputStream().also { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    bytes to (if (png || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) name else name.substringBeforeLast('.') + ".jpg")
}.getOrNull()

fun displayName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

/** An image stored in a chat message; tap to open it large. */
@Composable
fun MessageImage(c: AppContainer, path: String, caption: String?) {
    val file = remember(path) { app.sillytavern.android.media.MessageMedia.resolve(c.paths.root, path)?.takeIf { it.isFile } }
    var big by remember { mutableStateOf(false) }
    if (file == null) { Text("[image missing]", style = MaterialTheme.typography.labelSmall); return }
    AsyncImage(file, caption, Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { big = true }, contentScale = ContentScale.Fit)
    if (big) ImageViewerDialog(file, caption) { big = false }
}

@Composable
fun ImageViewerDialog(file: File, caption: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AsyncImage(file, caption, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                caption?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

/** Choose what to draw: a mode or a free prompt. */
@Composable
fun GenerateImageDialog(onDismiss: () -> Unit, onGenerate: (String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    val choices = listOf(
        "scene" to "The current scene", "you" to "The character", "me" to "Me (persona)", "face" to "The character's face",
        "last" to "The last message", "raw_last" to "Last message as the prompt", "background" to "A background",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Generate image")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { (k, label) -> TextButton(onClick = { onGenerate(k); onDismiss() }) { Text(tr(label)) } }
                Field(tr("Or describe it"), custom, { custom = it }, singleLine = false, minLines = 2)
            }
        },
        confirmButton = { TextButton(enabled = custom.isNotBlank(), onClick = { onGenerate(custom.trim()); onDismiss() }) { Text(tr("Generate")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Microphone button for the input bar: speech goes to [onText]. */
@Composable
fun MicButton(c: AppContainer, onText: (String) -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }
    val stt = c.stt
    fun begin() {
        listening = true
        if (stt.twoStep) runCatching { stt.beginRecording() }.onFailure { listening = false; onError("Microphone: ${it.message}") }
        else scope.launch {
            try { stt.listen().takeIf { it.isNotBlank() }?.let(onText) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onError("Speech recognition failed: ${e.message}") }
            finally { listening = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) begin() else onError("Microphone permission denied") }
    TextButton(onClick = {
        if (listening) {
            if (stt.twoStep) scope.launch {
                try { stt.endRecording().takeIf { it.isNotBlank() }?.let(onText) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { onError("Transcription failed: ${e.message}") }
                finally { listening = false }
            } else { stt.cancel(); listening = false }
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) { Text(if (listening) "⏹" else "🎤") }
}
