package com.trazo.scanner.image

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Máscara de borrado a la resolución de trabajo del procesador.
 * 255 = píxel visible, 0 = borrado. Se aplica multiplicando el alfa al renderizar.
 */
class EraserMask(val width: Int, val height: Int) {

    val values = ByteArray(width * height) { 255.toByte() }

    private var hasStrokes = false

    fun isEmpty(): Boolean = !hasStrokes

    fun clear() {
        values.fill(255.toByte())
        hasStrokes = false
    }

    fun copy(): ByteArray = values.copyOf()

    fun restore(snapshot: ByteArray) {
        snapshot.copyInto(values)
        hasStrokes = true
    }

    /**
     * Borra la línea entre dos puntos (en coordenadas de la máscara) con un pincel circular.
     * Se estampan discos a lo largo del segmento para que no queden huecos al arrastrar rápido.
     */
    fun eraseLine(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val distance = sqrt(dx * dx + dy * dy)
        val step = (radius * 0.4f).coerceAtLeast(1f)
        val steps = ceil(distance / step).toInt().coerceAtLeast(1)
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            stamp(x0 + dx * t, y0 + dy * t, radius)
        }
    }

    fun stamp(cx: Float, cy: Float, radius: Float) {
        hasStrokes = true
        val r2 = radius * radius
        val left = (cx - radius).toInt().coerceAtLeast(0)
        val right = (cx + radius).toInt().coerceAtMost(width - 1)
        val top = (cy - radius).toInt().coerceAtLeast(0)
        val bottom = (cy + radius).toInt().coerceAtMost(height - 1)
        if (left > right || top > bottom) return

        for (y in top..bottom) {
            val dy = y - cy
            val rowStart = y * width
            for (x in left..right) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) {
                    values[rowStart + x] = 0
                }
            }
        }
    }
}
