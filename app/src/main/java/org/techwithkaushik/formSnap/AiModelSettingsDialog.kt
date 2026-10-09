package org.techwithkaushik.formSnap

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.techwithkaushik.formsnap.ai.AiModelInfo
import org.techwithkaushik.formsnap.ai.AiModelManager
import java.util.Locale

@Composable
fun AiModelSettingsDialog(
    context: Context,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val manager = remember { AiModelManager(context) }
    var models by remember { mutableStateOf(manager.models()) }
    var backupName by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { manager.importModel(uri) }
            .onSuccess { info ->
                models = manager.models()
                onMessage("AI model imported and activated: ${info.name}")
            }
            .onFailure { onMessage("AI model import failed: ${it.message ?: "unknown error"}") }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val name = backupName
        backupName = null
        if (uri != null && name != null) {
            runCatching { manager.exportModel(name, uri) }
                .onSuccess { onMessage("AI model backup created.") }
                .onFailure { onMessage("Backup failed: ${it.message ?: "unknown error"}") }
        }
    }

    LaunchedEffect(Unit) { models = manager.models() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "AI models are stored outside the APK. Import a compatible .tflite model here; the active model is used for live detection and extraction.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import AI Model (.tflite)")
                }
                HorizontalDivider()
                if (models.isEmpty()) {
                    Text("No AI model imported.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(models.size) { index ->
                            val model = models[index]
                            ModelRow(
                                model = model,
                                onActivate = {
                                    runCatching { manager.setActive(model.name) }
                                        .onSuccess {
                                            models = manager.models()
                                            onMessage("Active model: ${model.name}")
                                        }
                                        .onFailure {
                                            onMessage("Cannot activate model: ${it.message ?: "incompatible model"}")
                                        }
                                },
                                onBackup = {
                                    backupName = model.name
                                    backupLauncher.launch(model.name)
                                },
                                onDelete = {
                                    runCatching { manager.delete(model.name) }
                                        .onSuccess {
                                            models = manager.models()
                                            onMessage("Model deleted.")
                                        }
                                        .onFailure { onMessage(it.message ?: "Cannot delete model.") }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ModelRow(
    model: AiModelInfo,
    onActivate: () -> Unit,
    onBackup: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(model.name, style = MaterialTheme.typography.titleSmall)
        Text(
            formatBytes(model.sizeBytes) + if (model.active) " • ACTIVE" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!model.active) {
                TextButton(onClick = onActivate) { Text("Activate") }
            }
            TextButton(onClick = onBackup) { Text("Backup") }
            if (!model.active) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
