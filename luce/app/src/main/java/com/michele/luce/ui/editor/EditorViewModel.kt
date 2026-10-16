package com.michele.luce.ui.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.michele.luce.data.BitmapIO
import com.michele.luce.data.EditStore
import com.michele.luce.data.ExportSettings
import com.michele.luce.data.Exporter
import com.michele.luce.data.MediaRepository
import com.michele.luce.data.PresetStore
import com.michele.luce.data.SettingsClipboard
import com.michele.luce.engine.Analysis
import com.michele.luce.engine.BrushStroke
import com.michele.luce.engine.EditParams
import com.michele.luce.engine.Geometry
import com.michele.luce.engine.Histogram
import com.michele.luce.engine.Img
import com.michele.luce.engine.Mask
import com.michele.luce.engine.MaskType
import com.michele.luce.engine.Pipeline
import com.michele.luce.engine.Preset
import com.michele.luce.engine.Presets
import com.michele.luce.engine.Profiles
import com.michele.luce.engine.RenderOptions
import com.michele.luce.engine.Treatment
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
    MASKS("Maschere"),
    LIGHT("Luce"),
    COLOR("Colore"),
    EFFECTS("Effetti"),
    DETAIL("Dettaglio"),
    OPTICS("Ottica")
}

enum class BrushMode { PAINT, ERASE }

data class BrushSettings(val size: Float = 0.06f, val feather: Float = 0.6f, val flow: Float = 0.8f, val mode: BrushMode = BrushMode.PAINT)

sealed class ExportResult {
    data class Saved(val message: String) : ExportResult()
    data class Share(val intent: Intent) : ExportResult()
    data class Failed(val message: String) : ExportResult()
}

class EditorViewModel(app: Application, val uri: Uri) : AndroidViewModel(app) {

    private val editStore = EditStore(app)
    private val presetStore = PresetStore(app)

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
    var showMaskOverlay by mutableStateOf(true)
    var selectedMaskId by mutableStateOf<Long?>(null)
        private set
    var brush by mutableStateOf(BrushSettings())

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
    private var originalLong = 1

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
            previewSrc = loaded.img
            draftSrc = draft
            thumbSrc = thumb
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

    private suspend fun renderLoop() {
        for (s in signal) {
            val src = previewSrc ?: continue
            val draft = draftSrc ?: continue
            fullJob?.cancel()
            val p = params
            val uncropped = tool == Tool.CROP
            if (interacting) {
                try {
                    val img = withContext(Dispatchers.Default) {
                        Pipeline.render(draft, p, RenderOptions(previewScale(draft), uncropped))
                    }
                    preview = BitmapIO.toBitmap(img).asImageBitmap()
                    previewUncropped = uncropped
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
            val result = withContext(Dispatchers.Default) {
                val cancel = { job?.isActive == false }
                val img = Pipeline.render(src, p, RenderOptions(previewScale(src), uncropped, cancel))
                val hist = Analysis.histogram(img)
                val bmp = BitmapIO.toBitmap(img)
                Triple(img, hist, bmp)
            }
            if (!coroutineContext.isActive) return
            preview = result.third.asImageBitmap()
            previewUncropped = uncropped
            histogram = result.second
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

    private suspend fun updateBefore(src: Img, p: EditParams, uncropped: Boolean) {
        val geo = p.copy(cropLeft = if (uncropped) 0f else p.cropLeft, cropTop = if (uncropped) 0f else p.cropTop,
            cropRight = if (uncropped) 1f else p.cropRight, cropBottom = if (uncropped) 1f else p.cropBottom)
        val key = EditParams().copy(
            rotate90 = geo.rotate90, flipH = geo.flipH, flipV = geo.flipV, straighten = geo.straighten,
            cropLeft = geo.cropLeft, cropTop = geo.cropTop, cropRight = geo.cropRight, cropBottom = geo.cropBottom,
            lensDistortion = geo.lensDistortion, chromaticAberration = geo.chromaticAberration
        )
        if (key == beforeKey && before != null) return
        val img = withContext(Dispatchers.Default) { Geometry.apply(src, key, uncropped) }
        before = BitmapIO.toBitmap(img).asImageBitmap()
        beforeKey = key
    }

    private suspend fun updateMaskOverlay(p: EditParams, uncropped: Boolean) {
        val id = selectedMaskId
        val mask = p.masks.firstOrNull { it.id == id }
        val draft = draftSrc
        if (tool != Tool.MASKS || mask == null || !showMaskOverlay || draft == null) {
            maskOverlay = null
            return
        }
        val bmp = withContext(Dispatchers.Default) {
            val (cw, ch) = Geometry.orientedSize(draft.width, draft.height, p.rotate90)
            val (ow, oh) = Geometry.outputSize(draft.width, draft.height, p, uncropped)
            val px = Pipeline.maskOverlay(p, mask, ow, oh, cw, ch, uncropped)
            BitmapIO.toBitmapWithAlpha(px, ow, oh)
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
        updateUndo(); scheduleSave(); requestRender()
    }

    fun redo() {
        if (hIndex >= history.size - 1) return
        hIndex++
        params = history[hIndex]
        interacting = false
        updateUndo(); scheduleSave(); requestRender()
    }

    private fun updateUndo() {
        canUndo = hIndex > 0
        canRedo = hIndex < history.size - 1
    }

    fun resetAll() = set(EditParams())

    fun resetAdjustments() = set(params.withAdjustmentsFrom(EditParams()).copy(masks = emptyList()))

    private fun scheduleSave() {
        saveJob?.cancel()
        val p = params
        saveJob = viewModelScope.launch {
            delay(700)
            val thumb = thumbSrc
            withContext(Dispatchers.IO) {
                try {
                    val bmp = if (thumb != null && !p.isDefault()) {
                        BitmapIO.toBitmap(Pipeline.render(thumb, p, RenderOptions(previewScale(thumb))))
                    } else null
                    editStore.save(uri, p, bmp)
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
            val thumb = thumbSrc
            try {
                val bmp = if (thumb != null && !p.isDefault()) BitmapIO.toBitmap(Pipeline.render(thumb, p, RenderOptions(previewScale(thumb)))) else null
                editStore.save(uri, p, bmp)
            } catch (_: Throwable) {
            }
        }
    }

    // ---------- strumenti ----------

    fun selectTool(t: Tool) {
        val wasCrop = tool == Tool.CROP
        tool = t
        if (t != Tool.MASKS) selectedMaskId = null
        if (t == Tool.PRESETS || t == Tool.PROFILE) generateThumbs()
        if (wasCrop != (t == Tool.CROP) || t == Tool.MASKS) requestRender()
        else if (maskOverlay != null) maskOverlay = null
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

    fun rotate90() {
        val p = params
        val (cw, ch) = canvasSize()
        // ruota in senso orario anche il riquadro di ritaglio e le maschere
        val np = p.copy(
            rotate90 = (p.rotate90 + 1) % 4,
            cropLeft = 1f - p.cropBottom, cropTop = p.cropLeft,
            cropRight = 1f - p.cropTop, cropBottom = p.cropRight,
            aspect = invertAspect(p.aspect),
            masks = p.masks.map { m ->
                m.copy(
                    x1 = 1f - m.y1, y1 = m.x1, x2 = 1f - m.y2, y2 = m.x2,
                    cx = 1f - m.cy, cy = m.cx,
                    rx = m.rx * cw / ch.coerceAtLeast(1), ry = m.ry * ch / cw.coerceAtLeast(1),
                    angle = m.angle + 90f,
                    strokes = m.strokes.map { st ->
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
            masks = p.masks.map { m ->
                m.copy(
                    x1 = 1f - m.x1, x2 = 1f - m.x2, cx = 1f - m.cx, angle = -m.angle,
                    strokes = m.strokes.map { st -> st.copy(points = st.points.mapIndexed { i, v -> if (i % 2 == 0) 1f - v else v }) }
                )
            }
        ))
    }

    fun flipV() {
        val p = params
        set(p.copy(
            flipV = !p.flipV, cropTop = 1f - p.cropBottom, cropBottom = 1f - p.cropTop,
            masks = p.masks.map { m ->
                m.copy(
                    y1 = 1f - m.y1, y2 = 1f - m.y2, cy = 1f - m.cy, angle = -m.angle,
                    strokes = m.strokes.map { st -> st.copy(points = st.points.mapIndexed { i, v -> if (i % 2 == 1) 1f - v else v }) }
                )
            }
        ))
    }

    fun resetCrop() = set(params.resetGeometry())

    // ---------- maschere ----------

    private var maskCounter = 0

    fun addMask(type: MaskType) {
        maskCounter = max(maskCounter, params.masks.size) + 1
        val name = when (type) {
            MaskType.LINEAR -> "Lineare $maskCounter"
            MaskType.RADIAL -> "Radiale $maskCounter"
            MaskType.BRUSH -> "Pennello $maskCounter"
        }
        val m = Mask(id = System.currentTimeMillis(), name = name, type = type)
        selectedMaskId = m.id
        set(params.copy(masks = params.masks + m))
    }

    fun selectMask(id: Long?) {
        selectedMaskId = id
        requestRender()
    }

    fun selectedMask(): Mask? = params.masks.firstOrNull { it.id == selectedMaskId }

    fun updateMask(m: Mask, live: Boolean) {
        val np = params.copy(masks = params.masks.map { if (it.id == m.id) m else it })
        if (live) update(np) else set(np)
    }

    fun deleteMask(id: Long) {
        if (selectedMaskId == id) selectedMaskId = null
        set(params.copy(masks = params.masks.filter { it.id != id }))
    }

    fun duplicateMask(id: Long) {
        val m = params.masks.firstOrNull { it.id == id } ?: return
        val copy = m.copy(id = System.currentTimeMillis(), name = m.name + " copia")
        selectedMaskId = copy.id
        set(params.copy(masks = params.masks + copy))
    }

    fun toggleOverlay() {
        showMaskOverlay = !showMaskOverlay
        requestRender()
    }

    fun beginStroke(u: Float, v: Float) {
        val m = selectedMask() ?: return
        if (m.type != MaskType.BRUSH) return
        val b = brush
        val stroke = BrushStroke(listOf(u, v), b.size, b.feather, b.flow, b.mode == BrushMode.ERASE)
        updateMask(m.copy(strokes = m.strokes + stroke), live = true)
    }

    fun extendStroke(u: Float, v: Float) {
        val m = selectedMask() ?: return
        if (m.type != MaskType.BRUSH || m.strokes.isEmpty()) return
        val last = m.strokes.last()
        val pts = last.points
        if (pts.size >= 2) {
            val du = u - pts[pts.size - 2]
            val dv = v - pts[pts.size - 1]
            if (du * du + dv * dv < (last.size * 0.08f) * (last.size * 0.08f)) return
        }
        val nl = last.copy(points = pts + listOf(u, v))
        updateMask(m.copy(strokes = m.strokes.dropLast(1) + nl), live = true)
    }

    fun endStroke() = commit()

    fun clearBrush() {
        val m = selectedMask() ?: return
        updateMask(m.copy(strokes = emptyList()), live = false)
    }

    // ---------- esportazione ----------

    fun maxExportEdge(): Int = min(Exporter.MAX_EXPORT, max(originalSize.first, originalSize.second).coerceAtLeast(1))

    fun export(settings: ExportSettings, share: Boolean) {
        if (exporting) return
        exporting = true
        val p = params
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                val job = coroutineContext[Job]
                val res = withContext(Dispatchers.Default) {
                    val bmp = Exporter.render(ctx, uri, p, settings) { job?.isActive == false }
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
        super.onCleared()
    }
}
