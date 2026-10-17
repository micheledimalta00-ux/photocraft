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
                Mask(1, "a", listOf(MaskComponent(11, ComponentType.LINEAR)), adjust = LocalAdjust(exposure = -1f)),
                Mask(2, "b", listOf(MaskComponent(12, ComponentType.RADIAL, invert = true), MaskComponent(13, ComponentType.COLOR_RANGE, CombineMode.INTERSECT, colorSamples = listOf(0.8f, 0.2f, 0.2f))), adjust = LocalAdjust(clarity = 20f, hue = 30f)),
                Mask(3, "c", listOf(MaskComponent(14, ComponentType.BRUSH, strokes = listOf(BrushStroke(listOf(0.1f, 0.1f, 0.5f, 0.5f)))), MaskComponent(15, ComponentType.LUMINANCE_RANGE, CombineMode.SUBTRACT)), adjust = LocalAdjust(exposure = 1f, sharpness = 30f, noise = 20f))
            ),
            pointColors = listOf(PointColor(1, 0f, 0.7f, 0.5f, hueShift = 30f)),
            calRedHue = 20f, calShadowsTint = 10f, removeCA = true, defringePurple = 5f,
            tVertical = 20f, constrainCrop = true
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

    @Test fun maskCombination() {
        val flat = Img(200, 100, IntArray(200 * 100) { (0xFF shl 24) or 0x808080 })
        val lin = MaskComponent(1, ComponentType.LINEAR, x1 = 0f, y1 = 0.5f, x2 = 1f, y2 = 0.5f)
        val rad = MaskComponent(2, ComponentType.RADIAL, CombineMode.SUBTRACT, cx = 0.1f, cy = 0.5f, rx = 0.05f, ry = 0.1f, feather = 10f)
        val ov = Pipeline.maskOverlay(flat, EditParams(), Mask(9, "m", listOf(lin, rad), adjust = LocalAdjust(exposure = 1f)), 200, 100, 200, 100, false, emptyMap(), Pipeline.OverlayMode.BW)
        assertTrue((ov[50 * 200 + 20] and 255) < 20)
        assertTrue((ov[50 * 200 + 60] and 255) > 100)
    }

    @Test fun uprightLevelsTiltedGrid() {
        val w = 600
        val h = 400
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x % 60 < 3 || y % 50 < 3) (0xFF shl 24) or 0x141414 else (0xFF shl 24) or 0xEBEBEB }
        val tilted = Pipeline.render(Img(w, h, px), EditParams(straighten = 4f))
        val r = Upright.analyze(tilted)
        val (a, _, _) = Upright.valuesFor("Livello", r)
        val fixed = Upright.analyze(Pipeline.render(tilted, EditParams(uprightAngle = a)))
        assertTrue(abs(fixed.angle) < 0.6f)
    }

    @Test fun aspectSquare() {
        val fa = Geometry.fitAspect(EditParams(), Geometry.ASPECTS.first { it.label == "1:1" }, 1200, 800)
        assertTrue(abs((fa.cropRight - fa.cropLeft) * 1200 - (fa.cropBottom - fa.cropTop) * 800) < 1f)
    }
}
