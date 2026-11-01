package com.btcsignal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.btcsignal.app.live.LiveMonitoringService
import com.btcsignal.app.ui.screens.*
import com.btcsignal.app.ui.theme.AtmosphereRedGlow
import com.btcsignal.app.ui.theme.BtcSignalTheme
import com.btcsignal.app.ui.theme.GradientBottom
import com.btcsignal.app.ui.theme.GradientMid
import com.btcsignal.app.ui.theme.GradientTop
import com.btcsignal.app.ui.theme.MetalHighlight
import com.btcsignal.app.ui.theme.MetalShadow
import com.btcsignal.app.ui.theme.TabBacktestColor
import com.btcsignal.app.ui.theme.TabHistoryColor
import com.btcsignal.app.ui.theme.TabLiveColor
import com.btcsignal.app.ui.theme.TabPerformanceColor
import com.btcsignal.app.ui.theme.TabSettingsColor
import com.btcsignal.app.ui.theme.TabStrategiesColor
import com.btcsignal.app.ui.theme.TextSecondary
import com.btcsignal.app.ui.theme.VignetteEdge

private data class Tab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

private val TABS = listOf(
    Tab("live", "Live", Icons.Filled.ShowChart, TabLiveColor),
    Tab("strategies", "Kronos", Icons.Filled.ListAlt, TabStrategiesColor),
    Tab("backtest", "Backtest", Icons.Filled.History, TabBacktestColor),
    Tab("history", "History", Icons.Filled.Receipt, TabHistoryColor),
    Tab("performance", "Performance", Icons.Filled.BarChart, TabPerformanceColor),
    Tab("settings", "Settings", Icons.Filled.Settings, TabSettingsColor)
)

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result observed via ContextCompat.checkSelfPermission where needed */ }

    private var highlightSignalIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        startLiveMonitoring()
        highlightSignalIdState.value = intent?.data?.lastPathSegment

        setContent {
            BtcSignalTheme {
                AppScaffold(highlightSignalIdState.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        highlightSignalIdState.value = intent.data?.lastPathSegment
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startLiveMonitoring() {
        val intent = Intent(this, LiveMonitoringService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

/** Root background for the whole app: the existing dark vertical gradient,
 * plus a restrained atmospheric red smoke glow near the top and a vignette at
 * the edges (brief item 3). Both are one-shot radial gradients recomputed
 * only when the canvas size changes ([drawWithCache]) — no per-frame cost,
 * no real-time blur, so this stays cheap on older devices (brief item 20).
 * Drawn once here at the root, so every screen sits on top of it and the
 * whole app reads as one consistent backdrop rather than six separate ones. */
private fun Modifier.gothicAtmosphere(): Modifier = this
    .background(Brush.verticalGradient(listOf(GradientTop, GradientMid, GradientBottom)))
    .drawWithCache {
        val glow = Brush.radialGradient(
            colors = listOf(AtmosphereRedGlow, Color.Transparent),
            center = Offset(size.width * 0.5f, -size.height * 0.1f),
            radius = size.width * 1.15f
        )
        val vignette = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Transparent, VignetteEdge),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = kotlin.math.max(size.width, size.height) * 0.8f
        )
        onDrawBehind {
            drawRect(glow)
            drawRect(vignette)
        }
    }

@Composable
private fun AppScaffold(highlightSignalId: String?) {
    val navController = rememberNavController()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .gothicAtmosphere()
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route
                GothicBottomNav(
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "live",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                composable("live") { LiveSignalScreen(highlightSignalId) }
                composable("strategies") { com.btcsignal.app.ui.screens.KronosScreen() }
                composable("backtest") { BacktestScreen() }
                composable("history") { HistoryScreen(highlightSignalId) }
                composable("performance") { PerformanceScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

/** Premium 3D gothic "control dock" bottom navigation (brief item 7): a dark
 * metal/glass base with individual icon compartments, and the active tab
 * raised with a neon-glowing container in its own tab color. The scale/color
 * transition is a single one-shot [animateFloatAsState] per tab triggered
 * only on selection change — not a continuous animation — so it stays cheap. */
@Composable
private fun GothicBottomNav(currentRoute: String?, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .shadow(18.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black.copy(alpha = 0.65f), spotColor = Color.Black.copy(alpha = 0.7f))
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF2151519), Color(0xFA09090C))))
            .border(1.2.dp, Brush.verticalGradient(listOf(MetalHighlight.copy(alpha = 0.85f), MetalShadow)), RoundedCornerShape(26.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TABS.forEach { tab ->
            GothicNavItem(
                tab = tab,
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab.route) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RowScope.GothicNavItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (selected) 1f else 0.9f, label = "navItemScale")
    val compartmentShape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .padding(3.dp)
            .scale(scale)
            .then(
                if (selected) {
                    Modifier
                        .shadow(10.dp, compartmentShape, ambientColor = tab.color.copy(alpha = 0.65f), spotColor = tab.color.copy(alpha = 0.75f))
                        .clip(compartmentShape)
                        .background(Brush.verticalGradient(listOf(tab.color.copy(alpha = 0.32f), Color(0xE60A0A0D))))
                        .border(1.dp, tab.color.copy(alpha = 0.85f), compartmentShape)
                } else {
                    Modifier.clip(compartmentShape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = if (selected) tab.color else TextSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}
