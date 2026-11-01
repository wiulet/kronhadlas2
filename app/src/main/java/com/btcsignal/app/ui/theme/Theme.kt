package com.btcsignal.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    background = BgBase,
    surface = BgSurface,
    surfaceVariant = BgSurfaceElevated,
    primary = AccentBlue,
    secondary = GreenSignal,
    error = RedSignal,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onPrimary = BgBase,
    outline = BorderSubtle
)

// Slight letter-spacing on titles gives the "engraved metal plate" feel the
// gothic reference uses for headings, without needing a custom display font.
val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = TextPrimary, letterSpacing = 0.6.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = TextPrimary, letterSpacing = 0.3.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 13.sp, color = TextSecondary),
    labelSmall = TextStyle(fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.3.sp)
)

/** Brushed-metal gradient for a handful of hero titles (e.g. the BTCUSDT brand
 * plate) — used via `TextStyle.copy(brush = metallicTextBrush())`. Kept out of
 * the base [AppTypography] so ordinary body/label text stays plain and
 * legible; only a few large, important titles get this treatment (per the
 * brief: "do not make every title overly decorative"). */
fun metallicTextBrush(): Brush = Brush.verticalGradient(
    listOf(androidx.compose.ui.graphics.Color(0xFFF5F1E8), androidx.compose.ui.graphics.Color(0xFFBBB2A6), androidx.compose.ui.graphics.Color(0xFF847B72))
)

@Composable
fun BtcSignalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}
