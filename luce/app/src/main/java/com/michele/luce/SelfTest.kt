package com.michele.luce

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.michele.luce.ai.AiSegmenter
import com.michele.luce.data.BitmapIO
import com.michele.luce.data.ExportSettings
import com.michele.luce.data.Exporter
import com.michele.luce.engine.AiMasks
import com.michele.luce.engine.AiRaster
import com.michele.luce.engine.BrushStroke
import com.michele.luce.engine.CombineMode
import com.michele.luce.engine.ComponentType
import com.michele.luce.engine.EditParams
import com.michele.luce.engine.GradeWheel
import com.michele.luce.engine.Img
import com.michele.luce.engine.LocalAdjust
import com.michele.luce.engine.Mask
import com.michele.luce.engine.MaskComponent
import com.michele.luce.engine.Pipeline
import com.michele.luce.engine.PointColor
import com.michele.luce.engine.RenderOptions
import com.michele.luce.engine.Upright
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Autodiagnosi usata dai test automatici (si attiva solo con l'extra "luce_selftest").
 * Esegue tutte le selezioni IA e una modifica completa su un'immagine e scrive un rapporto.
 */
object SelfTest {
    private const val TAG = "LuceSelfTest"

    fun run(context: Context, path: String) {
        val app = context.applicationContext
        thread(name = "luce-selftest") {
            val out = File(app.getExternalFilesDir(null), "selftest").apply { mkdirs() }
            val name = File(path).nameWithoutExtension
            val report = StringBuilder()
            fun log(s: String) {
                report.appendLine(s)
                Log.i(TAG, s)
                try { File(out, "report_$name.txt").writeText(report.toString()) } catch (_: Exception) { }
            }
            fun save(img: Img, file: String) {
                val bmp = BitmapIO.toBitmap(img)
                FileOutputStream(File(out, file)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
            fun overlay(base: Img, r: AiRaster): Img {
                val px = IntArray(base.width * base.height) { i ->
                    val x = i % base.width
                    val y = i / base.width
                    val a = r.sample((x + 0.5f) / base.width, (y + 0.5f) / base.height) * 0.65f
                    val c = base.pixels[i]
                    val rr = ((c shr 16 and 255) * (1 - a) + 255 * a).toInt()
                    val gg = ((c shr 8 and 255) * (1 - a) + 40 * a).toInt()
                    val bb = ((c and 255) * (1 - a) + 40 * a).toInt()
                    (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
                return Img(base.width, base.height, px)
            }
            try {
                val t0 = System.currentTimeMillis()
                val loaded = BitmapIO.load(app, Uri.fromFile(File(path)), 1600)
                log("immagine ${loaded.img.width}x${loaded.img.height} (originale ${loaded.originalWidth}x${loaded.originalHeight}) in ${System.currentTimeMillis() - t0} ms")
                val guide = BitmapIO.downscale(loaded.img, 1024)
                val input = BitmapIO.toBitmap(BitmapIO.downscale(loaded.img, 512))
                val faceIn = BitmapIO.toBitmap(guide)
                val seg = AiSegmenter(app)
                log("modelli disponibili: ${seg.modelsAvailable()}")
                val rasters = HashMap<String, AiRaster>()
                var data: AiMasks.SegData? = null
                val targets = listOf(AiMasks.SUBJECT, AiMasks.SKY, AiMasks.BACKGROUND) + AiMasks.PEOPLE + AiMasks.LANDSCAPE.map { it.first }
                for (t in targets) {
                    try {
                        val s = System.currentTimeMillis()
                        data = seg.analyze(input, faceIn, AiSegmenter.needsFor(t.key), data)
                        val r = AiMasks.build(t.key, data!!, guide)
                        val ms = System.currentTimeMillis() - s
                        if (r == null) {
                            log("IA ${t.key}: vuota ($ms ms)")
                        } else {
                            val cov = (0 until r.width * r.height).count { (r.data[it].toInt() and 0xFF) > 128 } * 100f / (r.width * r.height)
                            log("IA ${t.key}: ${r.width}x${r.height} copertura ${"%.1f".format(cov)}% ($ms ms)")
                            rasters[t.key] = r
                            save(overlay(guide, r), "ai_${name}_${t.key.replace(':', '_')}.jpg")
                        }
                    } catch (e: Throwable) {
                        log("IA ${t.key}: ERRORE ${Log.getStackTraceString(e)}")
                    }
                }
                log("volti trovati: ${data?.faces?.size ?: 0}")
                try {
                    val s = System.currentTimeMillis()
                    val conf = seg.objectAt(input, 0.5f, 0.5f)
                    val r = AiMasks.fromConfidence(conf, input.width, input.height, guide)
                    rasters["object:1"] = r
                    save(overlay(guide, r), "ai_${name}_object.jpg")
                    log("IA oggetto al centro: ok (${System.currentTimeMillis() - s} ms)")
                } catch (e: Throwable) {
                    log("IA oggetto: ERRORE ${Log.getStackTraceString(e)}")
                }
                seg.close()

                // upright
                val up = Upright.analyze(loaded.img)
                log("upright: $up")

                // modifica completa con tutti i tipi di maschera
                val masks = listOf(
                    Mask(1, "Soggetto", listOf(MaskComponent(1, ComponentType.AI, aiKey = "subject", aiLabel = "Soggetto")), adjust = LocalAdjust(exposure = 0.4f, clarity = 20f)),
                    Mask(2, "Cielo", listOf(MaskComponent(2, ComponentType.AI, aiKey = "sky"), MaskComponent(3, ComponentType.LINEAR, CombineMode.INTERSECT, x1 = 0.5f, y1 = 0f, x2 = 0.5f, y2 = 0.7f)), adjust = LocalAdjust(exposure = -0.6f, saturation = 25f, temperature = -15f)),
                    Mask(3, "Pelle", listOf(MaskComponent(4, ComponentType.AI, aiKey = "face_skin"), MaskComponent(5, ComponentType.AI, aiKey = "body_skin")), adjust = LocalAdjust(texture = -30f, sharpness = -10f)),
                    Mask(4, "Occhi", listOf(MaskComponent(6, ComponentType.AI, aiKey = "iris"), MaskComponent(7, ComponentType.AI, aiKey = "sclera")), adjust = LocalAdjust(exposure = 0.5f, saturation = 20f)),
                    Mask(5, "Rossi", listOf(MaskComponent(8, ComponentType.COLOR_RANGE, colorSamples = listOf(0.8f, 0.3f, 0.2f)), MaskComponent(9, ComponentType.RADIAL, CombineMode.SUBTRACT, cx = 0.1f, cy = 0.1f)), adjust = LocalAdjust(hue = 15f, colorHue = 30f, colorSat = 20f)),
                    Mask(6, "Ombre", listOf(MaskComponent(10, ComponentType.LUMINANCE_RANGE, lumLo = 0f, lumHi = 0.3f, lumSmooth = 30f)), adjust = LocalAdjust(shadows = 40f, noise = 30f, moire = 20f)),
                    Mask(7, "Pennello", listOf(MaskComponent(11, ComponentType.BRUSH, strokes = listOf(BrushStroke(listOf(0.2f, 0.8f, 0.5f, 0.85f, 0.8f, 0.8f), 0.1f, autoMask = true, colors = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f))))), invert = false, adjust = LocalAdjust(dehaze = 20f, defringe = 40f)),
                    Mask(8, "Oggetto", listOf(MaskComponent(12, ComponentType.AI, aiKey = "object:1")), amount = 150f, adjust = LocalAdjust(whites = 20f, blacks = -20f), invert = true)
                )
                val p = EditParams(
                    exposure = 0.2f, contrast = 15f, highlights = -30f, shadows = 25f, vibrance = 15f,
                    pointColors = listOf(PointColor(1, 20f, 0.6f, 0.5f, hueShift = 10f, satShift = 10f)),
                    gradeShadows = GradeWheel(210f, 15f), calRedHue = 10f, calShadowsTint = 5f,
                    texture = 10f, clarity = 10f, dehaze = 5f, vignetteAmount = -20f, vignetteStyle = 1,
                    sharpAmount = 40f, nrLuminance = 20f, nrLumContrast = 20f, nrColor = 25f,
                    removeCA = true, defringePurple = 5f, tVertical = -10f, constrainCrop = true,
                    straighten = 2f, cropLeft = 0.05f, cropRight = 0.95f,
                    masks = masks
                )
                val scale = max(loaded.img.width, loaded.img.height).toFloat() / max(1, loaded.originalLongEdge)
                val s = System.currentTimeMillis()
                val final = Pipeline.render(loaded.img, p, RenderOptions(resolutionScale = scale, aiRasters = rasters))
                log("sviluppo completo ${final.width}x${final.height} in ${System.currentTimeMillis() - s} ms")
                save(final, "final_$name.jpg")
                val s2 = System.currentTimeMillis()
                val exp = Exporter.render(app, Uri.fromFile(File(path)), p, ExportSettings(longEdge = 2048), rasters) { false }
                FileOutputStream(File(out, "export_$name.jpg")).use { exp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                log("esportazione ${exp.width}x${exp.height} in ${System.currentTimeMillis() - s2} ms")
                exp.recycle()
                log("FINE OK")
            } catch (e: Throwable) {
                log("ERRORE: ${Log.getStackTraceString(e)}")
                log("FINE ERRORE")
            }
        }
    }
}
