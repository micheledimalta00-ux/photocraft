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
        "v" to 1,
        "profile" to p.profile, "profileAmount" to p.profileAmount, "treatment" to p.treatment.name,
        "exposure" to p.exposure, "contrast" to p.contrast, "highlights" to p.highlights,
        "shadows" to p.shadows, "whites" to p.whites, "blacks" to p.blacks,
        "curveMaster" to curve(p.curveMaster), "curveRed" to curve(p.curveRed),
        "curveGreen" to curve(p.curveGreen), "curveBlue" to curve(p.curveBlue),
        "parHighlights" to p.parHighlights, "parLights" to p.parLights, "parDarks" to p.parDarks, "parShadows" to p.parShadows,
        "temperature" to p.temperature, "tint" to p.tint, "vibrance" to p.vibrance, "saturation" to p.saturation,
        "hslHue" to p.hslHue, "hslSat" to p.hslSat, "hslLum" to p.hslLum,
        "gradeShadows" to wheel(p.gradeShadows), "gradeMidtones" to wheel(p.gradeMidtones),
        "gradeHighlights" to wheel(p.gradeHighlights), "gradeGlobal" to wheel(p.gradeGlobal),
        "gradeBlending" to p.gradeBlending, "gradeBalance" to p.gradeBalance,
        "texture" to p.texture, "clarity" to p.clarity, "dehaze" to p.dehaze,
        "vignetteAmount" to p.vignetteAmount, "vignetteMidpoint" to p.vignetteMidpoint,
        "vignetteRoundness" to p.vignetteRoundness, "vignetteFeather" to p.vignetteFeather,
        "vignetteHighlights" to p.vignetteHighlights,
        "grainAmount" to p.grainAmount, "grainSize" to p.grainSize, "grainRoughness" to p.grainRoughness,
        "sharpAmount" to p.sharpAmount, "sharpRadius" to p.sharpRadius, "sharpDetail" to p.sharpDetail,
        "sharpMasking" to p.sharpMasking, "nrLuminance" to p.nrLuminance, "nrLumDetail" to p.nrLumDetail,
        "nrColor" to p.nrColor, "nrColorDetail" to p.nrColorDetail,
        "lensDistortion" to p.lensDistortion, "lensVignette" to p.lensVignette,
        "chromaticAberration" to p.chromaticAberration,
        "rotate90" to p.rotate90, "flipH" to p.flipH, "flipV" to p.flipV, "straighten" to p.straighten,
        "cropLeft" to p.cropLeft, "cropTop" to p.cropTop, "cropRight" to p.cropRight, "cropBottom" to p.cropBottom,
        "aspect" to p.aspect,
        "masks" to p.masks.map { maskToMap(it) }
    )

    private fun maskToMap(m: Mask): Map<String, Any?> = linkedMapOf(
        "id" to m.id, "name" to m.name, "type" to m.type.name,
        "x1" to m.x1, "y1" to m.y1, "x2" to m.x2, "y2" to m.y2,
        "cx" to m.cx, "cy" to m.cy, "rx" to m.rx, "ry" to m.ry, "angle" to m.angle,
        "feather" to m.feather, "invert" to m.invert, "visible" to m.visible,
        "strokes" to m.strokes.map {
            mapOf("p" to it.points, "size" to it.size, "feather" to it.feather, "flow" to it.flow, "erase" to it.erase)
        },
        "adjust" to with(m.adjust) {
            mapOf(
                "exposure" to exposure, "contrast" to contrast, "highlights" to highlights, "shadows" to shadows,
                "whites" to whites, "blacks" to blacks, "temperature" to temperature, "tint" to tint,
                "saturation" to saturation, "clarity" to clarity, "dehaze" to dehaze, "texture" to texture
            )
        }
    )

    private fun Map<String, Any?>.f(key: String, def: Float): Float = (this[key] as? Number)?.toFloat() ?: def
    private fun Map<String, Any?>.i(key: String, def: Int): Int = (this[key] as? Number)?.toInt() ?: def
    private fun Map<String, Any?>.l(key: String, def: Long): Long = (this[key] as? Number)?.toLong() ?: def
    private fun Map<String, Any?>.b(key: String, def: Boolean): Boolean = this[key] as? Boolean ?: def
    private fun Map<String, Any?>.s(key: String, def: String): String = this[key] as? String ?: def

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()

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
            temperature = m.f("temperature", 0f), tint = m.f("tint", 0f),
            vibrance = m.f("vibrance", 0f), saturation = m.f("saturation", 0f),
            hslHue = m.floats8("hslHue"), hslSat = m.floats8("hslSat"), hslLum = m.floats8("hslLum"),
            gradeShadows = m.wheel("gradeShadows"), gradeMidtones = m.wheel("gradeMidtones"),
            gradeHighlights = m.wheel("gradeHighlights"), gradeGlobal = m.wheel("gradeGlobal"),
            gradeBlending = m.f("gradeBlending", d.gradeBlending), gradeBalance = m.f("gradeBalance", 0f),
            texture = m.f("texture", 0f), clarity = m.f("clarity", 0f), dehaze = m.f("dehaze", 0f),
            vignetteAmount = m.f("vignetteAmount", 0f), vignetteMidpoint = m.f("vignetteMidpoint", d.vignetteMidpoint),
            vignetteRoundness = m.f("vignetteRoundness", 0f), vignetteFeather = m.f("vignetteFeather", d.vignetteFeather),
            vignetteHighlights = m.f("vignetteHighlights", 0f),
            grainAmount = m.f("grainAmount", 0f), grainSize = m.f("grainSize", d.grainSize),
            grainRoughness = m.f("grainRoughness", d.grainRoughness),
            sharpAmount = m.f("sharpAmount", 0f), sharpRadius = m.f("sharpRadius", d.sharpRadius),
            sharpDetail = m.f("sharpDetail", d.sharpDetail), sharpMasking = m.f("sharpMasking", 0f),
            nrLuminance = m.f("nrLuminance", 0f), nrLumDetail = m.f("nrLumDetail", d.nrLumDetail),
            nrColor = m.f("nrColor", 0f), nrColorDetail = m.f("nrColorDetail", d.nrColorDetail),
            lensDistortion = m.f("lensDistortion", 0f), lensVignette = m.f("lensVignette", 0f),
            chromaticAberration = m.f("chromaticAberration", 0f),
            rotate90 = ((m.i("rotate90", 0) % 4) + 4) % 4, flipH = m.b("flipH", false), flipV = m.b("flipV", false),
            straighten = m.f("straighten", 0f).coerceIn(-45f, 45f),
            cropLeft = m.f("cropLeft", 0f).coerceIn(0f, 1f), cropTop = m.f("cropTop", 0f).coerceIn(0f, 1f),
            cropRight = m.f("cropRight", 1f).coerceIn(0f, 1f), cropBottom = m.f("cropBottom", 1f).coerceIn(0f, 1f),
            aspect = m.s("aspect", "Libero"),
            masks = (m["masks"] as? List<*>)?.mapNotNull { e ->
                @Suppress("UNCHECKED_CAST")
                (e as? Map<String, Any?>)?.let { maskFromMap(it) }
            } ?: emptyList()
        ).let { p ->
            // ritaglio coerente
            if (p.cropRight - p.cropLeft < 0.01f || p.cropBottom - p.cropTop < 0.01f)
                p.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f) else p
        }
    }

    private fun maskFromMap(m: Map<String, Any?>): Mask? {
        val type = runCatching { MaskType.valueOf(m.s("type", "")) }.getOrNull() ?: return null
        val a = m.m("adjust")
        return Mask(
            id = m.l("id", System.nanoTime()), name = m.s("name", "Maschera"), type = type,
            x1 = m.f("x1", 0.5f), y1 = m.f("y1", 0.3f), x2 = m.f("x2", 0.5f), y2 = m.f("y2", 0.6f),
            cx = m.f("cx", 0.5f), cy = m.f("cy", 0.5f), rx = m.f("rx", 0.25f), ry = m.f("ry", 0.25f),
            angle = m.f("angle", 0f), feather = m.f("feather", 50f), invert = m.b("invert", false),
            visible = m.b("visible", true),
            strokes = (m["strokes"] as? List<*>)?.mapNotNull { st ->
                @Suppress("UNCHECKED_CAST")
                val sm = st as? Map<String, Any?> ?: return@mapNotNull null
                BrushStroke(
                    points = (sm["p"] as? List<*>)?.map { (it as? Number)?.toFloat() ?: 0f } ?: emptyList(),
                    size = sm.f("size", 0.05f), feather = sm.f("feather", 0.5f),
                    flow = sm.f("flow", 1f), erase = sm.b("erase", false)
                )
            } ?: emptyList(),
            adjust = LocalAdjust(
                exposure = a.f("exposure", 0f), contrast = a.f("contrast", 0f), highlights = a.f("highlights", 0f),
                shadows = a.f("shadows", 0f), whites = a.f("whites", 0f), blacks = a.f("blacks", 0f),
                temperature = a.f("temperature", 0f), tint = a.f("tint", 0f), saturation = a.f("saturation", 0f),
                clarity = a.f("clarity", 0f), dehaze = a.f("dehaze", 0f), texture = a.f("texture", 0f)
            )
        )
    }
}
