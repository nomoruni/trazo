package com.trazo.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trazo.scanner.R
import com.trazo.scanner.TrazoViewModel
import com.trazo.scanner.image.ImageStore
import com.trazo.scanner.image.InkColorMode
import com.trazo.scanner.ui.components.SignatureSurface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class EditorTab { ADJUST, CLEAN }

private enum class ExportFormat { PNG, PDF }

@Composable
fun EditorScreen(
    viewModel: TrazoViewModel,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(EditorTab.ADJUST) }
    var imageRect by remember { mutableStateOf(Rect.Zero) }
    var exportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var exportName by remember { mutableStateOf("") }

    // Transformación de la vista (pellizco de dos dedos) y cursor del borrador.
    var viewSize by remember { mutableStateOf(Size.Zero) }
    var zoom by remember(viewModel.processor) { mutableStateOf(1f) }
    var rotation by remember(viewModel.processor) { mutableStateOf(0f) }
    var pan by remember(viewModel.processor) { mutableStateOf(Offset.Zero) }
    var brushCursor by remember { mutableStateOf<Offset?>(null) }

    val processor = viewModel.processor

    fun toMask(point: Offset): Offset? {
        val rect = imageRect
        if (rect.width <= 0f || rect.height <= 0f) return null
        val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
        // Deshace la transformación aplicada con graphicsLayer: traslación, rotación y zoom.
        val relative = (point - center - pan) / zoom
        val radians = (-rotation * PI.toFloat() / 180f)
        val cosR = cos(radians)
        val sinR = sin(radians)
        val content = center + Offset(
            relative.x * cosR - relative.y * sinR,
            relative.x * sinR + relative.y * cosR,
        )
        val x = (content.x - rect.left) / rect.width
        val y = (content.y - rect.top) / rect.height
        if (x < -0.02f || y < -0.02f || x > 1.02f || y > 1.02f) return null
        val p = processor ?: return null
        return Offset(x.coerceIn(0f, 1f) * p.width, y.coerceIn(0f, 1f) * p.height)
    }

    /** Grosor actual del borrador en píxeles de la máscara (se lee en cada trazo). */
    fun eraserRadius(): Float = viewModel.brushSize * (processor?.width ?: 1000)

    /**
     * Aplica zoom/rotación/desplazamiento alrededor del centro de los dedos, de forma que
     * el punto de la imagen bajo el pellizco se quede quieto.
     */
    fun applyViewTransform(
        centroid: Offset,
        panChange: Offset,
        zoomChange: Float,
        rotationChange: Float,
    ) {
        val newZoom = (zoom * zoomChange).coerceIn(1f, 8f)
        val effectiveZoom = newZoom / zoom
        val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
        val vector = centroid - center - pan
        val radians = rotationChange * PI.toFloat() / 180f
        val cosR = cos(radians)
        val sinR = sin(radians)
        val rotated = Offset(
            vector.x * cosR - vector.y * sinR,
            vector.x * sinR + vector.y * cosR,
        )
        pan = centroid - center - rotated * effectiveZoom + panChange
        zoom = newZoom
        rotation += rotationChange
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
                .clipToBounds()
                .onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(processor) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var transforming = false
                        // Tras un pellizco no se borra ni se desplaza hasta levantar los dedos.
                        var suppressSingle = false
                        var erasing = false
                        var slopPassed = false
                        var lastMask: Offset? = null
                        if (viewModel.eraserEnabled) brushCursor = down.position

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                if (!transforming) {
                                    transforming = true
                                    suppressSingle = true
                                    erasing = false
                                    lastMask = null
                                    brushCursor = null
                                } else {
                                    applyViewTransform(
                                        centroid = event.calculateCentroid(useCurrent = false),
                                        panChange = event.calculatePan(),
                                        zoomChange = event.calculateZoom(),
                                        rotationChange = event.calculateRotation(),
                                    )
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val change = pressed.first()
                            if (transforming) transforming = false
                            if (suppressSingle) continue

                            val position = change.position
                            if (!slopPassed) {
                                slopPassed = (position - down.position).getDistance() > slop
                            }
                            if (slopPassed) {
                                if (viewModel.eraserEnabled) {
                                    if (!erasing) {
                                        viewModel.beginErase()
                                        erasing = true
                                        lastMask = toMask(down.position)
                                        lastMask?.let { start ->
                                            viewModel.eraseSegment(
                                                start.x, start.y, start.x, start.y, eraserRadius(),
                                            )
                                        }
                                    }
                                    val maskPoint = toMask(position)
                                    if (maskPoint != null) {
                                        val previous = lastMask
                                        val radius = eraserRadius()
                                        if (previous != null) {
                                            viewModel.eraseSegment(
                                                previous.x, previous.y, maskPoint.x, maskPoint.y, radius,
                                            )
                                        } else {
                                            viewModel.eraseSegment(
                                                maskPoint.x, maskPoint.y, maskPoint.x, maskPoint.y, radius,
                                            )
                                        }
                                        lastMask = maskPoint
                                    }
                                } else {
                                    if (zoom > 1.001f) pan += change.positionChange()
                                }
                                change.consume()
                            }
                            brushCursor = if (viewModel.eraserEnabled) position else null
                        }

                        // Un toque sin arrastrar también borra un punto.
                        if (!suppressSingle && !slopPassed && viewModel.eraserEnabled) {
                            val point = toMask(down.position)
                            if (point != null) {
                                viewModel.beginErase()
                                viewModel.eraseSegment(
                                    point.x, point.y, point.x, point.y, eraserRadius(),
                                )
                            }
                        }
                        brushCursor = null
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        rotationZ = rotation
                        translationX = pan.x
                        translationY = pan.y
                    },
            ) {
                SignatureSurface(
                    bitmap = if (viewModel.showOriginal) viewModel.croppedBitmap else viewModel.previewBitmap,
                    modifier = Modifier.fillMaxSize(),
                    checker = !viewModel.showOriginal,
                    onRect = { imageRect = it },
                )
            }

            val cursor = brushCursor
            if (viewModel.eraserEnabled && cursor != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = viewModel.brushSize * imageRect.width * zoom
                    if (radius > 0.05f) {
                        drawCircle(
                            color = Color(0x66000000),
                            radius = radius + 1.dp.toPx(),
                            center = cursor,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = radius,
                            center = cursor,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }

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

            if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    IconButton(onClick = {
                        zoom = 1f
                        rotation = 0f
                        pan = Offset.Zero
                    }) {
                        Icon(
                            Icons.Rounded.CenterFocusStrong,
                            contentDescription = stringResource(R.string.editor_reset_view),
                            modifier = Modifier.size(20.dp),
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
                        onClick = {
                            tab = EditorTab.ADJUST
                            viewModel.eraserEnabled = false
                        },
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
                        onClick = {
                            exportName = viewModel.defaultExportName()
                            exportFormat = ExportFormat.PNG
                        },
                    )
                    ActionButton(
                        icon = Icons.Rounded.Description,
                        label = stringResource(R.string.editor_save_pdf),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            exportName = viewModel.defaultExportName()
                            exportFormat = ExportFormat.PDF
                        },
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

    val format = exportFormat
    if (format != null) {
        ExportNameDialog(
            format = format,
            initialName = exportName,
            onNameChange = { exportName = it },
            onDismiss = { exportFormat = null },
            onConfirm = { name ->
                exportFormat = null
                when (format) {
                    ExportFormat.PNG -> viewModel.savePng(name)
                    ExportFormat.PDF -> viewModel.savePdf(name)
                }
            },
        )
    }
}

/**
 * Diálogo para escribir el nombre del archivo exportado. El nombre se limpia antes de
 * guardarlo (caracteres inválidos, extensiones duplicadas, nombre vacío).
 */
@Composable
private fun ExportNameDialog(
    format: ExportFormat,
    initialName: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val fallback = remember { initialName.ifBlank { "firma" } }
    val clean = ImageStore.sanitizeName(initialName, fallback)
    val extension = if (format == ExportFormat.PDF) "pdf" else "png"
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val confirm = {
        keyboard?.hide()
        onConfirm(clean)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_name_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = initialName,
                    onValueChange = onNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.export_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.export_name_hint, "$clean.$extension"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = clean.isNotBlank()) {
                Text(stringResource(R.string.export_name_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboard?.show()
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

    Spacer(Modifier.height(4.dp))

    Text(
        text = stringResource(R.string.editor_clean_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // El deslizador es exponencial: así se puede afinar en los grosores finos sin perder
    // el rango grande. 0 = mínimo, 1 = máximo.
    LabeledSlider(
        label = stringResource(R.string.editor_brush),
        value = brushToSlider(viewModel.brushSize),
        range = 0f..1f,
        valueText = "%.1f%%".format(viewModel.brushSize * 100),
        onChange = { viewModel.brushSize = sliderToBrush(it) },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = viewModel::undoErase, enabled = viewModel.canUndo) {
            Icon(Icons.Rounded.Undo, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.editor_undo))
        }
        TextButton(onClick = viewModel::redoErase, enabled = viewModel.canRedo) {
            Icon(Icons.Rounded.Redo, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.editor_redo))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = viewModel::restoreErased) {
            Text(stringResource(R.string.editor_restore_all))
        }
    }
}

/** Grosor mínimo y máximo del borrador, como fracción del ancho de la imagen. */
private const val MIN_BRUSH = 0.001f
private const val MAX_BRUSH = 0.2f

/** Convierte el grosor (fracción del ancho) a la posición 0..1 del deslizador. */
private fun brushToSlider(size: Float): Float =
    (ln(size / MIN_BRUSH) / ln(MAX_BRUSH / MIN_BRUSH)).coerceIn(0f, 1f)

/** Convierte la posición 0..1 del deslizador al grosor del borrador. */
private fun sliderToBrush(position: Float): Float =
    MIN_BRUSH * (MAX_BRUSH / MIN_BRUSH).pow(position.coerceIn(0f, 1f))

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: String? = null,
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
            text = valueText ?: "${(value * 100).roundToInt()}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(40.dp),
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
