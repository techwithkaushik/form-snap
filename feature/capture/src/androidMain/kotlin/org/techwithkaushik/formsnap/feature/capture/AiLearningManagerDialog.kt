package org.techwithkaushik.formsnap.feature.capture

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AiLearningManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AiLearningStore(context) }
    val scope = rememberCoroutineScope()
    val examples = remember { mutableStateListOf<AiLearningStore.Example>().apply { addAll(store.examples()) } }
    var message by remember { mutableStateOf<String?>(null) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    var showLabelDialog by remember { mutableStateOf(false) }

    val saveZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val source = exportFile
        if (uri == null || source == null) {
            message = "Export cancelled."
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            source.inputStream().buffered().use { it.copyTo(output) }
                        } ?: error("Could not open destination file.")
                    }
                }
                message = result.fold({ "Dataset ZIP saved successfully." }, { "Export failed: ${it.message}" })
                source.delete()
                exportFile = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline AI learning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Reviewed examples: ${examples.size}")
                Text("Photo: ${examples.count { it.classId == AiLearningStore.PHOTO }}  •  Signature: ${examples.count { it.classId == AiLearningStore.SIGNATURE }}")
                Text("Examples stay on this device. Export is manual; nothing is uploaded automatically.")
                Button(onClick = { showLabelDialog = true }) { Text("Add labeled example") }
                message?.let { Text(it) }
                if (examples.isEmpty()) {
                    Text("No reviewed examples collected yet.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(examples, key = { it.id }) { example ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(example.className)
                                    Text("${(example.left * 100).toInt()}%, ${(example.top * 100).toInt()}% — ${(example.right * 100).toInt()}%, ${(example.bottom * 100).toInt()}%")
                                }
                                TextButton(onClick = {
                                    if (store.delete(example.id)) examples.remove(example)
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        runCatching {
                            val output = File(context.cacheDir, "formsnap-reviewed-dataset.zip")
                            store.exportYoloZip(output)
                        }
                    }
                    file.fold(
                        onSuccess = {
                            exportFile = it
                            saveZipLauncher.launch("formsnap-reviewed-dataset.zip")
                        },
                        onFailure = { message = it.message ?: "Dataset export failed." },
                    )
                }
            }, enabled = examples.isNotEmpty()) { Text("Export YOLO ZIP") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        },
    )
    if (showLabelDialog) {
        AiExampleLabelDialog(store = store, onSaved = { example ->
            examples.add(0, example)
            message = "Example saved on this device."
            showLabelDialog = false
        }, onDismiss = { showLabelDialog = false })
    }
}
