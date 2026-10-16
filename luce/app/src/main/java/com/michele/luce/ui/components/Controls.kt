package com.michele.luce.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michele.luce.ui.theme.LuceColors
import java.util.Locale
import kotlin.math.abs

fun formatValue(v: Float, decimals: Int, signed: Boolean, suffix: String = ""): String {
    var mul = 1.0
    repeat(decimals) { mul *= 10.0 }
    val rounded = Math.round(v * mul) / mul
    val body = String.format(Locale.ITALY, "%.${decimals}f", abs(rounded))
    val sign = when {
        rounded < 0.0 -> "-"
        rounded > 0.0 && signed -> "+"
        else -> ""
    }
    return sign + body + suffix
}

/**
 * Cursore in stile camera oscura: trascinamento relativo (preciso), doppio tocco per azzerare,
 * riempimento dal punto neutro.
 */
@Composable
fun LuceSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    default: Float = 0f,
    decimals: Int = 0,
    suffix: String = "",
    gradient: List<Color>? = null,
    enabled: Boolean = true
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val span = range.endInclusive - range.start
    val signed = range.start < 0f
    var dragging by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(default) {
                    detectTapGestures(onDoubleTap = {
                        if (enabled) { currentOnChange(default); currentOnCommit() }
                    })
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) LuceColors.TextPrimary else LuceColors.TextDisabled,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatValue(value, decimals, signed, suffix),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !enabled -> LuceColors.TextDisabled
                    dragging -> LuceColors.Accent
                    abs(value - default) > 1e-4f -> LuceColors.TextPrimary
                    else -> LuceColors.TextSecondary
                },
                textAlign = TextAlign.End
            )
        }
        val accum = remember { mutableFloatStateOf(0f) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range.start, range.endInclusive), range)
                    setProgress { v -> currentOnChange(v.coerceIn(range.start, range.endInclusive)); currentOnCommit(); true }
                }
                .pointerInput(default, range) {
                    detectTapGestures(onDoubleTap = {
                        if (enabled) { currentOnChange(default); currentOnCommit() }
                    })
                }
                .pointerInput(range) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (enabled) { dragging = true; accum.floatValue = currentValue }
                        },
                        onDragEnd = { if (dragging) { dragging = false; currentOnCommit() } },
                        onDragCancel = { if (dragging) { dragging = false; currentOnCommit() } }
                    ) { change, dx ->
                        if (!enabled) return@detectHorizontalDragGestures
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        accum.floatValue = (accum.floatValue + dx / w * span).coerceIn(range.start, range.endInclusive)
                        val step = if (decimals == 0) 1f else if (decimals == 1) 0.1f else 0.01f
                        val snapped = (Math.round(accum.floatValue / step) * step).coerceIn(range.start, range.endInclusive)
                        if (snapped != currentValue) currentOnChange(snapped)
                    }
                }
        ) {
            val cy = size.height / 2f
            val pad = 10.dp.toPx()
            val left = pad
            val right = size.width - pad
            val trackH = if (gradient != null) 4.dp.toPx() else 2.dp.toPx()
            fun xOf(v: Float) = left + (v - range.start) / span * (right - left)
            if (gradient != null) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(gradient, startX = left, endX = right),
                    topLeft = Offset(left, cy - trackH / 2), size = Size(right - left, trackH),
                    cornerRadius = CornerRadius(trackH / 2)
                )
            } else {
                drawRoundRect(
                    color = Color(0xFF4A4A50),
                    topLeft = Offset(left, cy - trackH / 2), size = Size(right - left, trackH),
                    cornerRadius = CornerRadius(trackH / 2)
                )
                val origin = xOf(default.coerceIn(range.start, range.endInclusive))
                val x = xOf(value.coerceIn(range.start, range.endInclusive))
                drawRect(
                    color = if (enabled) LuceColors.Accent else LuceColors.TextDisabled,
                    topLeft = Offset(minOf(origin, x), cy - trackH / 2),
                    size = Size(abs(x - origin), trackH)
                )
                if (signed) drawCircle(Color(0xFF6A6A70), 2.dp.toPx(), Offset(origin, cy))
            }
            val tx = xOf(value.coerceIn(range.start, range.endInclusive))
            drawCircle(Color.Black.copy(alpha = 0.4f), (if (dragging) 10.dp else 8.dp).toPx() + 1.5f, Offset(tx, cy))
            drawCircle(if (enabled) Color.White else LuceColors.TextDisabled, (if (dragging) 10.dp else 8.dp).toPx(), Offset(tx, cy))
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(Locale.ITALY),
            style = MaterialTheme.typography.labelMedium,
            color = LuceColors.TextSecondary,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) LuceColors.Accent.copy(alpha = 0.22f) else LuceColors.SurfaceHigh)
            .border(1.dp, if (selected) LuceColors.Accent else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) LuceColors.Accent else LuceColors.TextPrimary, modifier = Modifier.padding(end = 6.dp).width(16.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) LuceColors.Accent else LuceColors.TextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
fun <T> PillRow(items: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item -> Pill(label(item), item == selected, { onSelect(item) }) }
    }
}

@Composable
fun ActionButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = when {
            !enabled -> LuceColors.TextDisabled
            active -> LuceColors.Accent
            else -> LuceColors.TextPrimary
        }
        Icon(icon, contentDescription = text, tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(LuceColors.Divider))
}

object Gradients {
    val Temperature = listOf(Color(0xFF3B7DFF), Color(0xFFCFCFCF), Color(0xFFFFC23B))
    val Tint = listOf(Color(0xFF45D16A), Color(0xFFCFCFCF), Color(0xFFE04BD7))
    val Saturation = listOf(Color(0xFF808080), Color(0xFFFF3B3B))
    val Spectrum = listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f).map { Color.hsv(it, 0.8f, 0.9f) }
    fun hue(center: Float): List<Color> = listOf(
        Color.hsv(((center - 40f) % 360f + 360f) % 360f, 0.8f, 0.9f),
        Color.hsv(((center) % 360f + 360f) % 360f, 0.8f, 0.9f),
        Color.hsv(((center + 40f) % 360f + 360f) % 360f, 0.8f, 0.9f)
    )
    fun sat(center: Float) = listOf(Color(0xFF7A7A7A), Color.hsv(((center % 360f) + 360f) % 360f, 0.9f, 0.9f))
    fun lum(center: Float) = listOf(Color.hsv(((center % 360f) + 360f) % 360f, 0.9f, 0.25f), Color.hsv(((center % 360f) + 360f) % 360f, 0.5f, 1f))
}
