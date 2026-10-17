package com.michele.luce.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.michele.luce.engine.AiRaster
import com.michele.luce.engine.EditParams
import com.michele.luce.engine.Pipeline
import com.michele.luce.engine.RenderOptions
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class ExportFormat(val label: String, val mime: String, val ext: String) {
    JPEG("JPG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png")
}

data class ExportSettings(
    val longEdge: Int = 4096,
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 92
)

object Exporter {
    /** Lato lungo massimo esportabile in sicurezza sulla memoria di un telefono. */
    const val MAX_EXPORT = 4096

    fun render(context: Context, source: Uri, params: EditParams, settings: ExportSettings, rasters: Map<String, AiRaster>, cancel: () -> Boolean): Bitmap {
        val orig = BitmapIO.originalSize(context, source)
        val origLong = orig?.let { max(it.first, it.second) } ?: MAX_EXPORT
        val target = min(min(settings.longEdge, MAX_EXPORT), origLong)
        val loaded = BitmapIO.load(context, source, target)
        val scale = max(loaded.img.width, loaded.img.height).toFloat() / max(1, loaded.originalLongEdge)
        val out = Pipeline.render(loaded.img, params, RenderOptions(resolutionScale = scale, cancel = cancel, aiRasters = rasters))
        return BitmapIO.toBitmap(out)
    }

    private fun fileName(baseName: String, format: ExportFormat): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = baseName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "Luce" }
        return "${base}_luce_$stamp.${format.ext}"
    }

    private fun compress(bmp: Bitmap, settings: ExportSettings, os: OutputStream) {
        val fmt = if (settings.format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (!bmp.compress(fmt, settings.quality.coerceIn(10, 100), os)) throw IllegalStateException("Compressione non riuscita")
    }

    /** Salva nella galleria (Immagini/Luce). Restituisce l'Uri creato. */
    fun saveToGallery(context: Context, bmp: Bitmap, baseName: String, settings: ExportSettings): Uri {
        val name = fileName(baseName, settings.format)
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, settings.format.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Luce")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Impossibile creare il file nella galleria")
            try {
                resolver.openOutputStream(uri)?.use { compress(bmp, settings, it) }
                    ?: throw IllegalStateException("Impossibile scrivere il file")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Luce")
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Impossibile creare la cartella Luce")
            val f = File(dir, name)
            FileOutputStream(f).use { compress(bmp, settings, it) }
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(settings.format.mime), null)
            Uri.fromFile(f)
        }
    }

    /** Crea un file temporaneo condivisibile e restituisce l'Intent di condivisione. */
    fun shareIntent(context: Context, bmp: Bitmap, baseName: String, settings: ExportSettings): Intent {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
        val f = File(dir, fileName(baseName, settings.format))
        FileOutputStream(f).use { compress(bmp, settings, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = settings.format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Condividi foto").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun cameraFile(context: Context): Pair<File, Uri> {
        val dir = (context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, "Pictures")).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "LUCE_$stamp.jpg")
        return f to FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }
}
