package com.michele.luce.engine

/** JSON minimale senza dipendenze, sufficiente per salvare impostazioni e predefiniti. */
object Json {

    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Float -> sb.append(if (v.isFinite()) trimNumber(v.toString()) else "0")
            is Double -> sb.append(if (v.isFinite()) trimNumber(v.toString()) else "0")
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, e)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun trimNumber(s: String): String =
        if (s.contains('E') || s.contains('e')) s else if (s.endsWith(".0")) s.dropLast(2) else s

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("JSON incompleto")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Carattere inatteso '$c'")
            }
        }

        fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Atteso $word")
            i += word.length
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("Atteso :")
                i++
                m[k] = value()
                ws()
                if (i >= s.length) throw IllegalArgumentException("JSON incompleto")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw IllegalArgumentException("Atteso , o }")
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                if (i >= s.length) throw IllegalArgumentException("JSON incompleto")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw IllegalArgumentException("Atteso , o ]")
            }
        }

        fun str(): String {
            if (s[i] != '"') throw IllegalArgumentException("Attesa stringa")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Stringa non chiusa")
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}

/** Conversione EditParams ⇄ JSON. Campi mancanti o sconosciuti vengono ignorati con valori predefiniti. */
object ParamsJson {

    fun encode(p: EditParams): String = Json.stringify(toMap(p))

    fun decode(text: String): EditParams = try {
        @Suppress("UNCHECKED_CAST")
        fromMap(Json.parse(text) as? Map<String, Any?> ?: emptyMap())
    } catch (e: Exception) {
        EditParams()
    }

    private fun curve(c: ToneCurve) = c.points.map { listOf(it.x, it.y) }
    private fun wheel(w: GradeWheel) = mapOf("h" to w.hue, "s" to w.sat, "l" to w.lum)

    fun toMap(p: EditParams): Map<String, Any?> = linkedMapOf(
        "v" to 2,
        "profile" to p.profile, "profileAmount" to p.profileAmount, "treatment" to p.treatment.name,
        "exposure" to p.exposure, "contrast" to p.contrast, "highlights" to p.highlights,
        "shadows" to p.shadows, "whites" to p.whites, "blacks" to p.blacks,
        "curveMaster" to curve(p.curveMaster), "curveRed" to curve(p.curveRed),
        "curveGreen" to curve(p.curveGreen), "curveBlue" to curve(p.curveBlue),
        "parHighlights" to p.parHighlights, "parLights" to p.parLights, "parDarks" to p.parDarks, "parShadows" to p.parShadows,
        "parSplitShadows" to p.parSplitShadows, "parSplitMidtones" to p.parSplitMidtones, "parSplitHighlights" to p.parSplitHighlights,
        "temperature" to p.temperature, "tint" to p.tint, "vibrance" to p.vibrance, "saturation" to p.saturation,
        "hslHue" to p.hslHue, "hslSat" to p.hslSat, "hslLum" to p.hslLum,
        "pointColors" to p.pointColors.map {
            mapOf(
                "id" to it.id, "hue" to it.hue, "sat" to it.sat, "lum" to it.lum,
                "hueShift" to it.hueShift, "satShift" to it.satShift, "lumShift" to it.lumShift, "variance" to it.variance,
                "range" to it.range, "hueRange" to it.hueRange, "satRange" to it.satRange, "lumRange" to it.lumRange
            )
        },
        "gradeShadows" to wheel(p.gradeShadows), "gradeMidtones" to wheel(p.gradeMidtones),
        "gradeHighlights" to wheel(p.gradeHighlights), "gradeGlobal" to wheel(p.gradeGlobal),
        "gradeBlending" to p.gradeBlending, "gradeBalance" to p.gradeBalance,
        "calShadowsTint" to p.calShadowsTint, "calRedHue" to p.calRedHue, "calRedSat" to p.calRedSat,
        "calGreenHue" to p.calGreenHue, "calGreenSat" to p.calGreenSat, "calBlueHue" to p.calBlueHue, "calBlueSat" to p.calBlueSat,
        "texture" to p.texture, "clarity" to p.clarity, "dehaze" to p.dehaze,
        "vignetteStyle" to p.vignetteStyle,
        "vignetteAmount" to p.vignetteAmount, "vignetteMidpoint" to p.vignetteMidpoint,
        "vignetteRoundness" to p.vignetteRoundness, "vignetteFeather" to p.vignetteFeather,
        "vignetteHighlights" to p.vignetteHighlights,
        "grainAmount" to p.grainAmount, "grainSize" to p.grainSize, "grainRoughness" to p.grainRoughness,
        "sharpAmount" to p.sharpAmount, "sharpRadius" to p.sharpRadius, "sharpDetail" to p.sharpDetail,
        "sharpMasking" to p.sharpMasking, "nrLuminance" to p.nrLuminance, "nrLumDetail" to p.nrLumDetail,
        "nrLumContrast" to p.nrLumContrast, "nrColor" to p.nrColor, "nrColorDetail" to p.nrColorDetail,
        "nrColorSmooth" to p.nrColorSmooth,
        "removeCA" to p.removeCA, "lensDistortion" to p.lensDistortion, "lensVignette" to p.lensVignette,
        "chromaticAberration" to p.chromaticAberration,
        "defringePurple" to p.defringePurple, "defringePurpleHueLo" to p.defringePurpleHueLo, "defringePurpleHueHi" to p.defringePurpleHueHi,
        "defringeGreen" to p.defringeGreen, "defringeGreenHueLo" to p.defringeGreenHueLo, "defringeGreenHueHi" to p.defringeGreenHueHi,
        "uprightMode" to p.uprightMode, "uprightAngle" to p.uprightAngle, "uprightV" to p.uprightV, "uprightH" to p.uprightH,
        "tVertical" to p.tVertical, "tHorizontal" to p.tHorizontal, "tRotate" to p.tRotate, "tAspect" to p.tAspect,
        "tScale" to p.tScale, "tOffsetX" to p.tOffsetX, "tOffsetY" to p.tOffsetY, "constrainCrop" to p.constrainCrop,
        "rotate90" to p.rotate90, "flipH" to p.flipH, "flipV" to p.flipV, "straighten" to p.straighten,
        "cropLeft" to p.cropLeft, "cropTop" to p.cropTop, "cropRight" to p.cropRight, "cropBottom" to p.cropBottom,
        "aspect" to p.aspect,
        "masks" to p.masks.map { maskToMap(it) }
    )

    private fun adjustToMap(a: LocalAdjust): Map<String, Any?> = mapOf(
        "exposure" to a.exposure, "contrast" to a.contrast, "highlights" to a.highlights, "shadows" to a.shadows,
        "whites" to a.whites, "blacks" to a.blacks, "temperature" to a.temperature, "tint" to a.tint,
        "hue" to a.hue, "saturation" to a.saturation, "texture" to a.texture, "clarity" to a.clarity,
        "dehaze" to a.dehaze, "sharpness" to a.sharpness, "noise" to a.noise, "moire" to a.moire,
        "defringe" to a.defringe, "colorHue" to a.colorHue, "colorSat" to a.colorSat
    )

    private fun componentToMap(c: MaskComponent): Map<String, Any?> = linkedMapOf(
        "id" to c.id, "type" to c.type.name, "mode" to c.mode.name, "invert" to c.invert,
        "x1" to c.x1, "y1" to c.y1, "x2" to c.x2, "y2" to c.y2,
        "cx" to c.cx, "cy" to c.cy, "rx" to c.rx, "ry" to c.ry, "angle" to c.angle, "feather" to c.feather,
        "strokes" to c.strokes.map {
            mapOf(
                "p" to it.points, "size" to it.size, "feather" to it.feather, "flow" to it.flow, "erase" to it.erase,
                "density" to it.density, "auto" to it.autoMask, "c" to it.colors
            )
        },
        "colorSamples" to c.colorSamples, "refine" to c.refine,
        "lumLo" to c.lumLo, "lumHi" to c.lumHi, "lumFeatherLo" to c.lumFeatherLo, "lumFeatherHi" to c.lumFeatherHi,
        "lumSmooth" to c.lumSmooth,
        "aiKey" to c.aiKey, "aiLabel" to c.aiLabel, "aiX" to c.aiX, "aiY" to c.aiY
    )

    private fun maskToMap(m: Mask): Map<String, Any?> = linkedMapOf(
        "id" to m.id, "name" to m.name, "amount" to m.amount, "visible" to m.visible, "invert" to m.invert,
        "components" to m.components.map { componentToMap(it) },
        "adjust" to adjustToMap(m.adjust)
    )

    private fun Map<String, Any?>.f(key: String, def: Float): Float = (this[key] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: def
    private fun Map<String, Any?>.i(key: String, def: Int): Int = (this[key] as? Number)?.toInt() ?: def
    private fun Map<String, Any?>.l(key: String, def: Long): Long = (this[key] as? Number)?.toLong() ?: def
    private fun Map<String, Any?>.b(key: String, def: Boolean): Boolean = this[key] as? Boolean ?: def
    private fun Map<String, Any?>.s(key: String, def: String): String = this[key] as? String ?: def

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()

    private fun Map<String, Any?>.floats(key: String): List<Float> =
        (this[key] as? List<*>)?.map { (it as? Number)?.toFloat() ?: 0f } ?: emptyList()

    private fun Map<String, Any?>.floats8(key: String): List<Float> {
        val l = (this[key] as? List<*>)?.map { (it as? Number)?.toFloat() ?: 0f } ?: return EditParams.ZERO8
        return List(8) { l.getOrElse(it) { 0f } }
    }

    private fun Map<String, Any?>.curve(key: String): ToneCurve {
        val l = this[key] as? List<*> ?: return ToneCurve()
        val pts = l.mapNotNull { e ->
            val pair = e as? List<*> ?: return@mapNotNull null
            val x = (pair.getOrNull(0) as? Number)?.toFloat() ?: return@mapNotNull null
            val y = (pair.getOrNull(1) as? Number)?.toFloat() ?: return@mapNotNull null
            CurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
        return if (pts.size >= 2) ToneCurve(pts.sortedBy { it.x }) else ToneCurve()
    }

    private fun Map<String, Any?>.wheel(key: String): GradeWheel {
        val w = m(key)
        return GradeWheel(w.f("h", 0f), w.f("s", 0f), w.f("l", 0f))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    fun fromMap(m: Map<String, Any?>): EditParams {
        val d = EditParams()
        return EditParams(
            profile = m.s("profile", d.profile),
            profileAmount = m.f("profileAmount", d.profileAmount),
            treatment = runCatching { Treatment.valueOf(m.s("treatment", "COLOR")) }.getOrDefault(Treatment.COLOR),
            exposure = m.f("exposure", 0f), contrast = m.f("contrast", 0f), highlights = m.f("highlights", 0f),
            shadows = m.f("shadows", 0f), whites = m.f("whites", 0f), blacks = m.f("blacks", 0f),
            curveMaster = m.curve("curveMaster"), curveRed = m.curve("curveRed"),
            curveGreen = m.curve("curveGreen"), curveBlue = m.curve("curveBlue"),
            parHighlights = m.f("parHighlights", 0f), parLights = m.f("parLights", 0f),
            parDarks = m.f("parDarks", 0f), parShadows = m.f("parShadows", 0f),
            parSplitShadows = m.f("parSplitShadows", d.parSplitShadows),
            parSplitMidtones = m.f("parSplitMidtones", d.parSplitMidtones),
            parSplitHighlights = m.f("parSplitHighlights", d.parSplitHighlights),
            temperature = m.f("temperature", 0f), tint = m.f("tint", 0f),
            vibrance = m.f("vibrance", 0f), saturation = m.f("saturation", 0f),
            hslHue = m.floats8("hslHue"), hslSat = m.floats8("hslSat"), hslLum = m.floats8("hslLum"),
            pointColors = m.maps("pointColors").map { pc ->
                PointColor(
                    id = pc.l("id", 0L), hue = pc.f("hue", 0f), sat = pc.f("sat", 0f), lum = pc.f("lum", 0.5f),
                    hueShift = pc.f("hueShift", 0f), satShift = pc.f("satShift", 0f), lumShift = pc.f("lumShift", 0f),
                    variance = pc.f("variance", 0f), range = pc.f("range", 50f), hueRange = pc.f("hueRange", 50f),
                    satRange = pc.f("satRange", 50f), lumRange = pc.f("lumRange", 50f)
                )
            },
            gradeShadows = m.wheel("gradeShadows"), gradeMidtones = m.wheel("gradeMidtones"),
            gradeHighlights = m.wheel("gradeHighlights"), gradeGlobal = m.wheel("gradeGlobal"),
            gradeBlending = m.f("gradeBlending", d.gradeBlending), gradeBalance = m.f("gradeBalance", 0f),
            calShadowsTint = m.f("calShadowsTint", 0f), calRedHue = m.f("calRedHue", 0f), calRedSat = m.f("calRedSat", 0f),
            calGreenHue = m.f("calGreenHue", 0f), calGreenSat = m.f("calGreenSat", 0f),
            calBlueHue = m.f("calBlueHue", 0f), calBlueSat = m.f("calBlueSat", 0f),
            texture = m.f("texture", 0f), clarity = m.f("clarity", 0f), dehaze = m.f("dehaze", 0f),
            vignetteStyle = m.i("vignetteStyle", 0).coerceIn(0, 2),
            vignetteAmount = m.f("vignetteAmount", 0f), vignetteMidpoint = m.f("vignetteMidpoint", d.vignetteMidpoint),
            vignetteRoundness = m.f("vignetteRoundness", 0f), vignetteFeather = m.f("vignetteFeather", d.vignetteFeather),
            vignetteHighlights = m.f("vignetteHighlights", 0f),
            grainAmount = m.f("grainAmount", 0f), grainSize = m.f("grainSize", d.grainSize),
            grainRoughness = m.f("grainRoughness", d.grainRoughness),
            sharpAmount = m.f("sharpAmount", 0f), sharpRadius = m.f("sharpRadius", d.sharpRadius),
            sharpDetail = m.f("sharpDetail", d.sharpDetail), sharpMasking = m.f("sharpMasking", 0f),
            nrLuminance = m.f("nrLuminance", 0f), nrLumDetail = m.f("nrLumDetail", d.nrLumDetail),
            nrLumContrast = m.f("nrLumContrast", 0f),
            nrColor = m.f("nrColor", 0f), nrColorDetail = m.f("nrColorDetail", d.nrColorDetail),
            nrColorSmooth = m.f("nrColorSmooth", d.nrColorSmooth),
            removeCA = m.b("removeCA", false),
            lensDistortion = m.f("lensDistortion", 0f), lensVignette = m.f("lensVignette", 0f),
            chromaticAberration = m.f("chromaticAberration", 0f),
            defringePurple = m.f("defringePurple", 0f), defringePurpleHueLo = m.f("defringePurpleHueLo", d.defringePurpleHueLo),
            defringePurpleHueHi = m.f("defringePurpleHueHi", d.defringePurpleHueHi),
            defringeGreen = m.f("defringeGreen", 0f), defringeGreenHueLo = m.f("defringeGreenHueLo", d.defringeGreenHueLo),
            defringeGreenHueHi = m.f("defringeGreenHueHi", d.defringeGreenHueHi),
            uprightMode = m.s("uprightMode", "Off"), uprightAngle = m.f("uprightAngle", 0f),
            uprightV = m.f("uprightV", 0f), uprightH = m.f("uprightH", 0f),
            tVertical = m.f("tVertical", 0f), tHorizontal = m.f("tHorizontal", 0f), tRotate = m.f("tRotate", 0f),
            tAspect = m.f("tAspect", 0f), tScale = m.f("tScale", 100f).coerceIn(10f, 400f),
            tOffsetX = m.f("tOffsetX", 0f), tOffsetY = m.f("tOffsetY", 0f), constrainCrop = m.b("constrainCrop", false),
            rotate90 = ((m.i("rotate90", 0) % 4) + 4) % 4, flipH = m.b("flipH", false), flipV = m.b("flipV", false),
            straighten = m.f("straighten", 0f).coerceIn(-45f, 45f),
            cropLeft = m.f("cropLeft", 0f).coerceIn(0f, 1f), cropTop = m.f("cropTop", 0f).coerceIn(0f, 1f),
            cropRight = m.f("cropRight", 1f).coerceIn(0f, 1f), cropBottom = m.f("cropBottom", 1f).coerceIn(0f, 1f),
            aspect = m.s("aspect", "Libero"),
            masks = m.maps("masks").mapNotNull { maskFromMap(it) }
        ).let { p ->
            // ritaglio coerente
            if (p.cropRight - p.cropLeft < 0.01f || p.cropBottom - p.cropTop < 0.01f)
                p.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f) else p
        }
    }

    private fun adjustFromMap(a: Map<String, Any?>) = LocalAdjust(
        exposure = a.f("exposure", 0f), contrast = a.f("contrast", 0f), highlights = a.f("highlights", 0f),
        shadows = a.f("shadows", 0f), whites = a.f("whites", 0f), blacks = a.f("blacks", 0f),
        temperature = a.f("temperature", 0f), tint = a.f("tint", 0f), hue = a.f("hue", 0f),
        saturation = a.f("saturation", 0f), texture = a.f("texture", 0f), clarity = a.f("clarity", 0f),
        dehaze = a.f("dehaze", 0f), sharpness = a.f("sharpness", 0f), noise = a.f("noise", 0f),
        moire = a.f("moire", 0f), defringe = a.f("defringe", 0f), colorHue = a.f("colorHue", 0f),
        colorSat = a.f("colorSat", 0f)
    )

    private fun componentFromMap(m: Map<String, Any?>, defId: Long): MaskComponent? {
        val type = when (val t = m.s("type", "")) {
            "BRUSH", "LINEAR", "RADIAL", "COLOR_RANGE", "LUMINANCE_RANGE", "AI" -> ComponentType.valueOf(t)
            else -> return null
        }
        return MaskComponent(
            id = m.l("id", defId), type = type,
            mode = runCatching { CombineMode.valueOf(m.s("mode", "ADD")) }.getOrDefault(CombineMode.ADD),
            invert = m.b("invert", false),
            x1 = m.f("x1", 0.5f), y1 = m.f("y1", 0.3f), x2 = m.f("x2", 0.5f), y2 = m.f("y2", 0.6f),
            cx = m.f("cx", 0.5f), cy = m.f("cy", 0.5f), rx = m.f("rx", 0.25f), ry = m.f("ry", 0.25f),
            angle = m.f("angle", 0f), feather = m.f("feather", 50f),
            strokes = m.maps("strokes").map { sm ->
                BrushStroke(
                    points = sm.floats("p"), size = sm.f("size", 0.05f), feather = sm.f("feather", 0.5f),
                    flow = sm.f("flow", 1f), erase = sm.b("erase", false), density = sm.f("density", 1f),
                    autoMask = sm.b("auto", false), colors = sm.floats("c")
                )
            },
            colorSamples = m.floats("colorSamples"), refine = m.f("refine", 50f),
            lumLo = m.f("lumLo", 0.25f), lumHi = m.f("lumHi", 0.75f),
            lumFeatherLo = m.f("lumFeatherLo", 0.1f), lumFeatherHi = m.f("lumFeatherHi", 0.1f),
            lumSmooth = m.f("lumSmooth", 0f),
            aiKey = m.s("aiKey", ""), aiLabel = m.s("aiLabel", ""), aiX = m.f("aiX", 0.5f), aiY = m.f("aiY", 0.5f)
        )
    }

    private fun maskFromMap(m: Map<String, Any?>): Mask? {
        val id = m.l("id", System.nanoTime())
        val comps = if (m.containsKey("components")) {
            m.maps("components").mapIndexedNotNull { i, c -> componentFromMap(c, id + i + 1) }
        } else {
            // formato della prima versione: una maschera = un solo componente
            listOfNotNull(componentFromMap(m + mapOf("invert" to m.b("invert", false)), id + 1))
        }
        if (comps.isEmpty()) return null
        return Mask(
            id = id, name = m.s("name", "Maschera"), components = comps,
            amount = m.f("amount", 100f).coerceIn(0f, 200f), adjust = adjustFromMap(m.m("adjust")),
            visible = m.b("visible", true),
            // nel formato v1 l'inversione era del componente
            invert = if (m.containsKey("components")) m.b("invert", false) else false
        )
    }
}
