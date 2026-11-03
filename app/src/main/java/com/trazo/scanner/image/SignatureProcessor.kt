package com.trazo.scanner.image

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Cómo pintar el trazo en el PNG final. */
enum class InkColorMode {
    /** Tinta negra pura: el resultado más limpio para firmar documentos. */
    BLACK,

    /** Conserva el color real del bolígrafo (azul, rojo…). */
    ORIGINAL,
}

data class SignatureSettings(
    /** 0..1 – a partir de qué "oscuridad" se considera tinta. */
    val threshold: Float = 0.35f,
    /** 0..1 – suavidad del borde del trazo. */
    val softness: Float = 0.35f,
    /** 0..1 – 0 = trazo fino, 1 = trazo grueso. */
    val weight: Float = 0.45f,
    val colorMode: InkColorMode = InkColorMode.BLACK,
)

/**
 * Extrae el trazo de una firma sobre papel y devuelve un bitmap con transparencia.
 *
 * Idea: el papel es casi uniformemente claro. Se estima la iluminación de fondo con un
 * desenfoque grande y se mide cuánto se oscurece cada píxel respecto a ese fondo. Así se
 * eliminan sombras y gradientes de luz (lo que un umbral fijo no consigue) y el alfa del
 * resultado queda proporcional a la tinta real.
 */
class SignatureProcessor private constructor(
    val width: Int,
    val height: Int,
    private val pixels: IntArray,
    private val lum: FloatArray,
    private val background: FloatArray,
) {

    /** Opacidad sugerida para el umbral, calculada con el método de Otsu. */
    fun suggestThreshold(): Float {
        val bins = 64
        val hist = IntArray(bins)
        var count = 0
        var i = 0
        val step = max(1, width * height / 400_000)
        while (i < lum.size) {
            val b = background[i]
            val ink = ((b - lum[i]) / max(b, 0.08f)).coerceIn(0f, 1f)
            hist[(ink * (bins - 1)).toInt()]++
            count++
            i += step
        }
        if (count == 0) return 0.35f

        var sum = 0.0
        for (b in 0 until bins) sum += b.toDouble() * hist[b]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var bestBin = -1
        for (b in 0 until bins) {
            wB += hist[b]
            if (wB == 0) continue
            val wF = count - wB
            if (wF == 0) break
            sumB += b.toDouble() * hist[b]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                bestBin = b
            }
        }
        if (bestBin <= 0) return 0.35f
        // Un poco por debajo del corte de Otsu: conserva trazos finos.
        val cut = (bestBin / (bins - 1f)) * 0.82f
        return ((cut - 0.04f) / 0.55f).coerceIn(0.12f, 0.85f)
    }

    /**
     * Genera el PNG con transparencia.
     * @param eraser máscara opcional (255 = visible, 0 = borrado) del mismo tamaño.
     */
    fun render(
        settings: SignatureSettings,
        eraser: ByteArray? = null,
        scale: Int = 1,
    ): Bitmap {
        val step = scale.coerceAtLeast(1)
        val outWidth = (width + step - 1) / step
        val outHeight = (height + step - 1) / step
        val out = IntArray(outWidth * outHeight)

        val threshold = 0.04f + settings.threshold * 0.55f
        val softness = 0.015f + settings.softness * 0.34f
        val low = (threshold - softness * 0.5f).coerceAtLeast(0.002f)
        val high = max(low + 0.004f, threshold + softness * 0.5f)
        val invRange = 1f / (high - low)
        val gamma = 1.65f - settings.weight * 1.25f
        val original = settings.colorMode == InkColorMode.ORIGINAL

        // Tabla de transferencia: borde suave + grosor. Evita pow() por píxel.
        val lutSize = 1024
        val lut = FloatArray(lutSize + 1) { i ->
            val x = i.toFloat() / lutSize
            val smooth = x * x * (3f - 2f * x)
            if (gamma == 1f) smooth else smooth.pow(gamma)
        }

        for (oy in 0 until outHeight) {
            val srcRow = (oy * step).coerceAtMost(height - 1) * width
            val dstRow = oy * outWidth
            for (ox in 0 until outWidth) {
                val i = srcRow + (ox * step).coerceAtMost(width - 1)
                val bg = background[i]
                val ink = (bg - lum[i]) / max(bg, 0.08f)
                if (ink <= low) {
                    out[dstRow + ox] = 0
                    continue
                }
                val normalized = ((ink - low) * invRange).coerceAtMost(1f)
                var a = lut[(normalized * lutSize).toInt().coerceIn(0, lutSize)]
                if (eraser != null) {
                    val m = (eraser[i].toInt() and 0xFF) / 255f
                    if (m < 1f) a *= m
                }
                if (a <= 0.004f) {
                    out[dstRow + ox] = 0
                    continue
                }

                val rgb = if (original) inkColor(pixels[i], a) else 0x000000
                out[dstRow + ox] =
                    (rgb and 0x00FFFFFF) or (((a * 255f).toInt().coerceIn(0, 255)) shl 24)
            }
        }

        return Bitmap.createBitmap(out, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    /**
     * Reconstruye el color real del trazo. Si un píxel del original vale P y se pintó con
     * alfa a sobre blanco, entonces P = C·a + 255·(1-a); despejando C se recupera la tinta
     * sin el velo del papel.
     */
    private fun inkColor(p: Int, a: Float): Int {
        val r = (((p shr 16) and 0xFF) - 255f * (1f - a)) / a
        val g = (((p shr 8) and 0xFF) - 255f * (1f - a)) / a
        val b = ((p and 0xFF) - 255f * (1f - a)) / a
        val rr = (r.toInt().coerceIn(0, 255) * 0.92f).toInt()
        val gg = (g.toInt().coerceIn(0, 255) * 0.92f).toInt()
        val bb = (b.toInt().coerceIn(0, 255) * 0.92f).toInt()
        return (rr shl 16) or (gg shl 8) or bb
    }

    companion object {
        const val DEFAULT_MAX_SIDE = 1600

        fun create(bitmap: Bitmap, maxSide: Int = DEFAULT_MAX_SIDE): SignatureProcessor {
            val scaled = scaleToFit(bitmap, maxSide)
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)

            val lum = FloatArray(w * h) { i ->
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }

            // Radio proporcional al tamaño: estima la iluminación del papel, no el trazo.
            val radius = max(6, min(w, h) / 20)
            var bg = boxBlur(lum, w, h, radius)
            bg = boxBlur(bg, w, h, radius)
            bg = boxBlur(bg, w, h, max(2, radius / 2))

            return SignatureProcessor(w, h, pixels, lum, bg)
        }

        /** Desenfoque de caja separable con sumas acumuladas: O(n) por pasada. */
        private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            if (r <= 0) return src.copyOf()
            val tmp = FloatArray(w * h)
            val dst = FloatArray(w * h)
            val win = (2 * r + 1).toFloat()

            for (y in 0 until h) {
                val row = y * w
                var sum = 0f
                for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[row + x] = sum / win
                    sum += src[row + (x + r + 1).coerceIn(0, w - 1)] -
                        src[row + (x - r).coerceIn(0, w - 1)]
                }
            }

            for (x in 0 until w) {
                var sum = 0f
                for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    dst[y * w + x] = sum / win
                    sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] -
                        tmp[(y - r).coerceIn(0, h - 1) * w + x]
                }
            }
            return dst
        }
    }
}

/** Escala manteniendo proporción, solo si hace falta. */
fun scaleToFit(bitmap: Bitmap, maxSide: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxSide) return bitmap
    val ratio = maxSide.toFloat() / longest
    val w = max(1, (bitmap.width * ratio).toInt())
    val h = max(1, (bitmap.height * ratio).toInt())
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}
