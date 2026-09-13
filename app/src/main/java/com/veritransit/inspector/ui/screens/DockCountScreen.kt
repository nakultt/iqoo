package com.veritransit.inspector.ui.screens

import android.Manifest as AndroidPermission
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.veritransit.inspector.ai.EvidenceCamera
import com.veritransit.inspector.ai.EvidenceViewfinder
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.ai.OpenRouterClient
import com.veritransit.inspector.ai.ReceivingAi
import com.veritransit.inspector.data.ItemScanTally
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.PackingItem
import com.veritransit.inspector.ui.ReceivingFlowState
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.PulseDot
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.StepBadge
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DockCountScreen(
    flow: ReceivingFlowState,
    onCountComplete: () -> Unit,
    onBack: () -> Unit,
    feedback: (String) -> Unit = {},
) {
    val packingList = flow.packingList
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = com.veritransit.inspector.ui.theme.LocalHapticsEnabled.current

    val camera = remember { EvidenceCamera() }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, AndroidPermission.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) askCamera.launch(AndroidPermission.permission.CAMERA)
    }

    // The receiver's own count: item codes the camera decodes, corrected by
    // hand. It starts at nothing received — never pre-filled with the packed
    // quantities, never a scripted scenario — and needs no model, so counting
    // works with every AI backend off.
    val tally = remember(packingList) {
        ItemScanTally(
            packingList?.items ?: emptyList(),
            purchaseOrderId = packingList?.purchaseOrderId,
            countedBoxes = if (flow.countedByAi) emptySet() else flow.countedBoxIds,
        ).apply {
            // Back from the result screen: pick the hand count up where it was
            // left, with the boxes already booked into it.
            if (!flow.countedByAi) flow.countedItems.forEach { adjust(it.sku, it.received) }
        }
    }
    var lines by remember(tally) { mutableStateOf(tally.lines) }
    var lastScan by remember { mutableStateOf<String?>(null) }

    var counting by remember { mutableStateOf(false) }
    var countError by remember { mutableStateOf<String?>(null) }

    /** A model's photo count is on screen; the scan tally steps aside. */
    val aiReveal = flow.countedByAi && flow.countedItems.isNotEmpty()

    val scanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    fun onItemCodes(payloads: List<String>) {
        if (counting || flow.countedByAi) return
        val now = SystemClock.elapsedRealtime()
        for (payload in payloads) {
            when (val outcome = tally.onCode(payload, now)) {
                is ItemScanTally.Outcome.Counted -> {
                    lines = tally.lines
                    val line = outcome.line
                    lastScan = "Counted ${line.sku} · ${line.name} — ${line.received} of ${line.expected}"
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    feedback("${line.sku} · ${line.received}/${line.expected}")
                }
                is ItemScanTally.Outcome.NotOnList ->
                    lastScan = "Not on this packing list: ${outcome.code.take(40)}"
                ItemScanTally.Outcome.StillInView -> Unit
                is ItemScanTally.Outcome.BoxCounted -> {
                    lines = tally.lines
                    val box = outcome.box
                    val booked = box.lines - outcome.notOnList.toSet()
                    // "As labelled": a box label books what the sender declared
                    // is inside, not a unit-by-unit count — the line says so.
                    lastScan = buildString {
                        append("Box ${box.seq} of ${box.of} counted as labelled — ")
                        append(
                            if (booked.isEmpty()) "nothing on this packing list"
                            else booked.joinToString(" · ") { "+${it.qty} ${it.sku}" },
                        )
                        if (outcome.notOnList.isNotEmpty()) {
                            append(" · not on this packing list: ")
                            append(outcome.notOnList.joinToString { "${it.sku} ×${it.qty}" })
                        }
                    }
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    feedback("Box ${box.seq}/${box.of} · ${booked.sumOf { it.qty }} units as labelled")
                }
                is ItemScanTally.Outcome.BoxAlreadyCounted ->
                    lastScan = "Box ${outcome.box.seq} of ${outcome.box.of} (${outcome.box.id}) is already counted"
                is ItemScanTally.Outcome.MasterLabel ->
                    lastScan = "Master box ${outcome.master.id} — ${outcome.master.boxCount} boxes, " +
                        "${outcome.master.units} units inside. Scan each box."
                is ItemScanTally.Outcome.OtherDelivery ->
                    lastScan = "That box is labelled for ${outcome.label.purchaseOrderId}, not this delivery"
            }
        }
    }

    val itemAnalyzer = remember(cameraGranted, tally) {
        if (!cameraGranted) {
            null
        } else {
            ImageAnalysis.Analyzer { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                    return@Analyzer
                }
                val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener { codes ->
                        val payloads = codes.mapNotNull { code -> code.rawValue?.takeIf { it.isNotBlank() } }
                        if (payloads.isNotEmpty()) onItemCodes(payloads)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
        }
    }

    /** Walks a model count in, one row at a time, then hands over. */
    suspend fun revealAndFinish() {
        val target = flow.countedItems.size
        while (flow.scanProgress < target) {
            delay(320)
            flow.scanProgress++
        }
        delay(820)
        onCountComplete()
    }

    fun captureAndCount() {
        val list = packingList ?: return
        if (counting || !camera.ready) return
        counting = true
        countError = null
        scope.launch {
            try {
                val frame = camera.capture(context)
                if (frame == null) {
                    countError = camera.error ?: "Camera could not take the shot."
                    return@launch
                }
                flow.dockEvidence = frame.absolutePath
                ReceivingAi.countDelivery(frame.absolutePath, list)
                    .onSuccess { count ->
                        flow.countedItems = count.items
                        flow.aiObservation = count.observation
                        flow.aiConfidence = count.confidence
                        flow.countedByAi = true
                        flow.countedBy = LlmGateway.lastBackend
                        flow.scanProgress = 0
                        revealAndFinish()
                    }
                    .onFailure { countError = it.message ?: "The model could not read that delivery." }
            } finally {
                counting = false
            }
        }
    }

    fun finishCount() {
        if (packingList == null || counting) return
        flow.countedItems = tally.lines
        flow.countedBoxIds = tally.countedBoxIds
        flow.scanProgress = flow.countedItems.size
        flow.countedByAi = false
        flow.countedBy = null
        onCountComplete()
    }

    val received = lines.sumOf { it.received }
    val expected = lines.filter { it.expected > 0 }.sumOf { it.expected }

    val bottomBar: (@Composable () -> Unit)? = when {
        packingList == null -> null
        aiReveal && flow.scanProgress < flow.countedItems.size -> null
        aiReveal -> {
            { PrimaryButton(text = "Continue to Receipt Result", onClick = onCountComplete) }
        }
        else -> {
            {
                PrimaryButton(
                    text = "Finish Count ($received of $expected units)",
                    onClick = ::finishCount,
                    enabled = !counting,
                )
            }
        }
    }

    FlowScaffold(bottomBar = bottomBar) {
        FlowHeader(
            title = "Receive / Count",
            subtitle = packingList?.let { "PO ${it.purchaseOrderId}" } ?: "Dock count",
            onBack = onBack,
            trailing = { StepBadge(3) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (aiReveal) {
                AiCountReveal(flow)
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.18f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141A22)),
                ) {
                    if (cameraGranted && packingList != null) {
                        EvidenceViewfinder(camera, Modifier.fillMaxSize(), analyzer = itemAnalyzer)
                    } else {
                        Text(
                            if (packingList == null) "No packing list" else "Camera off",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFCFD8E3),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Text(
                    when {
                        packingList == null -> "Go back and load a packing list first"
                        !cameraGranted -> "Allow camera access to scan item codes — or count with + and −"
                        camera.error != null -> "Camera unavailable — ${camera.error}"
                        else -> lastScan ?: "Scan each item's code or box label — or count with + and −"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VT.Slate,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!cameraGranted) {
                    SecondaryButton(
                        "Allow camera to scan items",
                        { askCamera.launch(AndroidPermission.permission.CAMERA) },
                        icon = Icons.Rounded.QrCodeScanner,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (LlmGateway.isAvailable && cameraGranted && packingList != null) {
                    if (!NpuEngine.isReady) {
                        NoticeStrip(
                            "The on-device model is not loaded — the dock photo will " +
                                "be sent to ${OpenRouterClient.DISPLAY_NAME} on OpenRouter.",
                        )
                    }
                    SecondaryButton(
                        if (counting) "Counting…" else "Count from a photo instead",
                        ::captureAndCount,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                countError?.let { NoticeStrip(it) }
                VTCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(
                            if (expected == 0) 0f else received / expected.toFloat(),
                            "$received",
                            "$expected",
                        )
                        Spacer(Modifier.width(18.dp))
                        Column {
                            Text("Counting the delivery", style = MaterialTheme.typography.titleMedium, color = VT.Ink)
                            Text("Units received against the packing list", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lines.forEach { line ->
                        TallyRow(
                            line,
                            onMinus = {
                                tally.adjust(line.sku, -1)
                                lines = tally.lines
                            },
                            onPlus = {
                                tally.adjust(line.sku, +1)
                                lines = tally.lines
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiCountReveal(flow: ReceivingFlowState) {
    val total = flow.countedItems.size
    val progress = if (total == 0) 0f else flow.scanProgress / total.toFloat()
    val cloud = flow.countedBy == LlmGateway.Backend.CLOUD
    VTCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(progress, "${flow.scanProgress}", "$total")
            Spacer(Modifier.width(18.dp))
            Column {
                Text("Counting the delivery", style = MaterialTheme.typography.titleMedium, color = VT.Ink)
                Text(
                    if (cloud) "GLM-5.3-Flash vision count against the packing list"
                    else "Qwen3-VL vision count against the packing list",
                    style = MaterialTheme.typography.bodySmall,
                    color = VT.Muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PulseDot(VT.Azure, 7.dp)
                    Text(
                        if (cloud) "GLM-5.3-FLASH · CLOUD" else "NPU · HTP0",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.08.sp),
                        color = VT.Azure,
                    )
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        flow.countedItems.take(flow.scanProgress).forEachIndexed { i, item ->
            CountRow(item, i)
        }
        if (flow.scanProgress < total) {
            VTCard(border = true) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CountingDot()
                    Spacer(Modifier.width(12.dp))
                    Text("Counting next line…", style = MaterialTheme.typography.bodyMedium, color = VT.Muted)
                }
            }
        }
    }
}

@Composable
private fun TallyRow(line: PackingItem, onMinus: () -> Unit, onPlus: () -> Unit) {
    VTCard {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                Text(
                    if (line.sku.isNotBlank()) "${line.sku} · ${line.detail}" else line.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = VT.Muted,
                )
            }
            StepButton(Icons.Rounded.Remove, "One fewer ${line.sku}", enabled = line.received > 0, onClick = onMinus)
            Text(
                "${line.received}/${line.expected}",
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = when {
                    line.received == line.expected -> VT.Emerald
                    line.received > line.expected -> VT.Amber
                    else -> VT.Ink
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.width(56.dp),
            )
            StepButton(Icons.Rounded.Add, "One more ${line.sku}", enabled = true, onClick = onPlus)
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(VT.Inset)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) VT.Slate else VT.Faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CountingDot() {
    val transition = rememberInfiniteTransition(label = "cd")
    val a by transition.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "cdA",
    )
    Box(
        Modifier
            .size(10.dp)
            .graphicsLayer { alpha = a }
            .clip(CircleShape)
            .background(VT.Azure),
    )
}

@Composable
private fun ProgressRing(progress: Float, done: String, total: String) {
    val animated by animateFloatAsState(progress, tween(650, easing = LinearEasing), label = "ring")
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val stroke = Stroke(width = 9f, cap = StrokeCap.Round)
            drawArc(
                color = VT.Inset,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = stroke,
            )
            drawArc(
                color = VT.Primary,
                startAngle = -90f,
                sweepAngle = 360f * animated.coerceIn(0f, 1f),
                useCenter = false,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(done, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp), color = VT.Ink)
                Text("/$total", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp), color = VT.Muted, modifier = Modifier.padding(bottom = 2.dp, start = 1.dp))
            }
            Text("counted", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
        }
    }
}

@Composable
private fun CountRow(item: PackingItem, index: Int) {
    val status = item.status
    val bg by animateColorAsState(
        when (status) {
            ItemStatus.UNLISTED, ItemStatus.DAMAGED -> Color(0xFFFDF3F3)
            ItemStatus.SHORT, ItemStatus.OVER -> Color(0xFFFBF3DF)
            else -> VT.Surface
        },
        tween(240), label = "rowBg",
    )
    var shown by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val pop by animateFloatAsState(
        targetValue = if (shown) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 320f),
        label = "pop",
    )
    VTCard(bg = bg, border = status == ItemStatus.MATCHED) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        when (status) {
                            ItemStatus.MATCHED -> VT.EmeraldBg
                            ItemStatus.SHORT, ItemStatus.OVER -> VT.AmberBg
                            ItemStatus.UNLISTED, ItemStatus.DAMAGED -> VT.CrimsonBg
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (status) {
                        ItemStatus.MATCHED -> Icons.Rounded.Check
                        ItemStatus.SHORT -> Icons.Rounded.Remove
                        ItemStatus.OVER -> Icons.Rounded.Add
                        ItemStatus.UNLISTED -> Icons.Rounded.Close
                        ItemStatus.DAMAGED -> Icons.Rounded.Warning
                    },
                    null,
                    tint = when (status) {
                        ItemStatus.MATCHED -> VT.Emerald
                        ItemStatus.SHORT, ItemStatus.OVER -> VT.AmberDot
                        ItemStatus.UNLISTED, ItemStatus.DAMAGED -> VT.Crimson
                    },
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (status == ItemStatus.UNLISTED || status == ItemStatus.DAMAGED) VT.Crimson else VT.Ink,
                )
                Text(
                    if (item.sku.isNotBlank()) "${item.sku} · ${item.detail}" else item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = VT.Muted,
                )
            }
            Spacer(Modifier.width(8.dp))
            CountPill(status, item.expected, item.received, item.damaged, Modifier.graphicsLayer { scaleX = pop; scaleY = pop })
        }
    }
}

@Composable
fun CountPill(status: ItemStatus, expected: Int, received: Int, damaged: Int = 0, modifier: Modifier = Modifier) {
    val (bg, fg, line, label) = when (status) {
        ItemStatus.MATCHED -> Quadruple(VT.EmeraldBg, VT.Emerald, VT.EmeraldLine, "Matched")
        ItemStatus.SHORT -> Quadruple(VT.AmberBg, VT.Amber, VT.AmberLine, "Short (-${expected - received})")
        ItemStatus.OVER -> Quadruple(VT.AmberBg, VT.Amber, VT.AmberLine, "Over (+${received - expected})")
        ItemStatus.UNLISTED -> Quadruple(VT.CrimsonBg, VT.Crimson, VT.CrimsonLine, "Unlisted (+$received)")
        ItemStatus.DAMAGED -> Quadruple(VT.CrimsonBg, VT.Crimson, VT.CrimsonLine, "Damaged ($damaged)")
    }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, line, RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
            color = fg,
        )
    }
}

private data class Quadruple(val a: Color, val b: Color, val c: Color, val d: String)
