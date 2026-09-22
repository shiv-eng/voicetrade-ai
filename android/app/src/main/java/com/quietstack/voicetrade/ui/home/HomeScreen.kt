package com.quietstack.voicetrade.ui.home

import android.Manifest
import com.quietstack.voicetrade.core.i18n.tr
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quietstack.voicetrade.R
import com.quietstack.voicetrade.core.designsystem.Hairline
import com.quietstack.voicetrade.core.designsystem.MicOrb
import com.quietstack.voicetrade.core.designsystem.OrbState
import com.quietstack.voicetrade.core.designsystem.PaperBadge
import com.quietstack.voicetrade.core.designsystem.Panel
import com.quietstack.voicetrade.core.designsystem.PersonAvatar
import com.quietstack.voicetrade.core.designsystem.SectionHeader
import com.quietstack.voicetrade.core.designsystem.Stat
import com.quietstack.voicetrade.core.designsystem.StatusChip
import com.quietstack.voicetrade.core.designsystem.StockRow
import com.quietstack.voicetrade.core.designsystem.TabularNumbers
import com.quietstack.voicetrade.core.designsystem.pnlColor
import com.quietstack.voicetrade.core.util.MoneyFormatter
import com.quietstack.voicetrade.domain.model.Wallet
import com.quietstack.voicetrade.ui.common.InlineError
import com.quietstack.voicetrade.ui.common.messageText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime

@Composable
fun HomeScreen(
    onStartSession: (micGranted: Boolean, prompt: String?) -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenWatchlist: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStock: (Long) -> Unit = {},
    onOpenIpos: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingPrompt by remember { mutableStateOf<String?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onEvent(HomeEvent.StartSession(granted, pendingPrompt))
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.NavigateSession -> onStartSession(effect.micGranted, effect.prompt)
                is HomeEffect.ShowError -> snackbar.showSnackbar(messageText(context, effect.error))
            }
        }
    }

    // Coming back from a session, Orders or Settings: what you hold and can spend may have changed.
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(HomeEvent.Refresh)
        onPauseOrDispose { }
    }

    fun startTapped(prompt: String?) {
        pendingPrompt = prompt
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onEvent(HomeEvent.StartSession(true, prompt))
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            HomeTopBar(name = state.name, onHistory = onOpenHistory, onSettings = onOpenSettings, onAlerts = onOpenAlerts)
            if (state.isRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            } else {
                Spacer(Modifier.height(4.dp))
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (state.isOffline) item { InlineError(stringResource(R.string.banner_offline), null) }
                state.error?.let { err -> item { InlineError(messageText(context, err), { viewModel.onEvent(HomeEvent.Refresh) }) } }

                item { VoiceCard(onTap = { startTapped(null) }, onPick = { startTapped(it) }) }

                if (state.market?.indices?.isNotEmpty() == true) item { IndexTiles(state.market) }

                state.account?.let { acc ->
                    item { BalanceCard(acc.wallets, state.pnl?.items?.associate { it.currency to it.daily }.orEmpty(), onOpenPortfolio) }
                }

                item { IpoEntry(onOpenIpos) }

                if (state.market != null) item { TopMovers(state.market, onOpenStock) }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(
                            stringResource(R.string.holdings),
                            action = if (state.positions.isNotEmpty()) stringResource(R.string.see_all) else null,
                            onAction = onOpenPortfolio,
                        )
                        if (state.positions.isEmpty() && state.loaded) {
                            EmptyHint(stringResource(R.string.holdings_empty))
                        } else if (state.positions.isNotEmpty()) {
                            Panel {
                                state.positions.take(3).forEachIndexed { i, p ->
                                    if (i > 0) Hairline()
                                    val cost = p.avgCost.multiply(p.quantity)
                                    val ret = if (cost.signum() > 0) {
                                        p.unrealizedPnl.multiply(BigDecimal(100)).divide(cost, 2, RoundingMode.HALF_UP)
                                    } else {
                                        BigDecimal.ZERO
                                    }
                                    StockRow(
                                        p.instrument,
                                        subtitle = stringResource(
                                            R.string.qty_at, p.quantity.stripTrailingZeros().toPlainString(),
                                            MoneyFormatter.format(p.marketPrice, p.instrument.currency),
                                        ),
                                        price = MoneyFormatter.format(p.marketValue, p.instrument.currency, 0),
                                        secondary = MoneyFormatter.formatSigned(p.unrealizedPnl, p.instrument.currency, 0) +
                                            "  (" + MoneyFormatter.formatPercent(ret) + ")",
                                        secondaryColor = pnlColor(p.unrealizedPnl),
                                        onClick = { onOpenStock(p.instrument.conid) },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(
                            stringResource(R.string.watchlist),
                            action = if (state.watchlist.isNotEmpty()) stringResource(R.string.see_all) else null,
                            onAction = onOpenWatchlist,
                        )
                        if (state.watchlist.isEmpty() && state.loaded) {
                            EmptyHint(stringResource(R.string.watchlist_empty_home))
                        } else if (state.watchlist.isNotEmpty()) {
                            Panel {
                                state.watchlist.take(4).forEachIndexed { i, row ->
                                    if (i > 0) Hairline()
                                    StockRow(
                                        row.instrument,
                                        price = row.quote?.let { MoneyFormatter.format(it.last, row.instrument.currency) },
                                        changePct = row.quote?.changePct,
                                        onClick = { onOpenStock(row.instrument.conid) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar)
        }
    }
}

@Composable
private fun HomeTopBar(name: String?, onHistory: () -> Unit, onSettings: () -> Unit, onAlerts: () -> Unit) {
    val hour = LocalTime.now().hour
    val greeting = stringResource(
        when {
            hour < 5 -> R.string.greeting_night
            hour < 12 -> R.string.greeting_morning
            hour < 17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        },
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonAvatar(name)
        Column(Modifier.weight(1f)) {
            Text(greeting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (name.isNullOrBlank()) stringResource(R.string.app_name) else name,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onAlerts) { Icon(Icons.Filled.Notifications, contentDescription = tr("Price alerts")) }
        IconButton(onClick = onHistory) { Icon(Icons.Filled.History, contentDescription = stringResource(R.string.history)) }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings)) }
    }
}

/** The one thing this app is for, front and centre: talk. Ideas to say sit right under the button. */
@Composable
private fun VoiceCard(onTap: () -> Unit, onPick: (String) -> Unit) {
    val prompts = listOf(
        stringResource(R.string.sugg_quote), stringResource(R.string.sugg_own), stringResource(R.string.sugg_cash),
        stringResource(R.string.sugg_watch), stringResource(R.string.sugg_buy),
    )
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(primary.copy(alpha = 0.22f), MaterialTheme.colorScheme.surfaceContainer)),
            RoundedCornerShape(32.dp),
        ).padding(top = 20.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MicOrb(
            state = OrbState.IDLE, level = 0f, contentDescription = stringResource(R.string.tap_to_talk),
            size = 132.dp, onClick = onTap, tint = primary,
        )
        Text(
            stringResource(R.string.talk_to_mira), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            stringResource(R.string.hero_subtitle), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
        )
        LazyRow(
            Modifier.padding(top = 14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(prompts) { p ->
                Text(
                    p, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                        .clickable { onPick(p) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Cash you can spend, the total wallet is worth, and today's move as a percentage — one wallet at a time,
 * stacked, so nothing is cramped. Tap for the full portfolio (holdings, breakdown). */
@Composable
private fun BalanceCard(wallets: List<Wallet>, dayByCurrency: Map<String, BigDecimal>, onOpenPortfolio: () -> Unit) {
    Panel(onClick = onOpenPortfolio) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.balance_title), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                PaperBadge()
            }
            wallets.forEachIndexed { i, w ->
                if (i > 0) Hairline()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(if (w.currency == "INR") R.string.wallet_india else R.string.wallet_us),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Stat(stringResource(R.string.cash), MoneyFormatter.format(w.cash, w.currency, 0), Modifier.weight(1f))
                        Stat(
                            stringResource(R.string.total_value), MoneyFormatter.format(w.netLiquidation, w.currency, 0),
                            Modifier.weight(1f), align = TextAlign.End,
                        )
                    }
                    val day = dayByCurrency[w.currency]
                    if (day != null) {
                        val base = w.netLiquidation.subtract(day)
                        val pct = if (base.signum() > 0) day.multiply(BigDecimal(100)).divide(base, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.today), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                            )
                            Text(
                                MoneyFormatter.formatSigned(day, w.currency, 0), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                fontWeight = FontWeight.SemiBold, color = pnlColor(day),
                            )
                            Box(Modifier.padding(start = 8.dp)) { StatusChip(MoneyFormatter.formatPercent(pct), pnlColor(day)) }
                        }
                    }
                }
            }
        }
    }
}

/** A doorway to what is new on the market: IPOs open now, coming soon and just listed. */
@Composable
private fun IpoEntry(onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("IPOs"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    tr("See what is open now, coming soon and just listed"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(18.dp),
    )
}
