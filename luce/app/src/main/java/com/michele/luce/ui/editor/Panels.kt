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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.michele.luce.engine.GradeWheel
import com.michele.luce.engine.LocalAdjust
import com.michele.luce.engine.Mask
import com.michele.luce.engine.MaskType
import com.michele.luce.engine.Preset
import com.michele.luce.engine.Presets
import com.michele.luce.engine.Profiles
import com.michele.luce.engine.ToneCurve
import com.michele.luce.engine.Treatment
import com.michele.luce.engine.with
import com.michele.luce.engine.Geometry
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
private fun PSlider(
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
fun ToolPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (vm.tool) {
            Tool.PRESETS -> PresetsPanel(vm)
            Tool.PROFILE -> ProfilePanel(vm)
            Tool.CROP -> CropPanel(vm)
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
        Pill("Auto", false, { vm.autoTone() }, icon = Icons.Filled.AutoAwesome)
    }
    PSlider(vm, "Esposizione", p.exposure, -5f..5f, decimals = 2) { e, v -> e.copy(exposure = v) }
    PSlider(vm, "Contrasto", p.contrast, -100f..100f) { e, v -> e.copy(contrast = v) }
    PSlider(vm, "Luci", p.highlights, -100f..100f) { e, v -> e.copy(highlights = v) }
    PSlider(vm, "Ombre", p.shadows, -100f..100f) { e, v -> e.copy(shadows = v) }
    PSlider(vm, "Bianchi", p.whites, -100f..100f) { e, v -> e.copy(whites = v) }
    PSlider(vm, "Neri", p.blacks, -100f..100f) { e, v -> e.copy(blacks = v) }

    var channel by rememberSaveable { mutableStateOf(CurveChannel.RGB) }
    SectionHeader("Curva") {
        Pill("Ripristina", false, {
            vm.set(vm.params.copy(
                curveMaster = ToneCurve(), curveRed = ToneCurve(), curveGreen = ToneCurve(), curveBlue = ToneCurve(),
                parHighlights = 0f, parLights = 0f, parDarks = 0f, parShadows = 0f
            ))
        })
    }
    PillRow(CurveChannel.entries, channel, { it.label }, { channel = it })
    if (channel == CurveChannel.PARAMETRIC) {
        PSlider(vm, "Luci", p.parHighlights, -100f..100f) { e, v -> e.copy(parHighlights = v) }
        PSlider(vm, "Chiari", p.parLights, -100f..100f) { e, v -> e.copy(parLights = v) }
        PSlider(vm, "Scuri", p.parDarks, -100f..100f) { e, v -> e.copy(parDarks = v) }
        PSlider(vm, "Ombre", p.parShadows, -100f..100f) { e, v -> e.copy(parShadows = v) }
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
        Text(
            "Tocca e trascina per aggiungere punti · doppio tocco su un punto per eliminarlo",
            style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ---------------- Colore ----------------

private enum class ColorTab(val label: String) { BASE("Base"), MIXER("Miscelazione"), GRADING("Color grading") }
private enum class GradeRange(val label: String) { SHADOWS("Ombre"), MIDTONES("Mezzitoni"), HIGHLIGHTS("Luci"), GLOBAL("Globale") }

private val bandColors = listOf(
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047),
    Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFD81B60)
)

@Composable
fun ColorPanel(vm: EditorViewModel) {
    val p = vm.params
    var tab by rememberSaveable { mutableStateOf(ColorTab.BASE) }
    PillRow(ColorTab.entries, tab, { it.label }, { tab = it }, Modifier.padding(top = 6.dp))
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Auto", false, { vm.autoWhiteBalance() })
                    Pill("Come scattato", false, { vm.set(vm.params.copy(temperature = 0f, tint = 0f)) })
                }
            }
            PSlider(vm, "Temperatura", p.temperature, -100f..100f, gradient = Gradients.Temperature) { e, v -> e.copy(temperature = v) }
            PSlider(vm, "Tinta", p.tint, -100f..100f, gradient = Gradients.Tint) { e, v -> e.copy(tint = v) }
            SectionHeader("Presenza")
            PSlider(vm, "Vividezza", p.vibrance, -100f..100f, enabled = !bw) { e, v -> e.copy(vibrance = v) }
            PSlider(vm, "Saturazione", p.saturation, -100f..100f, enabled = !bw) { e, v -> e.copy(saturation = v) }
        }
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
        ColorTab.GRADING -> {
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
    }
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
    PSlider(vm, "Fattore", p.vignetteAmount, -100f..100f) { e, v -> e.copy(vignetteAmount = v) }
    val vOn = p.vignetteAmount != 0f
    PSlider(vm, "Punto medio", p.vignetteMidpoint, 0f..100f, default = 50f, enabled = vOn) { e, v -> e.copy(vignetteMidpoint = v) }
    PSlider(vm, "Arrotondamento", p.vignetteRoundness, -100f..100f, enabled = vOn) { e, v -> e.copy(vignetteRoundness = v) }
    PSlider(vm, "Sfumatura", p.vignetteFeather, 0f..100f, default = 50f, enabled = vOn) { e, v -> e.copy(vignetteFeather = v) }
    PSlider(vm, "Luci", p.vignetteHighlights, 0f..100f, enabled = vOn && p.vignetteAmount < 0f) { e, v -> e.copy(vignetteHighlights = v) }
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
    PSlider(vm, "Colore", p.nrColor, 0f..100f) { e, v -> e.copy(nrColor = v) }
    PSlider(vm, "Dettaglio colore", p.nrColorDetail, 0f..100f, default = 50f, enabled = p.nrColor > 0f) { e, v -> e.copy(nrColorDetail = v) }
    Text(
        "Suggerimento: ingrandisci con due dita per valutare nitidezza e disturbo.",
        style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

// ---------------- Ottica ----------------

@Composable
fun OpticsPanel(vm: EditorViewModel) {
    val p = vm.params
    SectionHeader("Correzioni obiettivo")
    PSlider(vm, "Distorsione", p.lensDistortion, -100f..100f) { e, v -> e.copy(lensDistortion = v) }
    PSlider(vm, "Vignettatura", p.lensVignette, -100f..100f) { e, v -> e.copy(lensVignette = v) }
    PSlider(vm, "Aberrazione cromatica", p.chromaticAberration, -100f..100f) { e, v -> e.copy(chromaticAberration = v) }
    Text(
        "Distorsione positiva corregge l'effetto barilotto, negativa il cuscinetto. L'aberrazione cromatica sposta i canali rosso e blu ai bordi.",
        style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
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
    Text(
        "Trascina gli angoli o i bordi del riquadro. Trascina all'interno per spostarlo.",
        style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
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

@OptIn(ExperimentalFoundationApi::class)
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
    if (vm.userPresets.isNotEmpty()) {
        Text(
            "Tieni premuto un tuo predefinito per eliminarlo.",
            style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

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

// ---------------- Maschere ----------------

@Composable
fun MasksPanel(vm: EditorViewModel) {
    val p = vm.params
    val selected = vm.selectedMask()
    if (selected == null) {
        SectionHeader("Maschere")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton("Pennello", Icons.Filled.Brush, { vm.addMask(MaskType.BRUSH) })
            ActionButton("Lineare", Icons.Filled.Gradient, { vm.addMask(MaskType.LINEAR) })
            ActionButton("Radiale", Icons.Filled.RadioButtonUnchecked, { vm.addMask(MaskType.RADIAL) })
        }
        if (p.masks.isEmpty()) {
            Text(
                "Le maschere applicano regolazioni solo a una parte della foto. Scegli un tipo per iniziare.",
                style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
                modifier = Modifier.padding(16.dp)
            )
        }
        p.masks.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectMask(m.id) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (m.type) { MaskType.BRUSH -> Icons.Filled.Brush; MaskType.LINEAR -> Icons.Filled.Gradient; MaskType.RADIAL -> Icons.Filled.RadioButtonUnchecked },
                    null, tint = LuceColors.TextSecondary
                )
                Text(m.name, color = LuceColors.TextPrimary, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                IconButton(onClick = { vm.updateMask(m.copy(visible = !m.visible), live = false) }) {
                    Icon(if (m.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Visibilità", tint = LuceColors.TextSecondary)
                }
            }
        }
        return
    }

    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.selectMask(null) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tutte le maschere", tint = LuceColors.TextPrimary)
        }
        Icon(Icons.Filled.Layers, null, tint = LuceColors.Accent)
        Text(selected.name, style = MaterialTheme.typography.titleMedium, color = LuceColors.TextPrimary, modifier = Modifier.padding(start = 8.dp).weight(1f))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ActionButton("Inverti", Icons.Filled.InvertColors, { vm.updateMask(selected.copy(invert = !selected.invert), live = false) }, active = selected.invert)
        ActionButton("Sovrapposiz.", Icons.Filled.Visibility, { vm.toggleOverlay() }, active = vm.showMaskOverlay)
        ActionButton("Duplica", Icons.Filled.ContentCopy, { vm.duplicateMask(selected.id) })
        ActionButton("Elimina", Icons.Filled.Delete, { vm.deleteMask(selected.id) })
    }

    when (selected.type) {
        MaskType.BRUSH -> {
            SectionHeader("Pennello") {
                Pill("Cancella tratti", false, { vm.clearBrush() })
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Aggiungi", vm.brush.mode == BrushMode.PAINT, { vm.brush = vm.brush.copy(mode = BrushMode.PAINT) }, icon = Icons.Filled.Brush)
                Pill("Gomma", vm.brush.mode == BrushMode.ERASE, { vm.brush = vm.brush.copy(mode = BrushMode.ERASE) })
            }
            LuceSlider("Dimensione", vm.brush.size * 100f, 1f..30f, { vm.brush = vm.brush.copy(size = it / 100f) }, {}, default = 6f)
            LuceSlider("Sfumatura", vm.brush.feather * 100f, 0f..100f, { vm.brush = vm.brush.copy(feather = it / 100f) }, {}, default = 60f)
            LuceSlider("Flusso", vm.brush.flow * 100f, 5f..100f, { vm.brush = vm.brush.copy(flow = it / 100f) }, {}, default = 80f)
            Text(
                "Dipingi con un dito sulla foto · usa due dita per ingrandire e spostarti.",
                style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        MaskType.RADIAL -> {
            SectionHeader("Forma")
            MaskSlider(vm, selected, "Sfumatura", selected.feather, 0f..100f, 50f) { m, v -> m.copy(feather = v) }
            MaskSlider(vm, selected, "Angolo", selected.angle, -180f..180f, 0f, suffix = "°") { m, v -> m.copy(angle = v) }
            Text(
                "Trascina il centro per spostare, le maniglie per ridimensionare e ruotare.",
                style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        MaskType.LINEAR -> {
            Text(
                "La regolazione è piena sulla linea continua e svanisce fino alla linea tratteggiata.",
                style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    val a = selected.adjust
    SectionHeader("Regolazioni locali") {
        Pill("Ripristina", false, { vm.updateMask(selected.copy(adjust = LocalAdjust()), live = false) })
    }
    LocalSlider(vm, selected, "Esposizione", a.exposure, -4f..4f, 2) { l, v -> l.copy(exposure = v) }
    LocalSlider(vm, selected, "Contrasto", a.contrast, -100f..100f) { l, v -> l.copy(contrast = v) }
    LocalSlider(vm, selected, "Luci", a.highlights, -100f..100f) { l, v -> l.copy(highlights = v) }
    LocalSlider(vm, selected, "Ombre", a.shadows, -100f..100f) { l, v -> l.copy(shadows = v) }
    LocalSlider(vm, selected, "Bianchi", a.whites, -100f..100f) { l, v -> l.copy(whites = v) }
    LocalSlider(vm, selected, "Neri", a.blacks, -100f..100f) { l, v -> l.copy(blacks = v) }
    LocalSlider(vm, selected, "Temperatura", a.temperature, -100f..100f, gradient = Gradients.Temperature) { l, v -> l.copy(temperature = v) }
    LocalSlider(vm, selected, "Tinta", a.tint, -100f..100f, gradient = Gradients.Tint) { l, v -> l.copy(tint = v) }
    LocalSlider(vm, selected, "Saturazione", a.saturation, -100f..100f) { l, v -> l.copy(saturation = v) }
    LocalSlider(vm, selected, "Texture", a.texture, -100f..100f) { l, v -> l.copy(texture = v) }
    LocalSlider(vm, selected, "Chiarezza", a.clarity, -100f..100f) { l, v -> l.copy(clarity = v) }
    LocalSlider(vm, selected, "Rimozione foschia", a.dehaze, -100f..100f) { l, v -> l.copy(dehaze = v) }
}

@Composable
private fun MaskSlider(
    vm: EditorViewModel, mask: Mask, label: String, value: Float, range: ClosedFloatingPointRange<Float>, default: Float,
    suffix: String = "", apply: (Mask, Float) -> Mask
) {
    LuceSlider(
        label, value, range,
        onChange = { v -> vm.selectedMask()?.let { vm.updateMask(apply(it, v), live = true) } },
        onCommit = { vm.commit() },
        default = default, suffix = suffix
    )
}

@Composable
private fun LocalSlider(
    vm: EditorViewModel, mask: Mask, label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    decimals: Int = 0, gradient: List<Color>? = null, apply: (LocalAdjust, Float) -> LocalAdjust
) {
    LuceSlider(
        label, value, range,
        onChange = { v -> vm.selectedMask()?.let { m -> vm.updateMask(m.copy(adjust = apply(m.adjust, v)), live = true) } },
        onCommit = { vm.commit() },
        decimals = decimals, gradient = gradient
    )
}
