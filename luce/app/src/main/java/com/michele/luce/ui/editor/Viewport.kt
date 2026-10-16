package com.michele.luce.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.michele.luce.engine.EditParams
import com.michele.luce.engine.Geometry
import com.michele.luce.engine.Mask
import com.michele.luce.engine.MaskType
import com.michele.luce.ui.components.HistogramView
import com.michele.luce.ui.theme.LuceColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private enum class CropHit { NONE, MOVE, TL, TR, BL, BR, L, R, T, B }
private enum class MaskHit { NONE, CENTER, AXIS_X, AXIS_Y, P1, P2, LINE_CENTER, PAINT }

@Composable
fun Viewport(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(Size.Zero) }
    var brushCursor by remember { mutableStateOf<Offset?>(null) }
    var cropDragging by remember { mutableStateOf(false) }

    val image: ImageBitmap? = if (vm.showBefore) vm.before ?: vm.preview else vm.preview
    val params = vm.params
    val cropMode = vm.tool == Tool.CROP && vm.previewUncropped
    val maskMode = vm.tool == Tool.MASKS
    val selectedMask = vm.selectedMask()

    // Proporzioni dell'immagine visualizzata: si usano quelle attese dai parametri, così
    // il riquadro non "salta" mentre l'anteprima viene ricalcolata. Le funzioni sotto leggono
    // sempre lo stato corrente perché vengono usate anche dai gestori di tocco.
    fun expectedAspect(): Float {
        val (cw, ch) = vm.canvasSize()
        val p = vm.params
        return if (vm.tool == Tool.CROP) {
            cw.toFloat() / max(1, ch)
        } else {
            val w = (p.cropRight - p.cropLeft) * cw
            val h = (p.cropBottom - p.cropTop) * ch
            w / max(1f, h)
        }
    }

    if (vm.tool == Tool.CROP && zoom != 1f) { zoom = 1f; offset = Offset.Zero }

    fun baseRect(): Rect {
        val bw = boxSize.width
        val bh = boxSize.height
        if (bw <= 0f || bh <= 0f) return Rect.Zero
        val pad = if (vm.tool == Tool.CROP) with(density) { 24.dp.toPx() } else with(density) { 8.dp.toPx() }
        val aw = bw - pad * 2
        val ah = bh - pad * 2
        var w = aw
        val aspect = expectedAspect()
        var h = w / aspect
        if (h > ah) { h = ah; w = h * aspect }
        return Rect(Offset((bw - w) / 2f, (bh - h) / 2f), Size(w, h))
    }

    fun shownRect(): Rect {
        val b = baseRect()
        val c = Offset(boxSize.width / 2f, boxSize.height / 2f)
        val tl = c + (b.topLeft - c) * zoom + offset
        return Rect(tl, Size(b.width * zoom, b.height * zoom))
    }

    // ---- conversioni coordinate ----
    // In modalità maschera l'immagine mostrata è ritagliata: le maschere vivono sulla tela intera.
    fun toCanvasUv(pos: Offset): Offset {
        val r = shownRect()
        val nx = (pos.x - r.left) / max(1f, r.width)
        val ny = (pos.y - r.top) / max(1f, r.height)
        val p = vm.params
        return Offset(p.cropLeft + nx * (p.cropRight - p.cropLeft), p.cropTop + ny * (p.cropBottom - p.cropTop))
    }

    fun fromCanvasUv(u: Float, v: Float): Offset {
        val r = shownRect()
        val p = vm.params
        val nx = (u - p.cropLeft) / max(1e-4f, p.cropRight - p.cropLeft)
        val ny = (v - p.cropTop) / max(1e-4f, p.cropBottom - p.cropTop)
        return Offset(r.left + nx * r.width, r.top + ny * r.height)
    }

    /** Pixel dello schermo per pixel della tela. */
    fun screenPerCanvasPx(): Float {
        val (cw, _) = vm.canvasSize()
        val r = shownRect()
        val p = vm.params
        return r.width / max(1f, (p.cropRight - p.cropLeft) * cw)
    }

    val touch = with(density) { 30.dp.toPx() }
    val currentVm by rememberUpdatedState(vm)

    // stato dei trascinamenti
    var cropHit by remember { mutableStateOf(CropHit.NONE) }
    var cropStart by remember { mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f)) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var maskHit by remember { mutableStateOf(MaskHit.NONE) }
    var maskStart by remember { mutableStateOf<Mask?>(null) }

    fun cropHitTest(pos: Offset): CropHit {
        val r = shownRect()
        val p = vm.params
        val l = r.left + p.cropLeft * r.width
        val t = r.top + p.cropTop * r.height
        val rr = r.left + p.cropRight * r.width
        val b = r.top + p.cropBottom * r.height
        fun near(a: Offset) = (pos - a).getDistance() < touch
        return when {
            near(Offset(l, t)) -> CropHit.TL
            near(Offset(rr, t)) -> CropHit.TR
            near(Offset(l, b)) -> CropHit.BL
            near(Offset(rr, b)) -> CropHit.BR
            abs(pos.x - l) < touch && pos.y in t..b -> CropHit.L
            abs(pos.x - rr) < touch && pos.y in t..b -> CropHit.R
            abs(pos.y - t) < touch && pos.x in l..rr -> CropHit.T
            abs(pos.y - b) < touch && pos.x in l..rr -> CropHit.B
            pos.x in l..rr && pos.y in t..b -> CropHit.MOVE
            else -> CropHit.NONE
        }
    }

    fun applyCropDrag(pos: Offset) {
        val (cw, ch) = vm.canvasSize()
        val r = shownRect()
        if (r.width <= 0f) return
        val dx = (pos.x - dragStart.x) / r.width
        val dy = (pos.y - dragStart.y) / r.height
        var l = cropStart[0]; var t = cropStart[1]; var rr = cropStart[2]; var b = cropStart[3]
        val minS = 0.05f
        when (cropHit) {
            CropHit.MOVE -> {
                val w = rr - l; val h = b - t
                l = (l + dx).coerceIn(0f, 1f - w); t = (t + dy).coerceIn(0f, 1f - h)
                rr = l + w; b = t + h
            }
            CropHit.TL -> { l = (l + dx).coerceIn(0f, rr - minS); t = (t + dy).coerceIn(0f, b - minS) }
            CropHit.TR -> { rr = (rr + dx).coerceIn(l + minS, 1f); t = (t + dy).coerceIn(0f, b - minS) }
            CropHit.BL -> { l = (l + dx).coerceIn(0f, rr - minS); b = (b + dy).coerceIn(t + minS, 1f) }
            CropHit.BR -> { rr = (rr + dx).coerceIn(l + minS, 1f); b = (b + dy).coerceIn(t + minS, 1f) }
            CropHit.L -> l = (l + dx).coerceIn(0f, rr - minS)
            CropHit.R -> rr = (rr + dx).coerceIn(l + minS, 1f)
            CropHit.T -> t = (t + dy).coerceIn(0f, b - minS)
            CropHit.B -> b = (b + dy).coerceIn(t + minS, 1f)
            CropHit.NONE -> return
        }
        // rapporto d'aspetto bloccato
        val ratio = Geometry.aspectValue(vm.params.aspect, cw, ch)
        if (ratio != null && cropHit != CropHit.MOVE) {
            // lavora in pixel della tela
            var wPx = (rr - l) * cw
            var hPx = (b - t) * ch
            val horizontalDriver = cropHit == CropHit.L || cropHit == CropHit.R ||
                ((cropHit == CropHit.TL || cropHit == CropHit.TR || cropHit == CropHit.BL || cropHit == CropHit.BR) && abs(dx * r.width) >= abs(dy * r.height))
            if (horizontalDriver) hPx = wPx / ratio else wPx = hPx * ratio
            // ancoraggi
            val anchorRight = cropHit == CropHit.TL || cropHit == CropHit.BL || cropHit == CropHit.L
            val anchorBottom = cropHit == CropHit.TL || cropHit == CropHit.TR || cropHit == CropHit.T
            val centerX = (cropStart[0] + cropStart[2]) / 2f
            val centerY = (cropStart[1] + cropStart[3]) / 2f
            // limiti disponibili
            val maxW = when {
                cropHit == CropHit.T || cropHit == CropHit.B -> min(centerX, 1f - centerX) * 2f * cw
                anchorRight -> rr * cw
                else -> (1f - l) * cw
            }
            val maxH = when {
                cropHit == CropHit.L || cropHit == CropHit.R -> min(centerY, 1f - centerY) * 2f * ch
                anchorBottom -> b * ch
                else -> (1f - t) * ch
            }
            if (wPx > maxW) { wPx = maxW; hPx = wPx / ratio }
            if (hPx > maxH) { hPx = maxH; wPx = hPx * ratio }
            val nw = wPx / cw
            val nh = hPx / ch
            when (cropHit) {
                CropHit.T, CropHit.B -> { l = centerX - nw / 2f; rr = centerX + nw / 2f }
                else -> if (anchorRight) l = rr - nw else rr = l + nw
            }
            when (cropHit) {
                CropHit.L, CropHit.R -> { t = centerY - nh / 2f; b = centerY + nh / 2f }
                else -> if (anchorBottom) t = b - nh else b = t + nh
            }
        }
        val np = vm.params.copy(
            cropLeft = l.coerceIn(0f, 1f), cropTop = t.coerceIn(0f, 1f),
            cropRight = rr.coerceIn(0f, 1f), cropBottom = b.coerceIn(0f, 1f)
        )
        vm.update(np)
    }

    fun maskHitTest(pos: Offset, m: Mask): MaskHit {
        val (cw, ch) = vm.canvasSize()
        return when (m.type) {
            MaskType.RADIAL -> {
                val c = fromCanvasUv(m.cx, m.cy)
                val spp = screenPerCanvasPx()
                val a = Math.toRadians(m.angle.toDouble())
                val rxPx = m.rx * cw * spp
                val ryPx = m.ry * ch * spp
                val hx = c + Offset((cos(a) * rxPx).toFloat(), (sin(a) * rxPx).toFloat())
                val hy = c + Offset((-sin(a) * ryPx).toFloat(), (cos(a) * ryPx).toFloat())
                when {
                    (pos - hx).getDistance() < touch -> MaskHit.AXIS_X
                    (pos - hy).getDistance() < touch -> MaskHit.AXIS_Y
                    (pos - c).getDistance() < touch * 1.4f -> MaskHit.CENTER
                    else -> MaskHit.NONE
                }
            }
            MaskType.LINEAR -> {
                val p1 = fromCanvasUv(m.x1, m.y1)
                val p2 = fromCanvasUv(m.x2, m.y2)
                val mid = (p1 + p2) / 2f
                when {
                    (pos - p1).getDistance() < touch -> MaskHit.P1
                    (pos - p2).getDistance() < touch -> MaskHit.P2
                    (pos - mid).getDistance() < touch * 1.4f -> MaskHit.LINE_CENTER
                    else -> MaskHit.NONE
                }
            }
            MaskType.BRUSH -> MaskHit.PAINT
        }
    }

    fun applyMaskDrag(pos: Offset) {
        val (cw, ch) = vm.canvasSize()
        val m0 = maskStart ?: return
        val uv = toCanvasUv(pos)
        val uv0 = toCanvasUv(dragStart)
        val du = uv.x - uv0.x
        val dv = uv.y - uv0.y
        val m = when (maskHit) {
            MaskHit.CENTER -> m0.copy(cx = (m0.cx + du).coerceIn(-0.5f, 1.5f), cy = (m0.cy + dv).coerceIn(-0.5f, 1.5f))
            MaskHit.AXIS_X -> {
                val dxPx = (uv.x - m0.cx) * cw
                val dyPx = (uv.y - m0.cy) * ch
                val len = hypot(dxPx, dyPx)
                val ang = Math.toDegrees(atan2(dyPx, dxPx).toDouble()).toFloat()
                m0.copy(rx = (len / cw).coerceIn(0.01f, 2f), angle = ang)
            }
            MaskHit.AXIS_Y -> {
                val dxPx = (uv.x - m0.cx) * cw
                val dyPx = (uv.y - m0.cy) * ch
                val a = Math.toRadians(m0.angle.toDouble())
                // proiezione sull'asse locale y
                val ly = (-sin(a) * dxPx + cos(a) * dyPx).toFloat()
                m0.copy(ry = (abs(ly) / ch).coerceIn(0.01f, 2f))
            }
            MaskHit.P1 -> m0.copy(x1 = uv.x, y1 = uv.y)
            MaskHit.P2 -> m0.copy(x2 = uv.x, y2 = uv.y)
            MaskHit.LINE_CENTER -> m0.copy(x1 = m0.x1 + du, y1 = m0.y1 + dv, x2 = m0.x2 + du, y2 = m0.y2 + dv)
            else -> return
        }
        vm.updateMask(m, live = true)
    }

    val (drawCw, drawCh) = vm.canvasSize()

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                // pressione prolungata = mostra originale; doppio tocco = zoom
                detectTapGestures(
                    onDoubleTap = { pos ->
                        val v = currentVm
                        if (v.tool == Tool.CROP) return@detectTapGestures
                        if (zoom > 1.05f) {
                            zoom = 1f; offset = Offset.Zero
                        } else {
                            val z = 2.5f
                            val c = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            offset = (c - pos) * (z - 1f)
                            zoom = z
                        }
                    },
                    onPress = {
                        val v = currentVm
                        var job: Job? = null
                        if (!(v.tool == Tool.MASKS && v.selectedMask()?.type == MaskType.BRUSH) && v.tool != Tool.CROP) {
                            job = scope.launch {
                                delay(420)
                                v.showBefore = true
                            }
                        }
                        tryAwaitRelease()
                        job?.cancel()
                        if (v.showBefore) v.showBefore = false
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val v = currentVm
                    var mode = 0 // 0 = indeciso, 1 = trascinamento singolo, 2 = multi-tocco
                    val slop = viewConfiguration.touchSlop
                    val singleKind: Int = when {
                        v.tool == Tool.CROP && v.previewUncropped -> {
                            cropHit = cropHitTest(down.position)
                            if (cropHit != CropHit.NONE) 1 else 0
                        }
                        v.tool == Tool.MASKS && v.selectedMask() != null -> {
                            val m = v.selectedMask()!!
                            maskHit = maskHitTest(down.position, m)
                            maskStart = m
                            when (maskHit) {
                                MaskHit.NONE -> if (zoom > 1f) 3 else 0
                                MaskHit.PAINT -> 4
                                else -> 2
                            }
                        }
                        zoom > 1f -> 3
                        else -> 0
                    }
                    dragStart = down.position
                    if (singleKind == 1) {
                        val p = v.params
                        cropStart = floatArrayOf(p.cropLeft, p.cropTop, p.cropRight, p.cropBottom)
                        cropDragging = true
                    }
                    if (singleKind == 4) {
                        brushCursor = down.position
                        val uv = toCanvasUv(down.position)
                        v.beginStroke(uv.x, uv.y)
                        mode = 1
                    }
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 && v.tool != Tool.CROP) {
                            if (mode == 1) {
                                if (singleKind == 4) v.endStroke() else v.commit()
                                brushCursor = null
                            }
                            mode = 2
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newZoom = (zoom * z).coerceIn(1f, 8f)
                            val c = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            var newOffset = centroid + pan - c - (centroid - offset - c) * (newZoom / zoom)
                            val b = baseRect()
                            val maxX = max(0f, (b.width * newZoom - boxSize.width) / 2f + b.width * 0.25f)
                            val maxY = max(0f, (b.height * newZoom - boxSize.height) / 2f + b.height * 0.25f)
                            newOffset = Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
                            zoom = newZoom
                            offset = if (newZoom <= 1.001f) Offset.Zero else newOffset
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (mode != 2) {
                            val ch0 = pressed[0]
                            if (mode == 0 && (ch0.position - down.position).getDistance() > slop && singleKind != 0) mode = 1
                            if (mode == 1) {
                                when (singleKind) {
                                    1 -> applyCropDrag(ch0.position)
                                    2 -> applyMaskDrag(ch0.position)
                                    3 -> {
                                        val delta = ch0.position - last
                                        val b = baseRect()
                                        val maxX = max(0f, (b.width * zoom - boxSize.width) / 2f + b.width * 0.25f)
                                        val maxY = max(0f, (b.height * zoom - boxSize.height) / 2f + b.height * 0.25f)
                                        offset = Offset((offset.x + delta.x).coerceIn(-maxX, maxX), (offset.y + delta.y).coerceIn(-maxY, maxY))
                                    }
                                    4 -> {
                                        brushCursor = ch0.position
                                        val uv = toCanvasUv(ch0.position)
                                        v.extendStroke(uv.x, uv.y)
                                    }
                                }
                                ch0.consume()
                            }
                            last = ch0.position
                        }
                    }
                    if (mode == 1) {
                        when (singleKind) {
                            4 -> v.endStroke()
                            1, 2 -> v.commit()
                        }
                    } else if (mode == 0 && singleKind == 4) {
                        v.endStroke()
                    }
                    cropDragging = false
                    brushCursor = null
                    maskHit = MaskHit.NONE
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = shownRect()
            if (image != null && r.width > 0f) {
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(max(1, r.width.roundToInt()), max(1, r.height.roundToInt())),
                    filterQuality = FilterQuality.High
                )
                val overlay = vm.maskOverlay
                if (maskMode && overlay != null && !vm.showBefore) {
                    drawImage(
                        overlay,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(overlay.width, overlay.height),
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(max(1, r.width.roundToInt()), max(1, r.height.roundToInt())),
                        filterQuality = FilterQuality.Low
                    )
                }
            }
            if (cropMode && !vm.showBefore) drawCropOverlay(r, params, cropDragging)
            if (maskMode && selectedMask != null && !vm.showBefore) {
                drawMaskHandles(selectedMask, ::fromCanvasUv, screenPerCanvasPx(), drawCw, drawCh)
            }
            brushCursor?.let { pos ->
                val radius = vm.brush.size * max(drawCw, drawCh) * screenPerCanvasPx() / 2f
                drawCircle(Color.White, radius, pos, style = Stroke(1.5f.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.6f), radius * (1f - vm.brush.feather * 0.8f), pos, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            }
        }

        if (vm.showHistogram && vm.histogram != null && !cropMode) {
            HistogramView(
                vm.histogram!!,
                Modifier.align(Alignment.TopEnd).padding(10.dp).width(150.dp).height(64.dp)
            )
        }
        if (vm.showBefore) {
            Text(
                "Originale",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            )
        }
        if (vm.rendering && image == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = LuceColors.Accent)
        }
    }
}

private fun DrawScope.drawCropOverlay(r: Rect, p: EditParams, dragging: Boolean) {
    val l = r.left + p.cropLeft * r.width
    val t = r.top + p.cropTop * r.height
    val rr = r.left + p.cropRight * r.width
    val b = r.top + p.cropBottom * r.height
    val shade = Color.Black.copy(alpha = 0.6f)
    drawRect(shade, Offset(r.left, r.top), Size(r.width, t - r.top))
    drawRect(shade, Offset(r.left, b), Size(r.width, r.bottom - b))
    drawRect(shade, Offset(r.left, t), Size(l - r.left, b - t))
    drawRect(shade, Offset(rr, t), Size(r.right - rr, b - t))
    drawRect(Color.White, Offset(l, t), Size(rr - l, b - t), style = Stroke(1.dp.toPx()))
    val gridColor = Color.White.copy(alpha = if (dragging) 0.7f else 0.35f)
    for (i in 1..2) {
        val x = l + (rr - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(gridColor, Offset(x, t), Offset(x, b), 1f)
        drawLine(gridColor, Offset(l, y), Offset(rr, y), 1f)
    }
    val hl = 18.dp.toPx()
    val sw = 3.dp.toPx()
    fun corner(x: Float, y: Float, sx: Float, sy: Float) {
        drawLine(Color.White, Offset(x, y), Offset(x + hl * sx, y), sw, StrokeCap.Square)
        drawLine(Color.White, Offset(x, y), Offset(x, y + hl * sy), sw, StrokeCap.Square)
    }
    corner(l, t, 1f, 1f); corner(rr, t, -1f, 1f); corner(l, b, 1f, -1f); corner(rr, b, -1f, -1f)
    val mid = 14.dp.toPx()
    drawLine(Color.White, Offset((l + rr) / 2 - mid, t), Offset((l + rr) / 2 + mid, t), sw)
    drawLine(Color.White, Offset((l + rr) / 2 - mid, b), Offset((l + rr) / 2 + mid, b), sw)
    drawLine(Color.White, Offset(l, (t + b) / 2 - mid), Offset(l, (t + b) / 2 + mid), sw)
    drawLine(Color.White, Offset(rr, (t + b) / 2 - mid), Offset(rr, (t + b) / 2 + mid), sw)
}

private fun DrawScope.handle(c: Offset, filled: Boolean = false) {
    drawCircle(Color.Black.copy(alpha = 0.5f), 9.dp.toPx(), c)
    if (filled) drawCircle(LuceColors.Accent, 7.dp.toPx(), c)
    drawCircle(Color.White, 7.dp.toPx(), c, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.drawMaskHandles(m: Mask, map: (Float, Float) -> Offset, spp: Float, cw: Int, ch: Int) {
    when (m.type) {
        MaskType.RADIAL -> {
            val c = map(m.cx, m.cy)
            val a = Math.toRadians(m.angle.toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val rx = m.rx * cw * spp
            val ry = m.ry * ch * spp
            fun ellipse(scale: Float): Path = Path().apply {
                for (i in 0..64) {
                    val t = i / 64f * 2f * Math.PI.toFloat()
                    val lx = cos(t) * rx * scale
                    val ly = sin(t) * ry * scale
                    val x = c.x + lx * ca - ly * sa
                    val y = c.y + lx * sa + ly * ca
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(ellipse(1f), Color.White, style = Stroke(1.5f.dp.toPx()))
            val inner = 1f - max(0.01f, m.feather / 100f)
            if (inner > 0.02f) drawPath(ellipse(inner), Color.White.copy(alpha = 0.6f), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            handle(c, filled = true)
            handle(Offset(c.x + rx * ca, c.y + rx * sa))
            handle(Offset(c.x - ry * sa, c.y + ry * ca))
        }
        MaskType.LINEAR -> {
            val p1 = map(m.x1, m.y1)
            val p2 = map(m.x2, m.y2)
            val d = p2 - p1
            val len = sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(1f)
            val perp = Offset(-d.y / len, d.x / len) * 3000f
            drawLine(Color.White, p1 - perp, p1 + perp, 1.5f.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.6f), p2 - perp, p2 + perp, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val mid = (p1 + p2) / 2f
            drawLine(Color.White.copy(alpha = 0.35f), mid - perp, mid + perp, 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.8f), p1, p2, 1.dp.toPx())
            handle(p1)
            handle(p2)
            handle(mid, filled = true)
        }
        MaskType.BRUSH -> Unit
    }
}
