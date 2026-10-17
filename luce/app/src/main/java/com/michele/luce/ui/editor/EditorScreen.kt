package com.michele.luce.ui.editor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.michele.luce.data.ExportFormat
import com.michele.luce.data.ExportSettings
import com.michele.luce.ui.components.LuceSlider
import com.michele.luce.ui.components.Pill
import com.michele.luce.ui.components.SectionHeader
import com.michele.luce.ui.theme.LuceColors

private fun Tool.icon(): ImageVector = when (this) {
    Tool.PRESETS -> Icons.Filled.Style
    Tool.PROFILE -> Icons.Filled.Filter
    Tool.CROP -> Icons.Filled.Crop
    Tool.GEOMETRY -> Icons.Filled.Transform
    Tool.MASKS -> Icons.Filled.Layers
    Tool.LIGHT -> Icons.Filled.WbSunny
    Tool.COLOR -> Icons.Filled.Palette
    Tool.EFFECTS -> Icons.Filled.AutoFixHigh
    Tool.DETAIL -> Icons.Filled.Grain
    Tool.OPTICS -> Icons.Filled.CenterFocusStrong
}

@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showExport by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportSettings?>(null) }

    BackHandler {
        vm.flushSave()
        onBack()
    }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            vm.message = null
            snackbar.showSnackbar(m)
        }
    }
    LaunchedEffect(vm.exportResult) {
        when (val r = vm.exportResult) {
            is ExportResult.Share -> {
                vm.exportResult = null
                try {
                    context.startActivity(r.intent)
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar("Nessuna app disponibile per la condivisione")
                }
            }
            is ExportResult.Saved -> {
                vm.exportResult = null
                showExport = false
                snackbar.showSnackbar(r.message)
            }
            is ExportResult.Failed -> {
                vm.exportResult = null
                snackbar.showSnackbar(r.message)
            }
            null -> Unit
        }
    }

    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val s = pendingExport
        pendingExport = null
        if (granted && s != null) vm.export(s, share = false)
        else if (!granted) vm.message = "Permesso di archiviazione negato"
    }

    fun saveToGallery(s: ExportSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = s
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.export(s, share = false)
        }
    }

    Box(Modifier.fillMaxSize().background(LuceColors.Background)) {
        when {
            vm.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = LuceColors.Accent)
                Spacer(Modifier.height(12.dp))
                Text("Apertura della foto…", color = LuceColors.TextSecondary)
            }
            vm.error != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(vm.error ?: "", color = LuceColors.TextPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Torna alla libreria") }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val boxW = this.maxWidth
                val boxH = this.maxHeight
                val landscape = boxW > boxH
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            TopBar(vm, onBack, { showExport = true }, { showInfo = true })
                            Viewport(vm, Modifier.weight(1f).fillMaxWidth())
                        }
                        Column(
                            Modifier
                                .width(if (boxW > 900.dp) 360.dp else 300.dp)
                                .fillMaxHeight()
                                .background(LuceColors.Panel)
                        ) {
                            PanelTitle(vm)
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                ToolPanel(vm)
                            }
                        }
                        ToolRail(vm, Modifier.fillMaxHeight().width(76.dp))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(vm, onBack, { showExport = true }, { showInfo = true })
                        Viewport(vm, Modifier.weight(1f).fillMaxWidth())
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = boxH * 0.42f)
                                .background(LuceColors.Panel)
                                .verticalScroll(rememberScrollState())
                        ) {
                            ToolPanel(vm)
                        }
                        ToolStrip(vm)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    if (showExport) {
        ExportDialog(
            vm = vm,
            onDismiss = { if (!vm.exporting) showExport = false },
            onSave = { saveToGallery(it) },
            onShare = { vm.export(it, share = true) }
        )
    }
    if (showInfo) {
        val (w, h) = vm.originalSize
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Informazioni") },
            text = {
                Column {
                    Text(vm.fileName, color = LuceColors.TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text("Dimensioni originali: $w × $h px", color = LuceColors.TextSecondary)
                    Text("Megapixel: ${String.format(java.util.Locale.ITALY, "%.1f", w.toLong() * h / 1_000_000.0)}", color = LuceColors.TextSecondary)
                    Text("Le modifiche sono non distruttive: l'originale non viene mai modificato.", color = LuceColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Chiudi") } }
        )
    }
}

@Composable
private fun TopBar(vm: EditorViewModel, onBack: () -> Unit, onExport: () -> Unit, onInfo: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { vm.flushSave(); onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = LuceColors.TextPrimary)
        }
        Text(
            vm.fileName, style = MaterialTheme.typography.bodyMedium, color = LuceColors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, "Annulla", tint = if (vm.canUndo) LuceColors.TextPrimary else LuceColors.TextDisabled)
        }
        IconButton(onClick = { vm.redo() }, enabled = vm.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, "Ripeti", tint = if (vm.canRedo) LuceColors.TextPrimary else LuceColors.TextDisabled)
        }
        IconButton(onClick = { vm.showHistogram = !vm.showHistogram }) {
            Icon(Icons.Filled.BarChart, "Istogramma", tint = if (vm.showHistogram) LuceColors.Accent else LuceColors.TextPrimary)
        }
        IconButton(onClick = { vm.showBefore = !vm.showBefore }) {
            Icon(Icons.Filled.Compare, "Prima e dopo", tint = if (vm.showBefore) LuceColors.Accent else LuceColors.TextPrimary)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, "Altro", tint = LuceColors.TextPrimary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (vm.showClipping) "Nascondi avviso di ritaglio" else "Mostra avviso di ritaglio") }, onClick = { menu = false; vm.toggleClipping() })
                DropdownMenuItem(text = { Text("Copia impostazioni") }, onClick = { menu = false; vm.copySettings() })
                DropdownMenuItem(text = { Text("Incolla impostazioni") }, enabled = vm.hasClipboard(), onClick = { menu = false; vm.pasteSettings() })
                DropdownMenuItem(text = { Text("Ripristina regolazioni") }, onClick = { menu = false; vm.resetAdjustments() })
                DropdownMenuItem(text = { Text("Ripristina tutto") }, onClick = { menu = false; vm.resetAll() })
                DropdownMenuItem(text = { Text("Informazioni") }, onClick = { menu = false; onInfo() })
            }
        }
        IconButton(onClick = onExport) {
            Icon(Icons.Filled.IosShare, "Esporta", tint = LuceColors.Accent)
        }
    }
}

@Composable
private fun PanelTitle(vm: EditorViewModel) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(vm.tool.icon(), null, tint = LuceColors.Accent, modifier = Modifier.size(20.dp))
        Text(vm.tool.label, style = MaterialTheme.typography.titleMedium, color = LuceColors.TextPrimary, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun ToolItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) LuceColors.Accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (selected) LuceColors.Accent else LuceColors.TextPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (selected) LuceColors.Accent else LuceColors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToolStrip(vm: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(LuceColors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToolItem("Auto", Icons.Filled.AutoAwesome, false, { vm.autoTone() }, Modifier.width(72.dp))
        Tool.entries.forEach { t ->
            ToolItem(t.label, t.icon(), vm.tool == t, { vm.selectTool(t) }, Modifier.width(76.dp))
        }
    }
}

@Composable
private fun ToolRail(vm: EditorViewModel, modifier: Modifier) {
    Column(
        modifier
            .background(LuceColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToolItem("Auto", Icons.Filled.AutoAwesome, false, { vm.autoTone() }, Modifier.fillMaxWidth())
        Tool.entries.forEach { t ->
            ToolItem(t.label, t.icon(), vm.tool == t, { vm.selectTool(t) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ExportDialog(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
    onSave: (ExportSettings) -> Unit,
    onShare: (ExportSettings) -> Unit
) {
    val maxEdge = vm.maxExportEdge()
    val sizes = listOf(2048, 3072, 4096).filter { it < maxEdge } + maxEdge
    var size by remember { mutableStateOf(maxEdge) }
    var format by remember { mutableStateOf(ExportFormat.JPEG) }
    var quality by remember { mutableStateOf(92f) }
    val settings = ExportSettings(size, format, quality.toInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Esporta") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionHeader("Dimensione (lato lungo)", Modifier.padding(start = 0.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sizes.forEach { s ->
                        Pill(if (s == maxEdge) "Massima ($s px)" else "$s px", size == s, { size = s })
                    }
                }
                SectionHeader("Formato")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { f -> Pill(f.label, format == f, { format = f }) }
                }
                if (format == ExportFormat.JPEG) {
                    Spacer(Modifier.height(8.dp))
                    LuceSlider("Qualità", quality, 50f..100f, { quality = it }, {}, default = 92f, modifier = Modifier.padding(0.dp))
                }
                if (vm.exporting) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LuceColors.Accent)
                        Text("Elaborazione in alta risoluzione…", color = LuceColors.TextSecondary, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(settings) }, enabled = !vm.exporting,
                colors = ButtonDefaults.buttonColors(containerColor = LuceColors.Accent)
            ) { Text("Salva in galleria") }
        },
        dismissButton = {
            OutlinedButton(onClick = { onShare(settings) }, enabled = !vm.exporting) { Text("Condividi") }
        }
    )
}
