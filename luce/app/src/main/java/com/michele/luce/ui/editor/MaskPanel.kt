package com.michele.luce.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michele.luce.engine.AiMasks
import com.michele.luce.engine.CombineMode
import com.michele.luce.engine.ComponentType
import com.michele.luce.engine.LocalAdjust
import com.michele.luce.engine.Mask
import com.michele.luce.engine.MaskComponent
import com.michele.luce.ui.components.ActionButton
import com.michele.luce.ui.components.Gradients
import com.michele.luce.ui.components.LuceSlider
import com.michele.luce.ui.components.Pill
import com.michele.luce.ui.components.PillRow
import com.michele.luce.ui.components.SectionHeader
import com.michele.luce.ui.theme.LuceColors

private fun MaskComponent.icon(): ImageVector = when (type) {
    ComponentType.BRUSH -> Icons.Filled.Brush
    ComponentType.LINEAR -> Icons.Filled.Gradient
    ComponentType.RADIAL -> Icons.Filled.RadioButtonUnchecked
    ComponentType.COLOR_RANGE -> Icons.Filled.Palette
    ComponentType.LUMINANCE_RANGE -> Icons.Filled.Contrast
    ComponentType.AI -> when {
        aiKey == "sky" || aiKey == "land:sky" -> Icons.Filled.Cloud
        aiKey == "background" -> Icons.Filled.Wallpaper
        aiKey == "subject" -> Icons.Filled.CenterFocusWeak
        aiKey.startsWith("object") -> Icons.Filled.TouchApp
        aiKey.startsWith("land:") -> Icons.Filled.Landscape
        else -> Icons.Filled.Person
    }
}

private fun CombineMode.symbol() = when (this) {
    CombineMode.ADD -> "+"
    CombineMode.SUBTRACT -> "−"
    CombineMode.INTERSECT -> "∩"
}

@Composable
fun MasksPanel(vm: EditorViewModel) {
    val mask = vm.selectedMask()
    if (mask == null) MaskList(vm) else MaskDetail(vm, mask)
}

// ------------------------------------------------------------------
// Scelta del tipo (nuova maschera o componente)
// ------------------------------------------------------------------

@Composable
private fun TypeChooser(vm: EditorViewModel, mode: CombineMode?, onChosen: () -> Unit) {
    var people by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    val ai = vm.aiAvailable
    SectionHeader("Selezioni intelligenti")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ActionButton("Soggetto", Icons.Filled.CenterFocusWeak, { vm.createAi(AiMasks.SUBJECT, mode); onChosen() }, enabled = ai)
        ActionButton("Cielo", Icons.Filled.Cloud, { vm.createAi(AiMasks.SKY, mode); onChosen() }, enabled = ai)
        ActionButton("Sfondo", Icons.Filled.Wallpaper, { vm.createAi(AiMasks.BACKGROUND, mode); onChosen() }, enabled = ai)
        ActionButton("Oggetti", Icons.Filled.TouchApp, { vm.startObjectPick(mode); onChosen() }, enabled = ai)
        ActionButton("Persone", Icons.Filled.Person, { people = true }, enabled = ai)
        ActionButton("Paesaggio", Icons.Filled.Landscape, { landscape = true }, enabled = ai)
    }
    if (!ai) Hint("Le selezioni intelligenti non sono incluse in questa build.")
    SectionHeader("Strumenti")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ActionButton("Pennello", Icons.Filled.Brush, { vm.createComponent(ComponentType.BRUSH, mode); onChosen() })
        ActionButton("Lineare", Icons.Filled.Gradient, { vm.createComponent(ComponentType.LINEAR, mode); onChosen() })
        ActionButton("Radiale", Icons.Filled.RadioButtonUnchecked, { vm.createComponent(ComponentType.RADIAL, mode); onChosen() })
        ActionButton("Colore", Icons.Filled.Palette, { vm.createComponent(ComponentType.COLOR_RANGE, mode); onChosen() })
        ActionButton("Luminanza", Icons.Filled.Contrast, { vm.createComponent(ComponentType.LUMINANCE_RANGE, mode); onChosen() })
    }
    if (people) {
        TargetsDialog("Persone", "Scegli cosa selezionare", AiMasks.PEOPLE, listOf("person"), onDismiss = { people = false }) {
            people = false
            vm.createAi(it, mode)
            onChosen()
        }
    }
    if (landscape) {
        TargetsDialog("Paesaggio", "Scegli gli elementi da selezionare", AiMasks.LANDSCAPE.map { it.first }, listOf("land:sky"), onDismiss = { landscape = false }) {
            landscape = false
            vm.createAi(it, mode)
            onChosen()
        }
    }
}

@Composable
private fun TargetsDialog(
    title: String, subtitle: String, targets: List<AiMasks.Target>, initial: List<String>,
    onDismiss: () -> Unit, onConfirm: (List<AiMasks.Target>) -> Unit
) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                targets.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (t.key in chosen) chosen.remove(t.key) else chosen.add(t.key) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = t.key in chosen,
                            onCheckedChange = { c -> if (c) chosen.add(t.key) else chosen.remove(t.key) },
                            colors = CheckboxDefaults.colors(checkedColor = LuceColors.Accent)
                        )
                        Text(t.label, color = LuceColors.TextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(targets.filter { it.key in chosen }) }, enabled = chosen.isNotEmpty()) { Text("Crea") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

// ------------------------------------------------------------------
// Elenco delle maschere
// ------------------------------------------------------------------

@Composable
private fun MaskList(vm: EditorViewModel) {
    SectionHeader("Crea nuova maschera")
    TypeChooser(vm, null) { }
    if (vm.params.masks.isNotEmpty()) {
        SectionHeader("Maschere")
        vm.params.masks.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectMask(m.id) }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(m.components.firstOrNull()?.icon() ?: Icons.Filled.Layers, null, tint = LuceColors.TextSecondary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(m.name, color = LuceColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${m.components.size} " + if (m.components.size == 1) "componente" else "componenti",
                        style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary
                    )
                }
                IconButton(onClick = { vm.updateMask(m.copy(visible = !m.visible), live = false) }) {
                    Icon(if (m.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Visibilità", tint = LuceColors.TextSecondary)
                }
            }
        }
    } else {
        Hint("Le maschere applicano regolazioni solo a una parte della foto. Combina più selezioni con Aggiungi, Sottrai e Interseca.")
    }
}

// ------------------------------------------------------------------
// Dettaglio di una maschera
// ------------------------------------------------------------------

@Composable
private fun MaskDetail(vm: EditorViewModel, mask: Mask) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<CombineMode?>(null) }

    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.selectMask(null) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tutte le maschere", tint = LuceColors.TextPrimary)
        }
        Text(
            mask.name, style = MaterialTheme.typography.titleMedium, color = LuceColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { rename = true }
        )
        IconButton(onClick = { vm.updateMask(mask.copy(visible = !mask.visible), live = false) }) {
            Icon(if (mask.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Visibilità", tint = LuceColors.TextSecondary)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro", tint = LuceColors.TextPrimary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menu = false; rename = true })
                DropdownMenuItem(text = { Text("Duplica") }, onClick = { menu = false; vm.duplicateMask(mask.id, invert = false) })
                DropdownMenuItem(text = { Text("Duplica e inverti") }, onClick = { menu = false; vm.duplicateMask(mask.id, invert = true) })
                DropdownMenuItem(text = { Text(if (mask.invert) "Annulla inversione" else "Inverti maschera") }, onClick = { menu = false; vm.updateMask(mask.copy(invert = !mask.invert), live = false) })
                DropdownMenuItem(text = { Text("Elimina maschera", color = LuceColors.Danger) }, onClick = { menu = false; vm.deleteMask(mask.id) })
            }
        }
    }

    // componenti
    mask.components.forEachIndexed { i, c ->
        val sel = c.id == vm.selectedComponentId
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (sel) LuceColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
                .clickable { vm.selectComponent(c.id) }
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (i == 0) LuceColors.SurfaceHigh else LuceColors.AccentDim),
                contentAlignment = Alignment.Center
            ) {
                Text(c.mode.symbol(), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Icon(c.icon(), null, tint = if (sel) LuceColors.Accent else LuceColors.TextSecondary, modifier = Modifier.padding(start = 10.dp).size(20.dp))
            Text(
                c.label() + if (c.invert) " (invertito)" else "",
                color = if (sel) LuceColors.Accent else LuceColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            IconButton(onClick = { vm.updateComponent(c.copy(invert = !c.invert), live = false) }) {
                Icon(Icons.Filled.InvertColors, "Inverti", tint = if (c.invert) LuceColors.Accent else LuceColors.TextSecondary)
            }
            IconButton(onClick = { vm.deleteComponent(c.id) }) {
                Icon(Icons.Filled.Delete, "Elimina componente", tint = LuceColors.TextSecondary)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Aggiungi", chooser == CombineMode.ADD, { chooser = if (chooser == CombineMode.ADD) null else CombineMode.ADD })
        Pill("Sottrai", chooser == CombineMode.SUBTRACT, { chooser = if (chooser == CombineMode.SUBTRACT) null else CombineMode.SUBTRACT })
        Pill("Interseca", chooser == CombineMode.INTERSECT, { chooser = if (chooser == CombineMode.INTERSECT) null else CombineMode.INTERSECT })
    }
    chooser?.let { mode ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(LuceColors.Surface).padding(bottom = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (mode) { CombineMode.ADD -> "Aggiungi alla maschera"; CombineMode.SUBTRACT -> "Sottrai dalla maschera"; CombineMode.INTERSECT -> "Interseca con la maschera" },
                    style = MaterialTheme.typography.labelMedium, color = LuceColors.TextPrimary, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { chooser = null }) { Icon(Icons.Filled.Close, "Chiudi", tint = LuceColors.TextSecondary) }
            }
            TypeChooser(vm, mode) { chooser = null }
        }
    }

    // sovrapposizione
    SectionHeader("Sovrapposizione") {
        androidx.compose.material3.Switch(
            checked = vm.showMaskOverlay, onCheckedChange = { vm.setOverlay(show = it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = LuceColors.Accent)
        )
    }
    if (vm.showMaskOverlay) {
        PillRow(OverlayStyle.entries, vm.overlayStyle, { it.label }, { vm.setOverlay(style = it) })
        if (vm.overlayStyle == OverlayStyle.COLOR) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFF3030, 0x30D050, 0x3D9BFF, 0xFFD030, 0xFFFFFF).forEach { c ->
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF000000.toInt() or c))
                            .border(if (vm.overlayColor == c) 2.5.dp else 1.dp, if (vm.overlayColor == c) LuceColors.Accent else Color(0x55FFFFFF), CircleShape)
                            .clickable { vm.setOverlay(color = c) }
                    )
                }
            }
            LuceSlider("Opacità", vm.overlayOpacity * 100f, 10f..100f, { vm.setOverlay(opacity = it / 100f) }, { }, default = 60f)
        }
    }

    // impostazioni del componente selezionato
    vm.selectedComponent()?.let { ComponentSettings(vm, it) }

    // regolazioni
    val a = mask.adjust
    SectionHeader("Regolazioni") {
        Pill("Ripristina", false, { vm.updateMask(mask.copy(adjust = LocalAdjust(), amount = 100f), live = false) })
    }
    LuceSlider(
        "Quantità", mask.amount, 0f..200f,
        onChange = { v -> vm.selectedMask()?.let { vm.updateMask(it.copy(amount = v), live = true) } },
        onCommit = { vm.commit() }, default = 100f, suffix = "%"
    )
    SectionHeader("Luce")
    LocalSlider(vm, "Esposizione", a.exposure, -4f..4f, 2) { l, v -> l.copy(exposure = v) }
    LocalSlider(vm, "Contrasto", a.contrast, -100f..100f) { l, v -> l.copy(contrast = v) }
    LocalSlider(vm, "Luci", a.highlights, -100f..100f) { l, v -> l.copy(highlights = v) }
    LocalSlider(vm, "Ombre", a.shadows, -100f..100f) { l, v -> l.copy(shadows = v) }
    LocalSlider(vm, "Bianchi", a.whites, -100f..100f) { l, v -> l.copy(whites = v) }
    LocalSlider(vm, "Neri", a.blacks, -100f..100f) { l, v -> l.copy(blacks = v) }
    SectionHeader("Colore")
    LocalSlider(vm, "Temperatura", a.temperature, -100f..100f, gradient = Gradients.Temperature) { l, v -> l.copy(temperature = v) }
    LocalSlider(vm, "Tinta", a.tint, -100f..100f, gradient = Gradients.Tint) { l, v -> l.copy(tint = v) }
    LocalSlider(vm, "Tonalità", a.hue, -180f..180f) { l, v -> l.copy(hue = v) }
    LocalSlider(vm, "Saturazione", a.saturation, -100f..100f) { l, v -> l.copy(saturation = v) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Colore", style = MaterialTheme.typography.bodyMedium, color = LuceColors.TextPrimary, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (a.colorSat > 0f) Color.hsv(a.colorHue % 360f, (a.colorSat / 100f).coerceIn(0f, 1f), 0.9f) else LuceColors.SurfaceHigh)
                .border(1.dp, Color(0x55FFFFFF), CircleShape)
        )
    }
    LocalSlider(vm, "Tonalità colore", a.colorHue, 0f..360f, gradient = Gradients.Spectrum) { l, v -> l.copy(colorHue = v) }
    LocalSlider(vm, "Saturazione colore", a.colorSat, 0f..100f, gradient = Gradients.sat(a.colorHue)) { l, v -> l.copy(colorSat = v) }
    SectionHeader("Effetti")
    LocalSlider(vm, "Texture", a.texture, -100f..100f) { l, v -> l.copy(texture = v) }
    LocalSlider(vm, "Chiarezza", a.clarity, -100f..100f) { l, v -> l.copy(clarity = v) }
    LocalSlider(vm, "Rimozione foschia", a.dehaze, -100f..100f) { l, v -> l.copy(dehaze = v) }
    SectionHeader("Dettaglio")
    LocalSlider(vm, "Nitidezza", a.sharpness, -100f..100f) { l, v -> l.copy(sharpness = v) }
    LocalSlider(vm, "Disturbo", a.noise, -100f..100f) { l, v -> l.copy(noise = v) }
    LocalSlider(vm, "Moiré", a.moire, -100f..100f) { l, v -> l.copy(moire = v) }
    LocalSlider(vm, "Rimuovi frange", a.defringe, -100f..100f) { l, v -> l.copy(defringe = v) }

    if (rename) {
        var name by remember { mutableStateOf(mask.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Rinomina maschera") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameMask(mask.id, name); rename = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Annulla") } }
        )
    }
}

@Composable
private fun ComponentSettings(vm: EditorViewModel, c: MaskComponent) {
    when (c.type) {
        ComponentType.BRUSH -> {
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
            LuceSlider("Densità", vm.brush.density * 100f, 5f..100f, { vm.brush = vm.brush.copy(density = it / 100f) }, {}, default = 100f)
            ToggleRow("Maschera automatica", vm.brush.autoMask) { vm.brush = vm.brush.copy(autoMask = it) }
            Hint("Dipingi con un dito sulla foto · due dita per ingrandire. Con la maschera automatica il pennello si ferma ai bordi dei colori diversi.")
        }
        ComponentType.RADIAL -> {
            SectionHeader("Gradiente radiale")
            LuceSlider("Sfumatura", c.feather, 0f..100f,
                { v -> vm.selectedComponent()?.let { vm.updateComponent(it.copy(feather = v), live = true) } }, { vm.commit() }, default = 50f)
            LuceSlider("Angolo", c.angle, -180f..180f,
                { v -> vm.selectedComponent()?.let { vm.updateComponent(it.copy(angle = v), live = true) } }, { vm.commit() }, suffix = "°")
            Hint("Trascina il centro per spostare, le maniglie per ridimensionare e ruotare.")
        }
        ComponentType.LINEAR -> {
            SectionHeader("Gradiente lineare")
            Hint("L'effetto è pieno sulla linea continua e svanisce fino alla linea tratteggiata. Trascina i punti per regolarlo.")
        }
        ComponentType.COLOR_RANGE -> {
            SectionHeader("Intervallo colore") {
                IconButton(onClick = { vm.startPick(PickMode.COLOR_RANGE) }) {
                    Icon(Icons.Filled.Colorize, "Campiona colori", tint = if (vm.pickMode == PickMode.COLOR_RANGE) LuceColors.Accent else LuceColors.TextPrimary)
                }
            }
            val n = c.colorSamples.size / 3
            if (n == 0) Hint("Tocca la foto per scegliere il colore da selezionare.")
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(Color(c.colorSamples[i * 3].coerceIn(0f, 1f), c.colorSamples[i * 3 + 1].coerceIn(0f, 1f), c.colorSamples[i * 3 + 2].coerceIn(0f, 1f)))
                            .border(1.dp, Color(0x55FFFFFF), CircleShape)
                    )
                }
                if (n > 0) {
                    Spacer(Modifier.width(4.dp))
                    Pill("Svuota", false, { vm.updateComponent(c.copy(colorSamples = emptyList()), live = false) })
                }
            }
            LuceSlider("Rifinisci", c.refine, 0f..100f,
                { v -> vm.selectedComponent()?.let { vm.updateComponent(it.copy(refine = v), live = true) } }, { vm.commit() }, default = 50f)
        }
        ComponentType.LUMINANCE_RANGE -> {
            SectionHeader("Intervallo luminanza") {
                IconButton(onClick = { vm.startPick(PickMode.LUMINANCE_RANGE) }) {
                    Icon(Icons.Filled.Colorize, "Campiona la luminosità", tint = if (vm.pickMode == PickMode.LUMINANCE_RANGE) LuceColors.Accent else LuceColors.TextPrimary)
                }
            }
            LumRangeBar(c)
            fun upd(f: (MaskComponent) -> MaskComponent) { vm.selectedComponent()?.let { vm.updateComponent(f(it), live = true) } }
            LuceSlider("Minimo", c.lumLo * 100f, 0f..(c.lumHi * 100f).coerceAtLeast(1f), { v -> upd { it.copy(lumLo = v / 100f) } }, { vm.commit() }, default = 25f)
            LuceSlider("Massimo", c.lumHi * 100f, (c.lumLo * 100f).coerceAtMost(99f)..100f, { v -> upd { it.copy(lumHi = v / 100f) } }, { vm.commit() }, default = 75f)
            LuceSlider("Sfumatura scuri", c.lumFeatherLo * 100f, 0f..50f, { v -> upd { it.copy(lumFeatherLo = v / 100f) } }, { vm.commit() }, default = 10f)
            LuceSlider("Sfumatura chiari", c.lumFeatherHi * 100f, 0f..50f, { v -> upd { it.copy(lumFeatherHi = v / 100f) } }, { vm.commit() }, default = 10f)
            LuceSlider("Uniformità", c.lumSmooth, 0f..100f, { v -> upd { it.copy(lumSmooth = v) } }, { vm.commit() })
        }
        ComponentType.AI -> {
            SectionHeader(c.aiLabel.ifBlank { "Selezione intelligente" })
            Hint("Selezione calcolata sul telefono dai modelli di segmentazione. Combinala con altri strumenti per rifinirla.")
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Sottrai con pennello", false, { vm.createComponent(ComponentType.BRUSH, CombineMode.SUBTRACT) }, icon = Icons.Filled.Brush)
                Pill("Aggiungi con pennello", false, { vm.createComponent(ComponentType.BRUSH, CombineMode.ADD) }, icon = Icons.Filled.AutoAwesome)
            }
        }
    }
}

@Composable
private fun LumRangeBar(c: MaskComponent) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 6.dp).height(18.dp)) {
        val w = size.width
        val h = size.height
        for (i in 0 until 64) {
            val v = i / 63f
            val wgt = smoothStep(c.lumLo - c.lumFeatherLo, c.lumLo, v) * (1f - smoothStep(c.lumHi, c.lumHi + c.lumFeatherHi, v))
            val g = v
            drawRect(Color(g, g, g), androidx.compose.ui.geometry.Offset(w * i / 64f, 0f), androidx.compose.ui.geometry.Size(w / 64f + 1f, h * 0.55f))
            drawRect(LuceColors.Accent.copy(alpha = wgt), androidx.compose.ui.geometry.Offset(w * i / 64f, h * 0.65f), androidx.compose.ui.geometry.Size(w / 64f + 1f, h * 0.35f))
        }
    }
}

private fun smoothStep(e0: Float, e1: Float, x: Float): Float {
    if (e1 <= e0) return if (x >= e1) 1f else 0f
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
private fun LocalSlider(
    vm: EditorViewModel, label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    decimals: Int = 0, gradient: List<Color>? = null, apply: (LocalAdjust, Float) -> LocalAdjust
) {
    LuceSlider(
        label, value, range,
        onChange = { v -> vm.selectedMask()?.let { m -> vm.updateMask(m.copy(adjust = apply(m.adjust, v)), live = true) } },
        onCommit = { vm.commit() },
        decimals = decimals, gradient = gradient
    )
}
