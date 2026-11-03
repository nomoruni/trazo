package com.trazo.scanner.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Rectángulo donde encaja una imagen dentro del área disponible (tipo ContentScale.Fit). */
fun fitRect(imageWidth: Int, imageHeight: Int, area: Size): Rect {
    if (imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val scale = min(area.width / imageWidth, area.height / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val left = (area.width - w) / 2f
    val top = (area.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** Fondo de cuadros para que se vea la transparencia. */
fun DrawScope.drawCheckerboard(rect: Rect, cell: Float = 14.dp.toPx()) {
    val light = Color(0xFFFFFFFF)
    val dark = Color(0xFFE2DED4)
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        drawRect(light, topLeft = rect.topLeft, size = rect.size)
        var y = rect.top
        var row = 0
        while (y < rect.bottom) {
            var x = rect.left
            var col = 0
            while (x < rect.right) {
                if ((row + col) % 2 == 1) {
                    drawRect(
                        color = dark,
                        topLeft = Offset(x, y),
                        size = Size(min(cell, rect.right - x), min(cell, rect.bottom - y)),
                    )
                }
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

/**
 * Muestra una imagen ajustada al espacio disponible, opcionalmente sobre un fondo de cuadros.
 * [onRect] informa del rectángulo ocupado para poder mapear gestos sobre la imagen.
 */
@Composable
fun SignatureSurface(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    checker: Boolean = true,
    onRect: (Rect) -> Unit = {},
) {
    val outline = MaterialTheme.colorScheme.outline
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val image: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }
    val rect = fitRect(
        imageWidth = image?.width ?: 0,
        imageHeight = image?.height ?: 0,
        area = Size(areaSize.width.toFloat(), areaSize.height.toFloat()),
    )

    SideEffect { onRect(rect) }

    Box(modifier.onSizeChanged { areaSize = it }) {
        Canvas(Modifier.fillMaxSize()) {
            if (image == null) {
                drawRect(Color(0x14000000))
                return@Canvas
            }
            if (checker) drawCheckerboard(rect)
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                filterQuality = FilterQuality.Medium,
            )
            drawRect(
                color = outline.copy(alpha = 0.35f),
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
