package com.michele.luce.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Costruzione delle selezioni IA a partire dalle uscite grezze dei modelli
 * (DeepLab, segmentazione persone a 6 classi, ADE20K, punti del viso, selezione oggetto).
 * Tutto in Kotlin puro: i modelli girano nel livello Android, qui si combinano e si rifiniscono.
 */
object AiMasks {

    data class Target(val key: String, val label: String, val group: String)

    // gruppi come in Lightroom
    const val G_MAIN = "Selezioni"
    const val G_PEOPLE = "Persone"
    const val G_LAND = "Paesaggio"

    val SUBJECT = Target("subject", "Soggetto", G_MAIN)
    val SKY = Target("sky", "Cielo", G_MAIN)
    val BACKGROUND = Target("background", "Sfondo", G_MAIN)

    val PEOPLE = listOf(
        Target("person", "Persona intera", G_PEOPLE),
        Target("face_skin", "Pelle del viso", G_PEOPLE),
        Target("body_skin", "Pelle del corpo", G_PEOPLE),
        Target("eyebrows", "Sopracciglia", G_PEOPLE),
        Target("sclera", "Sclera", G_PEOPLE),
        Target("iris", "Iride e pupilla", G_PEOPLE),
        Target("lips", "Labbra", G_PEOPLE),
        Target("teeth", "Denti", G_PEOPLE),
        Target("hair", "Capelli", G_PEOPLE),
        Target("clothes", "Abbigliamento", G_PEOPLE)
    )

    /** Classi ADE20K (indice nell'uscita del modello) per ogni categoria di paesaggio. */
    val LANDSCAPE: List<Pair<Target, IntArray>> = listOf(
        Target("land:sky", "Cielo", G_LAND) to intArrayOf(3),
        Target("land:water", "Acqua", G_LAND) to intArrayOf(22, 27, 61, 129, 110, 114, 105),
        Target("land:vegetation", "Vegetazione", G_LAND) to intArrayOf(5, 10, 18, 67, 73),
        Target("land:mountains", "Montagne e colline", G_LAND) to intArrayOf(17, 69),
        Target("land:architecture", "Architettura", G_LAND) to intArrayOf(2, 26, 49, 85, 62, 80, 43, 1, 33, 87, 89, 107, 39, 96, 141),
        Target("land:natural_ground", "Terreno naturale", G_LAND) to intArrayOf(14, 47, 35, 30, 95, 92),
        Target("land:artificial_ground", "Terreno artificiale", G_LAND) to intArrayOf(7, 12, 4, 53, 55, 54, 122, 60, 29, 52, 102, 97)
    )

    fun labelFor(key: String): String =
        (listOf(SUBJECT, SKY, BACKGROUND) + PEOPLE + LANDSCAPE.map { it.first }).firstOrNull { it.key == key }?.label
            ?: if (key.startsWith("object")) "Oggetto" else "Selezione"

    // indici DeepLab (PASCAL VOC)
    const val DL_BACKGROUND = 0
    const val DL_PERSON = 15

    // indici della segmentazione persone (selfie multiclass)
    const val SF_BACKGROUND = 0
    const val SF_HAIR = 1
    const val SF_BODY_SKIN = 2
    const val SF_FACE_SKIN = 3
    const val SF_CLOTHES = 4
    const val SF_OTHERS = 5

    // contorni del viso (indici dei 478 punti)
    val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
    val LEFT_EYE = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)
    val RIGHT_EYE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    val LEFT_BROW = intArrayOf(276, 283, 282, 295, 285, 336, 296, 334, 293, 300)
    val RIGHT_BROW = intArrayOf(46, 53, 52, 65, 55, 107, 66, 105, 63, 70)
    const val RIGHT_IRIS_CENTER = 468
    val RIGHT_IRIS = intArrayOf(469, 470, 471, 472)
    const val LEFT_IRIS_CENTER = 473
    val LEFT_IRIS = intArrayOf(474, 475, 476, 477)

    /** Uscite dei modelli, tutte alla stessa risoluzione w×h (immagine di ingresso ai modelli). */
    class SegData(val w: Int, val h: Int) {
        var deeplab: Array<FloatArray>? = null       // 21 confidenze
        var selfie: Array<FloatArray>? = null        // 6 confidenze
        var ade: ByteArray? = null                   // categoria (indice) per pixel
        var faces: List<FloatArray> = emptyList()    // 478×2 coordinate normalizzate per volto
        var facesDone = false
    }

    // ------------------------------------------------------------------

    private fun blurred(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val c = src.copyOf()
        Pipeline.blur(c, w, h, r)
        return c
    }

    /** Porta i valori quasi binari a una maschera morbida e la rifinisce sui bordi della guida. */
    private fun finish(coarse: FloatArray, w: Int, h: Int, guide: Img, preBlur: Int = 1): AiRaster {
        val c = if (preBlur > 0) blurred(coarse, w, h, preBlur) else coarse
        return MaskEngine.refineToRaster(c, w, h, guide)
    }

    private fun personGate(d: SegData): FloatArray? {
        val dl = d.deeplab ?: return null
        val person = dl[DL_PERSON]
        val g = blurred(person, d.w, d.h, max(2, max(d.w, d.h) / 60))
        return FloatArray(g.size) { MaskEngine.smooth(0.08f, 0.3f, g[it] * 1.6f) }
    }

    /** Soggetto: tutto ciò che non è sfondo per DeepLab. Null se non trova nulla di rilevante. */
    fun subjectCoarse(d: SegData): FloatArray? {
        val dl = d.deeplab ?: return null
        val bg = dl[DL_BACKGROUND]
        val fg = FloatArray(bg.size) { (1f - bg[it]).coerceIn(0f, 1f) }
        // unisce le persone viste dal modello dedicato
        d.selfie?.let { sf ->
            val gate = personGate(d)
            if (gate != null) for (i in fg.indices) fg[i] = max(fg[i], (1f - sf[SF_BACKGROUND][i]) * gate[i])
        }
        // serve una parte significativa sicuramente in primo piano
        val strong = fg.count { it > 0.5f }.toFloat() / fg.size
        return if (strong < 0.01f) null else fg
    }

    fun build(key: String, d: SegData, guide: Img): AiRaster? {
        val w = d.w
        val h = d.h
        return when {
            key == "subject" -> subjectCoarse(d)?.let { finish(it, w, h, guide) }
            key == "background" -> subjectCoarse(d)?.let { s -> finish(FloatArray(s.size) { 1f - s[it] }, w, h, guide) }
            key == "sky" || key.startsWith("land:") -> {
                val ade = d.ade ?: return null
                val classes = if (key == "sky") intArrayOf(3) else LANDSCAPE.firstOrNull { it.first.key == key }?.second ?: return null
                val set = BooleanArray(256)
                classes.forEach { if (it in 0..255) set[it] = true }
                val m = FloatArray(w * h) { if (set[ade[it].toInt() and 0xFF]) 1f else 0f }
                finish(m, w, h, guide, preBlur = 2)
            }
            key == "person" -> {
                val dl = d.deeplab ?: return null
                val m = dl[DL_PERSON].copyOf()
                val sf = d.selfie
                val gate = personGate(d)
                if (sf != null && gate != null) for (i in m.indices) m[i] = max(m[i], (1f - sf[SF_BACKGROUND][i]) * gate[i])
                finish(m, w, h, guide)
            }
            key == "face_skin" || key == "body_skin" || key == "hair" || key == "clothes" -> {
                val sf = d.selfie ?: return null
                val idx = when (key) {
                    "face_skin" -> SF_FACE_SKIN
                    "body_skin" -> SF_BODY_SKIN
                    "hair" -> SF_HAIR
                    else -> SF_CLOTHES
                }
                val gate = personGate(d) ?: FloatArray(w * h) { 1f }
                val m = FloatArray(w * h) { sf[idx][it] * gate[it] }
                val r = finish(m, w, h, guide)
                if (key == "face_skin" && d.faces.isNotEmpty()) subtractFeatures(r, d.faces) else r
            }
            key in setOf("eyebrows", "sclera", "iris", "lips", "teeth") -> faceFeature(key, d.faces, guide)
            else -> null
        }
    }

    /** Selezione oggetto dalla confidenza del modello interattivo. */
    fun fromConfidence(conf: FloatArray, w: Int, h: Int, guide: Img): AiRaster = finish(conf, w, h, guide)

    // ------------------------------------------------------------------
    // Parti del viso dai punti caratteristici
    // ------------------------------------------------------------------

    private fun poly(face: FloatArray, idx: IntArray): FloatArray {
        val out = FloatArray(idx.size * 2)
        for (i in idx.indices) {
            out[i * 2] = face[idx[i] * 2]
            out[i * 2 + 1] = face[idx[i] * 2 + 1]
        }
        return out
    }

    private fun irisCircle(face: FloatArray, center: Int, ring: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val cx = face[center * 2] * w
        val cy = face[center * 2 + 1] * h
        var r = 0f
        for (i in ring) {
            val dx = face[i * 2] * w - cx
            val dy = face[i * 2 + 1] * h - cy
            r += sqrt(dx * dx + dy * dy)
        }
        return Triple(cx, cy, r / ring.size)
    }

    fun faceFeature(key: String, faces: List<FloatArray>, guide: Img): AiRaster? {
        if (faces.isEmpty()) return null
        val w = guide.width
        val h = guide.height
        val m = FloatArray(w * h)
        val soft = max(1f, max(w, h) / 900f)
        for (f in faces) {
            if (f.size < 478 * 2) continue
            when (key) {
                "lips" -> {
                    val outer = FloatArray(w * h)
                    MaskEngine.fillPolygon(outer, w, h, poly(f, LIPS_OUTER), soft)
                    val inner = FloatArray(w * h)
                    MaskEngine.fillPolygon(inner, w, h, poly(f, LIPS_INNER), soft)
                    for (i in m.indices) m[i] = max(m[i], outer[i] * (1f - inner[i]))
                }
                "teeth" -> {
                    val inner = FloatArray(w * h)
                    MaskEngine.fillPolygon(inner, w, h, poly(f, LIPS_INNER), soft)
                    val tmp = FloatArray(3)
                    for (i in m.indices) {
                        if (inner[i] <= 0f) continue
                        val c = guide.pixels[i]
                        Pipeline.rgbToHsl((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f, tmp)
                        val bright = MaskEngine.smooth(0.24f, 0.46f, tmp[2])
                        val pale = 1f - MaskEngine.smooth(0.38f, 0.72f, tmp[1])
                        m[i] = max(m[i], inner[i] * bright * pale)
                    }
                }
                "eyebrows" -> {
                    MaskEngine.fillPolygon(m, w, h, poly(f, LEFT_BROW), soft * 1.5f)
                    MaskEngine.fillPolygon(m, w, h, poly(f, RIGHT_BROW), soft * 1.5f)
                }
                "iris" -> {
                    val (lx, ly, lr) = irisCircle(f, LEFT_IRIS_CENTER, LEFT_IRIS, w, h)
                    val (rx, ry, rr) = irisCircle(f, RIGHT_IRIS_CENTER, RIGHT_IRIS, w, h)
                    val eyes = FloatArray(w * h)
                    MaskEngine.fillPolygon(eyes, w, h, poly(f, LEFT_EYE), soft)
                    MaskEngine.fillPolygon(eyes, w, h, poly(f, RIGHT_EYE), soft)
                    val circ = FloatArray(w * h)
                    MaskEngine.fillCircle(circ, w, h, lx, ly, lr, soft)
                    MaskEngine.fillCircle(circ, w, h, rx, ry, rr, soft)
                    // l'iride è visibile solo dentro la rima palpebrale
                    for (i in m.indices) m[i] = max(m[i], circ[i] * eyes[i])
                }
                "sclera" -> {
                    val eyes = FloatArray(w * h)
                    MaskEngine.fillPolygon(eyes, w, h, poly(f, LEFT_EYE), soft)
                    MaskEngine.fillPolygon(eyes, w, h, poly(f, RIGHT_EYE), soft)
                    val (lx, ly, lr) = irisCircle(f, LEFT_IRIS_CENTER, LEFT_IRIS, w, h)
                    val (rx, ry, rr) = irisCircle(f, RIGHT_IRIS_CENTER, RIGHT_IRIS, w, h)
                    val circ = FloatArray(w * h)
                    MaskEngine.fillCircle(circ, w, h, lx, ly, lr * 1.05f, soft)
                    MaskEngine.fillCircle(circ, w, h, rx, ry, rr * 1.05f, soft)
                    for (i in m.indices) m[i] = max(m[i], eyes[i] * (1f - circ[i]))
                }
            }
        }
        if (m.all { it <= 0f }) return null
        return MaskEngine.toRaster(m, w, h)
    }

    /** Toglie occhi, sopracciglia e labbra dalla pelle del viso. */
    private fun subtractFeatures(r: AiRaster, faces: List<FloatArray>): AiRaster {
        val w = r.width
        val h = r.height
        val feat = FloatArray(w * h)
        val soft = max(1.5f, max(w, h) / 600f)
        for (f in faces) {
            if (f.size < 478 * 2) continue
            MaskEngine.fillPolygon(feat, w, h, poly(f, LEFT_EYE), soft)
            MaskEngine.fillPolygon(feat, w, h, poly(f, RIGHT_EYE), soft)
            MaskEngine.fillPolygon(feat, w, h, poly(f, LEFT_BROW), soft)
            MaskEngine.fillPolygon(feat, w, h, poly(f, RIGHT_BROW), soft)
            MaskEngine.fillPolygon(feat, w, h, poly(f, LIPS_OUTER), soft)
        }
        val out = ByteArray(w * h) { i ->
            val v = (r.data[i].toInt() and 0xFF) * (1f - min(1f, feat[i]))
            v.toInt().coerceIn(0, 255).toByte()
        }
        return AiRaster(w, h, out)
    }
}
