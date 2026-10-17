package com.michele.luce.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Curves {
    const val LUT_SIZE = 1024

    /** Spline cubica monotona (Fritsch–Carlson): non produce oscillazioni tra i punti. */
    fun buildLut(curve: ToneCurve): FloatArray {
        val lut = FloatArray(LUT_SIZE)
        val pts = curve.points.sortedBy { it.x }.let { sorted ->
            // rimuove x duplicate tenendo l'ultima
            val out = ArrayList<CurvePoint>()
            for (p in sorted) {
                if (out.isNotEmpty() && abs(out.last().x - p.x) < 1e-5f) out[out.size - 1] = p else out.add(p)
            }
            out
        }
        if (pts.size < 2) {
            for (i in 0 until LUT_SIZE) lut[i] = i / (LUT_SIZE - 1f)
            return lut
        }
        val n = pts.size
        val xs = FloatArray(n) { pts[it].x }
        val ys = FloatArray(n) { pts[it].y.coerceIn(0f, 1f) }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-6f, xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
        var seg = 0
        for (i in 0 until LUT_SIZE) {
            val x = i / (LUT_SIZE - 1f)
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    while (seg < n - 2 && x > xs[seg + 1]) seg++
                    val h = xs[seg + 1] - xs[seg]
                    val t = (x - xs[seg]) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    val h00 = 2 * t3 - 3 * t2 + 1
                    val h10 = t3 - 2 * t2 + t
                    val h01 = -2 * t3 + 3 * t2
                    val h11 = t3 - t2
                    h00 * ys[seg] + h10 * h * m[seg] + h01 * ys[seg + 1] + h11 * h * m[seg + 1]
                }
            }
            lut[i] = y.coerceIn(0f, 1f)
        }
        return lut
    }

    /** Curva parametrica a quattro regioni, combinata con la curva a punti principale. */
    fun buildMasterLut(p: EditParams): FloatArray {
        val base = buildLut(p.curveMaster)
        val adj = floatArrayOf(p.parShadows, p.parDarks, p.parLights, p.parHighlights)
        if (adj.all { it == 0f }) return base
        val s1 = (p.parSplitShadows / 100f).coerceIn(0.05f, 0.9f)
        val s2 = (p.parSplitMidtones / 100f).coerceIn(s1 + 0.02f, 0.95f)
        val s3 = (p.parSplitHighlights / 100f).coerceIn(s2 + 0.02f, 0.98f)
        val lo = floatArrayOf(0f, s1, s2, s3)
        val hi = floatArrayOf(s1, s2, s3, 1f)
        val out = FloatArray(LUT_SIZE)
        var prev = 0f
        for (i in 0 until LUT_SIZE) {
            val x = i / (LUT_SIZE - 1f)
            var y = x
            for (k in 0 until 4) {
                val c = (lo[k] + hi[k]) / 2f
                val half = max(0.04f, hi[k] - lo[k])
                val t = (x - c) / half
                val bump = max(0f, 1f - t * t)
                y += adj[k] / 100f * 0.16f * bump * bump
            }
            y = y.coerceIn(0f, 1f)
            // mantiene la monotonia
            if (y < prev) y = prev
            prev = y
            out[i] = base[(y * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]
        }
        return out
    }

    fun isIdentityLut(lut: FloatArray): Boolean {
        for (i in lut.indices) if (abs(lut[i] - i / (LUT_SIZE - 1f)) > 1e-4f) return false
        return true
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun apply(lut: FloatArray, v: Float): Float {
        val f = min(max(v, 0f), 1f) * (LUT_SIZE - 1)
        val i = f.toInt()
        if (i >= LUT_SIZE - 1) return lut[LUT_SIZE - 1]
        val t = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * t
    }
}
