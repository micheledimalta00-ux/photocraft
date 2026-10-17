package com.michele.luce.ui.editor

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.michele.luce.ai.AiSegmenter
import com.michele.luce.data.AiMaskStore
import com.michele.luce.data.BitmapIO
import com.michele.luce.data.EditStore
import com.michele.luce.data.ExportSettings
import com.michele.luce.data.Exporter
import com.michele.luce.data.MediaRepository
import com.michele.luce.data.PresetStore
import com.michele.luce.data.SettingsClipboard
import com.michele.luce.engine.AiMasks
import com.michele.luce.engine.AiRaster
import com.michele.luce.engine.Analysis
import com.michele.luce.engine.BrushStroke
import com.michele.luce.engine.CombineMode
import com.michele.luce.engine.ComponentType
import com.michele.luce.engine.EditParams
import com.michele.luce.engine.GeoMap
import com.michele.luce.engine.Geometry
import com.michele.luce.engine.Histogram
import com.michele.luce.engine.Img
import com.michele.luce.engine.Mask
import com.michele.luce.engine.MaskComponent
import com.michele.luce.engine.MaskEngine
import com.michele.luce.engine.Pipeline
import com.michele.luce.engine.PointColor
import com.michele.luce.engine.Preset
import com.michele.luce.engine.Presets
import com.michele.luce.engine.Profiles
import com.michele.luce.engine.RenderOptions
import com.michele.luce.engine.Treatment
import com.michele.luce.engine.Upright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

enum class Tool(val label: String) {
    PRESETS("Predefiniti"),
    PROFILE("Profili"),
    CROP("Ritaglia"),
    GEOMETRY("Geometria"),
    MASKS("Maschere"),
    LIGHT("Luce"),
    COLOR("Colore"),
    EFFECTS("Effetti"),
    DETAIL("Dettaglio"),
    OPTICS("Ottica")
}

enum class BrushMode { PAINT, ERASE }

data class BrushSettings(
    val size: Float = 0.06f,
    val feather: Float = 0.6f,
    val flow: Float = 0.8f,
    val density: Float = 1f,
    val autoMask: Boolean = false,
    val mode: BrushMode = BrushMode.PAINT
)

/** Strumenti che attendono un tocco sull'immagine. */
enum class PickMode(val hint: String) {
    NONE(""),
    WHITE_BALANCE("Tocca un punto che dovrebbe essere grigio neutro"),
    POINT_COLOR("Tocca il colore da modificare"),
    COLOR_RANGE("Tocca i colori da selezionare (fino a 5)"),
    LUMINANCE_RANGE("Tocca la luminosità da selezionare"),
    OBJECT("Tocca l'oggetto da selezionare")
}

enum class OverlayStyle(val label: String, val mode: Pipeline.OverlayMode) {
    COLOR("Colore", Pipeline.OverlayMode.COLOR),
    ON_BLACK("Su nero", Pipeline.OverlayMode.ON_BLACK),
    ON_WHITE("Su bianco", Pipeline.OverlayMode.ON_WHITE),
    BW("Bianco e nero", Pipeline.OverlayMode.BW)
}

sealed class ExportResult {
    data class Saved(val message: String) : ExportResult()
    data class Share(val intent: Intent) : ExportResult()
    data class Failed(val message: String) : ExportResult()
}

class EditorViewModel(app: Application, val uri: Uri) : AndroidViewModel(app) {

    private val editStore = EditStore(app)
    private val presetStore = PresetStore(app)
    private val aiStore = AiMaskStore(app, uri)
    private val segmenter = AiSegmenter(app)

    // ---------- stato osservabile ----------
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var fileName by mutableStateOf("")
        private set
    var originalSize by mutableStateOf(0 to 0)
        private set

    var params by mutableStateOf(EditParams())
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Vero se l'anteprima mostra la tela intera (strumento Ritaglia). */
    var previewUncropped by mutableStateOf(false)
        private set
    var before by mutableStateOf<ImageBitmap?>(null)
        private set
    var histogram by mutableStateOf<Histogram?>(null)
        private set
    var maskOverlay by mutableStateOf<ImageBitmap?>(null)
        private set
    var rendering by mutableStateOf(false)
        private set

    var tool by mutableStateOf(Tool.LIGHT)
        private set
    var showBefore by mutableStateOf(false)
    var showHistogram by mutableStateOf(true)
    var showClipping by mutableStateOf(false)
        private set

    // maschere
    var selectedMaskId by mutableStateOf<Long?>(null)
        private set
    var selectedComponentId by mutableStateOf<Long?>(null)
        private set
    var showMaskOverlay by mutableStateOf(true)
        private set
    var overlayStyle by mutableStateOf(OverlayStyle.COLOR)
        private set
    var overlayColor by mutableStateOf(0xFF3030)
        private set
    var overlayOpacity by mutableStateOf(0.6f)
        private set
    var brush by mutableStateOf(BrushSettings())

    // selezioni sull'immagine
    var pickMode by mutableStateOf(PickMode.NONE)
        private set
    var selectedPointColorId by mutableStateOf<Long?>(null)
    var pointColorViewId by mutableStateOf<Long?>(null)
        private set
    var aiBusy by mutableStateOf<String?>(null)
        private set
    val aiAvailable: Boolean by lazy { segmenter.modelsAvailable() }

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    var exporting by mutableStateOf(false)
        private set
    var exportResult by mutableStateOf<ExportResult?>(null)

    var userPresets by mutableStateOf<List<Preset>>(emptyList())
        private set
    val presetThumbs = mutableStateMapOf<String, ImageBitmap>()
    val profileThumbs = mutableStateMapOf<String, ImageBitmap>()

    var message by mutableStateOf<String?>(null)

    // ---------- dati interni ----------
    private var previewSrc: Img? = null
    private var draftSrc: Img? = null
    private var thumbSrc: Img? = null
    private var guideSrc: Img? = null
    private var beforeImg: Img? = null
    private var originalLong = 1
    private var segData: AiMasks.SegData? = null
    private var modelInput: Bitmap? = null
    private var faceInput: Bitmap? = null

    private val history = ArrayList<EditParams>()
    private var hIndex = 0
    private var interacting = false

    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var fullJob: Job? = null
    private var saveJob: Job? = null
    private var thumbsJob: Job? = null
    private var beforeKey: EditParams? = null

    init {
        userPresets = presetStore.load()
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val ctx = getApplication<Application>()
            val dm = ctx.resources.displayMetrics
            val longScreen = max(dm.widthPixels, dm.heightPixels)
            val target = longScreen.coerceIn(1280, 2400)
            val loaded = withContext(Dispatchers.IO) { BitmapIO.load(ctx, uri, target) }
            val draft = withContext(Dispatchers.Default) { BitmapIO.downscale(loaded.img, 720) }
            val thumb = withContext(Dispatchers.Default) { BitmapIO.downscale(draft, 280) }
            val guide = withContext(Dispatchers.Default) { BitmapIO.downscale(loaded.img, 1024) }
            previewSrc = loaded.img
            draftSrc = draft
            thumbSrc = thumb
            guideSrc = guide
            originalLong = loaded.originalLongEdge
            originalSize = loaded.originalWidth to loaded.originalHeight
            fileName = withContext(Dispatchers.IO) { MediaRepository.displayName(ctx, uri) }
            val saved = withContext(Dispatchers.IO) { editStore.load(uri) }
            params = saved ?: EditParams()
            history.clear(); history.add(params); hIndex = 0
            updateUndo()
            loading = false
            viewModelScope.launch { renderLoop() }
            signal.trySend(Unit)
            restoreMissingRasters()
        } catch (e: OutOfMemoryError) {
            loading = false
            error = "Memoria insufficiente per aprire l'immagine"
        } catch (e: Exception) {
            loading = false
            error = e.message ?: "Impossibile aprire l'immagine"
        }
    }

    // ---------- rendering ----------

    private fun previewScale(src: Img) = max(src.width, src.height).toFloat() / max(1, originalLong)

    private fun rastersFor(p: EditParams): Map<String, AiRaster> = aiStore.snapshot(MaskEngine.requiredAiKeys(p))

    private fun options(src: Img, p: EditParams, uncropped: Boolean, cancel: () -> Boolean = { false }) = RenderOptions(
        resolutionScale = previewScale(src),
        ignoreCrop = uncropped,
        cancel = cancel,
        aiRasters = rastersFor(p),
        showClipping = showClipping,
        pointColorView = pointColorViewId
    )

    private suspend fun renderLoop() {
        for (s in signal) {
            val src = previewSrc ?: continue
            val draft = draftSrc ?: continue
            fullJob?.cancel()
            val p = params
            val uncropped = tool == Tool.CROP
            if (interacting) {
                try {
                    val opts = options(draft, p, uncropped)
                    val img = withContext(Dispatchers.Default) { Pipeline.render(draft, p, opts) }
                    preview = BitmapIO.toBitmap(img).asImageBitmap()
                    previewUncropped = uncropped
                    if (tool == Tool.MASKS) updateMaskOverlay(p, uncropped)
                } catch (e: OutOfMemoryError) {
                    message = "Memoria insufficiente"
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Errore di elaborazione: ${e.message}"
                }
            }
            val wait = interacting
            fullJob = viewModelScope.launch {
                if (wait) delay(260)
                renderFull(src, p, uncropped)
            }
        }
    }

    private suspend fun renderFull(src: Img, p: EditParams, uncropped: Boolean) {
        rendering = true
        try {
            val job = coroutineContext[Job]
            val opts = options(src, p, uncropped) { job?.isActive == false }
            val result = withContext(Dispatchers.Default) {
                val img = Pipeline.render(src, p, opts)
                val hist = Analysis.histogram(img)
                val bmp = BitmapIO.toBitmap(img)
                hist to bmp
            }
            if (!coroutineContext.isActive) return
            preview = result.second.asImageBitmap()
            previewUncropped = uncropped
            histogram = result.first
            updateBefore(src, p, uncropped)
            updateMaskOverlay(p, uncropped)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            message = "Memoria insufficiente per l'anteprima"
        } catch (e: Exception) {
            message = "Errore di elaborazione: ${e.message}"
        } finally {
            rendering = false
        }
    }

    /** Solo la geometria, senza regolazioni: per "prima" e per i contagocce. */
    private fun geometryKey(p: EditParams, uncropped: Boolean): EditParams {
        return EditParams().copy(
            rotate90 = p.rotate90, flipH = p.flipH, flipV = p.flipV, straighten = p.straighten,
            cropLeft = if (uncropped) 0f else p.cropLeft, cropTop = if (uncropped) 0f else p.cropTop,
            cropRight = if (uncropped) 1f else p.cropRight, cropBottom = if (uncropped) 1f else p.cropBottom,
            lensDistortion = p.lensDistortion, chromaticAberration = p.chromaticAberration,
            uprightMode = p.uprightMode, uprightAngle = p.uprightAngle, uprightV = p.uprightV, uprightH = p.uprightH,
            tVertical = p.tVertical, tHorizontal = p.tHorizontal, tRotate = p.tRotate, tAspect = p.tAspect,
            tScale = p.tScale, tOffsetX = p.tOffsetX, tOffsetY = p.tOffsetY, constrainCrop = p.constrainCrop
        )
    }

    private suspend fun updateBefore(src: Img, p: EditParams, uncropped: Boolean) {
        val key = geometryKey(p, uncropped)
        if (key == beforeKey && before != null && beforeImg != null) return
        val img = withContext(Dispatchers.Default) { Geometry.apply(src, key, uncropped) }
        beforeImg = img
        before = BitmapIO.toBitmap(img).asImageBitmap()
        beforeKey = key
    }

    private suspend fun updateMaskOverlay(p: EditParams, uncropped: Boolean) {
        val mask = p.masks.firstOrNull { it.id == selectedMaskId }
        val draft = draftSrc
        if (tool != Tool.MASKS || mask == null || !showMaskOverlay || draft == null || mask.components.isEmpty()) {
            maskOverlay = null
            return
        }
        val rasters = rastersFor(p)
        val style = overlayStyle
        val color = overlayColor
        val opacity = overlayOpacity
        val bmp = withContext(Dispatchers.Default) {
            val (cw, ch) = Geometry.orientedSize(draft.width, draft.height, p.rotate90)
            val input = Geometry.apply(draft, p, uncropped)
            val px = Pipeline.maskOverlay(input, p, mask, cw, ch, draft.width, draft.height, uncropped, rasters, style.mode, color, opacity)
            BitmapIO.toBitmapWithAlpha(px, input.width, input.height)
        }
        maskOverlay = bmp.asImageBitmap()
    }

    private fun requestRender() {
        signal.trySend(Unit)
    }

    // ---------- modifiche e cronologia ----------

    /** Modifica dal vivo (durante un trascinamento). */
    fun update(p: EditParams) {
        if (p == params) return
        params = p
        interacting = true
        requestRender()
    }

    /** Fine del gesto: salva nella cronologia e rende in qualità piena. */
    fun commit() {
        interacting = false
        if (history.isEmpty() || history[hIndex] != params) {
            while (history.size > hIndex + 1) history.removeAt(history.size - 1)
            history.add(params)
            if (history.size > 150) history.removeAt(0)
            hIndex = history.size - 1
            updateUndo()
            scheduleSave()
        }
        requestRender()
    }

    fun set(p: EditParams) {
        params = p
        commit()
    }

    fun undo() {
        if (hIndex <= 0) return
        hIndex--
        params = history[hIndex]
        interacting = false
        validateSelection()
        updateUndo(); scheduleSave(); requestRender()
    }

    fun redo() {
        if (hIndex >= history.size - 1) return
        hIndex++
        params = history[hIndex]
        interacting = false
        validateSelection()
        updateUndo(); scheduleSave(); requestRender()
    }

    private fun validateSelection() {
        val m = selectedMask()
        if (m == null) {
            selectedMaskId = null
            selectedComponentId = null
        } else if (m.components.none { it.id == selectedComponentId }) {
            selectedComponentId = m.components.lastOrNull()?.id
        }
        if (params.pointColors.none { it.id == selectedPointColorId }) selectedPointColorId = params.pointColors.lastOrNull()?.id
    }

    private fun updateUndo() {
        canUndo = hIndex > 0
        canRedo = hIndex < history.size - 1
    }

    fun resetAll() = set(EditParams())

    fun resetAdjustments() = set(params.withAdjustmentsFrom(EditParams()).copy(masks = emptyList()))

    private fun thumbFor(p: EditParams): Bitmap? {
        val thumb = thumbSrc ?: return null
        if (p.isDefault()) return null
        return BitmapIO.toBitmap(Pipeline.render(thumb, p, RenderOptions(previewScale(thumb), aiRasters = rastersFor(p))))
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val p = params
        saveJob = viewModelScope.launch {
            delay(700)
            withContext(Dispatchers.IO) {
                try {
                    editStore.save(uri, p, thumbFor(p))
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Salvataggio immediato, chiamato all'uscita dall'editor. */
    fun flushSave() {
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            val p = params
            try {
                editStore.save(uri, p, thumbFor(p))
            } catch (_: Throwable) {
            }
        }
    }

    // ---------- strumenti ----------

    fun selectTool(t: Tool) {
        val wasCrop = tool == Tool.CROP
        tool = t
        pickMode = PickMode.NONE
        if (pointColorViewId != null) pointColorViewId = null
        if (t != Tool.MASKS) {
            selectedMaskId = null
            selectedComponentId = null
        }
        if (t == Tool.PRESETS || t == Tool.PROFILE) generateThumbs()
        requestRender()
        if (wasCrop == (t == Tool.CROP) && t != Tool.MASKS && maskOverlay != null) maskOverlay = null
    }

    fun toggleClipping() {
        showClipping = !showClipping
        requestRender()
    }

    fun startPick(mode: PickMode) {
        pickMode = if (pickMode == mode) PickMode.NONE else mode
        if (pickMode == PickMode.OBJECT && !aiAvailable) {
            pickMode = PickMode.NONE
            message = "Modelli IA non disponibili in questa build"
        }
    }

    fun cancelPick() {
        pickMode = PickMode.NONE
    }

    /** Colore medio (5×5) dell'immagine senza regolazioni nel punto d'uscita normalizzato. */
    private fun sampleColor(uo: Float, vo: Float): FloatArray? {
        val img = beforeImg ?: return null
        val cx = (uo * img.width).toInt().coerceIn(0, img.width - 1)
        val cy = (vo * img.height).toInt().coerceIn(0, img.height - 1)
        var r = 0f; var g = 0f; var b = 0f; var n = 0
        for (y in max(0, cy - 2)..min(img.height - 1, cy + 2)) for (x in max(0, cx - 2)..min(img.width - 1, cx + 2)) {
            val c = img.pixels[y * img.width + x]
            r += (c shr 16 and 0xFF); g += (c shr 8 and 0xFF); b += (c and 0xFF); n++
        }
        if (n == 0) return null
        return floatArrayOf(r / n / 255f, g / n / 255f, b / n / 255f)
    }

    /** Tocco sull'immagine in modalità contagocce (coordinate normalizzate dell'immagine mostrata). */
    fun onPick(uo: Float, vo: Float) {
        if (uo !in 0f..1f || vo !in 0f..1f) return
        when (pickMode) {
            PickMode.NONE -> Unit
            PickMode.WHITE_BALANCE -> {
                val c = sampleColor(uo, vo) ?: return
                set(Analysis.whiteBalanceFromSample(c[0], c[1], c[2], params))
                pickMode = PickMode.NONE
            }
            PickMode.POINT_COLOR -> {
                val c = sampleColor(uo, vo) ?: return
                val hsl = FloatArray(3)
                Pipeline.rgbToHsl(c[0], c[1], c[2], hsl)
                if (hsl[1] < 0.04f) {
                    message = "Colore troppo neutro: scegli un punto colorato"
                    return
                }
                if (params.pointColors.size >= 8) {
                    message = "Massimo 8 colori puntuali"
                    pickMode = PickMode.NONE
                    return
                }
                val pc = PointColor(id = System.currentTimeMillis(), hue = hsl[0], sat = hsl[1], lum = hsl[2])
                selectedPointColorId = pc.id
                set(params.copy(pointColors = params.pointColors + pc))
                pickMode = PickMode.NONE
            }
            PickMode.COLOR_RANGE -> {
                val c = sampleColor(uo, vo) ?: return
                val comp = selectedComponent() ?: return
                if (comp.type != ComponentType.COLOR_RANGE) return
                val samples = comp.colorSamples.let { if (it.size >= 15) it.dropLast(3) else it } + listOf(c[0], c[1], c[2])
                updateComponent(comp.copy(colorSamples = samples), live = false)
            }
            PickMode.LUMINANCE_RANGE -> {
                val c = sampleColor(uo, vo) ?: return
                val comp = selectedComponent() ?: return
                if (comp.type != ComponentType.LUMINANCE_RANGE) return
                val l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
                updateComponent(comp.copy(lumLo = (l - 0.1f).coerceIn(0f, 1f), lumHi = (l + 0.1f).coerceIn(0f, 1f)), live = false)
                pickMode = PickMode.NONE
            }
            PickMode.OBJECT -> {
                val (cw, ch) = canvasSize()
                val map = GeoMap(params, cw, ch, previewUncropped)
                val out = FloatArray(2)
                map.toSource(uo, vo, out)
                pickMode = PickMode.NONE
                createObject(out[0].coerceIn(0f, 1f), out[1].coerceIn(0f, 1f))
            }
        }
    }

    fun autoTone() {
        val src = previewSrc ?: return
        viewModelScope.launch {
            val base = params
            val geoSrc = withContext(Dispatchers.Default) { Geometry.apply(src, base) }
            set(Analysis.autoTone(geoSrc, base))
            message = "Toni automatici applicati"
        }
    }

    fun autoWhiteBalance() {
        val src = previewSrc ?: return
        viewModelScope.launch {
            val base = params
            val geoSrc = withContext(Dispatchers.Default) { Geometry.apply(src, base) }
            set(Analysis.autoWhiteBalance(geoSrc, base))
        }
    }

    fun applyPreset(preset: Preset) = set(Presets.apply(params, preset))

    fun applyProfile(name: String) {
        val newProf = Profiles.byName(name)
        val oldProf = Profiles.byName(params.profile)
        val treatment = when {
            newProf.bw -> Treatment.BW
            oldProf.bw -> Treatment.COLOR
            else -> params.treatment
        }
        set(params.copy(profile = name, treatment = treatment))
    }

    fun copySettings() {
        SettingsClipboard.params = params
        message = "Impostazioni copiate"
    }

    fun pasteSettings() {
        val c = SettingsClipboard.params
        if (c == null) {
            message = "Nessuna impostazione copiata"
            return
        }
        set(params.withAdjustmentsFrom(c))
        message = "Impostazioni incollate"
    }

    fun hasClipboard() = SettingsClipboard.params != null

    fun saveUserPreset(name: String) {
        presetStore.add(name, params)
        userPresets = presetStore.load()
        generateThumbs()
        message = "Predefinito \"$name\" salvato"
    }

    fun deleteUserPreset(id: String) {
        presetStore.delete(id)
        userPresets = presetStore.load()
        presetThumbs.remove(id)
    }

    private fun generateThumbs() {
        val thumb = thumbSrc ?: return
        if (thumbsJob?.isActive == true) return
        thumbsJob = viewModelScope.launch {
            val base = params
            val scale = previewScale(thumb)
            val geo = withContext(Dispatchers.Default) { Geometry.apply(thumb, base) }
            for (preset in Presets.builtIn + userPresets) {
                if (presetThumbs.containsKey(preset.id)) continue
                val bmp = withContext(Dispatchers.Default) {
                    BitmapIO.toBitmap(Pipeline.develop(geo, preset.params, RenderOptions(scale), geo.width, geo.height)).asImageBitmap()
                }
                presetThumbs[preset.id] = bmp
            }
            for (prof in Profiles.all) {
                if (profileThumbs.containsKey(prof.name)) continue
                val pp = EditParams(profile = prof.name)
                val bmp = withContext(Dispatchers.Default) {
                    BitmapIO.toBitmap(Pipeline.develop(geo, pp, RenderOptions(scale), geo.width, geo.height)).asImageBitmap()
                }
                profileThumbs[prof.name] = bmp
            }
        }
    }

    // ---------- colore puntuale ----------

    fun selectedPointColor(): PointColor? = params.pointColors.firstOrNull { it.id == selectedPointColorId }

    fun updatePointColor(pc: PointColor, live: Boolean) {
        val np = params.copy(pointColors = params.pointColors.map { if (it.id == pc.id) pc else it })
        if (live) update(np) else set(np)
    }

    fun deletePointColor(id: Long) {
        if (pointColorViewId == id) pointColorViewId = null
        set(params.copy(pointColors = params.pointColors.filter { it.id != id }))
        selectedPointColorId = params.pointColors.lastOrNull()?.id
    }

    fun togglePointColorView() {
        val id = selectedPointColorId ?: return
        pointColorViewId = if (pointColorViewId == id) null else id
        requestRender()
    }

    // ---------- geometria ----------

    /** Dimensioni della tela orientata (in pixel dell'anteprima). */
    fun canvasSize(): Pair<Int, Int> {
        val src = previewSrc ?: return 1 to 1
        return Geometry.orientedSize(src.width, src.height, params.rotate90)
    }

    fun setAspect(label: String) {
        val a = Geometry.ASPECTS.firstOrNull { it.label == label } ?: return
        val (cw, ch) = canvasSize()
        set(Geometry.fitAspect(params, a, cw, ch))
    }

    private fun mapComponents(p: EditParams, f: (MaskComponent) -> MaskComponent): List<Mask> =
        p.masks.map { m -> m.copy(components = m.components.map(f)) }

    fun rotate90() {
        val p = params
        val (cw, ch) = canvasSize()
        // ruota in senso orario anche il riquadro di ritaglio e le maschere geometriche
        val np = p.copy(
            rotate90 = (p.rotate90 + 1) % 4,
            cropLeft = 1f - p.cropBottom, cropTop = p.cropLeft,
            cropRight = 1f - p.cropTop, cropBottom = p.cropRight,
            aspect = invertAspect(p.aspect),
            masks = mapComponents(p) { c ->
                c.copy(
                    x1 = 1f - c.y1, y1 = c.x1, x2 = 1f - c.y2, y2 = c.x2,
                    cx = 1f - c.cy, cy = c.cx,
                    rx = c.rx * cw / ch.coerceAtLeast(1), ry = c.ry * ch / cw.coerceAtLeast(1),
                    angle = c.angle + 90f,
                    strokes = c.strokes.map { st ->
                        st.copy(points = st.points.chunked(2).flatMap { if (it.size == 2) listOf(1f - it[1], it[0]) else emptyList() })
                    }
                )
            }
        )
        set(np)
    }

    private fun invertAspect(label: String): String {
        val parts = label.split(":")
        if (parts.size != 2) return label
        val inv = "${parts[1]}:${parts[0]}"
        return if (Geometry.ASPECTS.any { it.label == inv }) inv else label
    }

    fun flipH() {
        val p = params
        set(p.copy(
            flipH = !p.flipH, cropLeft = 1f - p.cropRight, cropRight = 1f - p.cropLeft,
            masks = mapComponents(p) { c ->
                c.copy(
                    x1 = 1f - c.x1, x2 = 1f - c.x2, cx = 1f - c.cx, angle = -c.angle,
                    strokes = c.strokes.map { st -> st.copy(points = st.points.mapIndexed { i, v -> if (i % 2 == 0) 1f - v else v }) }
                )
            }
        ))
    }

    fun flipV() {
        val p = params
        set(p.copy(
            flipV = !p.flipV, cropTop = 1f - p.cropBottom, cropBottom = 1f - p.cropTop,
            masks = mapComponents(p) { c ->
                c.copy(
                    y1 = 1f - c.y1, y2 = 1f - c.y2, cy = 1f - c.cy, angle = -c.angle,
                    strokes = c.strokes.map { st -> st.copy(points = st.points.mapIndexed { i, v -> if (i % 2 == 1) 1f - v else v }) }
                )
            }
        ))
    }

    fun resetCrop() = set(params.resetGeometry())

    fun resetTransform() = set(params.resetTransform())

    /** Upright automatico: analizza le linee dell'immagine e imposta la correzione. */
    fun setUpright(mode: String) {
        if (mode == "Off") {
            set(params.copy(uprightMode = "Off", uprightAngle = 0f, uprightV = 0f, uprightH = 0f))
            return
        }
        val src = draftSrc ?: return
        val base = params
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                val oriented = Geometry.apply(
                    src,
                    EditParams(rotate90 = base.rotate90, flipH = base.flipH, flipV = base.flipV, lensDistortion = base.lensDistortion)
                )
                Upright.analyze(oriented)
            }
            val (a, v, h) = Upright.valuesFor(mode, r)
            set(params.copy(uprightMode = mode, uprightAngle = a, uprightV = v, uprightH = h))
            if (a == 0f && v == 0f && h == 0f) message = "Nessuna linea guida trovata"
        }
    }

    // ---------- maschere ----------

    private var maskCounter = 0

    fun selectedMask(): Mask? = params.masks.firstOrNull { it.id == selectedMaskId }

    fun selectedComponent(): MaskComponent? = selectedMask()?.components?.firstOrNull { it.id == selectedComponentId }

    fun selectMask(id: Long?) {
        selectedMaskId = id
        selectedComponentId = params.masks.firstOrNull { it.id == id }?.components?.lastOrNull()?.id
        pickMode = PickMode.NONE
        requestRender()
    }

    fun selectComponent(id: Long) {
        selectedComponentId = id
        val c = selectedComponent()
        pickMode = when (c?.type) {
            ComponentType.COLOR_RANGE -> if (c.colorSamples.isEmpty()) PickMode.COLOR_RANGE else PickMode.NONE
            else -> PickMode.NONE
        }
        requestRender()
    }

    private fun newId(): Long = System.currentTimeMillis() * 10 + (maskCounter++ % 10)

    private fun defaultComponent(type: ComponentType, mode: CombineMode): MaskComponent {
        val id = newId()
        return when (type) {
            ComponentType.LINEAR -> MaskComponent(id, type, mode, x1 = 0.5f, y1 = 0.25f, x2 = 0.5f, y2 = 0.6f)
            ComponentType.RADIAL -> {
                val (cw, ch) = canvasSize()
                val r = 0.25f
                MaskComponent(id, type, mode, cx = 0.5f, cy = 0.5f, rx = r, ry = r * cw / ch.coerceAtLeast(1))
            }
            else -> MaskComponent(id, type, mode)
        }
    }

    private fun maskName(first: MaskComponent): String {
        val n = params.masks.size + 1
        return "${first.label()} $n"
    }

    /** Crea una nuova maschera (mode = null) o aggiunge un componente a quella selezionata. */
    fun createComponent(type: ComponentType, mode: CombineMode?) {
        if (type == ComponentType.AI) return
        val comp = defaultComponent(type, mode ?: CombineMode.ADD)
        attach(comp, mode)
        pickMode = when (type) {
            ComponentType.COLOR_RANGE -> PickMode.COLOR_RANGE
            ComponentType.LUMINANCE_RANGE -> PickMode.LUMINANCE_RANGE
            else -> PickMode.NONE
        }
    }

    private fun attach(comp: MaskComponent, mode: CombineMode?) {
        val sel = selectedMask()
        if (mode == null || sel == null) {
            val m = Mask(id = newId(), name = maskName(comp), components = listOf(comp.copy(mode = CombineMode.ADD)))
            selectedMaskId = m.id
            selectedComponentId = comp.id
            set(params.copy(masks = params.masks + m))
        } else {
            val updated = sel.copy(components = sel.components + comp.copy(mode = mode))
            selectedComponentId = comp.id
            set(params.copy(masks = params.masks.map { if (it.id == sel.id) updated else it }))
        }
    }

    fun updateMask(m: Mask, live: Boolean) {
        val np = params.copy(masks = params.masks.map { if (it.id == m.id) m else it })
        if (live) update(np) else set(np)
    }

    fun updateComponent(c: MaskComponent, live: Boolean) {
        val m = params.masks.firstOrNull { mk -> mk.components.any { it.id == c.id } } ?: return
        updateMask(m.copy(components = m.components.map { if (it.id == c.id) c else it }), live)
    }

    fun deleteComponent(id: Long) {
        val m = params.masks.firstOrNull { mk -> mk.components.any { it.id == id } } ?: return
        val rest = m.components.filter { it.id != id }
        if (rest.isEmpty()) {
            deleteMask(m.id)
            return
        }
        // il primo componente rimasto diventa sempre un'aggiunta
        val fixed = rest.mapIndexed { i, c -> if (i == 0) c.copy(mode = CombineMode.ADD) else c }
        if (selectedComponentId == id) selectedComponentId = fixed.last().id
        pickMode = PickMode.NONE
        updateMask(m.copy(components = fixed), live = false)
    }

    fun deleteMask(id: Long) {
        if (selectedMaskId == id) {
            selectedMaskId = null
            selectedComponentId = null
        }
        pickMode = PickMode.NONE
        set(params.copy(masks = params.masks.filter { it.id != id }))
    }

    fun duplicateMask(id: Long, invert: Boolean) {
        val m = params.masks.firstOrNull { it.id == id } ?: return
        var offset = 1L
        val copy = m.copy(
            id = newId(),
            name = m.name + if (invert) " invertita" else " copia",
            components = m.components.map { it.copy(id = newId() + offset++) },
            invert = if (invert) !m.invert else m.invert
        )
        selectedMaskId = copy.id
        selectedComponentId = copy.components.lastOrNull()?.id
        set(params.copy(masks = params.masks + copy))
    }

    fun renameMask(id: Long, name: String) {
        val m = params.masks.firstOrNull { it.id == id } ?: return
        updateMask(m.copy(name = name.trim().ifEmpty { m.name }), live = false)
    }

    fun setOverlay(show: Boolean = showMaskOverlay, style: OverlayStyle = overlayStyle, color: Int = overlayColor, opacity: Float = overlayOpacity) {
        showMaskOverlay = show
        overlayStyle = style
        overlayColor = color
        overlayOpacity = opacity.coerceIn(0.1f, 1f)
        requestRender()
    }

    fun beginStroke(u: Float, v: Float, uo: Float, vo: Float) {
        val c = selectedComponent() ?: return
        if (c.type != ComponentType.BRUSH) return
        val b = brush
        val auto = b.autoMask && b.mode == BrushMode.PAINT
        val col = if (auto) sampleColor(uo, vo)?.toList() ?: emptyList() else emptyList()
        val stroke = BrushStroke(
            points = listOf(u, v), size = b.size, feather = b.feather, flow = b.flow,
            erase = b.mode == BrushMode.ERASE, density = b.density, autoMask = auto && col.isNotEmpty(), colors = col
        )
        updateComponent(c.copy(strokes = c.strokes + stroke), live = true)
    }

    fun extendStroke(u: Float, v: Float, uo: Float, vo: Float) {
        val c = selectedComponent() ?: return
        if (c.type != ComponentType.BRUSH || c.strokes.isEmpty()) return
        val last = c.strokes.last()
        val pts = last.points
        if (pts.size >= 2) {
            val du = u - pts[pts.size - 2]
            val dv = v - pts[pts.size - 1]
            if (du * du + dv * dv < (last.size * 0.08f) * (last.size * 0.08f)) return
        }
        val newColors = if (last.autoMask) {
            val s = sampleColor(uo, vo)
            if (s != null) last.colors + s.toList() else last.colors + last.colors.takeLast(3)
        } else last.colors
        val nl = last.copy(points = pts + listOf(u, v), colors = newColors)
        updateComponent(c.copy(strokes = c.strokes.dropLast(1) + nl), live = true)
    }

    fun endStroke() = commit()

    fun clearBrush() {
        val c = selectedComponent() ?: return
        updateComponent(c.copy(strokes = emptyList()), live = false)
    }

    // ---------- selezioni IA ----------

    private fun ensureModelInputs() {
        val src = previewSrc ?: return
        if (modelInput == null) modelInput = BitmapIO.toBitmap(BitmapIO.downscale(src, 512))
        if (faceInput == null) faceInput = BitmapIO.toBitmap(guideSrc ?: BitmapIO.downscale(src, 1024))
    }

    /** Calcola (o recupera) il raster di una selezione IA. */
    private fun computeRaster(key: String, objX: Float = 0.5f, objY: Float = 0.5f): AiRaster? {
        aiStore.get(key)?.let { return it }
        val guide = guideSrc ?: return null
        ensureModelInputs()
        val input = modelInput ?: return null
        val faces = faceInput ?: return null
        val raster = if (key.startsWith("object")) {
            val conf = segmenter.objectAt(input, objX, objY)
            AiMasks.fromConfidence(conf, input.width, input.height, guide)
        } else {
            val needs = AiSegmenter.needsFor(key)
            segData = segmenter.analyze(input, faces, needs, segData)
            val d = segData ?: return null
            AiMasks.build(key, d, guide) ?: if (key == "subject" || key == "background") {
                // nessun soggetto riconosciuto: selezione dell'oggetto al centro
                val conf = segmenter.objectAt(input, 0.5f, 0.5f)
                val c = if (key == "background") FloatArray(conf.size) { 1f - conf[it] } else conf
                AiMasks.fromConfidence(c, input.width, input.height, guide)
            } else null
        }
        if (raster != null) aiStore.put(key, raster)
        return raster
    }

    private fun emptyMessage(key: String): String = when {
        key == "sky" || key == "land:sky" -> "Nessun cielo trovato"
        key.startsWith("land:") -> "Nessuna area \"${AiMasks.labelFor(key)}\" trovata"
        key in setOf("eyebrows", "sclera", "iris", "lips", "teeth") -> "Nessun volto trovato"
        key == "person" || key == "face_skin" || key == "body_skin" || key == "hair" || key == "clothes" -> "Nessuna persona trovata"
        else -> "Selezione non trovata"
    }

    /** Selezione IA (soggetto, cielo, sfondo, persone, paesaggio) come nuova maschera o componente. */
    fun createAi(target: AiMasks.Target, mode: CombineMode?) {
        createAi(listOf(target), mode)
    }

    /** Più selezioni IA insieme (es. parti della persona): un componente per ciascuna. */
    fun createAi(targets: List<AiMasks.Target>, mode: CombineMode?) {
        if (targets.isEmpty()) return
        if (!aiAvailable) {
            message = "Modelli IA non disponibili in questa build"
            return
        }
        if (aiBusy != null) return
        aiBusy = "Rilevamento: ${targets.joinToString(", ") { it.label }}…"
        viewModelScope.launch {
            try {
                var first = true
                val missing = ArrayList<String>()
                for (t in targets) {
                    val raster = withContext(Dispatchers.Default) { computeRaster(t.key) }
                    if (raster == null || isEmpty(raster)) {
                        missing.add(t.key)
                        continue
                    }
                    val m = if (first) mode else (mode ?: CombineMode.ADD)
                    val comp = MaskComponent(newId(), ComponentType.AI, m ?: CombineMode.ADD, aiKey = t.key, aiLabel = t.label)
                    attach(comp, m)
                    first = false
                }
                if (missing.isNotEmpty()) message = if (missing.size == 1) emptyMessage(missing[0]) else "Alcune selezioni non sono state trovate"
            } catch (e: OutOfMemoryError) {
                message = "Memoria insufficiente per la selezione"
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Selezione non riuscita: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                aiBusy = null
            }
        }
    }

    private var pendingObjectMode: CombineMode? = null
    private var pendingObjectNew = true

    /** Prepara la selezione di un oggetto: il prossimo tocco sull'immagine indica l'oggetto. */
    fun startObjectPick(mode: CombineMode?) {
        pendingObjectMode = mode
        pendingObjectNew = mode == null
        startPick(PickMode.OBJECT)
    }

    private fun createObject(sx: Float, sy: Float) {
        if (aiBusy != null) return
        val mode = if (pendingObjectNew) null else pendingObjectMode
        aiBusy = "Selezione dell'oggetto…"
        viewModelScope.launch {
            try {
                val id = newId()
                val key = "object:$id"
                val raster = withContext(Dispatchers.Default) { computeRaster(key, sx, sy) }
                if (raster == null || isEmpty(raster)) {
                    message = "Nessun oggetto trovato in quel punto"
                } else {
                    val comp = MaskComponent(id, ComponentType.AI, mode ?: CombineMode.ADD, aiKey = key, aiLabel = "Oggetto", aiX = sx, aiY = sy)
                    attach(comp, mode)
                }
            } catch (e: OutOfMemoryError) {
                message = "Memoria insufficiente per la selezione"
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Selezione non riuscita: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                aiBusy = null
            }
        }
    }

    private fun isEmpty(r: AiRaster): Boolean {
        var n = 0
        val total = r.width * r.height
        for (i in 0 until total) if ((r.data[i].toInt() and 0xFF) > 128) n++
        return n < total * 0.001f
    }

    /** Ricalcola le selezioni IA mancanti (es. dati dell'app cancellati). */
    private fun restoreMissingRasters() {
        val comps = params.masks.flatMap { it.components }.filter { it.type == ComponentType.AI && !aiStore.has(it.aiKey) }
        if (comps.isEmpty() || !aiAvailable) return
        viewModelScope.launch {
            aiBusy = "Ripristino delle selezioni…"
            try {
                withContext(Dispatchers.Default) {
                    for (c in comps) {
                        try { computeRaster(c.aiKey, c.aiX, c.aiY) } catch (_: Throwable) { }
                    }
                }
            } finally {
                aiBusy = null
                requestRender()
            }
        }
    }

    // ---------- esportazione ----------

    fun maxExportEdge(): Int = min(Exporter.MAX_EXPORT, max(originalSize.first, originalSize.second).coerceAtLeast(1))

    fun export(settings: ExportSettings, share: Boolean) {
        if (exporting) return
        exporting = true
        val p = params
        val rasters = rastersFor(p)
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                val job = coroutineContext[Job]
                val res = withContext(Dispatchers.Default) {
                    val bmp = Exporter.render(ctx, uri, p, settings, rasters) { job?.isActive == false }
                    try {
                        if (share) ExportResult.Share(Exporter.shareIntent(ctx, bmp, fileName, settings))
                        else {
                            Exporter.saveToGallery(ctx, bmp, fileName, settings)
                            ExportResult.Saved("Salvata in Immagini/Luce (${bmp.width}×${bmp.height})")
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
                exportResult = res
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                exportResult = ExportResult.Failed("Memoria insufficiente: prova una dimensione più piccola")
            } catch (e: Exception) {
                exportResult = ExportResult.Failed(e.message ?: "Esportazione non riuscita")
            } finally {
                exporting = false
            }
        }
    }

    override fun onCleared() {
        flushSave()
        signal.close()
        try { segmenter.close() } catch (_: Throwable) { }
        modelInput?.recycle()
        faceInput?.recycle()
        super.onCleared()
    }
}
