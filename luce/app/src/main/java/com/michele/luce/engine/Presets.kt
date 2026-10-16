package com.michele.luce.engine

data class Preset(
    val id: String,
    val name: String,
    val group: String,
    val params: EditParams,
    val builtIn: Boolean = true
)

object Presets {

    private fun curve(vararg xy: Float): ToneCurve =
        ToneCurve((xy.indices step 2).map { CurvePoint(xy[it], xy[it + 1]) })

    private fun hsl(vararg v: Float): List<Float> = List(8) { v.getOrElse(it) { 0f } }

    val builtIn: List<Preset> = listOf(
        // Ritratto
        Preset("p_port_soft", "Pelle morbida", "Ritratto", EditParams(
            profile = "Ritratto", exposure = 0.15f, contrast = -8f, highlights = -20f, shadows = 15f,
            texture = -18f, clarity = -8f, vibrance = 8f, hslSat = hsl(-5f, -10f), hslLum = hsl(5f, 10f),
            temperature = 4f
        )),
        Preset("p_port_bright", "Luminoso", "Ritratto", EditParams(
            exposure = 0.35f, contrast = -5f, highlights = -35f, shadows = 30f, whites = 10f, blacks = 5f,
            vibrance = 12f, texture = -10f, curveMaster = curve(0f, 0.04f, 0.5f, 0.54f, 1f, 1f)
        )),
        Preset("p_port_moody", "Ritratto intenso", "Ritratto", EditParams(
            exposure = -0.2f, contrast = 20f, highlights = -40f, shadows = -10f, blacks = -15f,
            clarity = 12f, vibrance = -5f, saturation = -10f, vignetteAmount = -25f,
            gradeShadows = GradeWheel(210f, 18f), gradeHighlights = GradeWheel(35f, 15f)
        )),
        // Paesaggio
        Preset("p_land_vivid", "Paesaggio vivido", "Paesaggio", EditParams(
            profile = "Paesaggio", contrast = 15f, highlights = -45f, shadows = 35f, whites = 12f, blacks = -12f,
            clarity = 20f, dehaze = 12f, vibrance = 25f, hslSat = hsl(0f, 5f, 10f, 15f, 10f, 15f),
            hslLum = hsl(0f, 0f, 0f, -5f, 0f, -12f)
        )),
        Preset("p_land_golden", "Ora d'oro", "Paesaggio", EditParams(
            temperature = 22f, tint = 6f, contrast = 10f, highlights = -30f, shadows = 20f,
            vibrance = 18f, clarity = 8f, gradeHighlights = GradeWheel(42f, 30f), gradeShadows = GradeWheel(25f, 10f),
            vignetteAmount = -15f
        )),
        Preset("p_land_cold", "Montagna fredda", "Paesaggio", EditParams(
            temperature = -18f, contrast = 18f, highlights = -30f, shadows = 15f, clarity = 18f, dehaze = 20f,
            vibrance = 10f, saturation = -8f, gradeShadows = GradeWheel(215f, 15f)
        )),
        // Cinematografico
        Preset("p_cine_teal", "Teal & Orange", "Cinematografico", EditParams(
            profile = "Cinema", contrast = 15f, highlights = -25f, shadows = 10f, vibrance = 10f,
            hslHue = hsl(0f, -5f, -15f, 30f, -10f, -10f), hslSat = hsl(0f, 10f, -10f, -30f, 15f, 15f),
            gradeShadows = GradeWheel(195f, 32f), gradeHighlights = GradeWheel(35f, 28f), gradeBalance = -10f
        )),
        Preset("p_cine_bleach", "Bleach bypass", "Cinematografico", EditParams(
            contrast = 40f, highlights = -20f, shadows = -5f, saturation = -45f, clarity = 22f,
            blacks = -15f, vignetteAmount = -20f
        )),
        Preset("p_cine_night", "Notte urbana", "Cinematografico", EditParams(
            exposure = -0.1f, temperature = -15f, tint = 8f, contrast = 20f, highlights = -30f, blacks = -10f,
            dehaze = 10f, vibrance = 15f, gradeShadows = GradeWheel(230f, 25f), gradeHighlights = GradeWheel(320f, 12f)
        )),
        // Vintage
        Preset("p_vint_film", "Pellicola anni '70", "Vintage", EditParams(
            profile = "Pellicola calda", contrast = -10f, highlights = -20f, saturation = -15f,
            curveMaster = curve(0f, 0.08f, 0.25f, 0.27f, 0.75f, 0.76f, 1f, 0.93f),
            grainAmount = 28f, grainSize = 30f, vignetteAmount = -18f, gradeShadows = GradeWheel(40f, 12f)
        )),
        Preset("p_vint_fade", "Sbiadito", "Vintage", EditParams(
            contrast = -20f, highlights = -15f, shadows = 20f, saturation = -20f,
            curveMaster = curve(0f, 0.12f, 0.5f, 0.52f, 1f, 0.95f), grainAmount = 15f,
            gradeHighlights = GradeWheel(50f, 12f)
        )),
        Preset("p_vint_polar", "Istantanea", "Vintage", EditParams(
            exposure = 0.2f, temperature = 10f, tint = -6f, contrast = -12f, saturation = -10f,
            curveMaster = curve(0f, 0.07f, 0.5f, 0.55f, 1f, 0.94f), curveBlue = curve(0f, 0.08f, 1f, 0.92f),
            vignetteAmount = -30f, vignetteFeather = 80f, grainAmount = 20f
        )),
        // Bianco e nero
        Preset("p_bw_classic", "B&N classico", "Bianco e nero", EditParams(
            profile = "Monocromatico", treatment = Treatment.BW, contrast = 15f, highlights = -20f, shadows = 15f,
            clarity = 10f
        )),
        Preset("p_bw_contrast", "B&N drammatico", "Bianco e nero", EditParams(
            profile = "B&N alto contrasto", treatment = Treatment.BW, contrast = 35f, highlights = -35f,
            shadows = -10f, whites = 15f, blacks = -25f, clarity = 25f, vignetteAmount = -25f, grainAmount = 12f
        )),
        Preset("p_bw_soft", "B&N delicato", "Bianco e nero", EditParams(
            profile = "B&N morbido", treatment = Treatment.BW, exposure = 0.15f, contrast = -10f,
            highlights = -15f, shadows = 25f, texture = -10f, curveMaster = curve(0f, 0.05f, 1f, 0.97f)
        )),
        Preset("p_bw_selenium", "Viraggio seppia", "Bianco e nero", EditParams(
            profile = "Monocromatico", treatment = Treatment.BW, contrast = 10f,
            gradeShadows = GradeWheel(30f, 25f), gradeHighlights = GradeWheel(45f, 18f), gradeMidtones = GradeWheel(35f, 15f)
        )),
        // Stile
        Preset("p_style_matte", "Opaco", "Stile", EditParams(
            contrast = -15f, highlights = -10f, shadows = 15f, curveMaster = curve(0f, 0.1f, 0.3f, 0.3f, 1f, 1f),
            saturation = -5f
        )),
        Preset("p_style_pop", "Pop", "Stile", EditParams(
            contrast = 25f, vibrance = 35f, saturation = 10f, clarity = 15f, highlights = -20f, shadows = 20f,
            whites = 10f, blacks = -10f
        )),
        Preset("p_style_food", "Cibo", "Stile", EditParams(
            exposure = 0.2f, temperature = 6f, contrast = 12f, highlights = -25f, shadows = 15f,
            texture = 15f, vibrance = 20f, hslSat = hsl(8f, 10f, 8f, 5f), sharpAmount = 30f
        )),
        Preset("p_style_hdr", "Effetto HDR", "Stile", EditParams(
            highlights = -80f, shadows = 70f, whites = 15f, blacks = -20f, clarity = 35f, texture = 20f,
            dehaze = 10f, vibrance = 20f
        ))
    )

    /** Applica le regolazioni di un predefinito mantenendo geometria e maschere dell'immagine. */
    fun apply(current: EditParams, preset: Preset): EditParams = current.withAdjustmentsFrom(preset.params)
}
