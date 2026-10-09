package org.techwithkaushik.formSnap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp


private fun vectorIcon(
    name: String,
    draw: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply(draw).build()

private val BackVector = vectorIcon("Back") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(19f, 12f); lineTo(5f, 12f); moveTo(5f, 12f); lineTo(11f, 6f); moveTo(5f, 12f); lineTo(11f, 18f)
    }
}
private val SettingsVector = vectorIcon("Settings") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 18f); lineTo(20f, 18f)
        moveTo(8f, 4f); lineTo(8f, 8f); moveTo(15f, 10f); lineTo(15f, 14f); moveTo(11f, 16f); lineTo(11f, 20f)
    }
}
private val CameraVector = vectorIcon("Camera") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 7f); lineTo(8f, 7f); lineTo(10f, 4f); lineTo(14f, 4f); lineTo(16f, 7f); lineTo(20f, 7f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(12f, 10f); curveTo(9.8f, 10f, 8f, 11.8f, 8f, 14f); curveTo(8f, 16.2f, 9.8f, 18f, 12f, 18f); curveTo(14.2f, 18f, 16f, 16.2f, 16f, 14f); curveTo(16f, 11.8f, 14.2f, 10f, 12f, 10f)
    }
}
private val ImportVector = vectorIcon("Import image") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
        moveTo(5f, 17f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 17f)
    }
}
private val DetectVector = vectorIcon("Detect") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f); moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f); moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }
}
private val FolderVector = vectorIcon("Folder") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3f, 7f); lineTo(3f, 19f); lineTo(21f, 19f); lineTo(21f, 8f); lineTo(11f, 8f); lineTo(9f, 5f); lineTo(3f, 5f); close()
    }
}
private val SaveVector = vectorIcon("Save") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 3f); lineTo(17f, 3f); lineTo(21f, 7f); lineTo(21f, 21f); lineTo(3f, 21f); lineTo(3f, 3f); close()
        moveTo(7f, 3f); lineTo(7f, 9f); lineTo(16f, 9f); lineTo(16f, 3f)
        moveTo(7f, 21f); lineTo(7f, 14f); lineTo(17f, 14f); lineTo(17f, 21f)
    }
}
private val CropVector = vectorIcon("Crop") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(8f, 3f); lineTo(8f, 16f); lineTo(21f, 16f); moveTo(3f, 8f); lineTo(16f, 8f); lineTo(16f, 21f)
    }
}
@Composable
private fun IconActionItem(
    label: String,
    description: String,
    image: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(
                    if (emphasized) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    RoundedCornerShape(12.dp),
                ),
        ) {
            Icon(
                imageVector = image,
                contentDescription = description,
                tint = if (emphasized) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(23.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun PipelinePreviewScreen(
    inputPreview: Bitmap?,
    photoPreview: Bitmap?,
    signaturePreview: Bitmap?,
    photoDetected: Boolean,
    signatureDetected: Boolean,
    photoConfidence: Float? = null,
    signatureConfidence: Float? = null,
    processing: Boolean,
    message: String?,
    personName: String,
    onPersonNameChange: (String) -> Unit,
    signatureAsJpeg: Boolean,
    onSignatureAsJpegChange: (Boolean) -> Unit,
    saving: Boolean = false,
    savingPhoto: Boolean = false,
    savingSignature: Boolean = false,
    photoSaved: Boolean = false,
    signatureSaved: Boolean = false,
    onProcess: () -> Unit,
    onRecapture: () -> Unit,
    onReimport: () -> Unit,
    onEditPhoto: () -> Unit,
    onEditSignature: () -> Unit,
    onSavePhoto: () -> Unit,
    onChoosePhotoFolder: () -> Unit,
    onSaveSignature: () -> Unit,
    onChooseSignatureFolder: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detectedCount = (if (photoDetected) 1 else 0) + (if (signatureDetected) 1 else 0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = !saving) {
                    Icon(BackVector, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "FormSnap",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Photo & signature studio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = when {
                        processing -> "Analyzing"
                        savingPhoto -> "Saving photo"
                        savingSignature -> "Saving signature"
                        saving -> "Saving"
                        photoSaved && signatureSaved -> "Both saved"
                        photoSaved -> "Photo saved"
                        signatureSaved -> "Signature saved"
                        detectedCount == 2 -> "2 ready"
                        detectedCount == 1 -> "1 ready"
                        else -> "Ready"
                    },
                    active = processing || saving || detectedCount > 0,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            IconActionItem(
                                label = "Capture again",
                                description = "Capture another form image",
                                image = CameraVector,
                                onClick = onRecapture,
                                enabled = !processing && !saving,
                                modifier = Modifier.weight(1f),
                            )
                            IconActionItem(
                                label = "Import image",
                                description = "Import another form image",
                                image = ImportVector,
                                onClick = onReimport,
                                enabled = !processing && !saving,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                SectionHeading(
                    title = "Source image",
                    subtitle = "Original image used for detection",
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    if (inputPreview != null) {
                        Image(
                            bitmap = inputPreview.asImageBitmap(),
                            contentDescription = "Original form image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Image preview will appear here",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onProcess,
                    enabled = !processing && !saving && inputPreview != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (processing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Analyzing image…")
                    } else {
                        Icon(DetectVector, contentDescription = "Detect photo and signature", modifier = Modifier.size(24.dp))
                    }
                }
                if (processing) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            if (!message.isNullOrBlank()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = personName,
                    onValueChange = onPersonNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Person's name *") },
                    placeholder = { Text("Enter person's full name") },
                    isError = personName.isBlank(),
                    supportingText = {
                        Text(
                            when {
                                personName.isBlank() -> "Required before saving either output."
                                signatureAsJpeg -> "Photo and signature filenames will use this name."
                                else -> "Photo and signature filenames will use this name."
                            },
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
            item {
                SectionHeading(
                    title = "Your outputs",
                    subtitle = "Review the crop, adjust if needed, then save",
                )
            }

            item {
                OutputPreviewCard(
                    title = "Photo",
                    description = "Portrait crop",
                    preview = photoPreview,
                    detected = photoDetected,
                    confidence = photoConfidence,
                    processing = processing,
                    saving = saving,
                    savingThisOutput = savingPhoto,
                    saved = photoSaved,
                    nameRequired = personName.isNotBlank(),
                    onEdit = onEditPhoto,
                    onSave = onSavePhoto,
                    onChooseFolder = onChoosePhotoFolder,
                )
            }

            item {
                OutputPreviewCard(
                    title = "Signature",
                    description = "Ink-only crop",
                    preview = signaturePreview,
                    detected = signatureDetected,
                    confidence = signatureConfidence,
                    processing = processing,
                    saving = saving,
                    savingThisOutput = savingSignature,
                    saved = signatureSaved,
                    nameRequired = personName.isNotBlank(),
                    onEdit = onEditSignature,
                    onSave = onSaveSignature,
                    onChooseFolder = onChooseSignatureFolder,
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionHeading(
                            title = "Save preferences",
                            subtitle = "One remembered folder is shared by both outputs",
                        )
                        Text(
                            "Choose a folder once; FormSnap will reuse it for both the photo and signature.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = signatureAsJpeg,
                                onCheckedChange = onSignatureAsJpegChange,
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Use JPEG for signature", fontWeight = FontWeight.Medium)
                                Text(
                                    "Smaller file, but not lossless",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (saving) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                "Verifying saved file…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Back to previous screen") }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OutputPreviewCard(
    title: String,
    description: String,
    preview: Bitmap?,
    detected: Boolean,
    confidence: Float?,
    processing: Boolean,
    saving: Boolean,
    savingThisOutput: Boolean,
    saved: Boolean,
    nameRequired: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (detected) "Review crop" else "Not found",
                    active = detected,
                )
            }

            if (preview != null && detected) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "$title crop preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (title == "Photo") 220.dp else 140.dp)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (processing) "Detection in progress" else "$title not detected",
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (processing) "Please wait while the image is analyzed."
                            else "Try another image or run detection again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                confidence?.let {
                    "Detector score: ${(it.coerceIn(0f, 1f) * 100f).toInt()}/100 · heuristic, not a probability"
                } ?: "No calibrated confidence score",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val lowConfidence = confidence != null && confidence < 0.35f
            val reviewConfidence = confidence != null && confidence >= 0.35f && confidence < 0.60f
            if (lowConfidence || reviewConfidence) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (lowConfidence) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            if (lowConfidence) "!" else "↗",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (lowConfidence) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (lowConfidence) "Low-confidence detection"
                                else "Please review this crop",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (lowConfidence) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                if (lowConfidence) {
                                    "Do not rely on this box alone. Tap Adjust crop and check that the complete ${title.lowercase()} is included without extra form borders."
                                } else {
                                    "Check the crop edges before saving. Adjust crop is available if the box misses any part."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lowConfidence) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconActionItem(
                    label = "Folder",
                    description = "Change output folder",
                    image = FolderVector,
                    onClick = onChooseFolder,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                )
                IconActionItem(
                    label = when {
                        savingThisOutput -> "Saving"
                        saved -> "Saved"
                        else -> "Save"
                    },
                    description = when {
                        savingThisOutput -> "Saving output file"
                        saved -> "Output file saved successfully"
                        else -> "Save output file"
                    },
                    image = SaveVector,
                    onClick = onSave,
                    enabled = detected && preview != null && nameRequired && !saving && !saved,
                    modifier = Modifier.weight(1f),
                    emphasized = true,
                )
            }

            IconActionItem(
                label = if (detected) "Adjust crop" else "Select crop",
                description = if (detected) "Adjust crop" else "Select a crop manually",
                image = CropVector,
                onClick = onEdit,
                enabled = !processing && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
