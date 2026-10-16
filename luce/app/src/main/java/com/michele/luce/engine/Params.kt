package com.michele.luce.engine

/**
 * Tutti i parametri di modifica, non distruttivi. Le scale seguono quelle di Lightroom:
 * la maggior parte dei cursori va da -100 a +100 con zero neutro.
 */

data class CurvePoint(val x: Float, val y: Float)

data class ToneCurve(
    val points: List<CurvePoint> = IDENTITY
) {
    val isIdentity: Boolean
        get() = points.size == 2 &&
            points[0].x == 0f && points[0].y == 0f &&
            points[1].x == 1f && points[1].y == 1f

    companion object {
        val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

/** Ruota per il color grading: tonalità 0..360, saturazione 0..100, luminanza -100..100. */
data class GradeWheel(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isNeutral: Boolean get() = sat == 0f && lum == 0f
}

enum class Treatment { COLOR, BW }

enum class MaskType { LINEAR, RADIAL, BRUSH }

/** Un tratto di pennello. I punti sono coppie (u, v) normalizzate sulla tela prima del ritaglio. */
data class BrushStroke(
    val points: List<Float>,
    val size: Float = 0.05f,     // diametro come frazione del lato lungo
    val feather: Float = 0.5f,   // 0..1
    val flow: Float = 1f,        // 0..1
    val erase: Boolean = false
)

data class LocalAdjust(
    val exposure: Float = 0f,     // -4..4 stop
    val contrast: Float = 0f,     // -100..100
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val texture: Float = 0f
) {
    val isNeutral: Boolean get() = this == LocalAdjust()
}

data class Mask(
    val id: Long,
    val name: String,
    val type: MaskType,
    // Lineare: l'effetto è pieno in (x1,y1) e nullo in (x2,y2)
    val x1: Float = 0.5f, val y1: Float = 0.3f,
    val x2: Float = 0.5f, val y2: Float = 0.6f,
    // Radiale: centro e semiassi (frazioni di larghezza/altezza della tela), angolo in gradi
    val cx: Float = 0.5f, val cy: Float = 0.5f,
    val rx: Float = 0.25f, val ry: Float = 0.25f,
    val angle: Float = 0f,
    val feather: Float = 50f,     // 0..100
    val invert: Boolean = false,
    val strokes: List<BrushStroke> = emptyList(),
    val adjust: LocalAdjust = LocalAdjust(),
    val visible: Boolean = true
)

data class EditParams(
    // Profilo
    val profile: String = Profiles.DEFAULT,
    val profileAmount: Float = 100f,   // 0..200
    val treatment: Treatment = Treatment.COLOR,

    // Luce
    val exposure: Float = 0f,          // -5..5 stop
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,

    // Curva di viraggio
    val curveMaster: ToneCurve = ToneCurve(),
    val curveRed: ToneCurve = ToneCurve(),
    val curveGreen: ToneCurve = ToneCurve(),
    val curveBlue: ToneCurve = ToneCurve(),
    // Curva parametrica (luci, chiari, scuri, ombre) -100..100
    val parHighlights: Float = 0f,
    val parLights: Float = 0f,
    val parDarks: Float = 0f,
    val parShadows: Float = 0f,

    // Colore
    val temperature: Float = 0f,       // -100..100
    val tint: Float = 0f,              // -100..100
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val hslHue: List<Float> = ZERO8,
    val hslSat: List<Float> = ZERO8,
    val hslLum: List<Float> = ZERO8,   // in bianco e nero funge da miscela B&N

    // Color grading
    val gradeShadows: GradeWheel = GradeWheel(),
    val gradeMidtones: GradeWheel = GradeWheel(),
    val gradeHighlights: GradeWheel = GradeWheel(),
    val gradeGlobal: GradeWheel = GradeWheel(),
    val gradeBlending: Float = 50f,    // 0..100
    val gradeBalance: Float = 0f,      // -100..100

    // Effetti
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignetteAmount: Float = 0f,    // -100..100
    val vignetteMidpoint: Float = 50f, // 0..100
    val vignetteRoundness: Float = 0f, // -100..100
    val vignetteFeather: Float = 50f,  // 0..100
    val vignetteHighlights: Float = 0f,// 0..100
    val grainAmount: Float = 0f,       // 0..100
    val grainSize: Float = 25f,        // 0..100
    val grainRoughness: Float = 50f,   // 0..100

    // Dettaglio
    val sharpAmount: Float = 0f,       // 0..150
    val sharpRadius: Float = 1f,       // 0.5..3.0
    val sharpDetail: Float = 25f,      // 0..100
    val sharpMasking: Float = 0f,      // 0..100
    val nrLuminance: Float = 0f,       // 0..100
    val nrLumDetail: Float = 50f,      // 0..100
    val nrColor: Float = 0f,           // 0..100
    val nrColorDetail: Float = 50f,    // 0..100

    // Ottica
    val lensDistortion: Float = 0f,    // -100..100
    val lensVignette: Float = 0f,      // -100..100 (correzione)
    val chromaticAberration: Float = 0f, // -100..100

    // Geometria
    val rotate90: Int = 0,             // 0..3, quarti di giro in senso orario
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,        // -45..45 gradi
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val aspect: String = "Libero",

    // Maschere
    val masks: List<Mask> = emptyList()
) {
    fun hasCrop(): Boolean = cropLeft > 0f || cropTop > 0f || cropRight < 1f || cropBottom < 1f

    /** Mantiene solo le regolazioni (senza geometria), usato da predefiniti e copia/incolla. */
    fun withAdjustmentsFrom(other: EditParams): EditParams = other.copy(
        rotate90 = rotate90, flipH = flipH, flipV = flipV, straighten = straighten,
        cropLeft = cropLeft, cropTop = cropTop, cropRight = cropRight, cropBottom = cropBottom,
        aspect = aspect, masks = masks
    )

    fun resetGeometry(): EditParams = copy(
        rotate90 = 0, flipH = false, flipV = false, straighten = 0f,
        cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f, aspect = "Libero"
    )

    fun isDefault(): Boolean = this == EditParams()

    companion object {
        val ZERO8: List<Float> = List(8) { 0f }
        val HSL_NAMES = listOf("Rosso", "Arancione", "Giallo", "Verde", "Acqua", "Blu", "Viola", "Magenta")
        /** Centri delle bande HSL in gradi. */
        val HSL_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
    }
}

fun List<Float>.with(index: Int, value: Float): List<Float> =
    mapIndexed { i, v -> if (i == index) value else v }
