package com.btcsignal.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// GOTHIC CYBERPUNK TRADING TERMINAL — global palette
// Every screen pulls from this single file (via Components.kt's shared
// glassPanel/StatusBadge/MetricCard/etc.), so retinting here is what makes the
// whole app read as one consistent design system rather than six reskinned
// screens. Names are kept stable from the previous palette so no call site
// elsewhere in the app needs to change — only the hex values move.
// ============================================================================

// Base surfaces — near-black charcoal (never pure #000, so panels still read
// as a material rather than a hole in the screen).
val BgBase = Color(0xFF07070A)
val BgSurface = Color(0xFF121218)
val BgSurfaceElevated = Color(0xFF1B1B24)

// Glass/metal panel fill + stroke — dark and slightly translucent so the
// atmospheric background glow shows through at the edges. Same cheap
// no-realtime-blur technique as before, just retinted from navy to charcoal.
val GlassFill = Color(0xE60F0F14)
val GlassFillElevated = Color(0xF0181820)
val GlassStroke = Color(0x3DFFFFFF)
val GlassHighlight = Color(0x59FFFFFF)

// Root background gradient (whole-app backdrop every screen sits on top of) —
// charcoal-to-black instead of the old corporate navy.
val GradientTop = Color(0xFF16090C)
val GradientMid = Color(0xFF0A0709)
val GradientBottom = Color(0xFF040405)

// Atmospheric red smoke glow + vignette layered behind the whole app (see
// AppScaffold in MainActivity.kt) — restrained/low-alpha per the brief's own
// "avoid a completely flat black background, but keep it dark enough to stay
// readable" and "do not turn the entire UI red" guidance.
val AtmosphereRedGlow = Color(0x3D3A0E14)
val VignetteEdge = Color(0xD9000000)

// Metallic bevel gradient stops — used for panel/button/nav borders. A
// light-to-dark sweep reads as a beveled metal edge catching light from above.
val MetalHighlight = Color(0xFF7A7E8C)
val MetalMid = Color(0xFF3A3B44)
val MetalShadow = Color(0xFF0A0A0D)

// Deep gothic red — reserved for restrained edge-lighting on a few hero
// elements (brand title plate, destructive actions), not applied to every
// panel border (the brief explicitly asks for restraint here).
val GothicRed = Color(0xFF8C1B30)
val GothicRedBright = Color(0xFFFF2D4D)

val GreenSignal = Color(0xFF23E6A8)
val RedSignal = Color(0xFFFF3B5C)
val AmberWarning = Color(0xFFFFA53E)
val TextPrimary = Color(0xFFF2F0F4)
val TextSecondary = Color(0xFFA6A2B1)
val BorderSubtle = Color(0x30FFFFFF)
val AccentBlue = Color(0xFF20D2EE)
val AccentPurple = Color(0xFFB16CFF)

// Colorful per-tab accents for the bottom navigation bar, remapped onto the
// gothic neon set (destinations unchanged — see item 7 of the brief).
val TabLiveColor = Color(0xFF20D2EE)
val TabStrategiesColor = Color(0xFFB16CFF)
val TabBacktestColor = Color(0xFFFFA53E)
val TabHistoryColor = Color(0xFFFF5FAE)
val TabPerformanceColor = Color(0xFF23E6A8)
val TabSettingsColor = Color(0xFFB16CFF)
