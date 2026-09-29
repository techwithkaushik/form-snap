package org.techwithkaushik.formSnap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun PipelinePreviewScreen(
    inputPreview: Bitmap?,
    photoPreview: Bitmap?,
    signaturePreview: Bitmap?,
    photoDetected: Boolean,
    signatureDetected: Boolean,
    processing: Boolean,
    message: String?,
    saving: Boolean = false,
    onProcess: () -> Unit,
    onRecapture: () -> Unit,
    onReimport: () -> Unit,
    onEditPhoto: () -> Unit,
    onAcceptPhoto: () -> Unit,
    onRejectPhoto: () -> Unit,
    onEditSignature: () -> Unit,
    onAcceptSignature: () -> Unit,
    onRejectSignature: () -> Unit,
    onSavePhoto: () -> Unit,
    onChoosePhotoFolder: () -> Unit,
    onSaveSignature: () -> Unit,
    onChooseSignatureFolder: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Preview") }

        inputPreview?.let { bitmap ->
            item {
                Card {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRecapture,
                    enabled = !processing,
                    modifier = Modifier.weight(1f),
                ) { Text("Recapture") }
                OutlinedButton(
                    onClick = onReimport,
                    enabled = !processing,
                    modifier = Modifier.weight(1f),
                ) { Text("Reimport") }
            }
        }

        item {
            Button(
                onClick = onProcess,
                enabled = !processing && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(when {
                    processing -> "Processing…"
                    saving -> "Saving…"
                    else -> "Process"
                })
            }
        }

        message?.let { text -> item { Text(text) } }

        if (photoDetected) {
            item {
                OutputPreviewCard(
                    title = "Photo",
                    preview = photoPreview,
                    detected = photoDetected,
                    onEdit = onEditPhoto,
                    onAccept = onAcceptPhoto,
                    onReject = onRejectPhoto,
                    onSave = onSavePhoto,
                    onChooseFolder = onChoosePhotoFolder,
                )
            }
        }

        if (signatureDetected) {
            item {
                OutputPreviewCard(
                    title = "Signature",
                    preview = signaturePreview,
                    detected = signatureDetected,
                    onEdit = onEditSignature,
                    onAccept = onAcceptSignature,
                    onReject = onRejectSignature,
                    onSave = onSaveSignature,
                    onChooseFolder = onChooseSignatureFolder,
                )
            }
        }

        item {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun OutputPreviewCard(
    title: String,
    preview: Bitmap?,
    detected: Boolean,
    onEdit: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onSave: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    Card {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title)
            Text(if (detected) "Detected" else "Not detected")

            preview?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSave,
                    enabled = detected && preview != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = onChooseFolder,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Folder")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    enabled = detected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reject")
                }
                OutlinedButton(
                    onClick = onEdit,
                    enabled = detected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Edit")
                }
                Button(
                    onClick = onAccept,
                    enabled = detected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Accept")
                }
            }
        }
    }
}