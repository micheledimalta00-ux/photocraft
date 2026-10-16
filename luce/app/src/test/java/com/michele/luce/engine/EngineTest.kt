package com.michele.luce.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EngineTest {

    private fun synth(w: Int, h: Int): Img {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val rgb = Pipeline.hslToRgb(x / (w - 1f) * 360f, 0.7f, 0.15f + 0.7f * (1 - y / (h - 1f)))
            val r = (rgb[0] * 255).toInt().coerceIn(0, 255)
            val g = (rgb[1] * 255).toInt().coerceIn(0, 255)
            val b = (rgb[2] * 255).toInt().coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Img(w, h, px)
    }

    private fun meanGreen(img: Img) = img.pixels.map { it shr 8 and 255 }.average()

    private val src = synth(240, 160)

    @Test fun defaultsAreIdentity() {
        assertTrue(Pipeline.render(src, EditParams()).pixels.contentEquals(src.pixels))
    }

    @Test fun exposureBrightens() {
        assertTrue(meanGreen(Pipeline.render(src, EditParams(exposure = 1f))) > meanGreen(src) + 10)
        assertTrue(meanGreen(Pipeline.render(src, EditParams(exposure = -1f))) < meanGreen(src) - 10)
    }

    @Test fun blackAndWhiteIsGrey() {
        val bw = Pipeline.render(src, EditParams(treatment = Treatment.BW))
        assertTrue(bw.pixels.all { (it shr 16 and 255) == (it and 255) && (it shr 8 and 255) == (it and 255) })
    }

    @Test fun cropAndRotateSize() {
        val out = Pipeline.render(src, EditParams(rotate90 = 1, cropLeft = 0.1f, cropTop = 0.2f, cropRight = 0.6f, cropBottom = 0.9f))
        assertEquals(80, out.width)
        assertEquals(168, out.height)
    }

    @Test fun rotateTwiceHalfIsIdentity() {
        val r = Pipeline.render(Pipeline.render(src, EditParams(rotate90 = 2)), EditParams(rotate90 = 2))
        assertTrue(r.pixels.contentEquals(src.pixels))
    }

    @Test fun allPresetsAndProfilesRender() {
        Presets.builtIn.forEach { Pipeline.render(src, it.params) }
        Profiles.all.forEach { Pipeline.render(src, EditParams(profile = it.name)) }
    }

    @Test fun everythingAtOnceAndJsonRoundTrip() {
        val p = EditParams(
            exposure = 0.4f, contrast = 30f, highlights = -60f, shadows = 50f, whites = 20f, blacks = -20f,
            curveMaster = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 1f))),
            temperature = 10f, tint = 5f, vibrance = 30f, saturation = 10f, hslSat = List(8) { 20f },
            gradeShadows = GradeWheel(200f, 30f, -10f), texture = 30f, clarity = 40f, dehaze = 30f,
            vignetteAmount = -40f, grainAmount = 40f, sharpAmount = 80f, nrLuminance = 40f, nrColor = 50f,
            lensDistortion = 30f, chromaticAberration = 40f, straighten = -5f, flipV = true,
            masks = listOf(
                Mask(1, "a", MaskType.LINEAR, adjust = LocalAdjust(exposure = -1f)),
                Mask(2, "b", MaskType.RADIAL, invert = true, adjust = LocalAdjust(clarity = 20f)),
                Mask(3, "c", MaskType.BRUSH, strokes = listOf(BrushStroke(listOf(0.1f, 0.1f, 0.5f, 0.5f))), adjust = LocalAdjust(exposure = 1f))
            )
        )
        val out = Pipeline.render(src, p)
        assertTrue(out.pixels.all { (it ushr 24) == 255 })
        assertEquals(p, ParamsJson.decode(ParamsJson.encode(p)))
        assertEquals(EditParams(), ParamsJson.decode("{rotto"))
    }

    @Test fun autoToneBrightensDarkImage() {
        val dark = Pipeline.render(src, EditParams(exposure = -2f))
        assertTrue(Analysis.autoTone(dark, EditParams()).exposure > 0.5f)
    }

    @Test fun curveIsMonotone() {
        val lut = Curves.buildLut(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.2f, 0.6f), CurvePoint(0.4f, 0.62f), CurvePoint(1f, 1f))))
        assertTrue((1 until lut.size).all { lut[it] >= lut[it - 1] - 1e-6f })
    }

    @Test fun aspectSquare() {
        val fa = Geometry.fitAspect(EditParams(), Geometry.ASPECTS.first { it.label == "1:1" }, 1200, 800)
        assertTrue(abs((fa.cropRight - fa.cropLeft) * 1200 - (fa.cropBottom - fa.cropTop) * 800) < 1f)
    }
}
