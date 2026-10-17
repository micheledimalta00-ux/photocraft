package com.michele.luce.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Histogram(val red: IntArray, val green: IntArray, val blue: IntArray, val luma: IntArray) {
    val max: Int = run {
        // ignora gli estremi per non schiacciare il grafico
        var m = 1
        for (i in 1 until 255) {
            m = max(m, max(red[i], max(green[i], max(blue[i], luma[i]))))
        }
        m
    }
    val clipShadows: Boolean get() = luma[0] > total() * 0.002f
    val clipHighlights: Boolean get() = red[255] + green[255] + blue[255] > total() * 0.006f
    fun total(): Int = luma.sum().coerceAtLeast(1)
}

object Analysis {

    fun histogram(img: Img): Histogram {
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        val l = IntArray(256)
        val n = img.width * img.height
        val step = max(1, n / 300_000)
        val px = img.pixels
        var i = 0
        while (i < n) {
            val c = px[i]
            val rr = c shr 16 and 0xFF
            val gg = c shr 8 and 0xFF
            val bb = c and 0xFF
            r[rr]++; g[gg]++; b[bb]++
            l[min(255, (0.2126f * rr + 0.7152f * gg + 0.0722f * bb + 0.5f).toInt())]++
            i += step
        }
        return Histogram(r, g, b, l)
    }

    private fun percentile(hist: IntArray, total: Int, p: Float): Float {
        val target = total * p
        var acc = 0f
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= target) return i / 255f
        }
        return 1f
    }

    /** Toni automatici, calcolati sull'immagine sorgente. Mantiene le altre regolazioni. */
    fun autoTone(src: Img, base: EditParams): EditParams {
        val hist = histogram(src).luma
        val total = hist.sum().coerceAtLeast(1)
        val median = percentile(hist, total, 0.5f).coerceIn(0.02f, 0.98f)
        val lo = percentile(hist, total, 0.005f)
        val hi = percentile(hist, total, 0.995f)

        val ev = (2.2f * log2(0.46f / median) * 0.75f).coerceIn(-2.5f, 2.5f)
        val gain = 2f.pow(ev / 2.2f)
        val hiN = min(1.2f, hi * gain)
        val loN = lo * gain

        var clipped = 0
        val thr = (0.97f / gain * 255f).toInt().coerceIn(0, 255)
        for (i in thr..255) clipped += hist[i]
        val clipFrac = clipped.toFloat() / total
        var dark = 0
        val dthr = (0.12f / gain * 255f).toInt().coerceIn(0, 255)
        for (i in 0..dthr) dark += hist[i]
        val darkFrac = dark.toFloat() / total

        val whites = ((0.98f - hiN) * 250f).coerceIn(-40f, 50f)
        val blacks = if (loN > 0.02f) -min(50f, (loN - 0.02f) * 400f) else min(20f, (0.02f - loN) * 300f)
        val highlights = -min(70f, clipFrac * 600f + 15f)
        val shadows = min(55f, darkFrac * 180f + 8f)

        return base.copy(
            exposure = round2(ev),
            contrast = 8f,
            highlights = highlights.roundTo(),
            shadows = shadows.roundTo(),
            whites = whites.roundTo(),
            blacks = blacks.roundTo(),
            vibrance = max(base.vibrance, 12f),
            saturation = base.saturation + 3f
        )
    }

    /** Bilanciamento del bianco automatico (mondo grigio sui mezzitoni). */
    fun autoWhiteBalance(src: Img, base: EditParams): EditParams {
        val px = src.pixels
        val n = src.width * src.height
        val step = max(1, n / 200_000)
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var count = 0
        var i = 0
        while (i < n) {
            val c = px[i]
            val r = Pipeline.srgbToLinear((c shr 16 and 0xFF) / 255f)
            val g = Pipeline.srgbToLinear((c shr 8 and 0xFF) / 255f)
            val b = Pipeline.srgbToLinear((c and 0xFF) / 255f)
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (y in 0.02f..0.8f && max(r, max(g, b)) < 0.97f) {
                sr += r; sg += g; sb += b; count++
            }
            i += step
        }
        if (count < 50) return base
        return wbFor((sr / count).toFloat(), (sg / count).toFloat(), (sb / count).toFloat(), base, 0.8f)
    }

    /**
     * Contagocce del bilanciamento del bianco: rende neutro il colore campionato
     * (r, g, b in 0..1, valori dell'immagine originale).
     */
    fun whiteBalanceFromSample(r: Float, g: Float, b: Float, base: EditParams): EditParams =
        wbFor(Pipeline.srgbToLinear(r), Pipeline.srgbToLinear(g), Pipeline.srgbToLinear(b), base, 1f)

    private fun wbFor(lr: Float, lg: Float, lb: Float, base: EditParams, strength: Float): EditParams {
        val mr = lr.coerceAtLeast(1e-4f)
        val mg = lg.coerceAtLeast(1e-4f)
        val mb = lb.coerceAtLeast(1e-4f)
        val t = ((ln(mb / mr) / (2f * Pipeline.WB_GAIN)) * strength).coerceIn(-1f, 1f)
        val rM = kotlin.math.exp(Pipeline.WB_GAIN * t)
        val bM = kotlin.math.exp(-Pipeline.WB_GAIN * t)
        val q = (mr * rM + mb * bM) / 2f
        val tn = (-ln(q / mg) / 0.3f) * strength
        return base.copy(
            temperature = (t * 100f).coerceIn(-100f, 100f).roundTo(),
            tint = (tn * 100f).coerceIn(-100f, 100f).roundTo()
        )
    }

    private fun round2(v: Float) = (v * 100f).let { kotlin.math.round(it) } / 100f
    private fun Float.roundTo(): Float = kotlin.math.round(this)

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
