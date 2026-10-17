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

/** Colore puntuale: un colore campionato e le variazioni da applicargli. */
data class PointColor(
    val id: Long,
    val hue: Float,          // 0..360 colore campionato
    val sat: Float,          // 0..1
    val lum: Float,          // 0..1
    val hueShift: Float = 0f,   // -100..100
    val satShift: Float = 0f,   // -100..100
    val lumShift: Float = 0f,   // -100..100
    val variance: Float = 0f,   // -100..100
    val range: Float = 50f,     // 0..100
    val hueRange: Float = 50f,  // 0..100
    val satRange: Float = 50f,  // 0..100
    val lumRange: Float = 50f   // 0..100
) {
    val isNeutral: Boolean get() = hueShift == 0f && satShift == 0f && lumShift == 0f && variance == 0f
}

// ---------------- Maschere ----------------

enum class ComponentType { BRUSH, LINEAR, RADIAL, COLOR_RANGE, LUMINANCE_RANGE, AI }

enum class CombineMode { ADD, SUBTRACT, INTERSECT }

/**
 * Un tratto di pennello. I punti sono coppie (u, v) normalizzate sulla tela prima del ritaglio.
 * Con [autoMask] il tratto segue i bordi: [colors] contiene il colore (r, g, b) campionato in ogni punto.
 */
data class BrushStroke(
    val points: List<Float>,
    val size: Float = 0.05f,     // diametro come frazione del lato lungo
    val feather: Float = 0.5f,   // 0..1
    val flow: Float = 1f,        // 0..1
    val erase: Boolean = false,
    val density: Float = 1f,     // 0..1 opacità massima
    val autoMask: Boolean = false,
    val colors: List<Float> = emptyList()
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
    val hue: Float = 0f,          // -180..180 gradi
    val saturation: Float = 0f,
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val sharpness: Float = 0f,    // -100..100
    val noise: Float = 0f,        // -100..100 (positivo = riduce il disturbo)
    val moire: Float = 0f,        // -100..100
    val defringe: Float = 0f,     // -100..100
    val colorHue: Float = 0f,     // 0..360 colore di viraggio
    val colorSat: Float = 0f      // 0..100
) {
    val isNeutral: Boolean get() = this == LocalAdjust()

    fun scaled(f: Float): LocalAdjust = if (f == 1f) this else copy(
        exposure = exposure * f, contrast = contrast * f, highlights = highlights * f, shadows = shadows * f,
        whites = whites * f, blacks = blacks * f, temperature = temperature * f, tint = tint * f,
        hue = hue * f, saturation = saturation * f, texture = texture * f, clarity = clarity * f,
        dehaze = dehaze * f, sharpness = sharpness * f, noise = noise * f, moire = moire * f,
        defringe = defringe * f, colorSat = colorSat * f
    )
}

data class MaskComponent(
    val id: Long,
    val type: ComponentType,
    val mode: CombineMode = CombineMode.ADD,
    val invert: Boolean = false,
    // Lineare: l'effetto è pieno in (x1,y1) e nullo in (x2,y2)
    val x1: Float = 0.5f, val y1: Float = 0.3f,
    val x2: Float = 0.5f, val y2: Float = 0.6f,
    // Radiale: centro e semiassi (frazioni di larghezza/altezza della tela), angolo in gradi
    val cx: Float = 0.5f, val cy: Float = 0.5f,
    val rx: Float = 0.25f, val ry: Float = 0.25f,
    val angle: Float = 0f,
    val feather: Float = 50f,     // 0..100
    // Pennello
    val strokes: List<BrushStroke> = emptyList(),
    // Intervallo colore: campioni (r, g, b) 0..1, fino a 5
    val colorSamples: List<Float> = emptyList(),
    val refine: Float = 50f,      // 0..100
    // Intervallo luminanza (0..1)
    val lumLo: Float = 0.25f,
    val lumHi: Float = 0.75f,
    val lumFeatherLo: Float = 0.1f,
    val lumFeatherHi: Float = 0.1f,
    val lumSmooth: Float = 0f,    // 0..100
    // Selezione IA: chiave del raster calcolato sull'immagine
    val aiKey: String = "",
    val aiLabel: String = "",
    val aiX: Float = 0.5f,        // punto toccato per "Oggetto" (coordinate della sorgente)
    val aiY: Float = 0.5f
) {
    fun label(): String = when (type) {
        ComponentType.BRUSH -> "Pennello"
        ComponentType.LINEAR -> "Gradiente lineare"
        ComponentType.RADIAL -> "Gradiente radiale"
        ComponentType.COLOR_RANGE -> "Intervallo colore"
        ComponentType.LUMINANCE_RANGE -> "Intervallo luminanza"
        ComponentType.AI -> aiLabel.ifBlank { "Selezione" }
    }
}

data class Mask(
    val id: Long,
    val name: String,
    val components: List<MaskComponent>,
    val amount: Float = 100f,     // 0..200 % sulle regolazioni
    val adjust: LocalAdjust = LocalAdjust(),
    val visible: Boolean = true,
    val invert: Boolean = false
) {
    val isActive: Boolean get() = visible && components.isNotEmpty() && amount > 0f && !adjust.isNeutral
}

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
    // Curva parametrica (luci, chiari, scuri, ombre) -100..100 e divisioni delle regioni 0..100
    val parHighlights: Float = 0f,
    val parLights: Float = 0f,
    val parDarks: Float = 0f,
    val parShadows: Float = 0f,
    val parSplitShadows: Float = 25f,
    val parSplitMidtones: Float = 50f,
    val parSplitHighlights: Float = 75f,

    // Colore
    val temperature: Float = 0f,       // -100..100
    val tint: Float = 0f,              // -100..100
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val hslHue: List<Float> = ZERO8,
    val hslSat: List<Float> = ZERO8,
    val hslLum: List<Float> = ZERO8,   // in bianco e nero funge da miscela B&N
    val pointColors: List<PointColor> = emptyList(),

    // Color grading
    val gradeShadows: GradeWheel = GradeWheel(),
    val gradeMidtones: GradeWheel = GradeWheel(),
    val gradeHighlights: GradeWheel = GradeWheel(),
    val gradeGlobal: GradeWheel = GradeWheel(),
    val gradeBlending: Float = 50f,    // 0..100
    val gradeBalance: Float = 0f,      // -100..100

    // Calibrazione
    val calShadowsTint: Float = 0f,    // -100..100
    val calRedHue: Float = 0f,
    val calRedSat: Float = 0f,
    val calGreenHue: Float = 0f,
    val calGreenSat: Float = 0f,
    val calBlueHue: Float = 0f,
    val calBlueSat: Float = 0f,

    // Effetti
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignetteStyle: Int = 0,        // 0 priorità luci, 1 priorità colore, 2 sovrapposizione pittura
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
    val nrLumContrast: Float = 0f,     // 0..100
    val nrColor: Float = 0f,           // 0..100
    val nrColorDetail: Float = 50f,    // 0..100
    val nrColorSmooth: Float = 50f,    // 0..100

    // Ottica
    val removeCA: Boolean = false,
    val lensDistortion: Float = 0f,    // -100..100
    val lensVignette: Float = 0f,      // -100..100 (correzione)
    val chromaticAberration: Float = 0f, // -100..100
    val defringePurple: Float = 0f,    // 0..20
    val defringePurpleHueLo: Float = 30f, // 0..100
    val defringePurpleHueHi: Float = 70f,
    val defringeGreen: Float = 0f,     // 0..20
    val defringeGreenHueLo: Float = 40f,
    val defringeGreenHueHi: Float = 60f,

    // Geometria: Upright (valori calcolati) e trasformazioni manuali
    val uprightMode: String = "Off",
    val uprightAngle: Float = 0f,      // gradi
    val uprightV: Float = 0f,          // prospettiva verticale (unità normalizzate)
    val uprightH: Float = 0f,
    val tVertical: Float = 0f,         // -100..100
    val tHorizontal: Float = 0f,       // -100..100
    val tRotate: Float = 0f,           // -10..10 gradi
    val tAspect: Float = 0f,           // -100..100
    val tScale: Float = 100f,          // 50..150
    val tOffsetX: Float = 0f,          // -100..100
    val tOffsetY: Float = 0f,          // -100..100
    val constrainCrop: Boolean = false,

    // Ritaglio
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

    fun hasTransform(): Boolean = uprightAngle != 0f || uprightV != 0f || uprightH != 0f ||
        tVertical != 0f || tHorizontal != 0f || tRotate != 0f || tAspect != 0f || tScale != 100f ||
        tOffsetX != 0f || tOffsetY != 0f

    /** Mantiene geometria e maschere di questa immagine, prende le regolazioni da [other]. */
    fun withAdjustmentsFrom(other: EditParams): EditParams = other.copy(
        rotate90 = rotate90, flipH = flipH, flipV = flipV, straighten = straighten,
        cropLeft = cropLeft, cropTop = cropTop, cropRight = cropRight, cropBottom = cropBottom,
        aspect = aspect, masks = masks,
        uprightMode = uprightMode, uprightAngle = uprightAngle, uprightV = uprightV, uprightH = uprightH,
        tVertical = tVertical, tHorizontal = tHorizontal, tRotate = tRotate, tAspect = tAspect,
        tScale = tScale, tOffsetX = tOffsetX, tOffsetY = tOffsetY, constrainCrop = constrainCrop
    )

    fun resetGeometry(): EditParams = copy(
        rotate90 = 0, flipH = false, flipV = false, straighten = 0f,
        cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f, aspect = "Libero"
    )

    fun resetTransform(): EditParams = copy(
        uprightMode = "Off", uprightAngle = 0f, uprightV = 0f, uprightH = 0f,
        tVertical = 0f, tHorizontal = 0f, tRotate = 0f, tAspect = 0f, tScale = 100f,
        tOffsetX = 0f, tOffsetY = 0f, constrainCrop = false
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
