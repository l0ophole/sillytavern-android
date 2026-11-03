package app.sillytavern.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.media.GalleryImage
import coil.compose.AsyncImage

@Composable
fun GalleryFoldersScreen(c: AppContainer, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val folders = remember { c.gallery.folders() }
    Scaffold(topBar = { BackTopBar(tr("Gallery"), onBack) }) { pad ->
        if (folders.isEmpty()) Text(tr("No images yet. Attach or generate images in a chat."), Modifier.padding(pad).padding(16.dp))
        else LazyColumn(Modifier.padding(pad)) {
            items(folders) { (name, count) ->
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text("$count") }, modifier = Modifier.clickable { onOpen(name) })
            }
        }
    }
}

/** Images of one character / group folder: view, add from the device, save a copy, delete. */
@Composable
fun GalleryScreen(c: AppContainer, folder: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var images by remember { mutableStateOf(c.gallery.list(folder)) }
    var viewing by remember { mutableStateOf<GalleryImage?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<GalleryImage?>(null) }
    val add = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            readScaledImage(ctx, uri, 0)?.let { (bytes, name) -> c.gallery.add(folder, name, bytes) }
        }
        images = c.gallery.list(folder)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val img = exporting; exporting = null
        if (uri == null || img == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(img.file.readBytes()) } }
            .onSuccess { message = "Saved" }.onFailure { message = "Save failed: ${it.message}" }
    }
    Scaffold(topBar = { BackTopBar(folder, onBack, actions = { TextButton(onClick = { add.launch("image/*") }) { Text(tr("Add")) } }) }) { pad ->
        Column(Modifier.padding(pad)) {
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
            LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                gridItems(images, key = { it.path }) { img ->
                    AsyncImage(img.file, img.name, Modifier.aspectRatio(1f).clickable { viewing = img }, contentScale = ContentScale.Crop)
                }
            }
        }
    }
    viewing?.let { img ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            text = { AsyncImage(img.file, img.name, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text(tr("Close")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { exporting = img; viewing = null; export.launch(img.name) }) { Text(tr("Save copy")) }
                    TextButton(onClick = { c.gallery.delete(img.path); images = c.gallery.list(folder); viewing = null }) { Text(tr("Delete")) }
                }
            },
        )
    }
}
