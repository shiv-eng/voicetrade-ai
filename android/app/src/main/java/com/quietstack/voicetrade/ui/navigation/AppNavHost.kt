package com.quietstack.voicetrade.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.quietstack.voicetrade.ui.ipo.IpoDetailScreen
import com.quietstack.voicetrade.ui.ipo.IpoScreen
import com.quietstack.voicetrade.ui.stock.StockDetailScreen
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.ui.history.HistoryDetailScreen
import com.quietstack.voicetrade.ui.history.HistoryScreen
import com.quietstack.voicetrade.ui.home.HomeScreen
import com.quietstack.voicetrade.ui.onboarding.OnboardingScreen
import com.quietstack.voicetrade.ui.orders.OrdersScreen
import com.quietstack.voicetrade.ui.portfolio.PortfolioScreen
import com.quietstack.voicetrade.ui.session.VoiceSessionScreen
import com.quietstack.voicetrade.ui.settings.SettingsScreen
import com.quietstack.voicetrade.ui.splash.SplashScreen
import com.quietstack.voicetrade.ui.watchlist.WatchlistScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object Splash
@Serializable data object Onboarding
@Serializable data object Home
@Serializable data class Session(val micGranted: Boolean, val prompt: String? = null, val resumeSessionId: Long? = null)
@Serializable data object Portfolio
@Serializable data object Orders
@Serializable data object Watchlist
@Serializable data object History
@Serializable data class HistoryDetail(val sessionId: Long)
@Serializable data object Settings
@Serializable data class StockDetail(val conid: Long)
@Serializable data object Ipos
@Serializable data object Alerts
@Serializable data class IpoDetailRoute(val symbol: String, val series: String, val name: String? = null)

private class Tab(val route: Any, val type: KClass<*>, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Home, Home::class, R.string.tab_home, Icons.Filled.Home),
    Tab(Portfolio, Portfolio::class, R.string.portfolio, Icons.Filled.AccountBalanceWallet),
    Tab(Orders, Orders::class, R.string.orders, Icons.AutoMirrored.Filled.ListAlt),
    Tab(Watchlist, Watchlist::class, R.string.watchlist, Icons.Filled.Visibility),
)

@Composable
fun AppNavHost(
    signedIn: Boolean,
    launchPrompt: String? = null,
    launchOpen: String? = null,
    onLaunchConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBar = tabs.any { destination?.hasRoute(it.type) == true }

    // Signed out (or the server rejected our token) anywhere in the app: go back to sign-in.
    LaunchedEffect(signedIn) {
        val d = navController.currentDestination
        val onAuthScreen = d == null || d.hasRoute(Splash::class) || d.hasRoute(Onboarding::class)
        if (!signedIn && !onAuthScreen) navController.navigate(Onboarding) { popUpTo(0) { inclusive = true } }
    }

    // A notification was tapped: once we are signed in and on Home, do what it asked.
    LaunchedEffect(launchPrompt, launchOpen, signedIn, destination) {
        if (!signedIn || (launchPrompt == null && launchOpen == null)) return@LaunchedEffect
        if (destination?.hasRoute(Home::class) != true) return@LaunchedEffect
        if (launchOpen == "alerts") navController.navigate(Alerts)
        else if (launchPrompt != null) {
            val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            navController.navigate(Session(mic, launchPrompt)) { launchSingleTop = true }
        }
        onLaunchConsumed()
    }

    fun toHomeClearing() = navController.navigate(Home) { popUpTo(0) { inclusive = true } }
    fun goTab(route: Any) = navController.navigate(route) {
        popUpTo(Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    var pendingMicPrompt by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingMicPrompt) { navController.navigate(Session(granted, null)) { launchSingleTop = true } }
        pendingMicPrompt = false
    }
    fun startVoiceSession() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            navController.navigate(Session(true, null)) { launchSingleTop = true }
        } else {
            pendingMicPrompt = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        bottomBar = { if (showBar) FloatingBottomNav(destination, ::goTab, ::startVoiceSession) },
    ) { inner ->
        NavHost(navController, startDestination = Splash, modifier = Modifier.padding(inner).consumeWindowInsets(inner)) {
            composable<Splash> {
                SplashScreen(
                    onLinked = { toHomeClearing() },
                    onNeedsOnboarding = { navController.navigate(Onboarding) { popUpTo(Splash) { inclusive = true } } },
                )
            }
            composable<Onboarding> { OnboardingScreen(onDone = { toHomeClearing() }) }
            composable<Home> {
                HomeScreen(
                    onOpenStock = { conid -> navController.navigate(StockDetail(conid)) },
                    onOpenIpos = { navController.navigate(Ipos) },
                    onStartSession = { mic, prompt -> navController.navigate(Session(mic, prompt)) { launchSingleTop = true } },
                    onOpenPortfolio = { goTab(Portfolio) },
                    onOpenWatchlist = { goTab(Watchlist) },
                    onOpenHistory = { navController.navigate(History) },
                    onOpenSettings = { navController.navigate(Settings) },
                    onOpenAlerts = { navController.navigate(Alerts) },
                )
            }
            composable<Session> { backStackEntry ->
                val route = backStackEntry.toRoute<Session>()
                VoiceSessionScreen(
                    micGranted = route.micGranted,
                    prompt = route.prompt,
                    resumeSessionId = route.resumeSessionId,
                    onBack = { navController.popBackStack(Home, inclusive = false) },
                    onOpenPortfolio = { goTab(Portfolio) },
                    onOpenStock = { conid -> navController.navigate(StockDetail(conid)) },
                    onOpenIpos = { navController.navigate(Ipos) },
                    onOpenIpo = { symbol, series, name -> navController.navigate(IpoDetailRoute(symbol, series, name)) },
                )
            }
            composable<Portfolio> { PortfolioScreen(onBack = null, onOpenStock = { navController.navigate(StockDetail(it)) }) }
            composable<Orders> { OrdersScreen(onBack = null) }
            composable<Watchlist> { WatchlistScreen(onBack = null, onOpenStock = { navController.navigate(StockDetail(it)) }) }
            composable<StockDetail> {
                StockDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAsk = { prompt ->
                        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        navController.navigate(Session(mic, prompt)) { launchSingleTop = true }
                    },
                )
            }
            composable<Alerts> { com.quietstack.voicetrade.ui.alerts.AlertsScreen(onBack = { navController.popBackStack() }) }
            composable<Ipos> {
                IpoScreen(
                    onBack = { navController.popBackStack() },
                    onOpenIpo = { symbol, series, name -> navController.navigate(IpoDetailRoute(symbol, series, name)) },
                )
            }
            composable<IpoDetailRoute> { IpoDetailScreen(onBack = { navController.popBackStack() }) }
            composable<History> {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> navController.navigate(HistoryDetail(id)) },
                )
            }
            composable<HistoryDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<HistoryDetail>()
                HistoryDetailScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = { mic -> navController.navigate(Session(mic, null, route.sessionId)) { launchSingleTop = true } },
                )
            }
            composable<Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onUnlinked = { navController.navigate(Onboarding) { popUpTo(0) { inclusive = true } } },
                )
            }
        }
    }
}

/** A floating pill, not an edge-to-edge bar, with a raised mic button astride its top edge that always
 * opens a fresh voice session — the one control that is the same from anywhere in the app. */
@Composable
private fun FloatingBottomNav(destination: NavDestination?, onTab: (Any) -> Unit, onMic: () -> Unit) {
    val barHeight = 68.dp
    val fabSize = 56.dp
    val poke = 26.dp // how far the mic button rises above the pill
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(barHeight + poke)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.BottomCenter),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
            shadowElevation = 12.dp,
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                tabs.take(2).forEach { NavItem(it, destination, onTab) }
                Spacer(Modifier.width(fabSize))
                tabs.drop(2).forEach { NavItem(it, destination, onTab) }
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).size(fabSize).clip(CircleShape)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)))
                .clickable(onClick = onMic),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.talk_to_mira), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun NavItem(tab: Tab, destination: NavDestination?, onClick: (Any) -> Unit) {
    val selected = destination?.hierarchy?.any { it.hasRoute(tab.type) } == true
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.Column(
        Modifier.width(62.dp).clickable(onClick = { onClick(tab.route) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(
            stringResource(tab.label), style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = color, maxLines = 1,
        )
    }
}
