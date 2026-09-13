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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.DashboardSync
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.MasterBox
import com.veritransit.inspector.data.ReceiptOutcome
import com.veritransit.inspector.data.ReceivingAction
import com.veritransit.inspector.data.Repo
import com.veritransit.inspector.ui.components.ToastBar
import com.veritransit.inspector.ui.screens.DockCountScreen
import com.veritransit.inspector.ui.screens.AppSettings
import com.veritransit.inspector.ui.screens.HomeScreen
import com.veritransit.inspector.ui.screens.ChatScreen
import com.veritransit.inspector.ui.screens.PackingListScreen
import com.veritransit.inspector.ui.screens.NpuScreen
import com.veritransit.inspector.ui.screens.ReceiptsScreen
import com.veritransit.inspector.ui.screens.ReceiptResultScreen
import com.veritransit.inspector.ui.screens.ScanScreen
import com.veritransit.inspector.ui.screens.SettingsScreen
import com.veritransit.inspector.ui.screens.BoxContentsScreen
import com.veritransit.inspector.ui.screens.BoxLabelsScreen
import com.veritransit.inspector.ui.screens.PackMasterBoxScreen
import com.veritransit.inspector.ui.theme.Hanken
import com.veritransit.inspector.ui.theme.VT
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SCAN("Receive", Icons.Rounded.QrCodeScanner),
    RECORDS("Receipts", Icons.Rounded.History),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

private sealed interface Page {
    data object PackingListStep : Page
    data object DockCount : Page
    data object ResultActive : Page
    data class ResultView(val recordId: String) : Page
    data object NpuModel : Page
    data object Chat : Page
    data object BoxContents : Page
    /** A master box's labels: [packing] while it is being packed, false when reopened from Home. */
    data class BoxLabels(val box: MasterBox, val packing: Boolean) : Page
}

private sealed interface NavTarget {
    val depth: Int
    data class TabT(val tab: Tab) : NavTarget { override val depth = 0 }
    data class PageT(val page: Page) : NavTarget { override val depth = 1 }
}

@Composable
fun AppRoot() {
    val now = remember { System.currentTimeMillis() }
    var tab by remember { mutableStateOf(Tab.HOME) }
    val stack = remember { mutableStateListOf<Page>() }
    val flow = remember { ReceivingFlowState() }
    val settings = remember { AppSettings() }
    // Receiving or sending: decides what Home offers and what the second tab opens.
    var mode by remember { mutableStateOf(AppMode.RECEIVING) }
    val sender = remember { SenderFlowState().apply { startNew() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Survives navigation so the transcript is still there on the way back.
    val chat = remember { com.veritransit.inspector.ai.ChatSession() }
    var toast by remember { mutableStateOf<String?>(null) }
    var startInManualFlag by remember { mutableStateOf(false) }

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

    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.HOME) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.HOME
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
                        Tab.HOME -> HomeScreen(
                            now = now,
                            mode = mode,
                            onModeChange = { mode = it },
                            onStartReceiving = {
                                flow.startNew(false)
                                startInManualFlag = false
                                gotoTab(Tab.SCAN)
                            },
                            onLookup = {
                                flow.startNew(true)
                                startInManualFlag = true
                                gotoTab(Tab.SCAN)
                            },
                            onPackMasterBox = {
                                sender.startNew()
                                gotoTab(Tab.SCAN)
                            },
                            onOpenRecord = { id -> stack.add(Page.ResultView(id)) },
                            onOpenMasterBox = { box -> stack.add(Page.BoxLabels(box, packing = false)) },
                            onOpenReceipts = { gotoTab(Tab.RECORDS) },
                        )
                        Tab.SCAN -> if (mode == AppMode.SENDING) {
                            PackMasterBoxScreen(
                                sender = sender,
                                onContinue = {
                                    sender.planIfNeeded()
                                    stack.add(Page.BoxContents)
                                },
                                onBack = { gotoTab(Tab.HOME) },
                            )
                        } else {
                            ScanScreen(
                                flow = flow,
                                startInManual = startInManualFlag,
                                onDetected = {
                                    if (flow.packingList == null) flow.packingList = ReceivingFlowState.defaultPackingList()
                                },
                                onContinue = { stack.add(Page.PackingListStep) },
                                onBack = { gotoTab(Tab.HOME) },
                                feedback = { msg -> feedback(msg, beep = true) },
                            )
                        }
                        Tab.RECORDS -> ReceiptsScreen(
                            now = now,
                            onOpenRecord = { id -> stack.add(Page.ResultView(id)) },
                            onToast = { feedback(it) },
                        )
                        Tab.SETTINGS -> SettingsScreen(
                            settings = settings,
                            onToast = { feedback(it) },
                            onOpenNpu = { stack.add(Page.NpuModel) },
                        )
                    }
                    is NavTarget.PageT -> when (val page = target.page) {
                        Page.BoxContents -> BoxContentsScreen(
                            sender = sender,
                            onGenerate = {
                                runCatching { sender.build(System.currentTimeMillis()) }
                                    .onSuccess { stack.add(Page.BoxLabels(it, packing = true)) }
                                    .onFailure { feedback(it.message ?: "Labels could not be made") }
                            },
                            onBack = { stack.removeAt(stack.lastIndex) },
                        )
                        is Page.BoxLabels -> BoxLabelsScreen(
                            box = page.box,
                            onPrint = {
                                // The ID goes on paper, so the box is saved first:
                                // no later master box is offered the same ID.
                                if (page.packing) sender.save(page.box)
                                LabelPrinter.print(context, page.box)
                            },
                            onFinish = if (page.packing) {
                                {
                                    sender.save(page.box)
                                    feedback("${page.box.id} packed · ${page.box.boxes.size + 1} QR labels", beep = true)
                                    sender.startNew()
                                    gotoTab(Tab.HOME)
                                }
                            } else {
                                null
                            },
                            onBack = { stack.removeAt(stack.lastIndex) },
                        )
                        Page.NpuModel -> NpuScreen(
                            onBack = { stack.removeAt(stack.lastIndex) },
                            onToast = { feedback(it) },
                            onOpenChat = { stack.add(Page.Chat) },
                        )
                        Page.Chat -> ChatScreen(
                            session = chat,
                            onBack = { stack.removeAt(stack.lastIndex) },
                        )
                        Page.PackingListStep -> PackingListScreen(
                            flow = flow,
                            onToggleCartonCount = { flow.cartonCountOk = !flow.cartonCountOk },
                            onToggleLabel = { flow.labelOk = !flow.labelOk },
                            onStartCount = {
                                flow.resetForCount()
                                stack.add(Page.DockCount)
                            },
                            onBack = { stack.removeAt(stack.lastIndex) },
                        )
                        Page.DockCount -> DockCountScreen(
                            flow = flow,
                            onCountComplete = {
                                if (flow.confidence() == 0f) flow.setConfidence(flow.aiConfidence)
                                stack.add(Page.ResultActive)
                            },
                            onBack = { stack.removeAt(stack.lastIndex) },
                            feedback = { msg -> feedback(msg, beep = true) },
                        )
                        Page.ResultActive -> {
                            val draft = flow.draftRecord(now)
                            ReceiptResultScreen(
                                record = draft,
                                flow = flow,
                                onConfirm = { action, note ->
                                    val final = draft.copy(
                                        outcome = if (action == ReceivingAction.ACCEPT) ReceiptOutcome.OK else ReceiptOutcome.MISMATCH,
                                        note = note,
                                    )
                                    Repo.commit(final)
                                    feedback("Receipt ${final.id} filed in the receipt log", beep = true)
                                    gotoTab(Tab.HOME)
                                    // Back-office handoff: flips the delivery to
                                    // accepted/held on the web dashboard and publishes
                                    // its PDF report. The log copy above is the one
                                    // of record — a failed push changes nothing.
                                    scope.launch {
                                        DashboardSync.push(final, flow.listEvidence, flow.dockEvidence)
                                            .onSuccess {
                                                feedback(
                                                    if (final.flagged) "Dashboard updated · delivery held"
                                                    else "Dashboard updated · accepted, OK to pay",
                                                )
                                            }
                                            .onFailure { feedback("Dashboard offline — receipt stays in the log") }
                                    }
                                },
                                onRecount = {
                                    flow.resetForCount()
                                    stack.removeAt(stack.lastIndex)
                                },
                                onPrint = { feedback("Summary queued for the dock printer") },
                                onBack = { stack.removeAt(stack.lastIndex) },
                            )
                        }
                        is Page.ResultView -> {
                            val record = Repo.records.firstOrNull { it.id == page.recordId }
                            if (record == null) {
                                Box(Modifier.fillMaxSize())
                            } else {
                                ReceiptResultScreen(
                                    record = record,
                                    flow = null,
                                    onConfirm = { _, _ -> },
                                    onRecount = null,
                                    onPrint = { feedback("Summary queued for the dock printer") },
                                    onBack = { stack.removeAt(stack.lastIndex) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showBar) {
            BottomBar(
                current = tab,
                mode = mode,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSelect = { t ->
                    if (t != tab) {
                        startInManualFlag = false
                        // Receive always opens a fresh walkthrough, exactly like
                        // Home's Start Receiving: a filed receipt's flow would
                        // otherwise reopen already "LABEL LOCKED", with the
                        // scanner ignoring every frame.
                        if (t == Tab.SCAN) {
                            if (mode == AppMode.SENDING) sender.startNew() else flow.startNew(false)
                        }
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
private fun BottomBar(current: Tab, mode: AppMode, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
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
                    // Sending turns Receive into Pack; the other tabs serve both modes.
                    val (label, icon) = if (t == Tab.SCAN && mode == AppMode.SENDING) {
                        "Pack" to Icons.Rounded.Inventory2
                    } else {
                        t.label to t.icon
                    }
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
                            icon, label,
                            tint = color,
                            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                        )
                        Text(
                            label,
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
