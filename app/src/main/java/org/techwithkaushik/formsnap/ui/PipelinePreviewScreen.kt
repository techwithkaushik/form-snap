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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
                        saving -> "Saving"
                        detectedCount == 2 -> "2 found"
                        detectedCount == 1 -> "1 found"
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
                        Text(
                            "Turn a form into clean outputs",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Review detected regions, adjust the crop if needed, then save both files to your chosen folder.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onRecapture,
                                enabled = !processing && !saving,
                                modifier = Modifier.weight(1f),
                            ) { Text("Capture again", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(
                                onClick = onReimport,
                                enabled = !processing && !saving,
                                modifier = Modifier.weight(1f),
                            ) { Text("Import image", maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                        Text("Detect photo & signature", fontWeight = FontWeight.SemiBold)
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
                SectionHeading(
                    title = "Your outputs",
                    subtitle = "Correct the crop before accepting or saving",
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
                    onEdit = onEditPhoto,
                    onAccept = onAcceptPhoto,
                    onReject = onRejectPhoto,
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
                    onEdit = onEditSignature,
                    onAccept = onAcceptSignature,
                    onReject = onRejectSignature,
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
                        OutlinedTextField(
                            value = personName,
                            onValueChange = onPersonNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Person's name (optional)") },
                            placeholder = { Text("e.g. Arvind Kaushik") },
                            supportingText = {
                                Text(
                                    if (signatureAsJpeg) {
                                        "Photo: name-photo.jpg  •  Signature: name-sign.jpg"
                                    } else {
                                        "Photo: name-photo.jpg  •  Signature: name-sign.png"
                                    },
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
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
    onEdit: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onChooseFolder,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Change folder") }
                Button(
                    onClick = onSave,
                    enabled = detected && preview != null && !saving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (saving) "Saving…" else "Save file") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    enabled = detected && !processing && !saving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Reject") }
                OutlinedButton(
                    onClick = onEdit,
                    enabled = !processing && !saving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (detected) "Adjust crop" else "Select crop") }
                Button(
                    onClick = onAccept,
                    enabled = detected && !processing && !saving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Accept") }
            }
        }
    }
}
