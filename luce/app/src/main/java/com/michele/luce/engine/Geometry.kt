package com.michele.luce.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Immagine ARGB_8888 in memoria. */
class Img(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Dimensioni non valide" }
    }
}

data class AspectRatio(val label: String, val ratio: Float?) // ratio = larghezza/altezza; null = libero; <0 = originale

object Geometry {

    val ASPECTS = listOf(
        AspectRatio("Libero", null),
        AspectRatio("Originale", -1f),
        AspectRatio("1:1", 1f),
        AspectRatio("4:5", 4f / 5f),
        AspectRatio("5:4", 5f / 4f),
        AspectRatio("3:2", 3f / 2f),
        AspectRatio("2:3", 2f / 3f),
        AspectRatio("4:3", 4f / 3f),
        AspectRatio("3:4", 3f / 4f),
        AspectRatio("16:9", 16f / 9f),
        AspectRatio("9:16", 9f / 16f)
    )

    fun orientedSize(w: Int, h: Int, rotate90: Int): Pair<Int, Int> =
        if ((rotate90 and 1) == 1) h to w else w to h

    /** Fattore di ingrandimento perché l'immagine raddrizzata copra tutta la tela. */
    fun coverScale(cw: Int, ch: Int, angleDeg: Float): Float {
        if (angleDeg == 0f) return 1f
        val a = abs(angleDeg) * PI.toFloat() / 180f
        val c = cos(a)
        val s = sin(a)
        val sx = (cw * c + ch * s) / cw
        val sy = (cw * s + ch * c) / ch
        return max(sx, sy)
    }

    fun isIdentity(p: EditParams): Boolean =
        p.rotate90 % 4 == 0 && !p.flipH && !p.flipV && p.straighten == 0f &&
            p.lensDistortion == 0f && p.chromaticAberration == 0f && !p.hasCrop()

    /** Dimensioni dell'uscita (dopo il ritaglio) per una sorgente w×h. */
    fun outputSize(w: Int, h: Int, p: EditParams, ignoreCrop: Boolean = false): Pair<Int, Int> {
        val (cw, ch) = orientedSize(w, h, p.rotate90)
        if (ignoreCrop) return cw to ch
        val ow = max(1, ((p.cropRight - p.cropLeft) * cw).roundToInt())
        val oh = max(1, ((p.cropBottom - p.cropTop) * ch).roundToInt())
        return ow to oh
    }

    /**
     * Applica orientamento, raddrizzamento, distorsione dell'obiettivo, aberrazione cromatica e ritaglio.
     */
    fun apply(src: Img, p: EditParams, ignoreCrop: Boolean = false, cancel: () -> Boolean = { false }): Img {
        val effective = if (ignoreCrop) p.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f) else p
        if (isIdentity(effective)) return src
        val sw = src.width
        val sh = src.height
        val rot = ((p.rotate90 % 4) + 4) % 4
        val (cw, ch) = orientedSize(sw, sh, rot)
        val (ow, oh) = outputSize(sw, sh, effective)
        val out = IntArray(ow * oh)

        val cl = effective.cropLeft
        val ct = effective.cropTop
        val cr = effective.cropRight
        val cb = effective.cropBottom

        val angle = p.straighten * PI.toFloat() / 180f
        val scale = coverScale(cw, ch, p.straighten)
        val ca = cos(-angle)
        val sa = sin(-angle)
        val halfW = cw / 2f
        val halfH = ch / 2f
        val halfDiag = sqrt(halfW * halfW + halfH * halfH)
        val k = -p.lensDistortion / 100f * 0.2f
        val kNorm = 1f + max(k, 0f)
        val caAmt = p.chromaticAberration / 100f * 0.004f
        val hasCa = caAmt != 0f
        val hasDist = k != 0f
        val px = src.pixels

        Parallel.forRows(oh, cancel) { y0, y1 ->
            for (oy in y0 until y1) {
                val v = ct + (oy + 0.5f) / oh * (cb - ct)
                var idx = oy * ow
                for (ox in 0 until ow) {
                    val u = cl + (ox + 0.5f) / ow * (cr - cl)
                    // coordinate della tela in pixel, rispetto al centro
                    val dx = u * cw - halfW
                    val dy = v * ch - halfH
                    // annulla il raddrizzamento
                    var ix = (dx * ca - dy * sa) / scale
                    var iy = (dx * sa + dy * ca) / scale
                    // distorsione dell'obiettivo
                    if (hasDist) {
                        val r2 = (ix * ix + iy * iy) / (halfDiag * halfDiag)
                        val f = (1f + k * r2) / kNorm
                        ix *= f; iy *= f
                    }
                    if (!hasCa) {
                        out[idx] = sampleOriented(px, sw, sh, rot, p.flipH, p.flipV, cw, ch, ix + halfW, iy + halfH)
                    } else {
                        val fr = 1f + caAmt
                        val fb = 1f - caAmt
                        val cR = sampleOriented(px, sw, sh, rot, p.flipH, p.flipV, cw, ch, ix * fr + halfW, iy * fr + halfH)
                        val cG = sampleOriented(px, sw, sh, rot, p.flipH, p.flipV, cw, ch, ix + halfW, iy + halfH)
                        val cB = sampleOriented(px, sw, sh, rot, p.flipH, p.flipV, cw, ch, ix * fb + halfW, iy * fb + halfH)
                        out[idx] = (0xFF shl 24) or (cR and 0xFF0000) or (cG and 0x00FF00) or (cB and 0x0000FF)
                    }
                    idx++
                }
            }
        }
        return Img(ow, oh, out)
    }

    /** Campiona (bilineare) l'immagine orientata nel punto (x, y) in pixel dell'immagine orientata. */
    private fun sampleOriented(
        px: IntArray, sw: Int, sh: Int, rot: Int, flipH: Boolean, flipV: Boolean,
        cw: Int, ch: Int, x: Float, y: Float
    ): Int {
        var u = x / cw
        var v = y / ch
        if (flipH) u = 1f - u
        if (flipV) v = 1f - v
        val su: Float
        val sv: Float
        when (rot) {
            1 -> { su = v; sv = 1f - u }
            2 -> { su = 1f - u; sv = 1f - v }
            3 -> { su = 1f - v; sv = u }
            else -> { su = u; sv = v }
        }
        return bilinear(px, sw, sh, su * sw - 0.5f, sv * sh - 0.5f)
    }

    fun bilinear(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x = min(max(fx, 0f), (w - 1).toFloat())
        val y = min(max(fy, 0f), (h - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = x - x0
        val ty = y - y0
        val c00 = px[y0 * w + x0]
        val c10 = px[y0 * w + x1]
        val c01 = px[y1 * w + x0]
        val c11 = px[y1 * w + x1]
        if (tx < 1e-3f && ty < 1e-3f) return c00 or (0xFF shl 24)
        val w00 = (1 - tx) * (1 - ty)
        val w10 = tx * (1 - ty)
        val w01 = (1 - tx) * ty
        val w11 = tx * ty
        val r = ((c00 shr 16 and 0xFF) * w00 + (c10 shr 16 and 0xFF) * w10 + (c01 shr 16 and 0xFF) * w01 + (c11 shr 16 and 0xFF) * w11 + 0.5f).toInt()
        val g = ((c00 shr 8 and 0xFF) * w00 + (c10 shr 8 and 0xFF) * w10 + (c01 shr 8 and 0xFF) * w01 + (c11 shr 8 and 0xFF) * w11 + 0.5f).toInt()
        val b = ((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 + (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11 + 0.5f).toInt()
        return (0xFF shl 24) or (min(r, 255) shl 16) or (min(g, 255) shl 8) or min(b, 255)
    }

    /**
     * Applica un rapporto d'aspetto al ritaglio corrente mantenendone il centro.
     * cw, ch = dimensioni della tela orientata.
     */
    fun fitAspect(p: EditParams, aspect: AspectRatio, cw: Int, ch: Int): EditParams {
        val ratio = when {
            aspect.ratio == null -> return p.copy(aspect = aspect.label)
            aspect.ratio < 0f -> cw.toFloat() / ch
            else -> aspect.ratio
        }
        val curW = (p.cropRight - p.cropLeft) * cw
        val curH = (p.cropBottom - p.cropTop) * ch
        val centerX = (p.cropLeft + p.cropRight) / 2f * cw
        val centerY = (p.cropTop + p.cropBottom) / 2f * ch
        var nw = curW
        var nh = nw / ratio
        if (nh > curH) { nh = curH; nw = nh * ratio }
        // se il nuovo riquadro è troppo piccolo, parte dalla tela intera
        if (nw < cw * 0.2f && nh < ch * 0.2f) {
            nw = cw.toFloat(); nh = nw / ratio
            if (nh > ch) { nh = ch.toFloat(); nw = nh * ratio }
        }
        var l = centerX - nw / 2f
        var t = centerY - nh / 2f
        l = l.coerceIn(0f, cw - nw)
        t = t.coerceIn(0f, ch - nh)
        return p.copy(
            cropLeft = l / cw, cropTop = t / ch,
            cropRight = (l + nw) / cw, cropBottom = (t + nh) / ch,
            aspect = aspect.label
        )
    }

    fun aspectValue(label: String, cw: Int, ch: Int): Float? {
        val a = ASPECTS.firstOrNull { it.label == label } ?: return null
        return when {
            a.ratio == null -> null
            a.ratio < 0f -> cw.toFloat() / ch
            else -> a.ratio
        }
    }
}
