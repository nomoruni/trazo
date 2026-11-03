package com.trazo.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trazo.scanner.R
import com.trazo.scanner.TrazoViewModel
import com.trazo.scanner.image.InkColorMode
import com.trazo.scanner.ui.components.SignatureSurface
import kotlin.math.roundToInt

private enum class EditorTab { ADJUST, CLEAN }

@Composable
fun EditorScreen(
    viewModel: TrazoViewModel,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(EditorTab.ADJUST) }
    var imageRect by remember { mutableStateOf(Rect.Zero) }
    var lastMaskPoint by remember { mutableStateOf<Offset?>(null) }

    val processor = viewModel.processor

    fun toMask(point: Offset): Offset? {
        val rect = imageRect
        if (rect.width <= 0f || rect.height <= 0f) return null
        val x = (point.x - rect.left) / rect.width
        val y = (point.y - rect.top) / rect.height
        if (x < -0.02f || y < -0.02f || x > 1.02f || y > 1.02f) return null
        val p = processor ?: return null
        return Offset(x.coerceIn(0f, 1f) * p.width, y.coerceIn(0f, 1f) * p.height)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::recrop) {
                Icon(Icons.Rounded.Crop, contentDescription = stringResource(R.string.editor_crop_again))
            }
            IconButton(onClick = { viewModel.showOriginal = !viewModel.showOriginal }) {
                Icon(
                    imageVector = if (viewModel.showOriginal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(R.string.editor_original),
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(viewModel.eraserEnabled, processor) {
                    if (!viewModel.eraserEnabled) return@pointerInput
                    val radiusPx = viewModel.brushSize * (processor?.width ?: 1000)
                    detectDragGestures(
                        onDragStart = { position ->
                            viewModel.beginErase()
                            val maskPoint = toMask(position)
                            lastMaskPoint = maskPoint
                            if (maskPoint != null) {
                                viewModel.eraseSegment(
                                    maskPoint.x, maskPoint.y, maskPoint.x, maskPoint.y, radiusPx,
                                )
                            }
                        },
                        onDragEnd = { lastMaskPoint = null },
                        onDragCancel = { lastMaskPoint = null },
                        onDrag = { change, _ ->
                            change.consume()
                            val maskPoint = toMask(change.position)
                            if (maskPoint != null) {
                                val previous = lastMaskPoint
                                if (previous != null) {
                                    viewModel.eraseSegment(
                                        previous.x, previous.y, maskPoint.x, maskPoint.y, radiusPx,
                                    )
                                }
                                lastMaskPoint = maskPoint
                            }
                        },
                    )
                },
        ) {
            SignatureSurface(
                bitmap = if (viewModel.showOriginal) viewModel.croppedBitmap else viewModel.previewBitmap,
                modifier = Modifier.fillMaxSize(),
                checker = !viewModel.showOriginal,
                onRect = { imageRect = it },
            )

            if (viewModel.eraserEnabled) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Brush,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.editor_eraser),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == EditorTab.ADJUST,
                        onClick = { tab = EditorTab.ADJUST },
                        label = { Text(stringResource(R.string.editor_tab_adjust)) },
                    )
                    FilterChip(
                        selected = tab == EditorTab.CLEAN,
                        onClick = { tab = EditorTab.CLEAN },
                        label = { Text(stringResource(R.string.editor_tab_clean)) },
                    )
                }

                Spacer(Modifier.height(10.dp))

                when (tab) {
                    EditorTab.ADJUST -> AdjustTab(viewModel)
                    EditorTab.CLEAN -> CleanTab(viewModel)
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        icon = Icons.Rounded.Image,
                        label = stringResource(R.string.editor_save_png),
                        modifier = Modifier.weight(1f),
                        onClick = viewModel::savePng,
                    )
                    ActionButton(
                        icon = Icons.Rounded.Description,
                        label = stringResource(R.string.editor_save_pdf),
                        modifier = Modifier.weight(1f),
                        onClick = viewModel::savePdf,
                    )
                    ActionButton(
                        icon = Icons.Rounded.Share,
                        label = stringResource(R.string.editor_share),
                        modifier = Modifier.weight(1f),
                        onClick = viewModel::share,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdjustTab(viewModel: TrazoViewModel) {
    val settings = viewModel.settings
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = settings.colorMode == InkColorMode.BLACK,
            onClick = { viewModel.setColorMode(InkColorMode.BLACK) },
            label = { Text(stringResource(R.string.editor_color_black)) },
            leadingIcon = if (settings.colorMode == InkColorMode.BLACK) {
                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
            } else null,
        )
        FilterChip(
            selected = settings.colorMode == InkColorMode.ORIGINAL,
            onClick = { viewModel.setColorMode(InkColorMode.ORIGINAL) },
            label = { Text(stringResource(R.string.editor_color_original)) },
            leadingIcon = if (settings.colorMode == InkColorMode.ORIGINAL) {
                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
            } else null,
        )
    }

    Spacer(Modifier.height(4.dp))

    LabeledSlider(
        label = stringResource(R.string.editor_threshold),
        value = settings.threshold,
        onChange = viewModel::setThreshold,
    )
    LabeledSlider(
        label = stringResource(R.string.editor_softness),
        value = settings.softness,
        onChange = viewModel::setSoftness,
    )
    LabeledSlider(
        label = stringResource(R.string.editor_weight),
        value = settings.weight,
        onChange = viewModel::setWeight,
    )

    TextButton(onClick = viewModel::resetSettings) {
        Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.editor_reset))
    }
}

@Composable
private fun CleanTab(viewModel: TrazoViewModel) {
    FilterChip(
        selected = viewModel.eraserEnabled,
        onClick = { viewModel.eraserEnabled = !viewModel.eraserEnabled },
        label = { Text(stringResource(R.string.editor_eraser)) },
        leadingIcon = { Icon(Icons.Rounded.Brush, null, Modifier.size(18.dp)) },
    )

    LabeledSlider(
        label = stringResource(R.string.editor_brush),
        value = viewModel.brushSize,
        range = 0.01f..0.2f,
        onChange = { viewModel.brushSize = it },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = viewModel::undoErase, enabled = viewModel.canUndo) {
            Icon(Icons.Rounded.Undo, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.editor_undo))
        }
        TextButton(onClick = viewModel::restoreErased) {
            Text(stringResource(R.string.editor_restore_all))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(92.dp),
            textAlign = TextAlign.Start,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${(value * 100).roundToInt()}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(34.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
