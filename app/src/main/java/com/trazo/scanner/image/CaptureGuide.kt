package com.trazo.scanner.image

import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/** Tamaño final del recorte automático de la cámara (2400 × 1412 px). */
const val CAPTURE_WIDTH = 2400
const val CAPTURE_HEIGHT = 1412

/**
 * Zona del visor que se recortará automáticamente después de hacer la foto.
 *
 * [left], [top], [right] y [bottom] están normalizados (0..1) respecto al visor, y
 * [viewAspect] es el ancho dividido entre el alto del visor. Se guarda la proporción
 * porque el recorte se calcula luego sobre la imagen capturada, que puede tener otra
 * resolución.
 */
data class CaptureGuide(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val viewAspect: Float,
)

/**
 * Recuadro centrado con la proporción 2400:1412 ocupando el 90 % del ancho del visor
 * (y como máximo el 66 % del alto, para que quepa en horizontal).
 */
fun captureGuideFor(viewWidth: Float, viewHeight: Float): CaptureGuide {
    if (viewWidth <= 0f || viewHeight <= 0f) {
        return CaptureGuide(0f, 0f, 1f, 1f, 1f)
    }
    val ratio = CAPTURE_WIDTH.toFloat() / CAPTURE_HEIGHT
    var width = viewWidth * 0.90f
    var height = width / ratio
    val maxHeight = viewHeight * 0.66f
    if (height > maxHeight) {
        height = maxHeight
        width = height * ratio
    }
    val left = (viewWidth - width) / 2f
    val top = (viewHeight - height) / 2f
    return CaptureGuide(
        left = left / viewWidth,
        top = top / viewHeight,
        right = (left + width) / viewWidth,
        bottom = (top + height) / viewHeight,
        viewAspect = viewWidth / viewHeight,
    )
}

/**
 * Convierte el recuadro del visor en píxeles de la imagen capturada.
 *
 * El preview usa `FILL_CENTER`: la imagen se escala hasta llenar el visor y se recorta
 * por el centro. Por eso, si la imagen y el visor tienen proporciones distintas, hay que
 * descontar la franja que queda fuera de la pantalla antes de aplicar el recuadro.
 */
fun CaptureGuide.toImageRect(imageWidth: Int, imageHeight: Int): Rect {
    if (imageWidth <= 0 || imageHeight <= 0) return Rect()

    val imageAspect = imageWidth.toFloat() / imageHeight
    val safeViewAspect = viewAspect.coerceAtLeast(0.0001f)
    val visibleWidth = min(1f, safeViewAspect / imageAspect)
    val visibleHeight = min(1f, imageAspect / safeViewAspect)
    val offsetX = (1f - visibleWidth) / 2f
    val offsetY = (1f - visibleHeight) / 2f

    val x0 = ((offsetX + left * visibleWidth) * imageWidth).roundToInt()
    val y0 = ((offsetY + top * visibleHeight) * imageHeight).roundToInt()
    val x1 = ((offsetX + right * visibleWidth) * imageWidth).roundToInt()
    val y1 = ((offsetY + bottom * visibleHeight) * imageHeight).roundToInt()

    val safeLeft = x0.coerceIn(0, imageWidth - 1)
    val safeTop = y0.coerceIn(0, imageHeight - 1)
    val safeRight = x1.coerceIn(safeLeft + 1, imageWidth)
    val safeBottom = y1.coerceIn(safeTop + 1, imageHeight)
    return Rect(safeLeft, safeTop, safeRight, safeBottom)
}

/**
 * Zona automática para fotos que no vienen de la cámara: el rectángulo centrado más
 * grande posible con la proporción de la firma (2400:1412).
 */
fun centeredCropRect(imageWidth: Int, imageHeight: Int): Rect {
    if (imageWidth <= 0 || imageHeight <= 0) return Rect()
    val target = CAPTURE_WIDTH.toFloat() / CAPTURE_HEIGHT
    val imageAspect = imageWidth.toFloat() / imageHeight
    return if (imageAspect >= target) {
        val width = (imageHeight * target).roundToInt().coerceIn(1, imageWidth)
        val left = (imageWidth - width) / 2
        Rect(left, 0, left + width, imageHeight)
    } else {
        val height = (imageWidth / target).roundToInt().coerceIn(1, imageHeight)
        val top = (imageHeight - height) / 2
        Rect(0, top, imageWidth, top + height)
    }
}

/** Esquinas normalizadas (0..1) de [rect]: sup-izq, sup-der, inf-der, inf-izq. */
fun rectToNormalizedCorners(rect: Rect, imageWidth: Int, imageHeight: Int): List<PointF> {
    if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
    val w = imageWidth.toFloat()
    val h = imageHeight.toFloat()
    return listOf(
        PointF(rect.left / w, rect.top / h),
        PointF(rect.right / w, rect.top / h),
        PointF(rect.right / w, rect.bottom / h),
        PointF(rect.left / w, rect.bottom / h),
    )
}
