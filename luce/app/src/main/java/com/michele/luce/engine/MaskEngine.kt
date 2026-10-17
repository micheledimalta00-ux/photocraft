package com.michele.luce.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Spazio Lab approssimato (D65) per le distanze di colore. */
object ColorSpace {
    private const val N = 4096
    private val LIN = FloatArray(256) { Pipeline.srgbToLinear(it / 255f) }
    private val F = FloatArray(N + 1) { i ->
        val t = i / N.toFloat()
        if (t > 0.008856f) t.pow(1f / 3f) else 7.787f * t + 16f / 116f
    }

    private fun f(t: Float): Float {
        val x = min(max(t, 0f), 1f) * N
        val i = x.toInt()
        if (i >= N) return F[N]
        return F[i] + (F[i + 1] - F[i]) * (x - i)
    }

    /** r, g, b in 0..1 (gamma sRGB) → L, a, b in out. */
    fun lab(r: Float, g: Float, b: Float, out: FloatArray) {
        val rl = lin(r)
        val gl = lin(g)
        val bl = lin(b)
        val x = (0.4124f * rl + 0.3576f * gl + 0.1805f * bl) / 0.95047f
        val y = 0.2126f * rl + 0.7152f * gl + 0.0722f * bl
        val z = (0.0193f * rl + 0.1192f * gl + 0.9505f * bl) / 1.08883f
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    private fun lin(c: Float): Float {
        val v = min(max(c, 0f), 1f) * 255f
        val i = v.toInt()
        if (i >= 255) return LIN[255]
        return LIN[i] + (LIN[i + 1] - LIN[i]) * (v - i)
    }

    /** Distanza tra due colori Lab che pesa più tinta e croma della luminosità. */
    fun distance(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val dl = (l1 - l2) * 0.5f
        val da = a1 - a2
        val db = b1 - b2
        return sqrt(dl * dl + da * da + db * db)
    }
}

object MaskEngine {

    fun smooth(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x >= e1) 1f else 0f
        val t = min(max((x - e0) / (e1 - e0), 0f), 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------
    // Pennello
    // ------------------------------------------------------------------

    /** Raster del pennello sulla tela: peso normale, peso a maschera automatica e colori di riferimento. */
    class BrushRaster(
        val normal: SmallMap,
        val auto: SmallMap?,
        val refR: SmallMap?, val refG: SmallMap?, val refB: SmallMap?
    )

    fun rasterizeBrush(strokes: List<BrushStroke>, cw: Int, ch: Int): BrushRaster {
        val long = max(cw, ch)
        val k = max(1, long / 768)
        val sw = max(1, (cw + k - 1) / k)
        val sh = max(1, (ch + k - 1) / k)
        val normal = FloatArray(sw * sh)
        val hasAuto = strokes.any { it.autoMask && !it.erase }
        val auto = if (hasAuto) FloatArray(sw * sh) else null
        val rs = if (hasAuto) FloatArray(sw * sh) else null
        val gs = if (hasAuto) FloatArray(sw * sh) else null
        val bs = if (hasAuto) FloatArray(sw * sh) else null
        val ws = if (hasAuto) FloatArray(sw * sh) else null
        val longSmall = max(sw, sh).toFloat()
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.size < 2) continue
            val radius = max(0.75f, stroke.size * longSmall / 2f)
            val hard = 1f - stroke.feather.coerceIn(0f, 1f)
            val step = max(0.5f, radius * 0.25f)
            val target = if (stroke.autoMask && !stroke.erase && auto != null) auto else normal
            val density = stroke.density.coerceIn(0.05f, 1f)
            val cols = stroke.colors
            fun colorAt(i: Int, out: FloatArray): Boolean {
                if (cols.size < (i + 1) * 3) return false
                out[0] = cols[i * 3]; out[1] = cols[i * 3 + 1]; out[2] = cols[i * 3 + 2]
                return true
            }
            val col = FloatArray(3)
            val n = pts.size / 2
            var lx = pts[0] * sw
            var ly = pts[1] * sh
            fun stampAt(x: Float, y: Float, pi: Int) {
                val withColor = stroke.autoMask && !stroke.erase && colorAt(pi, col)
                stamp(target, sw, sh, x, y, radius, hard, stroke.flow, density, stroke.erase)
                if (stroke.erase && auto != null) stamp(auto, sw, sh, x, y, radius, hard, stroke.flow, density, true)
                if (withColor && rs != null && gs != null && bs != null && ws != null) {
                    accumulateColor(rs, gs, bs, ws, sw, sh, x, y, radius, col)
                }
            }
            stampAt(lx, ly, 0)
            for (pi in 1 until n) {
                val nx = pts[pi * 2] * sw
                val ny = pts[pi * 2 + 1] * sh
                val dx = nx - lx
                val dy = ny - ly
                val dist = sqrt(dx * dx + dy * dy)
                if (dist >= step) {
                    val m = (dist / step).toInt()
                    for (s in 1..m) {
                        val t = s / m.toFloat()
                        stampAt(lx + dx * t, ly + dy * t, pi)
                    }
                    lx = nx; ly = ny
                }
            }
        }
        if (rs != null && gs != null && bs != null && ws != null) {
            for (i in rs.indices) {
                val w = ws[i]
                if (w > 1e-6f) { rs[i] /= w; gs[i] /= w; bs[i] /= w }
            }
        }
        return BrushRaster(
            SmallMap(sw, sh, k, normal),
            auto?.let { SmallMap(sw, sh, k, it) },
            rs?.let { SmallMap(sw, sh, k, it) }, gs?.let { SmallMap(sw, sh, k, it) }, bs?.let { SmallMap(sw, sh, k, it) }
        )
    }

    private fun stamp(d: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, hard: Float, flow: Float, density: Float, erase: Boolean) {
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
                d[i] = if (erase) d[i] * (1f - a) else min(density, d[i] + a * (density - d[i]).coerceAtLeast(0f))
            }
        }
    }

    private fun accumulateColor(rs: FloatArray, gs: FloatArray, bs: FloatArray, ws: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, c: FloatArray) {
        val rr = r * 1.6f
        val x0 = max(0, (cx - rr).toInt())
        val x1 = min(w - 1, (cx + rr).toInt() + 1)
        val y0 = max(0, (cy - rr).toInt())
        val y1 = min(h - 1, (cy + rr).toInt() + 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val dn = sqrt(dx * dx + dy * dy) / rr
                if (dn >= 1f) continue
                val wt = 1f - dn
                val i = y * w + x
                rs[i] += c[0] * wt; gs[i] += c[1] * wt; bs[i] += c[2] * wt; ws[i] += wt
            }
        }
    }

    // ------------------------------------------------------------------
    // Rifinitura dei bordi (filtro guidato) e poligoni
    // ------------------------------------------------------------------

    private fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (i in -r..r) acc += src[row + min(max(i, 0), w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc / (2 * r + 1)
                acc += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (i in -r..r) acc += tmp[min(max(i, 0), h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / (2 * r + 1)
                acc += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
        return out
    }

    /**
     * Filtro guidato (He et al.): rende la maschera [p] aderente ai bordi della guida [g].
     * Entrambi w×h, valori 0..1.
     */
    fun guidedFilter(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val gp = FloatArray(n) { g[it] * p[it] }
        val gg = FloatArray(n) { g[it] * g[it] }
        val mg = boxMean(g, w, h, r)
        val mp = boxMean(p, w, h, r)
        val mgp = boxMean(gp, w, h, r)
        val mgg = boxMean(gg, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val cov = mgp[i] - mg[i] * mp[i]
            val v = mgg[i] - mg[i] * mg[i]
            a[i] = cov / (v + eps)
            b[i] = mp[i] - a[i] * mg[i]
        }
        val ma = boxMean(a, w, h, r)
        val mb = boxMean(b, w, h, r)
        return FloatArray(n) { (ma[it] * g[it] + mb[it]).coerceIn(0f, 1f) }
    }

    /** Ridimensionamento bilineare di una mappa. */
    fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = min(max((y + 0.5f) * sh / dh - 0.5f, 0f), (sh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = min(max((x + 0.5f) * sw / dw - 0.5f, 0f), (sw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                val a = src[y0 * sw + x0] + (src[y0 * sw + x1] - src[y0 * sw + x0]) * tx
                val b = src[y1 * sw + x0] + (src[y1 * sw + x1] - src[y1 * sw + x0]) * tx
                out[y * dw + x] = a + (b - a) * ty
            }
        }
        return out
    }

    /** Luminanza 0..1 di un'immagine. */
    fun luminance(img: Img): FloatArray {
        val px = img.pixels
        return FloatArray(img.width * img.height) {
            val c = px[it]
            (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
        }
    }

    /**
     * Porta una maschera grezza (anche a bassa risoluzione) alla risoluzione della guida
     * e la fa aderire ai bordi. Risultato quantizzato in un [AiRaster].
     */
    fun refineToRaster(coarse: FloatArray, cw: Int, ch: Int, guide: Img, radiusFrac: Float = 0.006f, eps: Float = 1.5e-3f): AiRaster {
        val w = guide.width
        val h = guide.height
        val up = if (cw == w && ch == h) coarse else resize(coarse, cw, ch, w, h)
        val g = luminance(guide)
        val r = max(2, (max(w, h) * radiusFrac).toInt())
        val refined = guidedFilter(g, up, w, h, r, eps)
        val data = ByteArray(w * h) { (refined[it] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
        return AiRaster(w, h, data)
    }

    fun toRaster(m: FloatArray, w: Int, h: Int): AiRaster =
        AiRaster(w, h, ByteArray(w * h) { (m[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() })

    /** Riempie un poligono (coordinate normalizzate x,y alternate) con bordo morbido di [soft] pixel. */
    fun fillPolygon(dst: FloatArray, w: Int, h: Int, poly: FloatArray, soft: Float = 1f, value: Float = 1f) {
        val n = poly.size / 2
        if (n < 3) return
        val xs = FloatArray(n) { poly[it * 2] * w }
        val ys = FloatArray(n) { poly[it * 2 + 1] * h }
        val minX = max(0, (xs.min() - soft - 1).toInt())
        val maxX = min(w - 1, (xs.max() + soft + 1).toInt())
        val minY = max(0, (ys.min() - soft - 1).toInt())
        val maxY = min(h - 1, (ys.max() + soft + 1).toInt())
        for (y in minY..maxY) {
            val py = y + 0.5f
            for (x in minX..maxX) {
                val px = x + 0.5f
                var inside = false
                var dmin = Float.MAX_VALUE
                var j = n - 1
                for (i in 0 until n) {
                    val xi = xs[i]; val yi = ys[i]; val xj = xs[j]; val yj = ys[j]
                    if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
                    // distanza dal segmento
                    val ex = xj - xi
                    val ey = yj - yi
                    val l2 = ex * ex + ey * ey
                    val t = if (l2 > 0f) (((px - xi) * ex + (py - yi) * ey) / l2).coerceIn(0f, 1f) else 0f
                    val qx = xi + t * ex - px
                    val qy = yi + t * ey - py
                    dmin = min(dmin, qx * qx + qy * qy)
                    j = i
                }
                val d = sqrt(dmin)
                val cov = if (inside) 0.5f + min(d, soft) / (2f * soft) else 0.5f - min(d, soft) / (2f * soft)
                if (cov > 0f) {
                    val idx = y * w + x
                    dst[idx] = max(dst[idx], cov.coerceIn(0f, 1f) * value)
                }
            }
        }
    }

    fun fillCircle(dst: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, soft: Float = 1f) {
        val x0 = max(0, (cx - r - soft - 1).toInt())
        val x1 = min(w - 1, (cx + r + soft + 1).toInt())
        val y0 = max(0, (cy - r - soft - 1).toInt())
        val y1 = min(h - 1, (cy + r + soft + 1).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy))
            val cov = (0.5f + (r - d) / (2f * soft)).coerceIn(0f, 1f)
            val i = y * w + x
            dst[i] = max(dst[i], cov)
        }
    }

    // ------------------------------------------------------------------
    // Valutazione delle maschere durante lo sviluppo
    // ------------------------------------------------------------------

    /** Contesto comune per la valutazione: dimensioni d'uscita e della tela. */
    class Context(
        val p: EditParams,
        val w: Int, val h: Int,          // uscita
        val cw: Int, val ch: Int,        // tela orientata
        val srcW: Int, val srcH: Int,    // sorgente
        val ignoreCrop: Boolean,
        val rasters: Map<String, AiRaster>,
        val inputLum: FloatArray,        // luminanza dell'ingresso (prima delle regolazioni)
        val cancel: () -> Boolean
    )

    private class ComponentEval(val c: MaskComponent, ctx: Context) {
        private val cwf = ctx.cw.toFloat()
        private val chf = ctx.ch.toFloat()
        private val ang = -c.angle * PI.toFloat() / 180f
        private val ca = cos(ang)
        private val sa = sin(ang)
        private val ldx = (c.x2 - c.x1) * cwf
        private val ldy = (c.y2 - c.y1) * chf
        private val llen2 = max(1e-6f, ldx * ldx + ldy * ldy)
        private val inner = 1f - max(0.01f, c.feather / 100f)
        private val brush: BrushRaster? = if (c.type == ComponentType.BRUSH) rasterizeBrush(c.strokes, ctx.cw, ctx.ch) else null
        val aiMap: FloatArray? = if (c.type == ComponentType.AI) {
            ctx.rasters[c.aiKey]?.let { Geometry.mapRaster(it, ctx.p, ctx.srcW, ctx.srcH, ctx.w, ctx.h, ctx.ignoreCrop, ctx.cancel) }
        } else null
        private val samplesLab: FloatArray = if (c.type == ComponentType.COLOR_RANGE) {
            val n = c.colorSamples.size / 3
            val out = FloatArray(n * 3)
            val t = FloatArray(3)
            for (i in 0 until n) {
                ColorSpace.lab(c.colorSamples[i * 3], c.colorSamples[i * 3 + 1], c.colorSamples[i * 3 + 2], t)
                out[i * 3] = t[0]; out[i * 3 + 1] = t[1]; out[i * 3 + 2] = t[2]
            }
            out
        } else FloatArray(0)
        private val tol = 4f + c.refine * 0.45f
        private val lumMap: SmallMap? = if (c.type == ComponentType.LUMINANCE_RANGE && c.lumSmooth > 0f) {
            val radius = max(1f, max(ctx.w, ctx.h) * c.lumSmooth / 100f * 0.02f)
            lumSmallMap(ctx.inputLum, ctx.w, ctx.h, radius, ctx.cancel)
        } else null
        val needsLab = c.type == ComponentType.COLOR_RANGE || (brush?.auto != null)

        fun weight(x: Int, y: Int, u: Float, v: Float, r: Float, g: Float, b: Float, lum: Float, lab: FloatArray, scratch: FloatArray): Float {
            val w = when (c.type) {
                ComponentType.LINEAR -> {
                    val px = (u - c.x1) * cwf
                    val py = (v - c.y1) * chf
                    val t = (px * ldx + py * ldy) / llen2
                    1f - smooth(0f, 1f, t)
                }
                ComponentType.RADIAL -> {
                    val px = (u - c.cx) * cwf
                    val py = (v - c.cy) * chf
                    val rx = px * ca - py * sa
                    val ry = px * sa + py * ca
                    val nx = rx / max(1f, c.rx * cwf)
                    val ny = ry / max(1f, c.ry * chf)
                    val d = sqrt(nx * nx + ny * ny)
                    1f - smooth(inner, 1f, d)
                }
                ComponentType.BRUSH -> {
                    val br = brush!!
                    var wb = br.normal.sampleUv(u, v)
                    val auto = br.auto
                    if (auto != null) {
                        val wa = auto.sampleUv(u, v)
                        if (wa > 0.002f) {
                            val rr = br.refR!!.sampleUv(u, v)
                            val gg = br.refG!!.sampleUv(u, v)
                            val bb = br.refB!!.sampleUv(u, v)
                            val t = scratch
                            ColorSpace.lab(rr, gg, bb, t)
                            val d = ColorSpace.distance(lab[0], lab[1], lab[2], t[0], t[1], t[2])
                            wb = max(wb, wa * (1f - smooth(8f, 22f, d)))
                        }
                    }
                    wb.coerceIn(0f, 1f)
                }
                ComponentType.COLOR_RANGE -> {
                    var best = 0f
                    val n = samplesLab.size / 3
                    for (i in 0 until n) {
                        val d = ColorSpace.distance(lab[0], lab[1], lab[2], samplesLab[i * 3], samplesLab[i * 3 + 1], samplesLab[i * 3 + 2])
                        best = max(best, 1f - smooth(tol * 0.45f, tol * 1.4f, d))
                    }
                    best
                }
                ComponentType.LUMINANCE_RANGE -> {
                    val l = lumMap?.sample(x, y) ?: lum
                    smooth(c.lumLo - c.lumFeatherLo, c.lumLo, l) * (1f - smooth(c.lumHi, c.lumHi + c.lumFeatherHi, l))
                }
                ComponentType.AI -> aiMap?.get(y * stride + x) ?: 0f
            }
            return if (c.invert) 1f - w else w
        }

        private val stride = ctx.w
    }

    private fun lumSmallMap(lum: FloatArray, w: Int, h: Int, radius: Float, cancel: () -> Boolean): SmallMap {
        val k = max(1, (radius / 3f).toInt())
        val sw = (w + k - 1) / k
        val sh = (h + k - 1) / k
        val small = FloatArray(sw * sh)
        for (sy in 0 until sh) for (sx in 0 until sw) {
            var acc = 0f
            var n = 0
            for (yy in sy * k until min(sy * k + k, h)) for (xx in sx * k until min(sx * k + k, w)) { acc += lum[yy * w + xx]; n++ }
            small[sy * sw + sx] = if (n > 0) acc / n else 0f
        }
        Pipeline.blur(small, sw, sh, max(1, (radius / k + 0.5f).toInt()), cancel)
        return SmallMap(sw, sh, k, small)
    }

    /** Valutatore di una maschera completa (componenti combinati). */
    class MaskEval(val mask: Mask, ctx: Context) {
        private val comps = mask.components.map { ComponentEval(it, ctx) }
        val needsLab = comps.any { it.needsLab }

        /** [scratch]: array di 3 elementi propria del thread chiamante. */
        fun weight(x: Int, y: Int, u: Float, v: Float, r: Float, g: Float, b: Float, lum: Float, lab: FloatArray, scratch: FloatArray): Float {
            var w = 0f
            var first = true
            for (ce in comps) {
                val cwt = ce.weight(x, y, u, v, r, g, b, lum, lab, scratch)
                w = if (first) {
                    first = false
                    when (ce.c.mode) {
                        CombineMode.SUBTRACT -> 0f
                        else -> cwt
                    }
                } else when (ce.c.mode) {
                    CombineMode.ADD -> max(w, cwt)
                    CombineMode.SUBTRACT -> w * (1f - cwt)
                    CombineMode.INTERSECT -> w * cwt
                }
            }
            return (if (mask.invert) 1f - w else w).coerceIn(0f, 1f)
        }
    }

    /** Chiavi IA richieste dalle maschere attive. */
    fun requiredAiKeys(p: EditParams): Set<String> =
        p.masks.flatMap { m -> m.components.filter { it.type == ComponentType.AI }.map { it.aiKey } }.toSet()
}
