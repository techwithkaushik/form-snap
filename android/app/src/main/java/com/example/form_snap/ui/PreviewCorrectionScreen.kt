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
fun PreviewCorrectionScreen(
    source: Bitmap?,
    resultPreview: Bitmap?,
    onOpenCrop: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            source?.let {
                Card {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        item {
            resultPreview?.let {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current result")
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onOpenCrop, modifier = Modifier.fillMaxWidth()) {
                Text("Crop / Resize")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                    Text("Reject")
                }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Text("Accept")
                }
            }
        }
    }
}
