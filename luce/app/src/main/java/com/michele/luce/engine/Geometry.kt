package com.michele.luce.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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

/** Raster a un canale (0..255) nello spazio della sorgente, usato dalle selezioni IA. */
class AiRaster(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(width > 0 && height > 0 && data.size >= width * height) { "Raster non valido" }
    }

    /** Campionamento bilineare in coordinate normalizzate della sorgente, risultato 0..1. */
    fun sample(u: Float, v: Float): Float {
        val fx = min(max(u * width - 0.5f, 0f), (width - 1).toFloat())
        val fy = min(max(v * height - 0.5f, 0f), (height - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = fx - x0
        val ty = fy - y0
        val a = (data[y0 * width + x0].toInt() and 0xFF) + ((data[y0 * width + x1].toInt() and 0xFF) - (data[y0 * width + x0].toInt() and 0xFF)) * tx
        val b = (data[y1 * width + x0].toInt() and 0xFF) + ((data[y1 * width + x1].toInt() and 0xFF) - (data[y1 * width + x0].toInt() and 0xFF)) * tx
        return (a + (b - a) * ty) / 255f
    }
}

data class AspectRatio(val label: String, val ratio: Float?) // ratio = larghezza/altezza; null = libero; <0 = originale

/** Matrice 3×3 per riga. */
class Mat3(val m: DoubleArray) {
    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += m[i * 3 + k] * o.m[k * 3 + j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun inverse(): Mat3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        if (abs(det) < 1e-12) return IDENTITY
        val inv = DoubleArray(9)
        inv[0] = c00 / det
        inv[1] = (a[2] * a[7] - a[1] * a[8]) / det
        inv[2] = (a[1] * a[5] - a[2] * a[4]) / det
        inv[3] = c01 / det
        inv[4] = (a[0] * a[8] - a[2] * a[6]) / det
        inv[5] = (a[2] * a[3] - a[0] * a[5]) / det
        inv[6] = c02 / det
        inv[7] = (a[1] * a[6] - a[0] * a[7]) / det
        inv[8] = (a[0] * a[4] - a[1] * a[3]) / det
        return Mat3(inv)
    }

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        fun rotation(deg: Double): Mat3 {
            val a = deg * PI / 180.0
            return Mat3(doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0))
        }
        fun scale(sx: Double, sy: Double) = Mat3(doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0))
        fun translate(tx: Double, ty: Double) = Mat3(doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0))
        fun perspective(px: Double, py: Double) = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, px, py, 1.0))
    }
}

/**
 * Mappatura geometrica completa dall'immagine d'uscita (dopo il ritaglio) alla sorgente.
 * Ordine in avanti: orientamento → correzione obiettivo → trasformazione (Upright e manuale)
 * → raddrizzamento → ritaglio.
 */
class GeoMap(val p: EditParams, val cw: Int, val ch: Int, ignoreCrop: Boolean = false) {
    val rot = ((p.rotate90 % 4) + 4) % 4
    private val cl = if (ignoreCrop) 0f else p.cropLeft
    private val ct = if (ignoreCrop) 0f else p.cropTop
    private val cr = if (ignoreCrop) 1f else p.cropRight
    private val cb = if (ignoreCrop) 1f else p.cropBottom
    private val halfW = cw / 2f
    private val halfH = ch / 2f
    private val halfDiag = sqrt(halfW * halfW + halfH * halfH)
    private val angle = p.straighten * PI.toFloat() / 180f
    private val ca = cos(-angle)
    private val sa = sin(-angle)
    private val k = -p.lensDistortion / 100f * 0.2f
    private val kNorm = 1f + max(k, 0f)
    val hasDist = k != 0f
    val hasTransform = p.hasTransform()
    private val inv: DoubleArray = Geometry.transformMatrix(p).inverse().m
    val zoom: Float = Geometry.zoomFor(p, cw, ch)

    val isIdentity: Boolean = rot == 0 && !p.flipH && !p.flipV && p.straighten == 0f && !hasDist &&
        !hasTransform && cl == 0f && ct == 0f && cr == 1f && cb == 1f

    /**
     * Uscita normalizzata → pixel della tela orientata (prima della correzione obiettivo).
     * Restituisce false se il punto cade fuori dall'immagine a causa della trasformazione.
     * [chScale] scala radiale per canale (aberrazione cromatica).
     */
    fun toCanvas(uo: Float, vo: Float, out: FloatArray, chScale: Float = 1f, z: Float = zoom): Boolean {
        val u = cl + uo * (cr - cl)
        val v = ct + vo * (cb - ct)
        val dx = u * cw - halfW
        val dy = v * ch - halfH
        var ix = (dx * ca - dy * sa) / z
        var iy = (dx * sa + dy * ca) / z
        var inside = true
        if (hasTransform) {
            val X = (ix / halfDiag).toDouble()
            val Y = (iy / halfDiag).toDouble()
            val w = inv[6] * X + inv[7] * Y + inv[8]
            if (w <= 1e-6) {
                out[0] = -1e6f; out[1] = -1e6f
                return false
            }
            ix = ((inv[0] * X + inv[1] * Y + inv[2]) / w * halfDiag).toFloat()
            iy = ((inv[3] * X + inv[4] * Y + inv[5]) / w * halfDiag).toFloat()
            if (ix < -halfW - 0.5f || ix > halfW + 0.5f || iy < -halfH - 0.5f || iy > halfH + 0.5f) inside = false
        }
        if (hasDist) {
            val r2 = (ix * ix + iy * iy) / (halfDiag * halfDiag)
            val f = (1f + k * r2) / kNorm
            ix *= f; iy *= f
        }
        if (chScale != 1f) { ix *= chScale; iy *= chScale }
        out[0] = ix + halfW
        out[1] = iy + halfH
        return inside
    }

    /** Pixel della tela orientata → coordinate normalizzate della sorgente. */
    fun canvasToSource(x: Float, y: Float, out: FloatArray) {
        var u = x / cw
        var v = y / ch
        if (p.flipH) u = 1f - u
        if (p.flipV) v = 1f - v
        when (rot) {
            1 -> { out[0] = v; out[1] = 1f - u }
            2 -> { out[0] = 1f - u; out[1] = 1f - v }
            3 -> { out[0] = 1f - v; out[1] = u }
            else -> { out[0] = u; out[1] = v }
        }
    }

    /** Uscita normalizzata → sorgente normalizzata. */
    fun toSource(uo: Float, vo: Float, out: FloatArray): Boolean {
        val inside = toCanvas(uo, vo, out)
        canvasToSource(out[0], out[1], out)
        return inside
    }
}

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

    const val OUTSIDE_COLOR = 0xFFFFFFFF.toInt()

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

    /** Valori di prospettiva effettivi (Upright + manuale) in unità normalizzate. */
    fun perspectiveV(p: EditParams) = p.uprightV - p.tVertical / 100f * 0.6f
    fun perspectiveH(p: EditParams) = p.uprightH + p.tHorizontal / 100f * 0.6f

    /** Trasformazione in avanti (sorgente → uscita) in coordinate centrate e normalizzate sulla semidiagonale. */
    fun transformMatrix(p: EditParams): Mat3 {
        if (!p.hasTransform()) return Mat3.IDENTITY
        val a = p.tAspect / 100.0 * 0.35
        val s = (p.tScale / 100.0).coerceIn(0.1, 4.0)
        return Mat3.translate(p.tOffsetX / 100.0 * 0.5, p.tOffsetY / 100.0 * 0.5) *
            Mat3.scale(s * exp(a), s * exp(-a)) *
            Mat3.rotation((p.tRotate + p.uprightAngle).toDouble()) *
            Mat3.perspective(perspectiveH(p).toDouble(), perspectiveV(p).toDouble())
    }

    /** Ingrandimento da applicare: copertura del raddrizzamento e, se richiesto, vincolo del ritaglio. */
    internal fun zoomFor(p: EditParams, cw: Int, ch: Int): Float {
        val base = coverScale(cw, ch, p.straighten)
        if (!p.constrainCrop || !p.hasTransform()) return base
        // sonda sulla tela intera, senza vincolo (evita la ricorsione)
        val probe = GeoMap(p.copy(constrainCrop = false, masks = emptyList()), cw, ch, ignoreCrop = true)
        val tmp = FloatArray(2)
        val n = 24
        fun fits(z: Float): Boolean {
            for (i in 0..n) {
                val t = i / n.toFloat()
                if (!probe.toCanvas(t, 0f, tmp, 1f, z)) return false
                if (!probe.toCanvas(t, 1f, tmp, 1f, z)) return false
                if (!probe.toCanvas(0f, t, tmp, 1f, z)) return false
                if (!probe.toCanvas(1f, t, tmp, 1f, z)) return false
            }
            return true
        }
        var lo = base
        var hi = max(base, 1f) * 8f
        if (fits(lo)) return lo
        if (!fits(hi)) return hi
        repeat(28) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) hi = mid else lo = mid
        }
        return hi
    }

    fun isIdentity(p: EditParams): Boolean =
        p.rotate90 % 4 == 0 && !p.flipH && !p.flipV && p.straighten == 0f &&
            p.lensDistortion == 0f && p.chromaticAberration == 0f && !p.hasCrop() && !p.hasTransform()

    /** Dimensioni dell'uscita (dopo il ritaglio) per una sorgente w×h. */
    fun outputSize(w: Int, h: Int, p: EditParams, ignoreCrop: Boolean = false): Pair<Int, Int> {
        val (cw, ch) = orientedSize(w, h, p.rotate90)
        if (ignoreCrop) return cw to ch
        val ow = max(1, ((p.cropRight - p.cropLeft) * cw).roundToInt())
        val oh = max(1, ((p.cropBottom - p.cropTop) * ch).roundToInt())
        return ow to oh
    }

    /**
     * Applica orientamento, correzioni dell'obiettivo, trasformazioni, raddrizzamento e ritaglio.
     */
    fun apply(src: Img, p: EditParams, ignoreCrop: Boolean = false, cancel: () -> Boolean = { false }): Img {
        val effective = if (ignoreCrop) p.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f) else p
        if (isIdentity(effective)) return src
        val sw = src.width
        val sh = src.height
        val (cw, ch) = orientedSize(sw, sh, p.rotate90)
        val (ow, oh) = outputSize(sw, sh, effective)
        val out = IntArray(ow * oh)
        val map = GeoMap(p, cw, ch, ignoreCrop)
        val caAmt = p.chromaticAberration / 100f * 0.004f
        val hasCa = caAmt != 0f
        val px = src.pixels
        val markOutside = map.hasTransform

        Parallel.forRows(oh, cancel) { y0, y1 ->
            val t = FloatArray(2)
            val s = FloatArray(2)
            for (oy in y0 until y1) {
                val vo = (oy + 0.5f) / oh
                var idx = oy * ow
                for (ox in 0 until ow) {
                    val uo = (ox + 0.5f) / ow
                    val inside = map.toCanvas(uo, vo, t)
                    if (markOutside && !inside) {
                        out[idx++] = OUTSIDE_COLOR
                        continue
                    }
                    if (!hasCa) {
                        map.canvasToSource(t[0], t[1], s)
                        out[idx] = bilinear(px, sw, sh, s[0] * sw - 0.5f, s[1] * sh - 0.5f)
                    } else {
                        map.toCanvas(uo, vo, t, 1f + caAmt)
                        map.canvasToSource(t[0], t[1], s)
                        val cR = bilinear(px, sw, sh, s[0] * sw - 0.5f, s[1] * sh - 0.5f)
                        map.toCanvas(uo, vo, t)
                        map.canvasToSource(t[0], t[1], s)
                        val cG = bilinear(px, sw, sh, s[0] * sw - 0.5f, s[1] * sh - 0.5f)
                        map.toCanvas(uo, vo, t, 1f - caAmt)
                        map.canvasToSource(t[0], t[1], s)
                        val cB = bilinear(px, sw, sh, s[0] * sw - 0.5f, s[1] * sh - 0.5f)
                        out[idx] = (0xFF shl 24) or (cR and 0xFF0000) or (cG and 0x00FF00) or (cB and 0x0000FF)
                    }
                    idx++
                }
            }
        }
        return Img(ow, oh, out)
    }

    /**
     * Porta un raster della sorgente nello spazio d'uscita w×h (stessa geometria dell'immagine).
     * Restituisce valori 0..1; fuori dall'immagine vale 0.
     */
    fun mapRaster(raster: AiRaster, p: EditParams, srcW: Int, srcH: Int, w: Int, h: Int, ignoreCrop: Boolean, cancel: () -> Boolean = { false }): FloatArray {
        val (cw, ch) = orientedSize(srcW, srcH, p.rotate90)
        val map = GeoMap(p, cw, ch, ignoreCrop)
        val out = FloatArray(w * h)
        Parallel.forRows(h, cancel) { y0, y1 ->
            val t = FloatArray(2)
            for (y in y0 until y1) {
                val vo = (y + 0.5f) / h
                for (x in 0 until w) {
                    val inside = map.toSource((x + 0.5f) / w, vo, t)
                    out[y * w + x] = if (inside || !map.hasTransform) raster.sample(t[0], t[1]) else 0f
                }
            }
        }
        return out
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
