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
import com.trazo.scanner.image.EraserMask
import com.trazo.scanner.image.ImageStore
import com.trazo.scanner.image.InkColorMode
import com.trazo.scanner.image.PerspectiveWarp
import com.trazo.scanner.image.SaveResult
import com.trazo.scanner.image.SignatureProcessor
import com.trazo.scanner.image.SignatureSettings
import com.trazo.scanner.image.scaleToFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, CAMERA, CROP, EDITOR }

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

    var message by mutableStateOf<String?>(null)

    private val renderRequests = Channel<Unit>(Channel.CONFLATED)
    private val undoStack = ArrayDeque<ByteArray>()
    private val app: Application = app

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
            screen = Screen.CROP
        }
    }

    /**
     * @param corners esquinas en coordenadas normalizadas (0..1) de [sourceBitmap],
     * en orden sup-izq, sup-der, inf-der, inf-izq. `null` = sin recorte.
     */
    fun applyCrop(corners: List<PointF>?) {
        val src = sourceBitmap ?: return
        busy = true
        viewModelScope.launch {
            val cropped = withContext(Dispatchers.Default) {
                if (corners == null) {
                    scaleToFit(src, 2400)
                } else {
                    val points = corners.map { PointF(it.x * src.width, it.y * src.height) }
                    PerspectiveWarp.warp(src, points, 2400)
                }
            }
            croppedBitmap = cropped
            val newProcessor = withContext(Dispatchers.Default) {
                SignatureProcessor.create(cropped)
            }
            processor = newProcessor
            eraserMask = EraserMask(newProcessor.width, newProcessor.height)
            undoStack.clear()
            canUndo = false
            eraserEnabled = false
            settings = SignatureSettings(threshold = newProcessor.suggestThreshold())
            previewBitmap = withContext(Dispatchers.Default) {
                newProcessor.render(settings, scale = previewScale(newProcessor))
            }
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
        val mask = eraserMask ?: return
        if (undoStack.size >= 8) undoStack.removeFirst()
        undoStack.addLast(mask.copy())
        canUndo = true
    }

    /** Coordenadas en píxeles de la máscara. */
    fun eraseSegment(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        val mask = eraserMask ?: return
        mask.eraseLine(x0, y0, x1, y1, radius)
        requestRender()
    }

    fun undoErase() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        eraserMask?.restore(snapshot)
        canUndo = undoStack.isNotEmpty()
        requestRender()
    }

    fun restoreErased() {
        val mask = eraserMask ?: return
        if (undoStack.size >= 8) undoStack.removeFirst()
        undoStack.addLast(mask.copy())
        canUndo = true
        mask.clear()
        requestRender()
    }

    // ------------------------------------------------------------- exportar

    fun savePng() = export(asPdf = false)

    fun savePdf() = export(asPdf = true)

    private fun export(asPdf: Boolean) {
        val target = processor ?: return
        busy = true
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                target.render(settings, eraserMask?.takeIf { !it.isEmpty() }?.values)
            }
            val name = "firma_${ImageStore.timestamp()}"
            val result = withContext(Dispatchers.IO) {
                if (asPdf) ImageStore.savePdf(app, bitmap, name) else ImageStore.savePng(app, bitmap, name)
            }
            busy = false
            message = when (result) {
                is SaveResult.Success -> app.getString(if (asPdf) R.string.saved_pdf else R.string.saved_png)
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
