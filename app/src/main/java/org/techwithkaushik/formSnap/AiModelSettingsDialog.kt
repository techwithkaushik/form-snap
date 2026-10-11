@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.techwithkaushik.formSnap

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    var labelsModelName by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { manager.importModel(uri) }
            .onSuccess { info -> models = manager.models(); onMessage("AI model imported and activated: ${info.name}") }
            .onFailure { onMessage("AI model import failed: ${it.message ?: "unknown error"}") }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = backupName; backupName = null
        if (uri != null && name != null) runCatching { manager.exportModel(name, uri) }
            .onSuccess { onMessage("Model backup created.") }
            .onFailure { onMessage("Backup failed: ${it.message ?: "unknown error"}") }
    }
    val labelsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = labelsModelName; labelsModelName = null
        if (uri != null && name != null) runCatching { manager.importLabels(uri, name) }
            .onSuccess { models = manager.models(); onMessage("Annotation labels imported for $name.") }
            .onFailure { onMessage("Labels import failed: ${it.message ?: "unknown error"}") }
    }
    LaunchedEffect(Unit) { models = manager.models() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Details", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onDismiss) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import TFLite model (.tflite)")
                }
                Text(
                    "Embedded class names are loaded automatically. If the model has no labels, Live Detection shows generic Class 0, Class 1, etc.; import the matching labels file to show real class names.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (models.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No model imported", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Import a compatible TensorFlow Lite object-detection model to enable live detection.")
                        }
                    }
                }
            }
            items(models, key = { it.name }) { model ->
                ModelCard(
                    model = model,
                    onActivate = {
                        runCatching { manager.setActive(model.name) }
                            .onSuccess { models = manager.models(); onMessage("Active model: ${model.name}") }
                            .onFailure { onMessage("Cannot activate model: ${it.message ?: "incompatible model"}") }
                    },
                    onLabels = { labelsModelName = model.name; labelsLauncher.launch(arrayOf("text/plain", "text/*")) },
                    onBackup = { backupName = model.name; backupLauncher.launch(model.name) },
                    onDelete = {
                        runCatching { manager.delete(model.name) }
                            .onSuccess { models = manager.models(); onMessage("Model deleted.") }
                            .onFailure { onMessage(it.message ?: "Cannot delete model.") }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: AiModelInfo,
    onActivate: () -> Unit,
    onBackup: () -> Unit,
    onLabels: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("AI", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(formatBytes(model.sizeBytes) + if (model.active) " • ACTIVE" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (model.active) "Model loaded successfully" else "Imported model", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()
            Text("Model Information", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            InfoLine("Model name", model.name)
            InfoLine("File size", formatBytes(model.sizeBytes))
            InfoLine("Input tensor", model.inputDetails.ifBlank { "Unavailable" })
            InfoLine("Output tensor", model.outputDetails.ifBlank { "Unavailable" })
            HorizontalDivider()
            Text("Classes / annotations", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                model.classDetails.ifBlank { "Semantic class names are not embedded. Live Detection will use generic class IDs until the matching labels file is imported." },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!model.active) TextButton(onClick = onActivate) { Text("Activate") }
                TextButton(onClick = onLabels) { Text("Import labels") }
                TextButton(onClick = onBackup) { Text("Backup") }
                if (!model.active) TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
