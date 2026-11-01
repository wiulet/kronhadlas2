package com.btcsignal.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btcsignal.app.ui.theme.*
import kotlin.math.max

// ============================================================================
// GOTHIC 3D DESIGN SYSTEM — shared building blocks
// ----------------------------------------------------------------------------
// This file is the single place the "premium dark 3D gothic cyberpunk" visual
// language lives. Every screen in the app already builds its cards, badges,
// buttons and charts out of the composables below (SectionCard, MetricCard,
// StatusBadge, DirectionPill, CircularCountdown, glassPanel, ...), so
// upgrading them here is what makes the whole application look like one
// coherent design system rather than a reskinned home screen — no screen file
// needed a rewrite of its layout or logic, only presentation-layer swaps.
// ============================================================================

/** Five accent "roles" used consistently for buttons/badges across the app —
 * matches the brief's color system (cyan = normal/live/system, red =
 * destructive/bearish, green = success, purple = AI/analysis, orange =
 * history/time). */
enum class GothicRole { CYAN, RED, GREEN, PURPLE, ORANGE }

fun GothicRole.color(): Color = when (this) {
    GothicRole.CYAN -> AccentBlue
    GothicRole.RED -> RedSignal
    GothicRole.GREEN -> GreenSignal
    GothicRole.PURPLE -> AccentPurple
    GothicRole.ORANGE -> AmberWarning
}

/** Small, cheap (no real-time blur) corner-bracket + top hairline ornament
 * drawn on top of every gothic panel — the restrained, non-illustrative stand
 * in for the reference's ornate metal frame corners. Pure vector drawing, so
 * no image assets and no per-frame cost beyond the normal one-shot draw. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGothicFrame(accent: Color) {
    val inset = 3.dp.toPx()
    val arm = 15.dp.toPx()
    val strokeOuter = 2.2.dp.toPx()
    val strokeInner = 1.dp.toPx()
    val dark = accent.copy(alpha = 0.85f)
    val light = accent.copy(alpha = 0.35f)

    // Each corner is a pair of lines (a slightly thicker outer + a thin offset
    // inner highlight) instead of one single hairline, so it reads as a small
    // beveled metal bracket/plate rather than a stray dash. Drawn as one
    // continuous L-shaped path per corner so the two arms always meet cleanly.
    fun corner(x: Float, y: Float, dx: Int, dy: Int) {
        val p = Path().apply {
            moveTo(x, y + arm * dy)
            lineTo(x, y)
            lineTo(x + arm * dx, y)
        }
        drawPath(p, color = dark, style = Stroke(width = strokeOuter, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val offset = strokeOuter * 0.9f
        val p2 = Path().apply {
            moveTo(x + offset * dx, y + (arm - offset) * dy)
            lineTo(x + offset * dx, y + offset * dy)
            lineTo(x + (arm - offset) * dx, y + offset * dy)
        }
        drawPath(p2, color = light, style = Stroke(width = strokeInner, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    corner(inset, inset, 1, 1)
    corner(size.width - inset, inset, -1, 1)
    corner(inset, size.height - inset, 1, -1)
    corner(size.width - inset, size.height - inset, -1, -1)

    // Top hairline highlight, simulating light catching a beveled top edge.
    drawLine(
        GlassHighlight,
        Offset(size.width * 0.14f, inset + 0.5f),
        Offset(size.width * 0.86f, inset + 0.5f),
        strokeWidth = 1f
    )
}

/** Soft colored halo painted with plain Canvas draws (several progressively
 *  larger, progressively more transparent rounded rects stacked behind the
 *  content) instead of relying on [androidx.compose.ui.draw.shadow]'s
 *  `ambientColor`/`spotColor`. Those two parameters are silently IGNORED on
 *  API < 28 (colored shadows need the RenderNode support Android only added
 *  in P) — on this app's minSdk 26/27 devices every "neon glow" button/badge
 *  fell back to a plain flat BLACK shadow instead of its intended accent
 *  color (reported: black shadow under the blue "Running…" button). This
 *  renders identically, and in color, on every supported API level. Pair it
 *  with a small plain (colorless) `.shadow()` for the actual elevation/lift
 *  cue — this modifier only does the color. */
private fun Modifier.neonGlow(
    color: Color,
    cornerRadius: Dp,
    maxSpread: Dp = 8.dp,
    baseAlpha: Float = 0.32f,
    layers: Int = 4
): Modifier = this.drawBehind {
    val cornerPx = cornerRadius.toPx()
    val spreadPx = maxSpread.toPx()
    for (i in layers downTo 1) {
        val t = i / layers.toFloat()
        val spread = spreadPx * t
        drawRoundRect(
            color = color.copy(alpha = baseAlpha * (1f - t * 0.65f)),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(cornerPx + spread, cornerPx + spread)
        )
    }
}

/** Circular counterpart of [neonGlow], for [CircleShape] elements (icon badges,
 *  the countdown ring). Same API<28 rationale — see [neonGlow]. */
private fun Modifier.neonGlowCircle(
    color: Color,
    maxSpread: Dp = 6.dp,
    baseAlpha: Float = 0.35f,
    layers: Int = 4
): Modifier = this.drawBehind {
    val spreadPx = maxSpread.toPx()
    val baseRadius = max(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    for (i in layers downTo 1) {
        val t = i / layers.toFloat()
        val spread = spreadPx * t
        drawCircle(color = color.copy(alpha = baseAlpha * (1f - t * 0.65f)), radius = baseRadius + spread, center = center)
    }
}

/** The base building block for every card/panel in the app: a dark
 * metal-and-glass surface with a beveled metallic border, restrained
 * elevation shadow, and inset corner-bracket ornaments. Cheap to draw (no
 * real-time blur), works down to minSdk 26. [glow] defaults to a neutral
 * metal tone rather than a neon color — the brief explicitly asks not to
 * make every panel border bright red/colored; pass an accent only for the
 * handful of places that should stand out (buttons, badges, the brand
 * title, ...). */
fun Modifier.glassPanel(shape: Shape = RoundedCornerShape(18.dp), glow: Color = MetalHighlight): Modifier = this
    .shadow(elevation = 10.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.6f))
    .clip(shape)
    .background(Brush.verticalGradient(listOf(GlassFillElevated, GlassFill)))
    .border(
        width = 1.1.dp,
        brush = Brush.linearGradient(listOf(glow.copy(alpha = 0.55f), MetalMid, MetalShadow, GlassStroke)),
        shape = shape
    )
    .drawWithContent {
        drawContent()
        drawGothicFrame(glow)
    }

@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .neonGlow(color, cornerRadius = 20.dp, maxSpread = 5.dp)
            .shadow(3.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.20f), Color(0xE60B0B10))))
            .border(1.dp, Brush.verticalGradient(listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.3f))), RoundedCornerShape(20.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** Circular icon "housing" — a small dark glass/metal capsule with a colored
 * glow and ring, matching the brief's icon requirement (3D depth, neon
 * illumination, metallic/glass housing, soft glow) without needing custom
 * icon artwork; any existing Material icon can be dropped in. */
@Composable
fun GothicIconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier = modifier
            .size(size)
            .neonGlowCircle(tint, maxSpread = 5.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.30f), Color(0xFF0A0A0D))))
            .border(1.dp, Brush.verticalGradient(listOf(tint.copy(alpha = 0.9f), tint.copy(alpha = 0.25f))), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
    icon: ImageVector? = null
) {
    Column(modifier = modifier.glassPanel(RoundedCornerShape(16.dp)).padding(14.dp)) {
        if (icon != null) {
            GothicIconBadge(icon, valueColor, size = 28.dp)
            Spacer(Modifier.height(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, glow: Color = MetalHighlight, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp), glow = glow).padding(16.dp)) {
        if (title.isNotEmpty()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** Reusable page header: an uppercase engraved-metal title with a thin accent
 * underline, plus an optional subtitle and a trailing slot (a status badge or
 * action). Used at the top of every screen so all six pages share the same
 * heading treatment. */
@Composable
fun GothicPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = AccentBlue,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
            trailing?.invoke()
        }
        subtitle?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.42f)
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), Color.Transparent)))
        )
        Spacer(Modifier.height(14.dp))
    }
}

/** Stronger visual treatment reserved for the app's single most important
 * title — the BTCUSDT brand plate on the Live dashboard (brief item 8). A
 * dark metal plaque with a brushed-metal gradient on the text itself and a
 * restrained red edge glow. */
@Composable
fun GothicBrandTitle(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .neonGlow(GothicRed, cornerRadius = 10.dp, maxSpread = 9.dp, baseAlpha = 0.4f)
            .shadow(4.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1013), Color(0xFF0A0708))))
            .border(1.2.dp, Brush.verticalGradient(listOf(MetalHighlight, GothicRed, MetalShadow)), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium.copy(brush = metallicTextBrush(), letterSpacing = 1.8.sp),
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/** A 3D "illuminated control panel" button: raised metallic/glass surface,
 * colored glow per [role], and a subtle pressed-in depth animation. [filled]
 * mirrors Material's Button (solid fill, primary action) vs OutlinedButton
 * (dark fill, colored border/text, secondary action). */
@Composable
fun GothicButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: GothicRole = GothicRole.CYAN,
    filled: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: ImageVector? = null
) {
    val color = role.color()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depressAlpha = if (enabled) 1f else 0.4f
    val elevation by animateFloatAsState(if (pressed) 2f else 8f, animationSpec = tween(90), label = "btnElevation")
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = if (compact) 34.dp else 46.dp)
            .neonGlow(color, cornerRadius = 14.dp, maxSpread = (elevation * 0.8f).dp, baseAlpha = 0.4f * depressAlpha)
            .shadow((elevation * 0.4f).dp, shape)
            .clip(shape)
            .background(
                if (filled) Brush.verticalGradient(listOf(color.copy(alpha = 0.95f * depressAlpha), color.copy(alpha = 0.65f * depressAlpha)))
                else Brush.verticalGradient(listOf(Color(0xE6141419), Color(0xF20A0A0D)))
            )
            .border(1.dp, Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f))), shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 7.dp else 12.dp)
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = if (filled) Color.Black else color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            color = if (filled) Color.Black else color,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun GothicSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.Black,
            checkedTrackColor = GreenSignal,
            checkedBorderColor = GreenSignal,
            uncheckedThumbColor = TextSecondary,
            uncheckedTrackColor = BgSurfaceElevated,
            uncheckedBorderColor = MetalMid
        )
    )
}

@Composable
fun GothicFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Color = AccentBlue
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = BgSurface,
            labelColor = TextSecondary,
            selectedContainerColor = role.copy(alpha = 0.22f),
            selectedLabelColor = role
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = MetalMid,
            selectedBorderColor = role,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.2.dp
        )
    )
}

/** Gothic-styled drop-in replacement for Material3's AlertDialog — same
 * essential params, so call sites only need `AlertDialog(` ->
 * `GothicAlertDialog(` plus an optional [accent]. Dark glass panel, metallic
 * border, restrained colored glow (red for destructive confirmations, cyan
 * by default). */
@Composable
fun GothicAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    accent: Color = AccentBlue
) {
    val shape = RoundedCornerShape(22.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        modifier = modifier
            .neonGlow(accent, cornerRadius = 22.dp, maxSpread = 12.dp, baseAlpha = 0.35f)
            .shadow(8.dp, shape)
            .border(1.2.dp, Brush.verticalGradient(listOf(MetalHighlight, accent.copy(alpha = 0.5f), MetalShadow)), shape),
        shape = shape,
        containerColor = BgSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        tonalElevation = 0.dp
    )
}

/** Colored text action for dialog confirm/dismiss buttons, so destructive
 * ("Yes"/"Clear") vs neutral ("No"/"Close") intent stays visually distinct
 * inside a [GothicAlertDialog]. */
@Composable
fun GothicDialogAction(text: String, onClick: () -> Unit, role: GothicRole = GothicRole.CYAN) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = role.color())) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/** Lightweight equity-curve / PnL line chart, pure Canvas, no external chart
 * library. Retains its original signature; only the drawing itself is
 * upgraded with a soft glow pass (cheap: a wide low-alpha stroke behind the
 * crisp line, not a real-time blur) and a faint grid to match the gothic
 * chart language used on the Live dashboard. */
@Composable
fun SimpleLineChart(values: List<Double>, modifier: Modifier = Modifier, lineColor: Color = GreenSignal) {
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (values.size < 2) return@Canvas
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it != 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v ->
            Offset(i * stepX, size.height - ((v - minV) / range * size.height).toFloat())
        }
        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(Color.White.copy(alpha = 0.045f), Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f)
        }
        val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        drawPath(path, color = lineColor.copy(alpha = 0.22f), style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color = lineColor, style = Stroke(width = 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.lastOrNull()?.let {
            drawCircle(lineColor.copy(alpha = 0.3f), radius = 9f, center = it)
            drawCircle(lineColor, radius = 4.5f, center = it)
        }
    }
}

/** Lightweight bar chart, pure Canvas. Positive values green, negative red —
 * same signature and semantics as before, glow pass added for consistency
 * with the rest of the gothic chart language. */
@Composable
fun SimpleBarChart(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (values.isEmpty()) return@Canvas
        val maxAbs = values.maxOf { kotlin.math.abs(it) }.takeIf { it != 0.0 } ?: 1.0
        val barWidth = size.width / values.size * 0.7f
        val gap = size.width / values.size * 0.3f
        val zeroY = size.height / 2f
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, zeroY), Offset(size.width, zeroY), 1f)
        values.forEachIndexed { i, v ->
            val barHeight = (kotlin.math.abs(v) / maxAbs * zeroY).toFloat()
            val x = i * (barWidth + gap)
            val color = if (v >= 0) GreenSignal else RedSignal
            val top = if (v >= 0) zeroY - barHeight else zeroY
            val rect = androidx.compose.ui.geometry.Size(barWidth, barHeight.coerceAtLeast(1f))
            drawRect(color.copy(alpha = 0.35f), topLeft = Offset(x - 2f, top - 2f), size = androidx.compose.ui.geometry.Size(barWidth + 4f, barHeight + 4f))
            drawRect(color, topLeft = Offset(x, top), size = rect)
        }
    }
}

@Composable
fun LabeledValueInline(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TextPrimary) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun DirectionPill(direction: String, modifier: Modifier = Modifier) {
    val color = if (direction == "GREEN") GreenSignal else RedSignal
    Box(
        modifier = modifier
            .neonGlow(color, cornerRadius = 12.dp, maxSpread = 7.dp)
            .shadow(3.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.75f))))
            .border(1.dp, color.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(direction, color = Color.Black, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * Circular countdown ring, restyled as a physical 3D gauge: a recessed dark
 * metal bezel, a glowing progress arc (soft wide pass + crisp pass, the same
 * cheap glow trick used elsewhere — no real-time blur), and bold centered
 * time. Sweeps down from 12 o'clock as the 5-minute candle window elapses.
 * [ringColor] is the neutral accent until a signal is locked, at which point
 * the caller passes the signal's own color (green/red) so the ring visually
 * matches the active prediction — same contract as before, only the drawing
 * changed, so every call site keeps working unmodified.
 */
@Composable
fun CircularCountdown(
    remainingSeconds: Long,
    totalSeconds: Long,
    ringColor: Color,
    modifier: Modifier = Modifier,
    label: String = "Countdown",
    diameter: Dp = 128.dp,
    ringStrokeWidth: Dp = 10.dp,
    timeFontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    showLabel: Boolean = true
) {
    val fraction = if (totalSeconds <= 0) 0f else (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    val mm = remainingSeconds / 60
    val ss = remainingSeconds % 60
    Box(
        modifier = modifier
            .size(diameter)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.6f), spotColor = Color.Black.copy(alpha = 0.6f))
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF17171E), Color(0xFF08080A))))
            .border(1.dp, Brush.verticalGradient(listOf(MetalHighlight, MetalShadow)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val strokeWidth = ringStrokeWidth.toPx()
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val arcSize = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth)
            // Recessed track groove.
            drawArc(
                color = Color.Black.copy(alpha = 0.5f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = strokeWidth + 2f, cap = StrokeCap.Round),
                topLeft = topLeft, size = arcSize
            )
            drawArc(
                color = GlassStroke,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = topLeft, size = arcSize
            )
            // Soft glow pass, then the crisp illuminated progress arc.
            drawArc(
                color = ringColor.copy(alpha = 0.35f),
                startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                style = Stroke(width = strokeWidth + 8f, cap = StrokeCap.Round),
                topLeft = topLeft, size = arcSize
            )
            drawArc(
                color = ringColor,
                startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = topLeft, size = arcSize
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%02d:%02d".format(mm, ss),
                color = TextPrimary,
                fontSize = timeFontSize,
                fontWeight = FontWeight.Bold
            )
            if (showLabel) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
