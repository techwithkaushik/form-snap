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
    onProcess: () -> Unit,
    onCorrectPhoto: () -> Unit,
    onCorrectSignature: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Button(onClick = onProcess, enabled = !processing, modifier = Modifier.fillMaxWidth()) {
                Text(if (processing) "Processing…" else "Process")
            }
        }
        message?.let { text -> item { Text(text) } }
        if (photoDetected || photoPreview != null) {
            item {
                OutputPreviewCard("Photo", photoPreview, photoDetected, onCorrectPhoto)
            }
        }
        if (signatureDetected || signaturePreview != null) {
            item {
                OutputPreviewCard("Signature", signaturePreview, signatureDetected, onCorrectSignature)
            }
        }
        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

@Composable
private fun OutputPreviewCard(
    title: String,
    preview: Bitmap?,
    detected: Boolean,
    onCorrect: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCorrect, enabled = detected) { Text("Correct") }
            }
        }
    }
}