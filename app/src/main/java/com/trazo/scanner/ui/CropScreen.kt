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

@Composable
fun CropScreen(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onApply: (List<Offset>?) -> Unit,
) {
    var corners by remember(bitmap) { mutableStateOf(defaultCorners()) }
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
            IconButton(onClick = { corners = defaultCorners() }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Color.White)
            }
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
                                        corners = corners.toMutableList().also { it[index] = updated }
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
                    val radius = if (index == activeCorner) 14.dp.toPx() else 11.dp.toPx()
                    drawCircle(Color.White, radius, point)
                    drawCircle(Ink, radius, point, style = Stroke(width = 2.dp.toPx()))
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
                text = stringResource(R.string.crop_hint),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onApply(null) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.crop_skip))
                }
                Button(
                    onClick = {
                        val clean = corners.map { Offset(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                        onApply(if (polygonArea(clean) < 0.02f) null else clean)
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
