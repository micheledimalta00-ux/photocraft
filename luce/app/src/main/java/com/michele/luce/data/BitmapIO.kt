package com.michele.luce.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.michele.luce.engine.Img
import kotlin.math.max
import kotlin.math.roundToInt

class LoadedImage(val img: Img, val originalWidth: Int, val originalHeight: Int) {
    val originalLongEdge: Int get() = max(originalWidth, originalHeight)
}

object BitmapIO {

    /** Legge l'orientamento EXIF (0 se assente o illeggibile). */
    private fun exifOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Dimensioni originali già orientate secondo EXIF. */
    fun originalSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val o = exifOrientation(context, uri)
        val swap = o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270 ||
            o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE
        return if (swap) opts.outHeight to opts.outWidth else opts.outWidth to opts.outHeight
    }

    /**
     * Decodifica l'immagine con lato lungo al massimo [maxLongEdge], applicando l'orientamento EXIF.
     * Lancia un'eccezione con messaggio leggibile in caso di errore.
     */
    fun load(context: Context, uri: Uri, maxLongEdge: Int): LoadedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("Impossibile aprire il file")
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) throw IllegalStateException("Formato immagine non supportato")

        var sample = 1
        while (max(w0, h0) / (sample * 2) >= maxLongEdge) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        var bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Impossibile decodificare l'immagine")

        // ridimensionamento fine al lato richiesto
        val long = max(bmp.width, bmp.height)
        if (long > maxLongEdge) {
            val s = maxLongEdge.toFloat() / long
            val nw = max(1, (bmp.width * s).roundToInt())
            val nh = max(1, (bmp.height * s).roundToInt())
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        // orientamento EXIF
        val orientation = exifOrientation(context, uri)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val conv = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            bmp = conv
        }

        val img = toImg(bmp)
        bmp.recycle()
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return LoadedImage(img, if (swap) h0 else w0, if (swap) w0 else h0)
    }

    fun toImg(bmp: Bitmap): Img {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Img(bmp.width, bmp.height, px)
    }

    fun toBitmap(img: Img): Bitmap =
        Bitmap.createBitmap(img.pixels, 0, img.width, img.width, img.height, Bitmap.Config.ARGB_8888)

    fun toBitmapWithAlpha(pixels: IntArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(pixels, 0, w, w, h, Bitmap.Config.ARGB_8888)

    /** Ridimensiona un'immagine del motore (per bozze e miniature). */
    fun downscale(img: Img, maxLongEdge: Int): Img {
        val long = max(img.width, img.height)
        if (long <= maxLongEdge) return img
        val bmp = toBitmap(img)
        val s = maxLongEdge.toFloat() / long
        val scaled = Bitmap.createScaledBitmap(bmp, max(1, (img.width * s).roundToInt()), max(1, (img.height * s).roundToInt()), true)
        val out = toImg(scaled)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return out
    }
}
