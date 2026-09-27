package org.techwithkaushik.formSnap.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.techwithkaushik.formSnap.pipeline.AppearanceAdjustments
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.PreviewCorrectionState

@Composable
fun PreviewCorrectionScreen(
    state: PreviewCorrectionState,
    preview: Bitmap?,
    onBoundsChange: (RectF) -> Unit,
    onAppearanceChange: (AppearanceAdjustments) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (state.kind == DetectionKind.PHOTO) "Photo correction" else "Signature correction")

        preview?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(if (state.kind == DetectionKind.PHOTO) 260.dp else 150.dp),
                contentScale = ContentScale.Fit,
            )
        }

        Text("Brightness ${state.appearance.brightness.asDisplay()}")
        Slider(value = state.appearance.brightness, onValueChange = {
            onAppearanceChange(state.appearance.copy(brightness = it))
        }, valueRange = -0.5f..0.5f)

        Text("Contrast ${state.appearance.contrast.asDisplay()}")
        Slider(value = state.appearance.contrast, onValueChange = {
            onAppearanceChange(state.appearance.copy(contrast = it))
        }, valueRange = 0.7f..1.5f)

        if (state.kind == DetectionKind.PHOTO) {
            Text("Saturation ${state.appearance.saturation.asDisplay()}")
            Slider(value = state.appearance.saturation, onValueChange = {
                onAppearanceChange(state.appearance.copy(saturation = it))
            }, valueRange = 0.5f..1.5f)
        } else {
            Text("Ink threshold ${state.appearance.inkThreshold}")
            Slider(value = state.appearance.inkThreshold.toFloat(), onValueChange = {
                onAppearanceChange(state.appearance.copy(inkThreshold = it.toInt()))
            }, valueRange = 80f..220f, steps = 13)
        }

        Text("Sharpness ${state.appearance.sharpness.asDisplay()}")
        Slider(value = state.appearance.sharpness, onValueChange = {
            onAppearanceChange(state.appearance.copy(sharpness = it))
        }, valueRange = 0f..1f)

        Text("Denoise ${state.appearance.denoise.asDisplay()}")
        Slider(value = state.appearance.denoise, onValueChange = {
            onAppearanceChange(state.appearance.copy(denoise = it))
        }, valueRange = 0f..1f)

        Text("Crop: ${state.currentBounds.left.toInt()}, ${state.currentBounds.top.toInt()} → ${state.currentBounds.right.toInt()}, ${state.currentBounds.bottom.toInt()}")
        Text("Use the host image editor to adjust the crop bounds.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.dirty, onClick = onReset, label = { Text("Reset") })
            OutlinedButton(onClick = onReject) { Text("Reject") }
            Button(onClick = onAccept) { Text("Accept") }
        }
    }
}

private fun Float.asDisplay(): String = "%.2f".format(this)