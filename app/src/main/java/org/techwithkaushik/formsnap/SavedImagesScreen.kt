package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun SavedImagesScreen(
    store: AutoSaveStore,
    refreshKey: Int,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var images by remember { mutableStateOf<List<SavedImageEntry>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<SavedImageEntry?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshKey) {
        loading = true
        images = withContext(Dispatchers.IO) { runCatching { store.listSavedImages() }.getOrDefault(emptyList()) }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Images", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        if (loading) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.CircularProgressIndicator() }
        } else if (images.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().padding(24.dp).padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No saved images yet. Captured crops will appear here, grouped by model and label.")
            }
        } else {
            val groups = images.groupBy { it.modelName to it.labelName }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                groups.forEach { (folder, entries) ->
                    item(key = "folder-${folder.first}-${folder.second}") {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                            Text(folder.second, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "AI ObjectSnap/savedImage/${folder.first}/${folder.second} • ${entries.size} image(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(entries, key = { it.uri }) { entry ->
                        SavedImageRow(
                            context = context,
                            entry = entry,
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete image?") },
            text = { Text("Delete ${entry.fileName} from ${entry.labelName}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val result = runCatching { store.deleteImage(entry) }
                    result.onSuccess {
                        images = images.filterNot { it.uri == entry.uri }
                        onMessage("Deleted ${entry.fileName}.")
                    }.onFailure { onMessage(it.message ?: "Unable to delete image.") }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SavedImageRow(
    context: Context,
    entry: SavedImageEntry,
    onDelete: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeThumbnail(context, Uri.parse(entry.uri)) }.getOrNull()
        }
    }
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = entry.fileName,
                    modifier = Modifier.height(88.dp).weight(0.8f),
                    contentScale = ContentScale.Fit,
                )
            }
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.fileName, fontWeight = FontWeight.SemiBold)
                Text(if (entry.width > 0) "${entry.width} × ${entry.height}px" else "Image", style = MaterialTheme.typography.bodySmall)
                Text(if (entry.byteCount > 0) String.format(Locale.US, "%.1f KB", entry.byteCount / 1024.0) else entry.relativePath,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Text("🗑") }
        }
    }
}

private fun decodeThumbnail(context: Context, uri: Uri): Bitmap? {
    fun decode(options: BitmapFactory.Options): Bitmap? =
        if (uri.scheme == "file") BitmapFactory.decodeFile(uri.path, options)
        else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 360 || bounds.outHeight / sample > 360) sample *= 2
    return decode(BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
}
