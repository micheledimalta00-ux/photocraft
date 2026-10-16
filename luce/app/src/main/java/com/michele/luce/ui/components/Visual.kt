package com.michele.luce.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.michele.luce.engine.CurvePoint
import com.michele.luce.engine.Curves
import com.michele.luce.engine.GradeWheel
import com.michele.luce.engine.Histogram
import com.michele.luce.engine.ToneCurve
import com.michele.luce.ui.theme.LuceColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Editor della curva di viraggio: tocca e trascina per aggiungere o spostare un punto,
 * doppio tocco su un punto per eliminarlo.
 */
@Composable
fun ToneCurveEditor(
    curve: ToneCurve,
    color: Color,
    histogram: IntArray?,
    onChange: (ToneCurve) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val current by rememberUpdatedState(curve)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val selected = remember { mutableIntStateOf(-1) }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { pos ->
                    val pts = current.points
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val idx = pts.indices.minByOrNull { hypot(pts[it].x * w - pos.x, (1 - pts[it].y) * h - pos.y) } ?: -1
                    if (idx > 0 && idx < pts.size - 1 &&
                        hypot(pts[idx].x * w - pos.x, (1 - pts[idx].y) * h - pos.y) < 32.dp.toPx()
                    ) {
                        change(ToneCurve(pts.toMutableList().also { it.removeAt(idx) }))
                        commit()
                    }
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val pts = current.points
                        val near = pts.indices.minByOrNull { hypot(pts[it].x * w - pos.x, (1 - pts[it].y) * h - pos.y) }
                        if (near != null && hypot(pts[near].x * w - pos.x, (1 - pts[near].y) * h - pos.y) < 32.dp.toPx()) {
                            selected.intValue = near
                        } else if (pts.size < 16) {
                            val x = (pos.x / w).coerceIn(0.01f, 0.99f)
                            val y = (1f - pos.y / h).coerceIn(0f, 1f)
                            val tooClose = pts.any { kotlin.math.abs(it.x - x) < 0.03f }
                            if (!tooClose) {
                                val list = (pts + CurvePoint(x, y)).sortedBy { it.x }
                                selected.intValue = list.indexOfFirst { it.x == x }
                                change(ToneCurve(list))
                            } else selected.intValue = -1
                        }
                    },
                    onDragEnd = { if (selected.intValue >= 0) commit(); selected.intValue = -1 },
                    onDragCancel = { if (selected.intValue >= 0) commit(); selected.intValue = -1 }
                ) { ch, _ ->
                    val idx = selected.intValue
                    val pts = current.points
                    if (idx < 0 || idx >= pts.size) return@detectDragGestures
                    ch.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val minX = if (idx == 0) 0f else pts[idx - 1].x + 0.01f
                    val maxX = if (idx == pts.size - 1) 1f else pts[idx + 1].x - 0.01f
                    val x = (ch.position.x / w).coerceIn(minX, max(minX, maxX))
                    val y = (1f - ch.position.y / h).coerceIn(0f, 1f)
                    val list = pts.toMutableList()
                    list[idx] = CurvePoint(x, y)
                    change(ToneCurve(list))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF151517))
        // istogramma di sfondo
        if (histogram != null) {
            val mx = max(1, histogram.drop(1).dropLast(1).maxOrNull() ?: 1).toFloat()
            val path = Path().apply {
                moveTo(0f, h)
                for (i in 0 until 256) lineTo(i / 255f * w, h - min(1f, histogram[i] / mx) * h * 0.9f)
                lineTo(w, h); close()
            }
            drawPath(path, Color.White.copy(alpha = 0.08f))
        }
        // griglia
        val grid = Color(0xFF2E2E32)
        for (i in 1..3) {
            drawLine(grid, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), 1f)
            drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
        }
        drawLine(Color(0xFF3A3A40), Offset(0f, h), Offset(w, 0f), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        // curva
        val lut = Curves.buildLut(curve)
        val path = Path()
        for (i in 0..128) {
            val x = i / 128f
            val y = Curves.apply(lut, x)
            if (i == 0) path.moveTo(x * w, (1 - y) * h) else path.lineTo(x * w, (1 - y) * h)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        curve.points.forEachIndexed { i, p ->
            val c = Offset(p.x * w, (1 - p.y) * h)
            drawCircle(Color.Black, 7.dp.toPx(), c)
            drawCircle(if (i == selected.intValue) LuceColors.Accent else Color.White, 5.dp.toPx(), c)
        }
    }
}

/** Ruota cromatica: angolo = tonalità, distanza dal centro = saturazione. */
@Composable
fun ColorWheel(
    wheel: GradeWheel,
    onChange: (GradeWheel) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val current by rememberUpdatedState(wheel)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val hues = remember {
        listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) }
    }
    fun update(pos: Offset, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - 8f
        val dx = pos.x - cx
        val dy = pos.y - cy
        var ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (ang < 0f) ang += 360f
        val sat = (sqrt(dx * dx + dy * dy) / r).coerceIn(0f, 1f) * 100f
        change(current.copy(hue = Math.round(ang).toFloat() % 360f, sat = Math.round(sat).toFloat()))
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { change(current.copy(sat = 0f)); commit() },
                    onTap = { pos -> update(pos, size.width.toFloat(), size.height.toFloat()); commit() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos -> update(pos, size.width.toFloat(), size.height.toFloat()) },
                    onDragEnd = { commit() },
                    onDragCancel = { commit() }
                ) { ch, _ ->
                    ch.consume()
                    update(ch.position, size.width.toFloat(), size.height.toFloat())
                }
            }
    ) {
        val r = min(size.width, size.height) / 2f - 8f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Brush.sweepGradient(hues, c), r, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8A8A), Color(0x008A8A8A)), c, r), r, c)
        drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.25f), 2.dp.toPx(), c)
        val a = Math.toRadians(wheel.hue.toDouble())
        val d = wheel.sat / 100f * r
        val pc = Offset(c.x + (cos(a) * d).toFloat(), c.y + (sin(a) * d).toFloat())
        drawCircle(Color.Black.copy(alpha = 0.5f), 9.dp.toPx(), pc)
        drawCircle(Color.White, 7.dp.toPx(), pc, style = Stroke(2.5f.dp.toPx()))
    }
}

/** Istogramma RGB sovrapposto con indicatori di ritaglio. */
@Composable
fun HistogramView(histogram: Histogram, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Color.Black.copy(alpha = 0.55f), size = Size(w, h))
        val mx = histogram.max.toFloat().coerceAtLeast(1f)
        fun channel(data: IntArray, color: Color) {
            val path = Path().apply {
                moveTo(0f, h)
                for (i in 0 until 256) {
                    val v = min(1f, data[i] / mx)
                    lineTo(i / 255f * w, h - v * (h - 4f))
                }
                lineTo(w, h); close()
            }
            drawPath(path, color)
        }
        channel(histogram.luma, Color(0x55FFFFFF))
        channel(histogram.red, Color(0x66FF4040))
        channel(histogram.green, Color(0x6640FF40))
        channel(histogram.blue, Color(0x664080FF))
        val tri = 7.dp.toPx()
        if (histogram.clipShadows) {
            drawPath(Path().apply { moveTo(3f, 3f); lineTo(3f + tri, 3f); lineTo(3f, 3f + tri); close() }, Color(0xFF3D9BFF))
        }
        if (histogram.clipHighlights) {
            drawPath(Path().apply { moveTo(w - 3f, 3f); lineTo(w - 3f - tri, 3f); lineTo(w - 3f, 3f + tri); close() }, Color.White)
        }
    }
}
