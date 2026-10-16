package com.michele.luce.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RenderOptions(
    /** Pixel elaborati per pixel dell'originale (es. 0.3 per un'anteprima ridotta). */
    val resolutionScale: Float = 1f,
    /** Se vero non applica il ritaglio (usato dallo strumento Ritaglia). */
    val ignoreCrop: Boolean = false,
    val cancel: () -> Boolean = { false }
)

/**
 * Mappa a bassa risoluzione (sfocata) campionabile in coordinate a piena risoluzione.
 */
class SmallMap(val w: Int, val h: Int, val k: Int, val data: FloatArray) {
    fun sample(x: Int, y: Int): Float {
        val fx = min(max((x + 0.5f) / k - 0.5f, 0f), (w - 1).toFloat())
        val fy = min(max((y + 0.5f) / k - 0.5f, 0f), (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val a = data[y0 * w + x0] + (data[y0 * w + x1] - data[y0 * w + x0]) * tx
        val b = data[y1 * w + x0] + (data[y1 * w + x1] - data[y1 * w + x0]) * tx
        return a + (b - a) * ty
    }

    /** Campiona in coordinate normalizzate 0..1. */
    fun sampleUv(u: Float, v: Float): Float {
        val fx = min(max(u * w - 0.5f, 0f), (w - 1).toFloat())
        val fy = min(max(v * h - 0.5f, 0f), (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val a = data[y0 * w + x0] + (data[y0 * w + x1] - data[y0 * w + x0]) * tx
        val b = data[y1 * w + x0] + (data[y1 * w + x1] - data[y1 * w + x0]) * tx
        return a + (b - a) * ty
    }
}

object Pipeline {

    // ---------- Conversioni colore ----------

    private val SRGB_TO_LIN = FloatArray(256) { srgbToLinear(it / 255f) }
    private const val ENC_N = 8192
    private const val ENC_MAX = 4f
    private val ENC = FloatArray(ENC_N) { linearToGamma(it.toFloat() / (ENC_N - 1) * ENC_MAX) }

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun linearToGamma(v: Float): Float = when {
        v <= 0f -> 0f
        v <= 0.0031308f -> v * 12.92f
        v <= 1f -> 1.055f * v.pow(1f / 2.4f) - 0.055f
        else -> v.pow(1f / 2.2f)
    }

    private fun enc(v: Float): Float {
        if (v <= 0f) return 0f
        val f = v / ENC_MAX * (ENC_N - 1)
        if (f >= ENC_N - 1) return v.pow(1f / 2.2f)
        val i = f.toInt()
        val t = f - i
        return ENC[i] + (ENC[i + 1] - ENC[i]) * t
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = min(max((x - e0) / (e1 - e0), 0f), 1f)
        return t * t * (3f - 2f * t)
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** Funzione dei toni base (spazio gamma). Tutti i parametri sono in -1..1. */
    fun tone(y0: Float, c: Float, hl: Float, sh: Float, wh: Float, bl: Float): Float {
        var y = y0
        if (c != 0f) {
            val yc = clamp01(y)
            y = if (c > 0f) {
                val s = yc * yc * (3f - 2f * yc)
                y + c * 0.7f * (s - yc)
            } else {
                y + c * 0.45f * (y - 0.46f)
            }
        }
        if (hl != 0f) {
            val w = smooth(0.4f, 1f, y)
            y = if (hl < 0f) y + hl * 0.6f * w * (y - 0.4f)
            else y + hl * 0.3f * w * (1.05f - min(y, 1.05f))
        }
        if (sh != 0f) {
            val w = 1f - smooth(0f, 0.6f, y)
            val yp = max(y, 0f)
            y = if (sh > 0f) {
                val guard = yp / (yp + 0.035f)
                y + sh * 0.32f * w * guard * (0.7f - min(yp, 0.7f))
            } else {
                y + sh * 0.6f * w * yp
            }
        }
        if (wh != 0f) {
            val w = smooth(0.3f, 1f, y)
            y += wh * 0.25f * w
        }
        if (bl != 0f) {
            val w = 1f - smooth(0f, 0.5f, y)
            y += bl * 0.12f * w
        }
        return y
    }

    // ---------- Sfocature ----------

    private fun boxBlurH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, cancel: () -> Boolean) {
        val norm = 1f / (2 * r + 1)
        Parallel.forRows(h, cancel) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var acc = 0f
                for (i in -r..r) acc += src[row + min(max(i, 0), w - 1)]
                for (x in 0 until w) {
                    dst[row + x] = acc * norm
                    val add = src[row + min(x + r + 1, w - 1)]
                    val rem = src[row + max(x - r, 0)]
                    acc += add - rem
                }
            }
        }
    }

    private fun boxBlurV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, cancel: () -> Boolean) {
        val norm = 1f / (2 * r + 1)
        Parallel.forRows(w, cancel) { x0, x1 ->
            for (x in x0 until x1) {
                var acc = 0f
                for (i in -r..r) acc += src[min(max(i, 0), h - 1) * w + x]
                for (y in 0 until h) {
                    dst[y * w + x] = acc * norm
                    val add = src[min(y + r + 1, h - 1) * w + x]
                    val rem = src[max(y - r, 0) * w + x]
                    acc += add - rem
                }
            }
        }
    }

    /** Tre passate di box blur ≈ sfocatura gaussiana. Modifica [data] in posto. */
    fun blur(data: FloatArray, w: Int, h: Int, radius: Int, cancel: () -> Boolean = { false }) {
        if (radius < 1) return
        val tmp = FloatArray(data.size)
        repeat(3) {
            boxBlurH(data, tmp, w, h, radius, cancel)
            boxBlurV(tmp, data, w, h, radius, cancel)
        }
    }

    private fun chooseK(radiusPx: Float): Int = max(1, (radiusPx / 3f).toInt())

    /** Mappa sfocata della luminanza con raggio in pixel della risoluzione di lavoro. */
    private fun mapFromLum(lum: FloatArray, w: Int, h: Int, radiusPx: Float, cancel: () -> Boolean): SmallMap {
        val k = chooseK(radiusPx)
        val sw = (w + k - 1) / k
        val sh = (h + k - 1) / k
        val small = FloatArray(sw * sh)
        if (k == 1) {
            System.arraycopy(lum, 0, small, 0, w * h)
        } else {
            Parallel.forRows(sh, cancel) { y0, y1 ->
                for (sy in y0 until y1) {
                    for (sx in 0 until sw) {
                        var acc = 0f
                        var n = 0
                        val yy0 = sy * k
                        val xx0 = sx * k
                        for (yy in yy0 until min(yy0 + k, h)) {
                            val row = yy * w
                            for (xx in xx0 until min(xx0 + k, w)) { acc += lum[row + xx]; n++ }
                        }
                        small[sy * sw + sx] = if (n > 0) acc / n else 0f
                    }
                }
            }
        }
        blur(small, sw, sh, max(1, (radiusPx / k + 0.5f).toInt()), cancel)
        return SmallMap(sw, sh, k, small)
    }

    /** mode: 0 = canale minimo (per dehaze), 1 = Cb, 2 = Cr. */
    private fun mapFromPacked(packed: IntArray, w: Int, h: Int, radiusPx: Float, mode: Int, cancel: () -> Boolean): SmallMap {
        val k = chooseK(radiusPx)
        val sw = (w + k - 1) / k
        val sh = (h + k - 1) / k
        val small = FloatArray(sw * sh)
        Parallel.forRows(sh, cancel) { y0, y1 ->
            for (sy in y0 until y1) {
                for (sx in 0 until sw) {
                    var acc = 0f
                    var n = 0
                    val yy0 = sy * k
                    val xx0 = sx * k
                    for (yy in yy0 until min(yy0 + k, h)) {
                        val row = yy * w
                        for (xx in xx0 until min(xx0 + k, w)) {
                            val c = packed[row + xx]
                            val r = (c ushr 20 and 0x3FF) / 1023f
                            val g = (c ushr 10 and 0x3FF) / 1023f
                            val b = (c and 0x3FF) / 1023f
                            acc += when (mode) {
                                0 -> min(r, min(g, b))
                                1 -> b - luma(r, g, b)
                                else -> r - luma(r, g, b)
                            }
                            n++
                        }
                    }
                    small[sy * sw + sx] = if (n > 0) acc / n else 0f
                }
            }
        }
        blur(small, sw, sh, max(1, (radiusPx / k + 0.5f).toInt()), cancel)
        return SmallMap(sw, sh, k, small)
    }

    // ---------- Maschere ----------

    private class MaskEval(
        val mask: Mask,
        val cw: Float, val ch: Float,
        val brush: SmallMap?
    ) {
        private val ang = -mask.angle * PI.toFloat() / 180f
        private val ca = cos(ang)
        private val sa = sin(ang)
        private val ldx = (mask.x2 - mask.x1) * cw
        private val ldy = (mask.y2 - mask.y1) * ch
        private val llen2 = max(1e-6f, ldx * ldx + ldy * ldy)
        private val inner = 1f - max(0.01f, mask.feather / 100f)

        /** u, v = coordinate normalizzate della tela (prima del ritaglio). */
        fun weight(u: Float, v: Float): Float {
            val w = when (mask.type) {
                MaskType.LINEAR -> {
                    val px = (u - mask.x1) * cw
                    val py = (v - mask.y1) * ch
                    val t = (px * ldx + py * ldy) / llen2
                    1f - smooth(0f, 1f, t)
                }
                MaskType.RADIAL -> {
                    val px = (u - mask.cx) * cw
                    val py = (v - mask.cy) * ch
                    val rx = px * ca - py * sa
                    val ry = px * sa + py * ca
                    val nx = rx / max(1f, mask.rx * cw)
                    val ny = ry / max(1f, mask.ry * ch)
                    val d = sqrt(nx * nx + ny * ny)
                    1f - smooth(inner, 1f, d)
                }
                MaskType.BRUSH -> brush?.sampleUv(u, v) ?: 0f
            }
            return if (mask.invert) 1f - w else w
        }
    }

    /** Rasterizza i tratti di pennello su una mappa della tela. */
    fun rasterizeBrush(mask: Mask, cw: Int, ch: Int): SmallMap {
        val long = max(cw, ch)
        val target = 768
        val k = max(1, long / target)
        val sw = max(1, (cw + k - 1) / k)
        val sh = max(1, (ch + k - 1) / k)
        val data = FloatArray(sw * sh)
        val longSmall = max(sw, sh).toFloat()
        for (stroke in mask.strokes) {
            val pts = stroke.points
            if (pts.size < 2) continue
            val radius = max(0.75f, stroke.size * longSmall / 2f)
            val hard = 1f - stroke.feather.coerceIn(0f, 1f)
            val step = max(0.5f, radius * 0.25f)
            var lx = pts[0] * sw
            var ly = pts[1] * sh
            stamp(data, sw, sh, lx, ly, radius, hard, stroke.flow, stroke.erase)
            var i = 2
            while (i + 1 < pts.size) {
                val nx = pts[i] * sw
                val ny = pts[i + 1] * sh
                val dx = nx - lx
                val dy = ny - ly
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= step) {
                    val n = (dist / step).toInt()
                    for (s in 1..n) {
                        val t = s / n.toFloat()
                        stamp(data, sw, sh, lx + dx * t, ly + dy * t, radius, hard, stroke.flow, stroke.erase)
                    }
                    lx = nx; ly = ny
                }
                i += 2
            }
        }
        return SmallMap(sw, sh, k, data)
    }

    private fun stamp(d: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, hard: Float, flow: Float, erase: Boolean) {
        val x0 = max(0, (cx - r).toInt())
        val x1 = min(w - 1, (cx + r).toInt() + 1)
        val y0 = max(0, (cy - r).toInt())
        val y1 = min(h - 1, (cy + r).toInt() + 1)
        val f = flow.coerceIn(0.05f, 1f) * 0.6f
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val dn = sqrt(dx * dx + dy * dy) / r
                if (dn >= 1f) continue
                val a = (1f - smooth(min(hard, 0.99f), 1f, dn)) * f
                val i = y * w + x
                d[i] = if (erase) d[i] * (1f - a) else d[i] + a * (1f - d[i])
            }
        }
    }

    // ---------- Colore HSL ----------

    private fun bandLerp(values: List<Float>, hue: Float): Float {
        val c = EditParams.HSL_CENTERS
        var h = hue % 360f
        if (h < 0f) h += 360f
        for (i in 0 until 8) {
            val a = c[i]
            val b = if (i == 7) 360f else c[i + 1]
            if (h >= a && h < b) {
                val t = (h - a) / (b - a)
                val va = values[i]
                val vb = values[if (i == 7) 0 else i + 1]
                return va + (vb - va) * t
            }
        }
        return values[0]
    }

    // ---------- Rendering ----------

    fun render(src: Img, p: EditParams, o: RenderOptions = RenderOptions()): Img {
        val geo = Geometry.apply(src, p, o.ignoreCrop, o.cancel)
        val rot = ((p.rotate90 % 4) + 4) % 4
        val (cw, ch) = Geometry.orientedSize(src.width, src.height, rot)
        return develop(geo, p, o, cw, ch)
    }

    fun adjustmentsAreDefault(p: EditParams): Boolean =
        p.resetGeometry().copy(masks = emptyList(), lensDistortion = 0f, chromaticAberration = 0f) == EditParams() &&
            p.masks.none { it.visible && !it.adjust.isNeutral }

    /**
     * Sviluppo dell'immagine già trasformata geometricamente.
     * cw, ch = dimensioni della tela orientata (prima del ritaglio) in pixel di lavoro.
     */
    fun develop(img: Img, p: EditParams, o: RenderOptions, cw: Int, ch: Int): Img {
        if (adjustmentsAreDefault(p)) return img
        val w = img.width
        val h = img.height
        val n = w * h
        val cancel = o.cancel
        val prof = Profiles.byName(p.profile)
        val pa = p.profileAmount / 100f

        val cropL = if (o.ignoreCrop) 0f else p.cropLeft
        val cropT = if (o.ignoreCrop) 0f else p.cropTop
        val cropR = if (o.ignoreCrop) 1f else p.cropRight
        val cropB = if (o.ignoreCrop) 1f else p.cropBottom

        // ---- parametri globali ----
        val temp = p.temperature / 100f + prof.warmth * pa
        val tint = p.tint / 100f
        var wbR = exp(0.42f * temp)
        var wbG = exp(-0.3f * tint)
        var wbB = exp(-0.42f * temp)
        val wbN = luma(wbR, wbG, wbB)
        wbR /= wbN; wbG /= wbN; wbB /= wbN
        val expo = 2f.pow(p.exposure)
        val shoulder = prof.shoulder * pa
        val cGlobal = (p.contrast / 100f + prof.contrast * pa).coerceIn(-1f, 1.5f)
        val hl = p.highlights / 100f
        val sh = p.shadows / 100f
        val wh = p.whites / 100f
        val bl = p.blacks / 100f
        val doTone = cGlobal != 0f || hl != 0f || sh != 0f || wh != 0f || bl != 0f

        // ---- passata A: bilanciamento, esposizione, toni ----
        val packed = IntArray(n)
        val lum = FloatArray(n)
        val srcPx = img.pixels
        Parallel.forRows(h, cancel) { y0, y1 ->
            for (y in y0 until y1) {
                var i = y * w
                val end = i + w
                while (i < end) {
                    val c = srcPx[i]
                    var r = SRGB_TO_LIN[c shr 16 and 0xFF] * wbR * expo
                    var g = SRGB_TO_LIN[c shr 8 and 0xFF] * wbG * expo
                    var b = SRGB_TO_LIN[c and 0xFF] * wbB * expo
                    if (shoulder > 0f) {
                        val comp = 1f + shoulder * 0.054f
                        r = r * comp / (1f + shoulder * r * 0.3f)
                        g = g * comp / (1f + shoulder * g * 0.3f)
                        b = b * comp / (1f + shoulder * b * 0.3f)
                    }
                    r = enc(r); g = enc(g); b = enc(b)
                    if (doTone) {
                        val yy = luma(r, g, b)
                        val y2 = tone(yy, cGlobal, hl, sh, wh, bl)
                        if (y2 != yy) {
                            if (yy > 1e-4f) {
                                val ratio = min(max(y2 / yy, 0f), 8f)
                                val d = y2 - yy
                                r = r * ratio * 0.7f + (r + d) * 0.3f
                                g = g * ratio * 0.7f + (g + d) * 0.3f
                                b = b * ratio * 0.7f + (b + d) * 0.3f
                            } else {
                                val d = y2 - yy
                                r += d; g += d; b += d
                            }
                        }
                    }
                    r = clamp01(r); g = clamp01(g); b = clamp01(b)
                    packed[i] = ((r * 1023f + 0.5f).toInt() shl 20) or ((g * 1023f + 0.5f).toInt() shl 10) or (b * 1023f + 0.5f).toInt()
                    lum[i] = luma(r, g, b)
                    i++
                }
            }
        }

        // ---- maschere ----
        val masks = p.masks.filter { it.visible && !it.adjust.isNeutral }
        val maskEvals = masks.map { m ->
            MaskEval(m, cw.toFloat(), ch.toFloat(), if (m.type == MaskType.BRUSH) rasterizeBrush(m, cw, ch) else null)
        }
        val nm = maskEvals.size

        // ---- mappe locali ----
        val longEdge = max(w, h).toFloat()
        val scale = o.resolutionScale.coerceIn(0.01f, 1f)
        val texture = p.texture / 100f
        val clarity = p.clarity / 100f
        val dehaze = p.dehaze / 100f
        val needTex = texture != 0f || masks.any { it.adjust.texture != 0f }
        val needCla = clarity != 0f || masks.any { it.adjust.clarity != 0f }
        val needDhz = dehaze != 0f || masks.any { it.adjust.dehaze != 0f }
        val sharpAmt = p.sharpAmount / 100f * 1.6f
        val nrL = p.nrLuminance / 100f
        val nrC = p.nrColor / 100f

        val texMap = if (needTex) mapFromLum(lum, w, h, max(1f, longEdge * 0.004f), cancel) else null
        val claMap = if (needCla) mapFromLum(lum, w, h, max(2f, longEdge * 0.03f), cancel) else null
        val dhzMap = if (needDhz) mapFromPacked(packed, w, h, max(2f, longEdge * 0.04f), 0, cancel) else null

        val sharpRadiusPx = p.sharpRadius * scale
        val sharpMap = if (sharpAmt > 0f) mapFromLum(lum, w, h, max(1f, sharpRadiusPx), cancel) else null
        val sharpAmtEff = sharpAmt * min(1f, sharpRadiusPx / 0.6f).coerceAtLeast(0.25f)
        val sharpLimit = 0.03f + p.sharpDetail / 100f * 0.22f
        val sharpMaskTh = p.sharpMasking / 100f * 0.08f

        val nrRadius = (0.8f + nrL * 1.6f) * scale
        val nrMap = if (nrL > 0f) mapFromLum(lum, w, h, max(1f, nrRadius), cancel) else null
        val nrTh = 0.01f + p.nrLumDetail / 100f * 0.08f

        val cnrRadius = max(2f, (3f + nrC * 10f) * scale)
        val cbMap = if (nrC > 0f) mapFromPacked(packed, w, h, cnrRadius, 1, cancel) else null
        val crMap = if (nrC > 0f) mapFromPacked(packed, w, h, cnrRadius, 2, cancel) else null
        val cnrTh = 0.02f + p.nrColorDetail / 100f * 0.2f

        // ---- curve ----
        val lutM = Curves.buildMasterLut(p)
        val lutR = Curves.buildLut(p.curveRed)
        val lutG = Curves.buildLut(p.curveGreen)
        val lutB = Curves.buildLut(p.curveBlue)
        val useM = !Curves.isIdentityLut(lutM)
        val useR = !p.curveRed.isIdentity
        val useG = !p.curveGreen.isIdentity
        val useB = !p.curveBlue.isIdentity

        // ---- colore ----
        val bw = p.treatment == Treatment.BW || prof.bw
        val vib = p.vibrance / 100f + prof.vibrance * pa
        val sat = p.saturation / 100f + prof.saturation * pa
        val hslActive = p.hslHue.any { it != 0f } || p.hslSat.any { it != 0f } || p.hslLum.any { it != 0f }
        val doColor = !bw && (hslActive || vib != 0f || sat != 0f)
        val bwR = prof.bwRed * pa
        val bwG = prof.bwGreen * pa
        val bwB = prof.bwBlue * pa

        // ---- color grading ----
        val wheels = arrayOf(p.gradeShadows, p.gradeMidtones, p.gradeHighlights, p.gradeGlobal)
        val doGrade = wheels.any { !it.isNeutral }
        val wheelVec = Array(4) { idx ->
            val wh0 = wheels[idx]
            val rgb = hueToRgb(wh0.hue)
            val l = luma(rgb[0], rgb[1], rgb[2])
            floatArrayOf((rgb[0] - l) * wh0.sat / 100f * 0.35f, (rgb[1] - l) * wh0.sat / 100f * 0.35f, (rgb[2] - l) * wh0.sat / 100f * 0.35f, wh0.lum / 100f * 0.25f)
        }
        val blend = p.gradeBlending / 100f
        val bal = p.gradeBalance / 100f
        val sEdge = 0.33f + bal * 0.15f
        val hEdge = 0.66f + bal * 0.15f
        val gWidth = 0.08f + blend * 0.4f

        // ---- vignettatura, grana, sbiadimento ----
        val vAmt = p.vignetteAmount / 100f
        val vMid = 0.15f + p.vignetteMidpoint / 100f * 0.75f
        val vFeather = 0.05f + p.vignetteFeather / 100f * 0.95f
        val vRound = p.vignetteRoundness / 100f
        val vExp = if (vRound < 0f) 2f + (-vRound) * 6f else 2f
        val vHl = p.vignetteHighlights / 100f
        val aspectXY = w.toFloat() / h
        val lensV = p.lensVignette / 100f
        val gAmt = p.grainAmount / 100f
        val gCell = max(0.7f, longEdge / 1500f * (0.6f + p.grainSize / 100f * 2.6f))
        val gRough = p.grainRoughness / 100f
        val fade = prof.fade * pa

        val out = IntArray(n)
        val halfDiag2 = (cw * cw + ch * ch) / 4f

        // ---- passata B ----
        Parallel.forRows(h, cancel) { y0, y1 ->
            val mw = FloatArray(max(1, nm))
            val tmp = FloatArray(3)
            for (y in y0 until y1) {
                val v = cropT + (y + 0.5f) / h * (cropB - cropT)
                val ny = (y + 0.5f) / h * 2f - 1f
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = packed[i]
                    var r = (c ushr 20 and 0x3FF) / 1023f
                    var g = (c ushr 10 and 0x3FF) / 1023f
                    var b = (c and 0x3FF) / 1023f
                    val l0 = lum[i]
                    val u = cropL + (x + 0.5f) / w * (cropR - cropL)

                    // pesi maschere
                    var dzTot = dehaze
                    var texTot = texture
                    var claTot = clarity
                    for (k in 0 until nm) {
                        val wk = maskEvals[k].weight(u, v)
                        mw[k] = wk
                        if (wk > 0f) {
                            val a = masks[k].adjust
                            dzTot += a.dehaze / 100f * wk
                            texTot += a.texture / 100f * wk
                            claTot += a.clarity / 100f * wk
                        }
                    }

                    // foschia
                    if (dhzMap != null && dzTot != 0f) {
                        if (dzTot > 0f) {
                            val dk = dhzMap.sample(x, y)
                            val t = max(1f - dzTot * 0.85f * dk, 0.2f)
                            val air = 1f - t
                            r = (r - air) / t; g = (g - air) / t; b = (b - air) / t
                        } else {
                            val a = -dzTot * 0.6f
                            r += (0.85f - r) * a; g += (0.86f - g) * a; b += (0.88f - b) * a
                        }
                    }

                    // luminanza: riduzione rumore, texture, chiarezza, nitidezza
                    var ln = l0
                    if (nrMap != null) {
                        val bn = nrMap.sample(x, y)
                        val e = abs(l0 - bn)
                        val wN = nrL * (1f - smooth(nrTh * 0.5f, nrTh * 1.5f, e))
                        ln += (bn - l0) * wN
                    }
                    if (texMap != null && texTot != 0f) {
                        val hp = l0 - texMap.sample(x, y)
                        ln += texTot * 1.1f * hp
                    }
                    if (claMap != null && claTot != 0f) {
                        val hp = l0 - claMap.sample(x, y)
                        val mwt = 0.25f + 3f * l0 * (1f - l0)
                        ln += claTot * 0.75f * hp * mwt
                    }
                    if (sharpMap != null) {
                        var hp = l0 - sharpMap.sample(x, y)
                        hp = min(max(hp, -sharpLimit), sharpLimit)
                        var m = 1f
                        if (sharpMaskTh > 0f) {
                            val xl = if (x > 0) lum[i - 1] else l0
                            val xr = if (x < w - 1) lum[i + 1] else l0
                            val yu = if (y > 0) lum[i - w] else l0
                            val yd = if (y < h - 1) lum[i + w] else l0
                            val gm = abs(xr - xl) + abs(yd - yu)
                            m = smooth(sharpMaskTh * 0.5f, sharpMaskTh * 1.5f, gm)
                        }
                        ln += sharpAmtEff * hp * m
                    }
                    if (ln != l0) {
                        val d = ln - l0
                        r += d; g += d; b += d
                    }

                    // regolazioni locali delle maschere
                    for (k in 0 until nm) {
                        val wk = mw[k]
                        if (wk <= 0.001f) continue
                        val a = masks[k].adjust
                        if (a.exposure != 0f) {
                            val f = 2f.pow(a.exposure * wk / 2.2f)
                            r *= f; g *= f; b *= f
                        }
                        if (a.temperature != 0f || a.tint != 0f) {
                            val t = a.temperature / 100f * wk
                            val tn = a.tint / 100f * wk
                            r *= exp(0.18f * t); b *= exp(-0.18f * t); g *= exp(-0.12f * tn)
                        }
                        if (a.contrast != 0f || a.highlights != 0f || a.shadows != 0f || a.whites != 0f || a.blacks != 0f) {
                            val yy = luma(r, g, b)
                            val y2 = tone(yy, a.contrast / 100f * wk, a.highlights / 100f * wk, a.shadows / 100f * wk, a.whites / 100f * wk, a.blacks / 100f * wk)
                            val d = y2 - yy
                            if (yy > 1e-4f) {
                                val ratio = min(max(y2 / yy, 0f), 8f)
                                r = r * ratio * 0.6f + (r + d) * 0.4f
                                g = g * ratio * 0.6f + (g + d) * 0.4f
                                b = b * ratio * 0.6f + (b + d) * 0.4f
                            } else { r += d; g += d; b += d }
                        }
                        if (a.saturation != 0f) {
                            val yy = luma(r, g, b)
                            val f = 1f + a.saturation / 100f * wk
                            r = yy + (r - yy) * f; g = yy + (g - yy) * f; b = yy + (b - yy) * f
                        }
                    }

                    // riduzione rumore colore
                    if (cbMap != null && crMap != null) {
                        val yy = luma(r, g, b)
                        val cb = b - yy
                        val cr = r - yy
                        val mcb = cbMap.sample(x, y)
                        val mcr = crMap.sample(x, y)
                        val diff = abs(cb - mcb) + abs(cr - mcr)
                        val wC = nrC * (1f - smooth(cnrTh, cnrTh * 2.5f, diff))
                        val ncb = cb + (mcb - cb) * wC
                        val ncr = cr + (mcr - cr) * wC
                        r = yy + ncr
                        b = yy + ncb
                        g = (yy - 0.2126f * r - 0.0722f * b) / 0.7152f
                    }

                    r = clamp01(r); g = clamp01(g); b = clamp01(b)

                    // curva di viraggio
                    if (useM) { r = Curves.apply(lutM, r); g = Curves.apply(lutM, g); b = Curves.apply(lutM, b) }
                    if (useR) r = Curves.apply(lutR, r)
                    if (useG) g = Curves.apply(lutG, g)
                    if (useB) b = Curves.apply(lutB, b)

                    // colore: HSL, vividezza, saturazione / bianco e nero
                    if (bw) {
                        val yy = luma(r, g, b)
                        var grey = yy + bwR * (r - yy) + bwG * (g - yy) + bwB * (b - yy)
                        if (hslActive) {
                            val mx = max(r, max(g, b))
                            val mn = min(r, min(g, b))
                            val chroma = mx - mn
                            if (chroma > 1e-4f) {
                                val hue = hueOf(r, g, b, mx, chroma)
                                grey += bandLerp(p.hslLum, hue) / 100f * 0.35f * min(1f, chroma * 2.5f)
                            }
                        }
                        r = grey; g = grey; b = grey
                    } else if (doColor) {
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        var l = (mx + mn) / 2f
                        val chroma = mx - mn
                        if (chroma > 1e-5f) {
                            var s = chroma / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-5f)
                            s = min(s, 1f)
                            var hue = hueOf(r, g, b, mx, chroma)
                            val cw0 = min(1f, chroma * 4f)
                            if (hslActive) {
                                hue += bandLerp(p.hslHue, hue) * 0.3f * cw0
                                val sa = bandLerp(p.hslSat, hue) / 100f
                                s = if (sa >= 0f) s + (1f - s) * sa * s * 0.9f + s * sa * 0.35f else s * (1f + sa)
                                l += bandLerp(p.hslLum, hue) / 100f * 0.3f * cw0 * (1f - abs(2f * l - 1f)).coerceAtLeast(0.15f)
                            }
                            if (vib != 0f) {
                                s = if (vib > 0f) {
                                    val skin = skinWeight(hue)
                                    s + vib * 0.6f * (1f - s) * min(1f, s * 4f) * (1f - 0.55f * skin)
                                } else s * (1f + vib * 0.7f * (1f - s * 0.5f))
                            }
                            if (sat != 0f) s *= (1f + sat)
                            s = clamp01(s)
                            l = clamp01(l)
                            hslToRgbInto(hue, s, l, tmp)
                            r = tmp[0]; g = tmp[1]; b = tmp[2]
                        }
                    }

                    // color grading
                    if (doGrade) {
                        val yy = luma(r, g, b)
                        val ws = 1f - smooth(sEdge - gWidth, sEdge + gWidth, yy)
                        val whh = smooth(hEdge - gWidth, hEdge + gWidth, yy)
                        val wm = max(0f, 1f - ws - whh)
                        for (k in 0 until 4) {
                            val wv = when (k) { 0 -> ws; 1 -> wm; 2 -> whh; else -> 1f }
                            if (wv <= 0f) continue
                            val vec = wheelVec[k]
                            r += (vec[0] + vec[3]) * wv
                            g += (vec[1] + vec[3]) * wv
                            b += (vec[2] + vec[3]) * wv
                        }
                    }

                    // correzione vignettatura obiettivo (tela)
                    if (lensV != 0f) {
                        val dx = (u - 0.5f) * cw
                        val dy = (v - 0.5f) * ch
                        val d2 = (dx * dx + dy * dy) / halfDiag2
                        val f = 1f + lensV * 0.6f * d2
                        r *= f; g *= f; b *= f
                    }

                    // vignettatura post-ritaglio
                    if (vAmt != 0f) {
                        var vx = (x + 0.5f) / w * 2f - 1f
                        var vy = ny
                        if (vRound > 0f) {
                            // verso il cerchio
                            if (aspectXY > 1f) vx *= 1f + (aspectXY - 1f) * vRound else vy *= 1f + (1f / aspectXY - 1f) * vRound
                        }
                        val d = if (vExp == 2f) sqrt(vx * vx + vy * vy) else (abs(vx).pow(vExp) + abs(vy).pow(vExp)).pow(1f / vExp)
                        val t = smooth(vMid, vMid + vFeather, d)
                        if (t > 0f) {
                            if (vAmt < 0f) {
                                var e = -vAmt * t
                                if (vHl > 0f) e *= 1f - vHl * smooth(0.55f, 1f, luma(r, g, b))
                                val f = 1f - e * 0.95f
                                r *= f; g *= f; b *= f
                            } else {
                                val e = vAmt * t * 0.9f
                                r += (1f - r) * e; g += (1f - g) * e; b += (1f - b) * e
                            }
                        }
                    }

                    if (fade > 0f) {
                        r = fade + r * (1f - fade); g = fade + g * (1f - fade); b = fade + b * (1f - fade)
                    }

                    // grana
                    if (gAmt > 0f) {
                        val gx = x / gCell
                        val gy = y / gCell
                        var nz = valueNoise(gx, gy, 17)
                        if (gRough > 0f) nz = nz * (1f - gRough * 0.5f) + valueNoise(gx * 2.3f + 11.7f, gy * 2.3f + 5.3f, 91) * gRough * 0.5f
                        val yy = clamp01(luma(r, g, b))
                        val amp = gAmt * 0.2f * (0.35f + 2.6f * yy * (1f - yy))
                        val d = (nz - 0.5f) * amp
                        r += d; g += d; b += d
                    }

                    val ri = (clamp01(r) * 255f + 0.5f).toInt()
                    val gi = (clamp01(g) * 255f + 0.5f).toInt()
                    val bi = (clamp01(b) * 255f + 0.5f).toInt()
                    out[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        }
        return Img(w, h, out)
    }

    /** Sovrapposizione rossa del peso di una maschera, dimensione dell'uscita w×h. */
    fun maskOverlay(p: EditParams, mask: Mask, w: Int, h: Int, cw: Int, ch: Int, ignoreCrop: Boolean = false): IntArray {
        val eval = MaskEval(mask, cw.toFloat(), ch.toFloat(), if (mask.type == MaskType.BRUSH) rasterizeBrush(mask, cw, ch) else null)
        val cropL = if (ignoreCrop) 0f else p.cropLeft
        val cropT = if (ignoreCrop) 0f else p.cropTop
        val cropR = if (ignoreCrop) 1f else p.cropRight
        val cropB = if (ignoreCrop) 1f else p.cropBottom
        val out = IntArray(w * h)
        Parallel.forRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val v = cropT + (y + 0.5f) / h * (cropB - cropT)
                for (x in 0 until w) {
                    val u = cropL + (x + 0.5f) / w * (cropR - cropL)
                    val a = (eval.weight(u, v).coerceIn(0f, 1f) * 150f).toInt()
                    out[y * w + x] = (a shl 24) or (0xFF shl 16) or (0x30 shl 8) or 0x30
                }
            }
        }
        return out
    }

    // ---------- utilità ----------

    private fun hueOf(r: Float, g: Float, b: Float, mx: Float, chroma: Float): Float {
        var hh = when (mx) {
            r -> ((g - b) / chroma) % 6f
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        } * 60f
        if (hh < 0f) hh += 360f
        return hh
    }

    fun hslToRgb(hue: Float, s: Float, l: Float): FloatArray {
        val out = FloatArray(3)
        hslToRgbInto(hue, s, l, out)
        return out
    }

    fun hslToRgbInto(hue: Float, s: Float, l: Float, out: FloatArray) {
        var h = hue % 360f
        if (h < 0f) h += 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val m = l - c / 2f
        when {
            hp < 1f -> { out[0] = c + m; out[1] = x + m; out[2] = m }
            hp < 2f -> { out[0] = x + m; out[1] = c + m; out[2] = m }
            hp < 3f -> { out[0] = m; out[1] = c + m; out[2] = x + m }
            hp < 4f -> { out[0] = m; out[1] = x + m; out[2] = c + m }
            hp < 5f -> { out[0] = x + m; out[1] = m; out[2] = c + m }
            else -> { out[0] = c + m; out[1] = m; out[2] = x + m }
        }
    }

    fun hueToRgb(hue: Float): FloatArray = hslToRgb(hue, 1f, 0.5f)

    private fun skinWeight(hue: Float): Float {
        // tonalità della pelle intorno a 15–45°
        val d = abs(((hue - 28f + 540f) % 360f) - 180f)
        return 1f - smooth(10f, 30f, d)
    }

    private fun hash(ix: Int, iy: Int, seed: Int): Float {
        var hsh = ix * -0x61c88647 xor (iy * 0x27d4eb2d) xor (seed * 0x165667b1)
        hsh = hsh xor (hsh ushr 16)
        hsh *= -0x7a143595
        hsh = hsh xor (hsh ushr 13)
        hsh *= -0x3d4d51cb
        hsh = hsh xor (hsh ushr 16)
        return (hsh and 0xFFFFFF) / 16777215f
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val xf = kotlin.math.floor(x)
        val yf = kotlin.math.floor(y)
        val ix = xf.toInt()
        val iy = yf.toInt()
        var tx = x - xf
        var ty = y - yf
        tx = tx * tx * (3f - 2f * tx)
        ty = ty * ty * (3f - 2f * ty)
        val a = hash(ix, iy, seed)
        val b = hash(ix + 1, iy, seed)
        val c = hash(ix, iy + 1, seed)
        val d = hash(ix + 1, iy + 1, seed)
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return top + (bot - top) * ty
    }
}
