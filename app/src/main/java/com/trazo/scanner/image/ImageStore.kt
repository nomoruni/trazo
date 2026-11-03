package com.trazo.scanner.image

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface SaveResult {
    data class Success(val uri: Uri?, val path: String) : SaveResult
    data object PermissionRequired : SaveResult
    data class Failure(val error: Throwable) : SaveResult
}

/** Lectura y escritura de imágenes: cámara, galería, PNG transparente, PDF y compartir. */
object ImageStore {

    private const val ALBUM = "Trazo"

    // SimpleDateFormat no es thread-safe: se crea uno por llamada.
    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Limpia el nombre escrito por el usuario para usarlo como nombre de archivo. */
    fun sanitizeName(raw: String, fallback: String): String {
        var base = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ')
            .take(80)
        val lower = base.lowercase()
        if (lower.endsWith(".png") || lower.endsWith(".pdf")) {
            base = base.dropLast(4).trim('.', ' ')
        }
        return base.ifBlank { fallback }
    }

    // ---------------------------------------------------------------- lectura

    fun decode(context: Context, uri: Uri, maxSide: Int = 2600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Ojo: en modo inJustDecodeBounds decodeStream devuelve null siempre (solo rellena
        // `bounds`), así que no se puede usar el resultado como comprobación de éxito.
        val boundsStream = openStream(context, uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxSide * 2) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return applyExifRotation(context, uri, decoded)
    }

    private fun openStream(context: Context, uri: Uri): InputStream? = try {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            FileInputStream(File(path))
        } else {
            context.contentResolver.openInputStream(uri)
        }
    } catch (t: Throwable) {
        null
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            val stream = openStream(context, uri) ?: return bitmap
            stream.use {
                when (ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        } catch (t: Throwable) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    // ---------------------------------------------------------------- PNG

    fun needsLegacyStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED

    fun savePng(context: Context, bitmap: Bitmap, name: String): SaveResult = runCatching {
        savePngInternal(context, bitmap, name)
    }.getOrElse { SaveResult.Failure(it) }

    private fun savePngInternal(context: Context, bitmap: Bitmap, name: String): SaveResult {
        return if (needsLegacyStoragePermission(context)) {
            SaveResult.PermissionRequired
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return SaveResult.Failure(IllegalStateException("MediaStore rechazó la imagen"))
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SaveResult.Success(uri, "Pictures/$ALBUM/$name.png")
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM,
            ).apply { mkdirs() }
            val file = File(dir, "$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            SaveResult.Success(Uri.fromFile(file), file.absolutePath)
        }
    }

    // ---------------------------------------------------------------- PDF

    fun savePdf(context: Context, bitmap: Bitmap, name: String): SaveResult = runCatching {
        savePdfInternal(context, bitmap, name)
    }.getOrElse { SaveResult.Failure(it) }

    private fun savePdfInternal(context: Context, bitmap: Bitmap, name: String): SaveResult {
        return if (needsLegacyStoragePermission(context)) {
            SaveResult.PermissionRequired
        } else {
            val document = PdfDocument()
            val pageWidth = 595 // A4 a 72 ppp
            val pageHeight = 842
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            val canvas: Canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val margin = 56f
            val availableW = pageWidth - margin * 2
            val availableH = pageHeight - margin * 2
            val scale = minOf(availableW / bitmap.width, availableH / bitmap.height, 1.6f)
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale
            val left = (pageWidth - drawW) / 2f
            val top = (pageHeight - drawH) / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(left, top, left + drawW, top + drawH),
                paint,
            )
            document.finishPage(page)

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$name.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return SaveResult.Failure(IllegalStateException("MediaStore rechazó el PDF"))
                resolver.openOutputStream(uri)?.use { document.writeTo(it) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SaveResult.Success(uri, "Download/$ALBUM/$name.pdf")
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    ALBUM,
                ).apply { mkdirs() }
                val file = File(dir, "$name.pdf")
                file.outputStream().use { document.writeTo(it) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                SaveResult.Success(Uri.fromFile(file), file.absolutePath)
            }
            document.close()
            result
        }
    }

    // ---------------------------------------------------------------- compartir

    fun sharePng(context: Context, bitmap: Bitmap, name: String): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
