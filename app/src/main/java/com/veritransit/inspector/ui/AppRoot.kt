package com.veritransit.inspector.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.ui.components.ToastBar
import com.veritransit.inspector.ui.screens.AppSettings
import com.veritransit.inspector.ui.warehouse.LoadReconciliationScreen
import com.veritransit.inspector.ui.warehouse.PackageScanScreen
import com.veritransit.inspector.ui.warehouse.ShipmentListScreen
import com.veritransit.inspector.ui.warehouse.WarehouseViewModel
import com.veritransit.inspector.ui.theme.Hanken
import com.veritransit.inspector.ui.theme.VT

private enum class Tab(val label: String, val icon: ImageVector) {
    // Demo build: exactly two modes, no login, no setup.
    // Send creates signed QRs locally; Receive scans them. Same phone works.
    SEND("Send", Icons.Rounded.Send),
    RECEIVE("Receive", Icons.Rounded.CallReceived),
}

private sealed interface Page {
    /** Receive mode: pick a shipment, scan it, see what's missing. */
    data class WarehouseScan(val ref: String, val receiving: Boolean) : Page
    data class WarehouseRecon(val ref: String) : Page
}

private sealed interface NavTarget {
    val depth: Int
    data class TabT(val tab: Tab) : NavTarget { override val depth = 0 }
    data class PageT(val page: Page) : NavTarget { override val depth = 1 }
}

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.SEND) }
    val stack = remember { mutableStateListOf<Page>() }
    val settings = remember { AppSettings() }
    var toast by remember { mutableStateOf<String?>(null) }

    val warehouse: WarehouseViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    val tone = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 72) }
    DisposableEffect(Unit) { onDispose { tone.release() } }

    fun feedback(msg: String, beep: Boolean = false) {
        toast = msg
        if (beep && settings.sound) tone.startTone(ToneGenerator.TONE_PROP_ACK, 130)
    }

    fun gotoTab(t: Tab) {
        tab = t
        stack.clear()
    }

    val navTarget: NavTarget = if (stack.isEmpty()) NavTarget.TabT(tab) else NavTarget.PageT(stack.last())
    val showBar = stack.isEmpty()

    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.SEND) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.SEND
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.veritransit.inspector.ui.theme.LocalHapticsEnabled provides settings.haptics,
    ) {
        Box(Modifier.fillMaxSize().background(VT.Alabaster)) {
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val barHeight = 64.dp + navInset
        Box(Modifier.fillMaxSize().padding(bottom = if (showBar) barHeight else 0.dp)) {
            AnimatedContent(
                targetState = navTarget,
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    (
                        if (forward) slideInHorizontally(tween(330)) { it / 4 } + fadeIn(tween(260))
                        else slideInHorizontally(tween(330)) { -it / 7 } + fadeIn(tween(260))
                        ).togetherWith(
                        if (forward) slideOutHorizontally(tween(330)) { -it / 7 } + fadeOut(tween(190))
                        else slideOutHorizontally(tween(330)) { it / 4 } + fadeOut(tween(190))
                    )
                },
                label = "nav",
            ) { target ->
                when (target) {
                    is NavTarget.TabT -> when (target.tab) {
                        Tab.SEND -> com.veritransit.inspector.ui.screens.SenderScreen(
                            onToast = { feedback(it) },
                        )
                        Tab.RECEIVE -> ShipmentListScreen(
                            vm = warehouse,
                            onOpen = { ref -> stack.add(Page.WarehouseScan(ref, receiving = true)) },
                        )
                    }
                    is NavTarget.PageT -> when (val page = target.page) {
                        is Page.WarehouseScan -> PackageScanScreen(
                            vm = warehouse,
                            kind = if (page.receiving) com.veritransit.core.ScanKind.RECEIVE
                                   else com.veritransit.core.ScanKind.LOAD,
                            onDone = {
                                stack.removeAt(stack.lastIndex)
                                stack.add(Page.WarehouseRecon(page.ref))
                            },
                        )
                        is Page.WarehouseRecon -> LoadReconciliationScreen(
                            vm = warehouse,
                            onBack = { stack.removeAt(stack.lastIndex) },
                        )
                    }
                }
            }
        }

        if (showBar) {
            BottomBar(
                current = tab,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSelect = { t ->
                    if (t != tab) {
                        gotoTab(t)
                    }
                },
            )
        }

        ToastBar(
            message = toast,
            visible = toast != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showBar) barHeight + 12.dp else 28.dp),
        )
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2600)
            toast = null
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = VT.Surface,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.background(VT.Surface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { t ->
                    val active = t == current
                    val color by androidx.compose.animation.animateColorAsState(
                        if (active) VT.Primary else VT.Muted,
                        tween(220),
                        label = "tab",
                    )
                    val scale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (active) 1f else 0.94f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 480f),
                        label = "tabScale",
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(
                            t.icon, t.label,
                            tint = color,
                            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                        )
                        Text(
                            t.label,
                            style = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                            color = color,
                            modifier = Modifier.alpha(if (active) 1f else 0.85f),
                        )
                    }
                }
            }
        }
    }
}
