package com.michele.luce.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Upright automatico: stima l'inclinazione dell'orizzonte e la convergenza delle linee
 * verticali e orizzontali dalle direzioni dei bordi.
 */
object Upright {

    data class Result(val angle: Float, val vertical: Float, val horizontal: Float)

    val MODES = listOf("Off", "Auto", "Livello", "Verticale", "Completo")

    /** Compensa la sottostima della pendenza dovuta alla levigatura dei bordi. */
    private const val GAIN = 1.12f

    /** [img] = immagine orientata (rotazione a 90° e rifletti applicati), senza raddrizzamento. */
    fun analyze(img: Img): Result {
        val small = downscale(img, 640)
        val w = small.width
        val h = small.height
        val lum = MaskEngine.luminance(small)
        Pipeline.blur(lum, w, h, 1)
        val halfW = w / 2f
        val halfH = h / 2f
        val halfDiag = sqrt(halfW * halfW + halfH * halfH)

        // raccolta dei bordi
        val mags = FloatArray(w * h)
        val angs = FloatArray(w * h)
        var maxMag = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - 1] + lum[i + w - 1])
            val gy = (lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - w] + lum[i - w + 1])
            val m = sqrt(gx * gx + gy * gy)
            mags[i] = m
            // direzione della linea: perpendicolare al gradiente
            angs[i] = atan2(gy, gx) + (PI / 2).toFloat()
            if (m > maxMag) maxMag = m
        }
        if (maxMag <= 1e-4f) return Result(0f, 0f, 0f)
        val th = maxMag * 0.18f

        // istogramma delle deviazioni (gradi) per orizzontali e verticali
        val bins = 121 // -15..15 con passo 0.25
        val hist = FloatArray(bins)
        // dati per le regressioni
        val vx = ArrayList<Float>(); val vd = ArrayList<Float>(); val vw = ArrayList<Float>()
        val hy = ArrayList<Float>(); val hd = ArrayList<Float>(); val hw = ArrayList<Float>()
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val m = mags[i]
            if (m < th) continue
            var a = Math.toDegrees(angs[i].toDouble()).toFloat()
            // normalizza in -90..90
            while (a > 90f) a -= 180f
            while (a <= -90f) a += 180f
            val wgt = min(m / maxMag, 1f)
            if (abs(a) < 15f) {
                // quasi orizzontale: a = pendenza della linea
                val bi = ((a + 15f) / 0.25f).toInt().coerceIn(0, bins - 1)
                hist[bi] += wgt
                hy.add((y - halfH) / halfDiag); hd.add(a); hw.add(wgt)
            } else if (abs(abs(a) - 90f) < 15f) {
                // quasi verticale: deviazione dalla verticale (positiva = inclinata verso destra scendendo)
                val dev = if (a > 0f) a - 90f else a + 90f
                val bi = ((dev + 15f) / 0.25f).toInt().coerceIn(0, bins - 1)
                hist[bi] += wgt * 0.6f
                vx.add((x - halfW) / halfDiag); vd.add(dev); vw.add(wgt)
            }
        }
        // livello: picco dell'istogramma levigato
        val sm = FloatArray(bins)
        for (i in 0 until bins) {
            var s = 0f
            for (d in -4..4) {
                val j = i + d
                if (j in 0 until bins) s += hist[j] * (5 - abs(d))
            }
            sm[i] = s
        }
        var best = bins / 2
        for (i in 0 until bins) if (sm[i] > sm[best]) best = i
        // raffinamento parabolico
        var level = best * 0.25f - 15f
        if (best in 1 until bins - 1) {
            val a = sm[best - 1]; val b = sm[best]; val c = sm[best + 1]
            val den = a - 2 * b + c
            if (abs(den) > 1e-6f) level += (0.5f * (a - c) / den) * 0.25f
        }
        val total = hist.sum()
        // affidabilità: quota di bordi concentrata attorno al picco
        var peakMass = 0f
        for (d in -6..6) { val j = best + d; if (j in 0 until bins) peakMass += hist[j] }
        val pixels = (w * h).toFloat()
        if (total < pixels * 0.004f || peakMass < total * 0.3f) level = 0f

        // convergenza: deviazione (radianti) = k·x + b
        val kv = if (reliable(vx, vw, w * h)) fitSlope(vx, vd, vw) else 0f
        val kh = if (reliable(hy, hw, w * h)) fitSlope(hy, hd, hw) else 0f
        val vert = (Math.toRadians(kv.toDouble()).toFloat()).coerceIn(-0.6f, 0.6f)
        val horiz = (-Math.toRadians(kh.toDouble()).toFloat()).coerceIn(-0.6f, 0.6f)
        return Result(level.coerceIn(-12f, 12f), vert, horiz)
    }

    /** Valori da salvare per la modalità scelta. */
    fun valuesFor(mode: String, r: Result): Triple<Float, Float, Float> = when (mode) {
        "Livello" -> Triple(-r.angle, 0f, 0f)
        "Verticale" -> Triple(-r.angle, -r.vertical * GAIN, 0f)
        "Completo" -> Triple(-r.angle, -r.vertical * GAIN, -r.horizontal * GAIN)
        "Auto" -> Triple(-r.angle, -r.vertical * GAIN * 0.85f, -r.horizontal * GAIN * 0.6f)
        else -> Triple(0f, 0f, 0f)
    }

    /** Servono abbastanza bordi e distribuiti su gran parte dell'immagine. */
    private fun reliable(pos: List<Float>, ws: List<Float>, pixels: Int): Boolean {
        if (pos.size < max(200, pixels / 400)) return false
        var sw = 0.0; var sx = 0.0; var sxx = 0.0
        for (i in pos.indices) { val w = ws[i].toDouble(); val x = pos[i].toDouble(); sw += w; sx += w * x; sxx += w * x * x }
        if (sw <= 0.0) return false
        val mean = sx / sw
        val std = sqrt(max(0.0, sxx / sw - mean * mean))
        return std > 0.12
    }

    /** Regressione pesata robusta (due passate) della pendenza di d rispetto a x. */
    private fun fitSlope(xs: List<Float>, ds: List<Float>, ws: List<Float>): Float {
        if (xs.size < 30) return 0f
        var keep = BooleanArray(xs.size) { true }
        var k = 0f
        var b = 0f
        repeat(3) { pass ->
            var sw = 0.0; var sx = 0.0; var sd = 0.0; var sxx = 0.0; var sxd = 0.0
            for (i in xs.indices) {
                if (!keep[i]) continue
                val w = ws[i].toDouble()
                val x = xs[i].toDouble()
                val d = ds[i].toDouble()
                sw += w; sx += w * x; sd += w * d; sxx += w * x * x; sxd += w * x * d
            }
            if (sw < 1e-6) return 0f
            val den = sw * sxx - sx * sx
            if (abs(den) < 1e-9) return 0f
            k = ((sw * sxd - sx * sd) / den).toFloat()
            b = ((sd - k * sx) / sw).toFloat()
            // scarta i residui grandi
            val res = FloatArray(xs.size) { abs(ds[it] - (k * xs[it] + b)) }
            val sorted = res.filterIndexed { i, _ -> keep[i] }.sorted()
            if (sorted.isEmpty()) return k
            val lim = max(0.5f, sorted[(sorted.size * 0.6f).toInt().coerceAtMost(sorted.size - 1)])
            keep = BooleanArray(xs.size) { res[it] <= lim }
            if (pass == 2) {
                // le linee devono seguire davvero il modello: residuo mediano piccolo
                val med = sorted[sorted.size / 2]
                return if (med < 1.2f) k else 0f
            }
        }
        return k
    }

    fun downscale(img: Img, maxLong: Int): Img {
        val long = max(img.width, img.height)
        if (long <= maxLong) return img
        val f = (long + maxLong - 1) / maxLong
        val w = max(1, img.width / f)
        val h = max(1, img.height / f)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (yy in y * f until min(y * f + f, img.height)) for (xx in x * f until min(x * f + f, img.width)) {
                val c = img.pixels[yy * img.width + xx]
                r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Img(w, h, out)
    }
}
