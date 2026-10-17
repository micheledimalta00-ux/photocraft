package com.michele.luce.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michele.luce.engine.EditParams
import com.michele.luce.engine.Geometry
import com.michele.luce.engine.GradeWheel
import com.michele.luce.engine.PointColor
import com.michele.luce.engine.Preset
import com.michele.luce.engine.Presets
import com.michele.luce.engine.Profiles
import com.michele.luce.engine.ToneCurve
import com.michele.luce.engine.Treatment
import com.michele.luce.engine.Upright
import com.michele.luce.engine.with
import com.michele.luce.ui.components.ActionButton
import com.michele.luce.ui.components.ColorWheel
import com.michele.luce.ui.components.Gradients
import com.michele.luce.ui.components.LuceSlider
import com.michele.luce.ui.components.Pill
import com.michele.luce.ui.components.PillRow
import com.michele.luce.ui.components.SectionHeader
import com.michele.luce.ui.components.ToneCurveEditor
import com.michele.luce.ui.theme.LuceColors

/** Cursore collegato a un campo di EditParams. */
@Composable
internal fun PSlider(
    vm: EditorViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float = 0f,
    decimals: Int = 0,
    suffix: String = "",
    gradient: List<Color>? = null,
    enabled: Boolean = true,
    apply: (EditParams, Float) -> EditParams
) {
    LuceSlider(
        label = label, value = value, range = range,
        onChange = { vm.update(apply(vm.params, it)) },
        onCommit = { vm.commit() },
        default = default, decimals = decimals, suffix = suffix, gradient = gradient, enabled = enabled
    )
}

@Composable
internal fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LuceColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = LuceColors.Accent)
        )
    }
}

@Composable
fun ToolPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (vm.tool) {
            Tool.PRESETS -> PresetsPanel(vm)
            Tool.PROFILE -> ProfilePanel(vm)
            Tool.CROP -> CropPanel(vm)
            Tool.GEOMETRY -> GeometryPanel(vm)
            Tool.MASKS -> MasksPanel(vm)
            Tool.LIGHT -> LightPanel(vm)
            Tool.COLOR -> ColorPanel(vm)
            Tool.EFFECTS -> EffectsPanel(vm)
            Tool.DETAIL -> DetailPanel(vm)
            Tool.OPTICS -> OpticsPanel(vm)
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------- Luce ----------------

private enum class CurveChannel(val label: String) { RGB("RGB"), RED("Rosso"), GREEN("Verde"), BLUE("Blu"), PARAMETRIC("Parametrica") }

@Composable
fun LightPanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Luce") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Ritaglio", vm.showClipping, { vm.toggleClipping() }, icon = Icons.Filled.WarningAmber)
            Pill("Auto", false, { vm.autoTone() }, icon = Icons.Filled.AutoAwesome)
        }
    }
    PSlider(vm, "Esposizione", p.exposure, -5f..5f, decimals = 2) { e, v -> e.copy(exposure = v) }
    PSlider(vm, "Contrasto", p.contrast, -100f..100f) { e, v -> e.copy(contrast = v) }
    PSlider(vm, "Luci", p.highlights, -100f..100f) { e, v -> e.copy(highlights = v) }
    PSlider(vm, "Ombre", p.shadows, -100f..100f) { e, v -> e.copy(shadows = v) }
    PSlider(vm, "Bianchi", p.whites, -100f..100f) { e, v -> e.copy(whites = v) }
    PSlider(vm, "Neri", p.blacks, -100f..100f) { e, v -> e.copy(blacks = v) }
    if (vm.showClipping) Hint("Rosso: luci bruciate · Blu: ombre chiuse")

    var channel by rememberSaveable { mutableStateOf(CurveChannel.RGB) }
    SectionHeader("Curva") {
        Pill("Ripristina", false, {
            vm.set(vm.params.copy(
                curveMaster = ToneCurve(), curveRed = ToneCurve(), curveGreen = ToneCurve(), curveBlue = ToneCurve(),
                parHighlights = 0f, parLights = 0f, parDarks = 0f, parShadows = 0f,
                parSplitShadows = 25f, parSplitMidtones = 50f, parSplitHighlights = 75f
            ))
        })
    }
    PillRow(CurveChannel.entries, channel, { it.label }, { channel = it })
    if (channel == CurveChannel.PARAMETRIC) {
        PSlider(vm, "Luci", p.parHighlights, -100f..100f) { e, v -> e.copy(parHighlights = v) }
        PSlider(vm, "Chiari", p.parLights, -100f..100f) { e, v -> e.copy(parLights = v) }
        PSlider(vm, "Scuri", p.parDarks, -100f..100f) { e, v -> e.copy(parDarks = v) }
        PSlider(vm, "Ombre", p.parShadows, -100f..100f) { e, v -> e.copy(parShadows = v) }
        SectionHeader("Divisione delle regioni")
        PSlider(vm, "Ombre / Scuri", p.parSplitShadows, 10f..(p.parSplitMidtones - 5f).coerceAtLeast(11f), default = 25f) { e, v -> e.copy(parSplitShadows = v) }
        PSlider(vm, "Scuri / Chiari", p.parSplitMidtones, (p.parSplitShadows + 5f)..(p.parSplitHighlights - 5f).coerceAtLeast(p.parSplitShadows + 6f), default = 50f) { e, v -> e.copy(parSplitMidtones = v) }
        PSlider(vm, "Chiari / Luci", p.parSplitHighlights, (p.parSplitMidtones + 5f).coerceAtMost(89f)..90f, default = 75f) { e, v -> e.copy(parSplitHighlights = v) }
    } else {
        val curve = when (channel) {
            CurveChannel.RED -> p.curveRed
            CurveChannel.GREEN -> p.curveGreen
            CurveChannel.BLUE -> p.curveBlue
            else -> p.curveMaster
        }
        val color = when (channel) {
            CurveChannel.RED -> Color(0xFFFF5A5A)
            CurveChannel.GREEN -> Color(0xFF5AE07A)
            CurveChannel.BLUE -> Color(0xFF5A9BFF)
            else -> Color.White
        }
        val hist = vm.histogram?.let {
            when (channel) {
                CurveChannel.RED -> it.red
                CurveChannel.GREEN -> it.green
                CurveChannel.BLUE -> it.blue
                else -> it.luma
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            ToneCurveEditor(
                curve = curve, color = color, histogram = hist,
                onChange = { c ->
                    val e = vm.params
                    vm.update(when (channel) {
                        CurveChannel.RED -> e.copy(curveRed = c)
                        CurveChannel.GREEN -> e.copy(curveGreen = c)
                        CurveChannel.BLUE -> e.copy(curveBlue = c)
                        else -> e.copy(curveMaster = c)
                    })
                },
                onCommit = { vm.commit() },
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
        Hint("Tocca e trascina per aggiungere punti · doppio tocco su un punto per eliminarlo")
    }
}

// ---------------- Colore ----------------

private enum class ColorTab(val label: String) {
    BASE("Base"), POINT("Colore puntuale"), MIXER("Miscelazione"), GRADING("Color grading"), CALIBRATION("Calibrazione")
}
private enum class GradeRange(val label: String) { SHADOWS("Ombre"), MIDTONES("Mezzitoni"), HIGHLIGHTS("Luci"), GLOBAL("Globale") }

private val bandColors = listOf(
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047),
    Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFD81B60)
)

@Composable
fun ColorPanel(vm: EditorViewModel) {
    val p = vm.params
    var tab by rememberSaveable { mutableStateOf(ColorTab.BASE) }
    PillRow(ColorTab.entries, tab, { it.label }, { tab = it; vm.cancelPick() }, Modifier.padding(top = 6.dp))
    val bw = p.treatment == Treatment.BW || Profiles.byName(p.profile).bw
    when (tab) {
        ColorTab.BASE -> {
            SectionHeader("Trattamento")
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Colore", !bw, {
                    val np = if (Profiles.byName(vm.params.profile).bw) vm.params.copy(profile = Profiles.DEFAULT) else vm.params
                    vm.set(np.copy(treatment = Treatment.COLOR))
                })
                Pill("Bianco e nero", bw, { vm.set(vm.params.copy(treatment = Treatment.BW)) })
            }
            SectionHeader("Bilanciamento del bianco") {
                IconButton(onClick = { vm.startPick(PickMode.WHITE_BALANCE) }) {
                    Icon(
                        Icons.Filled.Colorize, "Contagocce del bilanciamento del bianco",
                        tint = if (vm.pickMode == PickMode.WHITE_BALANCE) LuceColors.Accent else LuceColors.TextPrimary
                    )
                }
            }
            val asShot = p.temperature == 0f && p.tint == 0f
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Come scattato", asShot, { vm.set(vm.params.copy(temperature = 0f, tint = 0f)) })
                Pill("Auto", false, { vm.autoWhiteBalance() })
                Pill("Personalizzato", !asShot, { })
            }
            PSlider(vm, "Temperatura", p.temperature, -100f..100f, gradient = Gradients.Temperature) { e, v -> e.copy(temperature = v) }
            PSlider(vm, "Tinta", p.tint, -100f..100f, gradient = Gradients.Tint) { e, v -> e.copy(tint = v) }
            SectionHeader("Presenza")
            PSlider(vm, "Vividezza", p.vibrance, -100f..100f, enabled = !bw) { e, v -> e.copy(vibrance = v) }
            PSlider(vm, "Saturazione", p.saturation, -100f..100f, enabled = !bw) { e, v -> e.copy(saturation = v) }
        }
        ColorTab.POINT -> PointColorPanel(vm, bw)
        ColorTab.MIXER -> {
            var band by rememberSaveable { mutableIntStateOf(0) }
            SectionHeader(if (bw) "Miscela bianco e nero" else "Miscelazione colori") {
                Pill("Ripristina", false, {
                    vm.set(vm.params.copy(hslHue = EditParams.ZERO8, hslSat = EditParams.ZERO8, hslLum = EditParams.ZERO8))
                })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                bandColors.forEachIndexed { i, c ->
                    val changed = p.hslHue[i] != 0f || p.hslSat[i] != 0f || p.hslLum[i] != 0f
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(if (i == band) 2.5.dp else 0.dp, if (i == band) Color.White else Color.Transparent, CircleShape)
                            .clickable { band = i },
                        contentAlignment = Alignment.Center
                    ) {
                        if (changed) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
            val center = EditParams.HSL_CENTERS[band]
            Text(
                EditParams.HSL_NAMES[band], style = MaterialTheme.typography.titleMedium,
                color = LuceColors.TextPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (!bw) {
                PSlider(vm, "Tonalità", p.hslHue[band], -100f..100f, gradient = Gradients.hue(center)) { e, v -> e.copy(hslHue = e.hslHue.with(band, v)) }
                PSlider(vm, "Saturazione", p.hslSat[band], -100f..100f, gradient = Gradients.sat(center)) { e, v -> e.copy(hslSat = e.hslSat.with(band, v)) }
            }
            PSlider(vm, if (bw) "Luminosità nel B&N" else "Luminanza", p.hslLum[band], -100f..100f, gradient = Gradients.lum(center)) { e, v -> e.copy(hslLum = e.hslLum.with(band, v)) }
        }
        ColorTab.GRADING -> GradingPanel(vm)
        ColorTab.CALIBRATION -> {
            SectionHeader("Calibrazione") {
                Pill("Ripristina", false, {
                    vm.set(vm.params.copy(calShadowsTint = 0f, calRedHue = 0f, calRedSat = 0f, calGreenHue = 0f, calGreenSat = 0f, calBlueHue = 0f, calBlueSat = 0f))
                })
            }
            PSlider(vm, "Tinta ombre", p.calShadowsTint, -100f..100f, gradient = Gradients.Tint) { e, v -> e.copy(calShadowsTint = v) }
            SectionHeader("Primario rosso")
            PSlider(vm, "Tonalità", p.calRedHue, -100f..100f, gradient = listOf(Color(0xFFE0207A), Color(0xFFE53935), Color(0xFFFB8C00))) { e, v -> e.copy(calRedHue = v) }
            PSlider(vm, "Saturazione", p.calRedSat, -100f..100f, gradient = Gradients.sat(0f)) { e, v -> e.copy(calRedSat = v) }
            SectionHeader("Primario verde")
            PSlider(vm, "Tonalità", p.calGreenHue, -100f..100f, gradient = listOf(Color(0xFFC0CA33), Color(0xFF43A047), Color(0xFF00ACC1))) { e, v -> e.copy(calGreenHue = v) }
            PSlider(vm, "Saturazione", p.calGreenSat, -100f..100f, gradient = Gradients.sat(120f)) { e, v -> e.copy(calGreenSat = v) }
            SectionHeader("Primario blu")
            PSlider(vm, "Tonalità", p.calBlueHue, -100f..100f, gradient = listOf(Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF8E24AA))) { e, v -> e.copy(calBlueHue = v) }
            PSlider(vm, "Saturazione", p.calBlueSat, -100f..100f, gradient = Gradients.sat(240f)) { e, v -> e.copy(calBlueSat = v) }
        }
    }
}

@Composable
private fun GradingPanel(vm: EditorViewModel) {
    val p = vm.params
    var range by rememberSaveable { mutableStateOf(GradeRange.MIDTONES) }
    SectionHeader("Color grading") {
        Pill("Ripristina", false, {
            vm.set(vm.params.copy(
                gradeShadows = GradeWheel(), gradeMidtones = GradeWheel(), gradeHighlights = GradeWheel(),
                gradeGlobal = GradeWheel(), gradeBlending = 50f, gradeBalance = 0f
            ))
        })
    }
    PillRow(GradeRange.entries, range, { it.label }, { range = it })
    val wheel = when (range) {
        GradeRange.SHADOWS -> p.gradeShadows
        GradeRange.MIDTONES -> p.gradeMidtones
        GradeRange.HIGHLIGHTS -> p.gradeHighlights
        GradeRange.GLOBAL -> p.gradeGlobal
    }
    fun withWheel(e: EditParams, w: GradeWheel) = when (range) {
        GradeRange.SHADOWS -> e.copy(gradeShadows = w)
        GradeRange.MIDTONES -> e.copy(gradeMidtones = w)
        GradeRange.HIGHLIGHTS -> e.copy(gradeHighlights = w)
        GradeRange.GLOBAL -> e.copy(gradeGlobal = w)
    }
    fun currentWheel(e: EditParams) = when (range) {
        GradeRange.SHADOWS -> e.gradeShadows
        GradeRange.MIDTONES -> e.gradeMidtones
        GradeRange.HIGHLIGHTS -> e.gradeHighlights
        GradeRange.GLOBAL -> e.gradeGlobal
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        ColorWheel(
            wheel = wheel,
            onChange = { w -> vm.update(withWheel(vm.params, w)) },
            onCommit = { vm.commit() },
            modifier = Modifier.size(190.dp)
        )
    }
    PSlider(vm, "Tonalità", wheel.hue, 0f..360f, default = 0f, gradient = Gradients.Spectrum) { e, v -> withWheel(e, currentWheel(e).copy(hue = v)) }
    PSlider(vm, "Saturazione", wheel.sat, 0f..100f) { e, v -> withWheel(e, currentWheel(e).copy(sat = v)) }
    PSlider(vm, "Luminanza", wheel.lum, -100f..100f) { e, v -> withWheel(e, currentWheel(e).copy(lum = v)) }
    SectionHeader("Regolazioni")
    PSlider(vm, "Fusione", p.gradeBlending, 0f..100f, default = 50f) { e, v -> e.copy(gradeBlending = v) }
    PSlider(vm, "Bilanciamento", p.gradeBalance, -100f..100f) { e, v -> e.copy(gradeBalance = v) }
}

@Composable
private fun PointColorPanel(vm: EditorViewModel, bw: Boolean) {
    val p = vm.params
    SectionHeader("Colore puntuale") {
        IconButton(onClick = { vm.startPick(PickMode.POINT_COLOR) }, enabled = !bw) {
            Icon(
                Icons.Filled.Colorize, "Campiona un colore",
                tint = when {
                    bw -> LuceColors.TextDisabled
                    vm.pickMode == PickMode.POINT_COLOR -> LuceColors.Accent
                    else -> LuceColors.TextPrimary
                }
            )
        }
    }
    if (bw) {
        Hint("Il colore puntuale non è disponibile in bianco e nero.")
        return
    }
    if (p.pointColors.isEmpty()) {
        Hint("Tocca il contagocce, poi tocca un colore della foto per modificarne con precisione tonalità, saturazione e luminanza.")
        return
    }
    // campioni
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        p.pointColors.forEach { pc ->
            val sel = pc.id == vm.selectedPointColorId
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.hsl(pc.hue % 360f, pc.sat.coerceIn(0f, 1f), pc.lum.coerceIn(0.05f, 0.95f)))
                    .border(if (sel) 2.5.dp else 1.dp, if (sel) Color.White else Color(0x55FFFFFF), CircleShape)
                    .clickable { vm.selectedPointColorId = pc.id }
            )
        }
    }
    val pc = vm.selectedPointColor() ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Visualizza intervallo", vm.pointColorViewId == pc.id, { vm.togglePointColorView() }, icon = Icons.Filled.Visibility)
        Pill("Elimina", false, { vm.deletePointColor(pc.id) }, icon = Icons.Filled.Delete)
    }
    PcSlider(vm, "Spostamento tonalità", pc.hueShift, -100f..100f, gradient = listOf(Color.hsv((pc.hue + 300f) % 360f, 0.8f, 0.9f), Color.hsv(pc.hue % 360f, 0.8f, 0.9f), Color.hsv((pc.hue + 60f) % 360f, 0.8f, 0.9f))) { c, v -> c.copy(hueShift = v) }
    PcSlider(vm, "Spostamento saturazione", pc.satShift, -100f..100f, gradient = Gradients.sat(pc.hue)) { c, v -> c.copy(satShift = v) }
    PcSlider(vm, "Spostamento luminanza", pc.lumShift, -100f..100f, gradient = Gradients.lum(pc.hue)) { c, v -> c.copy(lumShift = v) }
    PcSlider(vm, "Varianza", pc.variance, -100f..100f) { c, v -> c.copy(variance = v) }
    SectionHeader("Intervallo")
    PcSlider(vm, "Intervallo", pc.range, 0f..100f, default = 50f) { c, v -> c.copy(range = v) }
    PcSlider(vm, "Intervallo tonalità", pc.hueRange, 0f..100f, default = 50f) { c, v -> c.copy(hueRange = v) }
    PcSlider(vm, "Intervallo saturazione", pc.satRange, 0f..100f, default = 50f) { c, v -> c.copy(satRange = v) }
    PcSlider(vm, "Intervallo luminanza", pc.lumRange, 0f..100f, default = 50f) { c, v -> c.copy(lumRange = v) }
}

@Composable
private fun PcSlider(
    vm: EditorViewModel, label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    default: Float = 0f, gradient: List<Color>? = null, f: (PointColor, Float) -> PointColor
) {
    LuceSlider(
        label, value, range,
        onChange = { v -> vm.selectedPointColor()?.let { vm.updatePointColor(f(it, v), live = true) } },
        onCommit = { vm.commit() }, default = default, gradient = gradient
    )
}

// ---------------- Effetti ----------------

@Composable
fun EffectsPanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Effetti")
    PSlider(vm, "Texture", p.texture, -100f..100f) { e, v -> e.copy(texture = v) }
    PSlider(vm, "Chiarezza", p.clarity, -100f..100f) { e, v -> e.copy(clarity = v) }
    PSlider(vm, "Rimozione foschia", p.dehaze, -100f..100f) { e, v -> e.copy(dehaze = v) }
    SectionHeader("Vignettatura")
    PillRow(listOf(0, 1, 2), p.vignetteStyle, { listOf("Priorità luci", "Priorità colore", "Sovrapposizione pittura")[it] }, { vm.set(vm.params.copy(vignetteStyle = it)) })
    PSlider(vm, "Fattore", p.vignetteAmount, -100f..100f) { e, v -> e.copy(vignetteAmount = v) }
    val vOn = p.vignetteAmount != 0f
    PSlider(vm, "Punto medio", p.vignetteMidpoint, 0f..100f, default = 50f, enabled = vOn) { e, v -> e.copy(vignetteMidpoint = v) }
    PSlider(vm, "Arrotondamento", p.vignetteRoundness, -100f..100f, enabled = vOn) { e, v -> e.copy(vignetteRoundness = v) }
    PSlider(vm, "Sfumatura", p.vignetteFeather, 0f..100f, default = 50f, enabled = vOn) { e, v -> e.copy(vignetteFeather = v) }
    PSlider(vm, "Luci", p.vignetteHighlights, 0f..100f, enabled = vOn && p.vignetteAmount < 0f && p.vignetteStyle != 2) { e, v -> e.copy(vignetteHighlights = v) }
    SectionHeader("Granulosità")
    PSlider(vm, "Fattore", p.grainAmount, 0f..100f) { e, v -> e.copy(grainAmount = v) }
    val gOn = p.grainAmount > 0f
    PSlider(vm, "Dimensione", p.grainSize, 0f..100f, default = 25f, enabled = gOn) { e, v -> e.copy(grainSize = v) }
    PSlider(vm, "Ruvidità", p.grainRoughness, 0f..100f, default = 50f, enabled = gOn) { e, v -> e.copy(grainRoughness = v) }
}

// ---------------- Dettaglio ----------------

@Composable
fun DetailPanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Nitidezza")
    PSlider(vm, "Fattore", p.sharpAmount, 0f..150f) { e, v -> e.copy(sharpAmount = v) }
    val sOn = p.sharpAmount > 0f
    PSlider(vm, "Raggio", p.sharpRadius, 0.5f..3f, default = 1f, decimals = 1, enabled = sOn) { e, v -> e.copy(sharpRadius = v) }
    PSlider(vm, "Dettaglio", p.sharpDetail, 0f..100f, default = 25f, enabled = sOn) { e, v -> e.copy(sharpDetail = v) }
    PSlider(vm, "Mascheratura", p.sharpMasking, 0f..100f, enabled = sOn) { e, v -> e.copy(sharpMasking = v) }
    SectionHeader("Riduzione disturbo")
    PSlider(vm, "Luminanza", p.nrLuminance, 0f..100f) { e, v -> e.copy(nrLuminance = v) }
    PSlider(vm, "Dettaglio", p.nrLumDetail, 0f..100f, default = 50f, enabled = p.nrLuminance > 0f) { e, v -> e.copy(nrLumDetail = v) }
    PSlider(vm, "Contrasto", p.nrLumContrast, 0f..100f, enabled = p.nrLuminance > 0f) { e, v -> e.copy(nrLumContrast = v) }
    PSlider(vm, "Colore", p.nrColor, 0f..100f) { e, v -> e.copy(nrColor = v) }
    PSlider(vm, "Dettaglio colore", p.nrColorDetail, 0f..100f, default = 50f, enabled = p.nrColor > 0f) { e, v -> e.copy(nrColorDetail = v) }
    PSlider(vm, "Uniformità", p.nrColorSmooth, 0f..100f, default = 50f, enabled = p.nrColor > 0f) { e, v -> e.copy(nrColorSmooth = v) }
    Hint("Suggerimento: ingrandisci con due dita per valutare nitidezza e disturbo.")
}

// ---------------- Ottica ----------------

@Composable
fun OpticsPanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Correzioni obiettivo")
    ToggleRow("Rimuovi aberrazione cromatica", p.removeCA) { vm.set(vm.params.copy(removeCA = it)) }
    PSlider(vm, "Distorsione", p.lensDistortion, -100f..100f) { e, v -> e.copy(lensDistortion = v) }
    PSlider(vm, "Vignettatura", p.lensVignette, -100f..100f) { e, v -> e.copy(lensVignette = v) }
    PSlider(vm, "Aberrazione cromatica", p.chromaticAberration, -100f..100f) { e, v -> e.copy(chromaticAberration = v) }
    SectionHeader("Rimuovi frange") {
        Pill("Ripristina", false, {
            vm.set(vm.params.copy(defringePurple = 0f, defringePurpleHueLo = 30f, defringePurpleHueHi = 70f, defringeGreen = 0f, defringeGreenHueLo = 40f, defringeGreenHueHi = 60f))
        })
    }
    val purple = listOf(Color(0xFF6A5BFF), Color(0xFFB040FF), Color(0xFFFF40C0))
    val green = listOf(Color(0xFFD0E040), Color(0xFF40D060), Color(0xFF40C0C0))
    PSlider(vm, "Fattore viola", p.defringePurple, 0f..20f) { e, v -> e.copy(defringePurple = v) }
    PSlider(vm, "Tonalità viola (min)", p.defringePurpleHueLo, 0f..(p.defringePurpleHueHi - 10f).coerceAtLeast(1f), default = 30f, gradient = purple, enabled = p.defringePurple > 0f) { e, v -> e.copy(defringePurpleHueLo = v) }
    PSlider(vm, "Tonalità viola (max)", p.defringePurpleHueHi, (p.defringePurpleHueLo + 10f).coerceAtMost(99f)..100f, default = 70f, gradient = purple, enabled = p.defringePurple > 0f) { e, v -> e.copy(defringePurpleHueHi = v) }
    PSlider(vm, "Fattore verde", p.defringeGreen, 0f..20f) { e, v -> e.copy(defringeGreen = v) }
    PSlider(vm, "Tonalità verde (min)", p.defringeGreenHueLo, 0f..(p.defringeGreenHueHi - 10f).coerceAtLeast(1f), default = 40f, gradient = green, enabled = p.defringeGreen > 0f) { e, v -> e.copy(defringeGreenHueLo = v) }
    PSlider(vm, "Tonalità verde (max)", p.defringeGreenHueHi, (p.defringeGreenHueLo + 10f).coerceAtMost(99f)..100f, default = 60f, gradient = green, enabled = p.defringeGreen > 0f) { e, v -> e.copy(defringeGreenHueHi = v) }
    Hint("Distorsione positiva corregge l'effetto barilotto, negativa il cuscinetto. Le frange vengono desaturate solo lungo i bordi ad alto contrasto.")
}

// ---------------- Geometria ----------------

@Composable
fun GeometryPanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Upright") {
        Pill("Ripristina", false, { vm.resetTransform() }, icon = Icons.Filled.RestartAlt)
    }
    PillRow(Upright.MODES, p.uprightMode, { if (it == "Off") "Disattivato" else it }, { vm.setUpright(it) })
    Hint("Auto bilancia livello e prospettiva · Livello raddrizza l'orizzonte · Verticale corregge le linee che convergono · Completo applica tutte le correzioni.")
    ToggleRow("Vincola ritaglio", p.constrainCrop) { vm.set(vm.params.copy(constrainCrop = it)) }
    SectionHeader("Trasformazione manuale")
    PSlider(vm, "Verticale", p.tVertical, -100f..100f) { e, v -> e.copy(tVertical = v) }
    PSlider(vm, "Orizzontale", p.tHorizontal, -100f..100f) { e, v -> e.copy(tHorizontal = v) }
    PSlider(vm, "Ruota", p.tRotate, -10f..10f, decimals = 1, suffix = "°") { e, v -> e.copy(tRotate = v) }
    PSlider(vm, "Aspetto", p.tAspect, -100f..100f) { e, v -> e.copy(tAspect = v) }
    PSlider(vm, "Scala", p.tScale, 50f..150f, default = 100f) { e, v -> e.copy(tScale = v) }
    PSlider(vm, "Spostamento X", p.tOffsetX, -100f..100f) { e, v -> e.copy(tOffsetX = v) }
    PSlider(vm, "Spostamento Y", p.tOffsetY, -100f..100f) { e, v -> e.copy(tOffsetY = v) }
}

// ---------------- Ritaglia ----------------

@Composable
fun CropPanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Proporzioni")
    PillRow(Geometry.ASPECTS.map { it.label }, p.aspect, { it }, { vm.setAspect(it) })
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ActionButton("Ruota", Icons.Filled.Rotate90DegreesCw, { vm.rotate90() })
        ActionButton("Rifletti ↔", Icons.Filled.Flip, { vm.flipH() }, active = p.flipH)
        ActionButton("Rifletti ↕", Icons.Filled.Flip, { vm.flipV() }, active = p.flipV)
        ActionButton("Ripristina", Icons.Filled.RestartAlt, { vm.resetCrop() })
    }
    SectionHeader("Raddrizza")
    PSlider(vm, "Angolo", p.straighten, -45f..45f, decimals = 1, suffix = "°") { e, v -> e.copy(straighten = v) }
    Hint("Trascina gli angoli o i bordi del riquadro. Trascina all'interno per spostarlo.")
}

// ---------------- Profili ----------------

@Composable
fun ProfilePanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Profilo: ${p.profile}")
    PSlider(vm, "Quantità", p.profileAmount, 0f..200f, default = 100f) { e, v -> e.copy(profileAmount = v) }
    Profiles.all.groupBy { it.group }.forEach { (group, list) ->
        SectionHeader(group)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            list.forEach { prof ->
                Thumb(
                    title = prof.name,
                    image = vm.profileThumbs[prof.name],
                    selected = p.profile == prof.name,
                    onClick = { vm.applyProfile(prof.name) }
                )
            }
        }
    }
}

// ---------------- Predefiniti ----------------

@Composable
fun PresetsPanel(vm: EditorViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Preset?>(null) }
    SectionHeader("Predefiniti") {
        Pill("Crea", false, { showCreate = true }, icon = Icons.Filled.Add)
    }
    val groups = (vm.userPresets + Presets.builtIn).groupBy { it.group }
    groups.forEach { (group, list) ->
        SectionHeader(group)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            list.forEach { preset ->
                Thumb(
                    title = preset.name,
                    image = vm.presetThumbs[preset.id],
                    selected = false,
                    onClick = { vm.applyPreset(preset) },
                    onLongClick = if (!preset.builtIn) ({ toDelete = preset }) else null
                )
            }
        }
    }
    if (vm.userPresets.isNotEmpty()) Hint("Tieni premuto un tuo predefinito per eliminarlo.")

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nuovo predefinito") },
            text = {
                Column {
                    Text("Salva le regolazioni correnti (senza ritaglio e maschere).", style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Nome") })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveUserPreset(name.trim().ifEmpty { "Il mio predefinito" }); showCreate = false }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Annulla") } }
        )
    }
    toDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare \"${preset.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.deleteUserPreset(preset.id); toDelete = null }) { Text("Elimina", color = LuceColors.Danger) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(title: String, image: ImageBitmap?, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LuceColors.SurfaceHigh)
                .border(2.dp, if (selected) LuceColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
        ) {
            if (image != null) {
                Image(image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title, style = MaterialTheme.typography.labelSmall,
            color = if (selected) LuceColors.Accent else LuceColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
