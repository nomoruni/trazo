package com.trazo.scanner.image

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Corrección de perspectiva: convierte el cuadrilátero marcado por el usuario en una imagen
 * rectangular, como si la foto se hubiera tomado de frente.
 */
object PerspectiveWarp {

    /** Puntos en orden: sup-izq, sup-der, inf-der, inf-izq. */
    fun warp(src: Bitmap, quad: List<PointF>, maxSide: Int = 2400): Bitmap {
        require(quad.size == 4) { "Se esperaban 4 esquinas" }
        val p0 = quad[0]
        val p1 = quad[1]
        val p2 = quad[2]
        val p3 = quad[3]

        val widthTop = distance(p0, p1)
        val widthBottom = distance(p3, p2)
        val heightLeft = distance(p0, p3)
        val heightRight = distance(p1, p2)

        var outW = ((widthTop + widthBottom) / 2f).roundToInt().coerceAtLeast(16)
        var outH = ((heightLeft + heightRight) / 2f).roundToInt().coerceAtLeast(16)
        val limit = maxSide.toFloat() / max(outW, outH)
        if (limit < 1f) {
            outW = (outW * limit).roundToInt().coerceAtLeast(16)
            outH = (outH * limit).roundToInt().coerceAtLeast(16)
        }

        // Matriz que lleva el cuadrado unidad al cuadrilátero (Heckbert).
        val dx1 = p1.x - p2.x
        val dx2 = p3.x - p2.x
        val dy1 = p1.y - p2.y
        val dy2 = p3.y - p2.y
        val sx = p0.x - p1.x + p2.x - p3.x
        val sy = p0.y - p1.y + p2.y - p3.y

        var g = 0f
        var h = 0f
        if (sx != 0f || sy != 0f) {
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) > 1e-6f) {
                g = (sx * dy2 - dx2 * sy) / den
                h = (dx1 * sy - sx * dy1) / den
            }
        }

        val a = p1.x - p0.x + g * p1.x
        val b = p3.x - p0.x + h * p3.x
        val c = p0.x
        val d = p1.y - p0.y + g * p1.y
        val e = p3.y - p0.y + h * p3.y
        val f = p0.y

        val sw = src.width
        val sh = src.height
        val srcPixels = IntArray(sw * sh)
        src.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)

        val out = IntArray(outW * outH)
        val maxX = sw - 1
        val maxY = sh - 1

        for (y in 0 until outH) {
            val v = (y + 0.5f) / outH
            for (x in 0 until outW) {
                val u = (x + 0.5f) / outW
                val w = g * u + h * v + 1f
                if (w == 0f) {
                    out[y * outW + x] = 0
                    continue
                }
                val sxf = (a * u + b * v + c) / w
                val syf = (d * u + e * v + f) / w
                out[y * outW + x] = sampleBilinear(srcPixels, sw, sh, sxf, syf, maxX, maxY)
            }
        }

        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun sampleBilinear(
        pixels: IntArray,
        w: Int,
        h: Int,
        x: Float,
        y: Float,
        maxX: Int,
        maxY: Int,
    ): Int {
        if (x < 0f || y < 0f || x > maxX.toFloat() || y > maxY.toFloat()) return 0
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, maxX)
        val y1 = min(y0 + 1, maxY)
        val fx = x - x0
        val fy = y - y0

        val p00 = pixels[y0 * w + x0]
        val p10 = pixels[y0 * w + x1]
        val p01 = pixels[y1 * w + x0]
        val p11 = pixels[y1 * w + x1]

        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy

        val r = ((p00 shr 16 and 0xFF) * w00 + (p10 shr 16 and 0xFF) * w10 +
            (p01 shr 16 and 0xFF) * w01 + (p11 shr 16 and 0xFF) * w11).toInt()
        val gg = ((p00 shr 8 and 0xFF) * w00 + (p10 shr 8 and 0xFF) * w10 +
            (p01 shr 8 and 0xFF) * w01 + (p11 shr 8 and 0xFF) * w11).toInt()
        val b = ((p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 +
            (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11).toInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (gg.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun distance(p: PointF, q: PointF): Float {
        val dx = p.x - q.x
        val dy = p.y - q.y
        return sqrt(dx * dx + dy * dy)
    }
}
