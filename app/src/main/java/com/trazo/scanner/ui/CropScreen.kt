package com.trazo.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.trazo.scanner.R
import com.trazo.scanner.ui.components.fitRect
import com.trazo.scanner.ui.theme.Ink
import kotlin.math.abs
import kotlin.math.max

private fun defaultCorners(): List<Offset> = listOf(
    Offset(0.04f, 0.04f),
    Offset(0.96f, 0.04f),
    Offset(0.96f, 0.96f),
    Offset(0.04f, 0.96f),
)

private enum class CropMode { AUTO, FREE, RECTANGLE }

/** Tamaño mínimo del rectángulo, en fracción de la imagen. */
private const val MIN_RECT_SIZE = 0.06f

/**
 * Convierte cuatro puntos cualesquiera en el rectángulo alineado a los ejes que los contiene.
 * Se usa al pasar del modo libre al rectangular.
 */
private fun asRectangle(points: List<Offset>): List<Offset> {
    val left = points.minOf { it.x }.coerceIn(0f, 1f - MIN_RECT_SIZE)
    val top = points.minOf { it.y }.coerceIn(0f, 1f - MIN_RECT_SIZE)
    val right = points.maxOf { it.x }.coerceAtLeast(left + MIN_RECT_SIZE).coerceAtMost(1f)
    val bottom = points.maxOf { it.y }.coerceAtLeast(top + MIN_RECT_SIZE).coerceAtMost(1f)
    return rectangleCorners(left, top, right, bottom)
}

private fun rectangleCorners(left: Float, top: Float, right: Float, bottom: Float) = listOf(
    Offset(left, top),
    Offset(right, top),
    Offset(right, bottom),
    Offset(left, bottom),
)

/**
 * Mueve la esquina [index] a [target] manteniendo la forma rectangular: la esquina opuesta
 * queda fija y las otras dos se recalculan. Orden de esquinas: sup-izq, sup-der, inf-der, inf-izq.
 */
private fun rectangularCorners(current: List<Offset>, index: Int, target: Offset): List<Offset> {
    val anchor = current[(index + 2) % 4]
    val x = target.x.coerceIn(0f, 1f)
    val y = target.y.coerceIn(0f, 1f)

    return when (index) {
        0 -> { // sup-izq, ancla inf-der
            val right = anchor.x
            val bottom = anchor.y
            rectangleCorners(
                left = x.coerceAtMost(right - MIN_RECT_SIZE).coerceAtLeast(0f),
                top = y.coerceAtMost(bottom - MIN_RECT_SIZE).coerceAtLeast(0f),
                right = right,
                bottom = bottom,
            )
        }
        1 -> { // sup-der, ancla inf-izq
            val left = anchor.x
            val bottom = anchor.y
            rectangleCorners(
                left = left,
                top = y.coerceAtMost(bottom - MIN_RECT_SIZE).coerceAtLeast(0f),
                right = x.coerceAtLeast(left + MIN_RECT_SIZE).coerceAtMost(1f),
                bottom = bottom,
            )
        }
        2 -> { // inf-der, ancla sup-izq
            val left = anchor.x
            val top = anchor.y
            rectangleCorners(
                left = left,
                top = top,
                right = x.coerceAtLeast(left + MIN_RECT_SIZE).coerceAtMost(1f),
                bottom = y.coerceAtLeast(top + MIN_RECT_SIZE).coerceAtMost(1f),
            )
        }
        else -> { // inf-izq, ancla sup-der
            val right = anchor.x
            val top = anchor.y
            rectangleCorners(
                left = x.coerceAtMost(right - MIN_RECT_SIZE).coerceAtLeast(0f),
                top = top,
                right = right,
                bottom = y.coerceAtLeast(top + MIN_RECT_SIZE).coerceAtMost(1f),
            )
        }
    }
}

@Composable
private fun CropModeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.10f),
            labelColor = Color.White,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
        ),
    )
}

@Composable
fun CropScreen(
    bitmap: Bitmap,
    autoCorners: List<Offset>?,
    startInAuto: Boolean,
    onCancel: () -> Unit,
    onApply: (List<Offset>?, Boolean) -> Unit,
) {
    val initialCorners = remember(bitmap, autoCorners) {
        if (startInAuto && autoCorners != null) autoCorners else defaultCorners()
    }
    var corners by remember(bitmap) { mutableStateOf(initialCorners) }
    var mode by remember(bitmap) {
        mutableStateOf(if (startInAuto && autoCorners != null) CropMode.AUTO else CropMode.FREE)
    }
    var activeCorner by remember { mutableStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.crop_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                corners = if (mode == CropMode.AUTO) autoCorners ?: defaultCorners() else defaultCorners()
                activeCorner = -1
            }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CropModeChip(
                selected = mode == CropMode.AUTO,
                label = stringResource(R.string.crop_mode_auto),
                onClick = {
                    if (mode != CropMode.AUTO) {
                        corners = autoCorners ?: corners
                        activeCorner = -1
                    }
                    mode = CropMode.AUTO
                },
            )
            Spacer(Modifier.size(8.dp))
            CropModeChip(
                selected = mode == CropMode.FREE,
                label = stringResource(R.string.crop_mode_free),
                onClick = { mode = CropMode.FREE },
            )
            Spacer(Modifier.size(8.dp))
            CropModeChip(
                selected = mode == CropMode.RECTANGLE,
                label = stringResource(R.string.crop_mode_rect),
                onClick = {
                    if (mode != CropMode.RECTANGLE) corners = asRectangle(corners)
                    mode = CropMode.RECTANGLE
                },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap) {
                        detectDragGestures(
                            onDragStart = { position ->
                                if (mode == CropMode.AUTO) return@detectDragGestures
                                val area = Size(size.width.toFloat(), size.height.toFloat())
                                val rect = fitRect(bitmap.width, bitmap.height, area)
                                val points = corners.map {
                                    Offset(
                                        rect.left + it.x * rect.width,
                                        rect.top + it.y * rect.height,
                                    )
                                }
                                var best = -1
                                var bestDistance = 96f
                                points.forEachIndexed { index, point ->
                                    val d = (point - position).getDistance()
                                    if (d < bestDistance) {
                                        bestDistance = d
                                        best = index
                                    }
                                }
                                activeCorner = best
                            },
                            onDragEnd = { activeCorner = -1 },
                            onDragCancel = { activeCorner = -1 },
                            onDrag = { change, dragAmount ->
                                val index = activeCorner
                                if (index >= 0) {
                                    change.consume()
                                    val area = Size(size.width.toFloat(), size.height.toFloat())
                                    val rect = fitRect(bitmap.width, bitmap.height, area)
                                    if (rect.width > 0f && rect.height > 0f) {
                                        val current = corners[index]
                                        val updated = Offset(
                                            (current.x + dragAmount.x / rect.width).coerceIn(0f, 1f),
                                            (current.y + dragAmount.y / rect.height).coerceIn(0f, 1f),
                                        )
                                        corners = if (mode == CropMode.RECTANGLE) {
                                            rectangularCorners(corners, index, updated)
                                        } else {
                                            corners.toMutableList().also { it[index] = updated }
                                        }
                                    }
                                }
                            },
                        )
                    },
            ) {
                val rect = fitRect(bitmap.width, bitmap.height, size)
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                    dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )

                val points = corners.map {
                    Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height)
                }

                // Oscurece todo lo que queda fuera del recorte.
                val scrim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    moveTo(points[0].x, points[0].y)
                    lineTo(points[1].x, points[1].y)
                    lineTo(points[2].x, points[2].y)
                    lineTo(points[3].x, points[3].y)
                    close()
                }
                drawPath(scrim, Color(0x99000000))

                // Guías de tercios.
                val guide = Color.White.copy(alpha = 0.25f)
                fun lerp(a: Offset, b: Offset, t: Float) = Offset(
                    a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t,
                )
                for (t in listOf(0.33f, 0.66f)) {
                    val top = lerp(points[0], points[1], t)
                    val bottom = lerp(points[3], points[2], t)
                    drawLine(guide, top, bottom, strokeWidth = 1f)
                    val left = lerp(points[0], points[3], t)
                    val right = lerp(points[1], points[2], t)
                    drawLine(guide, left, right, strokeWidth = 1f)
                }

                val outline = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    lineTo(points[1].x, points[1].y)
                    lineTo(points[2].x, points[2].y)
                    lineTo(points[3].x, points[3].y)
                    close()
                }
                drawPath(
                    path = outline,
                    color = Color.White,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )

                points.forEachIndexed { index, point ->
                    if (mode == CropMode.AUTO) {
                        // En automático la zona es fija: no se muestran agarraderas.
                        drawCircle(Color.White.copy(alpha = 0.85f), 5.dp.toPx(), point)
                    } else {
                        val radius = if (index == activeCorner) 14.dp.toPx() else 11.dp.toPx()
                        drawCircle(Color.White, radius, point)
                        drawCircle(Ink, radius, point, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    when (mode) {
                        CropMode.AUTO -> R.string.crop_hint_auto
                        CropMode.RECTANGLE -> R.string.crop_hint_rect
                        CropMode.FREE -> R.string.crop_hint
                    },
                ),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onApply(null, false) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.crop_skip))
                }
                Button(
                    onClick = {
                        val clean = corners.map { Offset(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                        val valid = polygonArea(clean) >= 0.02f
                        onApply(if (valid) clean else null, mode == CropMode.AUTO)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.crop_apply))
                }
            }
        }
    }
}

private fun polygonArea(points: List<Offset>): Float {
    var sum = 0f
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x * b.y - b.x * a.y
    }
    return max(0f, abs(sum) / 2f)
}
