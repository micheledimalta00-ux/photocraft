package com.michele.luce.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.michele.luce.engine.AiMasks
import java.io.Closeable
import java.nio.ByteOrder

/**
 * Esegue i modelli di segmentazione sul telefono (MediaPipe, tutto offline).
 * Le uscite grezze vengono poi combinate da [AiMasks].
 */
class AiSegmenter(context: Context) : Closeable {

    private val app = context.applicationContext

    private var deeplab: ImageSegmenter? = null
    private var selfie: ImageSegmenter? = null
    private var ade: ImageSegmenter? = null
    private var interactive: InteractiveSegmenter? = null
    private var faces: FaceLandmarker? = null

    enum class Need { DEEPLAB, SELFIE, ADE, FACES }

    fun modelsAvailable(): Boolean = try {
        val list = app.assets.list("models")?.toSet() ?: emptySet()
        MODELS.all { it in list }
    } catch (e: Exception) {
        false
    }

    private fun base(name: String): BaseOptions =
        BaseOptions.builder().setModelAssetPath("models/$name").build()

    private fun segmenter(name: String, category: Boolean): ImageSegmenter {
        val opts = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base(name))
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(category)
            .setOutputConfidenceMasks(!category)
            .build()
        return ImageSegmenter.createFromOptions(app, opts)
    }

    /**
     * MediaPipe ricicla la bitmap quando l'MPImage viene chiuso: si lavora sempre su una copia,
     * così la bitmap del chiamante resta valida per le chiamate successive.
     */
    private fun mp(bitmap: Bitmap): MPImage {
        check(!bitmap.isRecycled) { "Immagine non più disponibile" }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException("Memoria insufficiente")
        return BitmapImageBuilder(copy).build()
    }

    private fun floats(img: MPImage): FloatArray {
        val buf = ByteBufferExtractor.extract(img)
        buf.rewind()
        val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    private fun bytes(img: MPImage): ByteArray {
        val buf = ByteBufferExtractor.extract(img)
        buf.rewind()
        val out = ByteArray(buf.remaining())
        buf.get(out)
        return out
    }

    private fun confidences(seg: ImageSegmenter, bitmap: Bitmap, w: Int, h: Int): Array<FloatArray> {
        val input = mp(bitmap)
        val res = try { seg.segment(input) } finally { input.close() }
        val masks = res.confidenceMasks().orElse(null) ?: throw IllegalStateException("Il modello non ha prodotto maschere")
        return Array(masks.size) { i ->
            val m = masks[i]
            val f = floats(m)
            m.close()
            if (f.size >= w * h) f.copyOf(w * h) else FloatArray(w * h)
        }
    }

    /**
     * Esegue i modelli richiesti. [input] è l'immagine ridotta per i modelli,
     * [faceInput] un'immagine più grande per il rilevamento dei volti.
     */
    @Synchronized
    fun analyze(input: Bitmap, faceInput: Bitmap, needs: Set<Need>, into: AiMasks.SegData?): AiMasks.SegData {
        val w = input.width
        val h = input.height
        val data = into?.takeIf { it.w == w && it.h == h } ?: AiMasks.SegData(w, h)
        run {
            if (Need.DEEPLAB in needs && data.deeplab == null) {
                val s = deeplab ?: segmenter("deeplab_v3.tflite", false).also { deeplab = it }
                data.deeplab = confidences(s, input, w, h)
            }
            if (Need.SELFIE in needs && data.selfie == null) {
                val s = selfie ?: segmenter("selfie_multiclass.tflite", false).also { selfie = it }
                data.selfie = confidences(s, input, w, h)
            }
            if (Need.ADE in needs && data.ade == null) {
                val s = ade ?: segmenter("ade20k.tflite", true).also { ade = it }
                val img = mp(input)
                val res = try { s.segment(img) } finally { img.close() }
                val cat = res.categoryMask().orElse(null) ?: throw IllegalStateException("Il modello del paesaggio non ha prodotto risultati")
                val b = bytes(cat)
                cat.close()
                data.ade = if (b.size >= w * h) b.copyOf(w * h) else ByteArray(w * h)
            }
            if (Need.FACES in needs && !data.facesDone) {
                val f = faces ?: FaceLandmarker.createFromOptions(
                    app,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base("face_landmarker.task"))
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumFaces(8)
                        .setMinFaceDetectionConfidence(0.4f)
                        .setMinFacePresenceConfidence(0.4f)
                        .build()
                ).also { faces = it }
                val fimg = mp(faceInput)
                try {
                    val res = f.detect(fimg)
                    data.faces = res.faceLandmarks().map { lms ->
                        val arr = FloatArray(478 * 2)
                        for (i in 0 until minOf(478, lms.size)) {
                            arr[i * 2] = lms[i].x()
                            arr[i * 2 + 1] = lms[i].y()
                        }
                        arr
                    }
                } finally {
                    fimg.close()
                }
                data.facesDone = true
            }
        }
        return data
    }

    /** Selezione dell'oggetto nel punto (x, y) normalizzato. */
    @Synchronized
    fun objectAt(input: Bitmap, x: Float, y: Float): FloatArray {
        val s = interactive ?: InteractiveSegmenter.createFromOptions(
            app,
            InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                .setBaseOptions(base("magic_touch.tflite"))
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build()
        ).also { interactive = it }
        val image = mp(input)
        try {
            val roi = InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
            val res = s.segment(image, roi)
            val masks = res.confidenceMasks().orElse(null) ?: throw IllegalStateException("Nessun oggetto trovato")
            val w = input.width
            val h = input.height
            val f = floats(masks[0])
            masks.forEach { it.close() }
            return if (f.size >= w * h) f.copyOf(w * h) else FloatArray(w * h)
        } finally {
            image.close()
        }
    }

    @Synchronized
    override fun close() {
        listOf(deeplab, selfie, ade, interactive, faces).forEach {
            try { it?.close() } catch (_: Exception) { }
        }
        deeplab = null; selfie = null; ade = null; interactive = null; faces = null
    }

    companion object {
        val MODELS = listOf("deeplab_v3.tflite", "selfie_multiclass.tflite", "ade20k.tflite", "magic_touch.tflite", "face_landmarker.task")

        /** Modelli necessari per una selezione. */
        fun needsFor(key: String): Set<Need> = when {
            key == "subject" || key == "background" -> setOf(Need.DEEPLAB, Need.SELFIE)
            key == "sky" || key.startsWith("land:") -> setOf(Need.ADE)
            key == "person" || key == "body_skin" || key == "hair" || key == "clothes" -> setOf(Need.DEEPLAB, Need.SELFIE)
            key == "face_skin" -> setOf(Need.DEEPLAB, Need.SELFIE, Need.FACES)
            key in setOf("eyebrows", "sclera", "iris", "lips", "teeth") -> setOf(Need.FACES)
            else -> emptySet()
        }
    }
}
