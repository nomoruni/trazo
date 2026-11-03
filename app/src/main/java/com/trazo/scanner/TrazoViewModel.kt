package com.trazo.scanner

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trazo.scanner.image.CAPTURE_HEIGHT
import com.trazo.scanner.image.CAPTURE_WIDTH
import com.trazo.scanner.image.CaptureGuide
import com.trazo.scanner.image.EraserMask
import com.trazo.scanner.image.ImageStore
import com.trazo.scanner.image.InkColorMode
import com.trazo.scanner.image.PerspectiveWarp
import com.trazo.scanner.image.SaveResult
import com.trazo.scanner.image.SignatureProcessor
import com.trazo.scanner.image.SignatureSettings
import com.trazo.scanner.image.centeredCropRect
import com.trazo.scanner.image.rectToNormalizedCorners
import com.trazo.scanner.image.scaleToFit
import com.trazo.scanner.image.toImageRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, CAMERA, CROP, EDITOR }

/** Acción del historial del borrador, para deshacer y rehacer. */
private sealed interface EraseAction {
    /** Trazo continuo con todos sus segmentos. */
    data class Stroke(val segments: MutableList<EraseSegment> = mutableListOf()) : EraseAction

    /** "Restaurar borrados": vuelve a dejar la firma completa. */
    data object ClearAll : EraseAction
}

private data class EraseSegment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val radius: Float,
)

class TrazoViewModel(app: Application) : AndroidViewModel(app) {

    var screen by mutableStateOf(Screen.HOME)
        private set

    /** Foto original, ya enderezada según EXIF. */
    var sourceBitmap by mutableStateOf<Bitmap?>(null)
        private set

    /** Resultado del recorte; es la imagen sobre la que se calcula la firma. */
    var croppedBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var previewBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var processor by mutableStateOf<SignatureProcessor?>(null)
        private set

    var settings by mutableStateOf(SignatureSettings())
        private set

    var eraserMask by mutableStateOf<EraserMask?>(null)
        private set

    var eraserEnabled by mutableStateOf(false)

    /** Grosor del borrador como fracción del ancho de la imagen. */
    var brushSize by mutableStateOf(0.05f)

    var showOriginal by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)

    /** Región del recorte automático sobre [sourceBitmap], en coordenadas normalizadas. */
    var autoCropCorners by mutableStateOf<List<PointF>?>(null)
        private set

    /** Si al abrir el recorte hay que empezar en modo Automático (foto de cámara). */
    var cropStartsWithAuto by mutableStateOf(false)
        private set

    private val renderRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Historial del borrador como lista de acciones (no copias de la máscara): así se
     * pueden guardar muchos pasos gastando muy poca memoria y se puede rehacer.
     */
    private val eraseHistory = ArrayDeque<EraseAction>()
    private val redoHistory = ArrayDeque<EraseAction>()

    /** Acciones que ya salieron del historial por el límite de pasos; se reaplican al reconstruir. */
    private val eraseBase = mutableListOf<EraseSegment>()
    private var currentStroke: EraseAction.Stroke? = null

    private val app: Application = app

    private companion object {
        /** Pasos de deshacer que se conservan. */
        const val MAX_HISTORY = 300
    }

    init {
        // Un único trabajador: si llegan muchos cambios seguidos solo renderiza el último.
        viewModelScope.launch(Dispatchers.Default) {
            for (unused in renderRequests) {
                val target = processor ?: continue
                val snapshot = settings
                val mask = eraserMask?.takeIf { !it.isEmpty() }?.values
                val bitmap = try {
                    target.render(snapshot, mask, previewScale(target))
                } catch (t: Throwable) {
                    null
                } ?: continue
                withContext(Dispatchers.Main) {
                    if (processor === target) previewBitmap = bitmap
                }
            }
        }
    }

    // ------------------------------------------------------------- navegación

    fun goCamera() {
        screen = Screen.CAMERA
    }

    fun openGalleryResult(uri: Uri) {
        openImage(uri)
    }

    fun back() {
        screen = when (screen) {
            Screen.CAMERA -> Screen.HOME
            Screen.CROP -> if (croppedBitmap != null) Screen.EDITOR else Screen.HOME
            Screen.EDITOR -> Screen.HOME
            Screen.HOME -> Screen.HOME
        }
    }

    fun goHome() {
        screen = Screen.HOME
    }

    fun recrop() {
        if (sourceBitmap != null) screen = Screen.CROP
    }

    // ------------------------------------------------------------- carga

    fun openImage(uri: Uri) {
        busy = true
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageStore.decode(app, uri, maxSide = 2600)
            }
            busy = false
            if (bitmap == null) {
                message = app.getString(R.string.error_load)
                return@launch
            }
            sourceBitmap = bitmap
            croppedBitmap = null
            previewBitmap = null
            processor = null
            // Sin recuadro de cámara: el modo Automático propone un recorte centrado 2400:1412.
            autoCropCorners = rectToNormalizedCorners(
                centeredCropRect(bitmap.width, bitmap.height),
                bitmap.width,
                bitmap.height,
            )
            cropStartsWithAuto = false
            screen = Screen.CROP
        }
    }

    /**
     * Foto hecha con la cámara: se abre el recorte con los tres modos disponibles
     * (Automático, Libre y Rectángulo), empezando en Automático con la zona del
     * recuadro guía. Así se puede aceptar el recorte sugerido o ajustarlo a mano.
     */
    fun openCaptured(uri: Uri, guide: CaptureGuide) {
        busy = true
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageStore.decode(app, uri, maxSide = 2600)
            }
            busy = false
            if (bitmap == null) {
                message = app.getString(R.string.error_load)
                return@launch
            }
            sourceBitmap = bitmap
            croppedBitmap = null
            previewBitmap = null
            processor = null
            autoCropCorners = rectToNormalizedCorners(
                guide.toImageRect(bitmap.width, bitmap.height),
                bitmap.width,
                bitmap.height,
            )
            cropStartsWithAuto = true
            screen = Screen.CROP
        }
    }

    /** Deja el editor listo sobre [cropped]: procesador, máscara de borrado y vista previa. */
    private suspend fun startEditor(cropped: Bitmap) {
        croppedBitmap = cropped
        val newProcessor = withContext(Dispatchers.Default) {
            SignatureProcessor.create(cropped)
        }
        processor = newProcessor
        eraserMask = EraserMask(newProcessor.width, newProcessor.height)
        eraseHistory.clear()
        redoHistory.clear()
        eraseBase.clear()
        currentStroke = null
        canUndo = false
        canRedo = false
        eraserEnabled = false
        settings = SignatureSettings(threshold = newProcessor.suggestThreshold())
        previewBitmap = withContext(Dispatchers.Default) {
            newProcessor.render(settings, scale = previewScale(newProcessor))
        }
    }

    /**
     * @param corners esquinas en coordenadas normalizadas (0..1) de [sourceBitmap],
     * en orden sup-izq, sup-der, inf-der, inf-izq. `null` = sin recorte.
     * @param auto `true` cuando viene del modo Automático: además de recortar, se escala
     * al tamaño fijo de la firma (2400 × 1412).
     */
    fun applyCrop(corners: List<PointF>?, auto: Boolean = false) {
        val src = sourceBitmap ?: return
        busy = true
        viewModelScope.launch {
            val cropped = withContext(Dispatchers.Default) {
                if (corners == null) {
                    scaleToFit(src, 2400)
                } else {
                    val points = corners.map { PointF(it.x * src.width, it.y * src.height) }
                    val warped = PerspectiveWarp.warp(src, points, 2400)
                    if (auto) {
                        if (warped.width != CAPTURE_WIDTH || warped.height != CAPTURE_HEIGHT) {
                            val scaled = Bitmap.createScaledBitmap(
                                warped,
                                CAPTURE_WIDTH,
                                CAPTURE_HEIGHT,
                                true,
                            )
                            if (scaled !== warped) warped.recycle()
                            scaled
                        } else {
                            warped
                        }
                    } else {
                        warped
                    }
                }
            }
            startEditor(cropped)
            cropStartsWithAuto = false
            busy = false
            screen = Screen.EDITOR
        }
    }

    // ------------------------------------------------------------- ajustes

    fun updateSettings(transform: (SignatureSettings) -> SignatureSettings) {
        settings = transform(settings)
        requestRender()
    }

    fun setThreshold(value: Float) = updateSettings { it.copy(threshold = value) }

    fun setSoftness(value: Float) = updateSettings { it.copy(softness = value) }

    fun setWeight(value: Float) = updateSettings { it.copy(weight = value) }

    fun setColorMode(mode: InkColorMode) = updateSettings { it.copy(colorMode = mode) }

    fun resetSettings() {
        val suggested = processor?.suggestThreshold() ?: 0.35f
        settings = SignatureSettings(threshold = suggested)
        requestRender()
    }

    private fun previewScale(processor: SignatureProcessor): Int =
        if (processor.width > 1100) 2 else 1

    private fun requestRender() {
        renderRequests.trySend(Unit)
    }

    // ------------------------------------------------------------- borrador

    fun beginErase() {
        if (eraserMask == null) return
        val stroke = EraseAction.Stroke()
        eraseHistory.addLast(stroke)
        trimHistory()
        redoHistory.clear()
        currentStroke = stroke
        updateHistoryFlags()
    }

    /** Coordenadas en píxeles de la máscara. */
    fun eraseSegment(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        val mask = eraserMask ?: return
        mask.eraseLine(x0, y0, x1, y1, radius)
        currentStroke?.segments?.add(EraseSegment(x0, y0, x1, y1, radius))
        requestRender()
    }

    fun undoErase() {
        val mask = eraserMask ?: return
        val action = eraseHistory.removeLastOrNull() ?: return
        redoHistory.addLast(action)
        currentStroke = null
        rebuildMask(mask)
        updateHistoryFlags()
        requestRender()
    }

    fun redoErase() {
        val mask = eraserMask ?: return
        val action = redoHistory.removeLastOrNull() ?: return
        eraseHistory.addLast(action)
        currentStroke = null
        applyAction(mask, action)
        updateHistoryFlags()
        requestRender()
    }

    fun restoreErased() {
        val mask = eraserMask ?: return
        eraseHistory.addLast(EraseAction.ClearAll)
        trimHistory()
        redoHistory.clear()
        currentStroke = null
        mask.clear()
        updateHistoryFlags()
        requestRender()
    }

    /** Si el historial supera el límite, lo más antiguo pasa a [eraseBase]. */
    private fun trimHistory() {
        while (eraseHistory.size > MAX_HISTORY) {
            when (val removed = eraseHistory.removeFirst()) {
                is EraseAction.Stroke -> eraseBase.addAll(removed.segments)
                EraseAction.ClearAll -> eraseBase.clear()
            }
        }
    }

    /** Reconstruye la máscara desde cero aplicando [eraseBase] y el historial actual. */
    private fun rebuildMask(mask: EraserMask) {
        mask.clear()
        for (segment in eraseBase) {
            mask.eraseLine(segment.x0, segment.y0, segment.x1, segment.y1, segment.radius)
        }
        for (action in eraseHistory) applyAction(mask, action)
    }

    private fun applyAction(mask: EraserMask, action: EraseAction) {
        when (action) {
            is EraseAction.Stroke -> for (segment in action.segments) {
                mask.eraseLine(segment.x0, segment.y0, segment.x1, segment.y1, segment.radius)
            }
            EraseAction.ClearAll -> mask.clear()
        }
    }

    private fun updateHistoryFlags() {
        canUndo = eraseHistory.isNotEmpty()
        canRedo = redoHistory.isNotEmpty()
    }

    // ------------------------------------------------------------- exportar

    /** Nombre propuesto en el diálogo de guardado, p. ej. `firma_20261103_1530`. */
    fun defaultExportName(): String = "firma_${ImageStore.timestamp()}"

    fun savePng(name: String) = export(asPdf = false, rawName = name)

    fun savePdf(name: String) = export(asPdf = true, rawName = name)

    private fun export(asPdf: Boolean, rawName: String) {
        val target = processor ?: return
        busy = true
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                target.render(settings, eraserMask?.takeIf { !it.isEmpty() }?.values)
            }
            val name = ImageStore.sanitizeName(rawName, defaultExportName())
            val result = withContext(Dispatchers.IO) {
                if (asPdf) ImageStore.savePdf(app, bitmap, name) else ImageStore.savePng(app, bitmap, name)
            }
            busy = false
            message = when (result) {
                is SaveResult.Success -> app.getString(
                    if (asPdf) R.string.saved_pdf else R.string.saved_png,
                    name,
                )
                is SaveResult.PermissionRequired -> app.getString(R.string.saved_error)
                is SaveResult.Failure -> app.getString(R.string.saved_error)
            }
        }
    }

    fun share() {
        val target = processor ?: return
        busy = true
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                target.render(settings, eraserMask?.takeIf { !it.isEmpty() }?.values)
            }
            val uri = withContext(Dispatchers.IO) {
                ImageStore.sharePng(app, bitmap, "firma_${ImageStore.timestamp()}")
            }
            busy = false
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, app.getString(R.string.share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(chooser)
        }
    }

    fun consumeMessage() {
        message = null
    }
}
