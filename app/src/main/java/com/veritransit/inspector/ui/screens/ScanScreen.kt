package com.veritransit.inspector.ui.screens

import android.Manifest as AndroidPermission
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.QrCodeScanner
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.veritransit.inspector.ai.EvidenceCamera
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.ai.EvidenceViewfinder
import com.veritransit.inspector.ai.ReceivingAi
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.ai.OpenRouterClient
import com.veritransit.inspector.data.PackingList
import com.veritransit.inspector.data.Presets
import com.veritransit.inspector.data.QrLabel
import com.veritransit.inspector.ui.ListSections
import com.veritransit.inspector.ui.ReceivingFlowState
import com.veritransit.inspector.ui.components.FieldLabel
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.StepBadge
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(
    flow: ReceivingFlowState,
    startInManual: Boolean,
    onDetected: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    feedback: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(if (startInManual) 1 else 0) }
    LaunchedEffect(startInManual) { if (startInManual) mode = 1 }

    FlowScaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = flow.detected && mode == 0,
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) { it } + fadeIn(),
                exit = fadeOut(),
            ) {
                PrimaryButton("Continue to Packing List", onContinue, trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward)
            }
        },
    ) {
        FlowHeader(
            title = "Load Packing List",
            subtitle = "Carton label or packing-list photo",
            onBack = onBack,
            trailing = { StepBadge(1) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(mode) { mode = it }
            if (mode == 0) Viewfinder(flow, onDetected, feedback) else ManualEntry(flow, feedback, onContinue)
        }
    }
}

/* ---------------------------- Segmented control ---------------------------- */

@Composable
private fun Segmented(selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(VT.Inset)
            .padding(3.dp),
    ) {
        val half = maxWidth / 2
        val x by animateDpAsState(
            targetValue = if (selected == 0) 0.dp else half,
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f),
            label = "segX",
        )
        Box(
            Modifier
                .offset(x = x)
                .width(half)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(VT.Primary),
        )
        Row(Modifier.fillMaxSize()) {
            SegTab("Scan Label", Icons.Rounded.QrCodeScanner, selected == 0, Modifier.weight(1f)) { onSelect(0) }
            SegTab("Manual Entry", Icons.Rounded.Keyboard, selected == 1, Modifier.weight(1f)) { onSelect(1) }
        }
    }
}

@Composable
private fun SegTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Color.White else VT.Slate, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (active) Color.White else VT.Slate)
    }
}

/* ------------------------------- Viewfinder ------------------------------- */

private enum class CornerQ { TL, TR, BL, BR }

@Composable
private fun Bracket(corner: CornerQ, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "brk")
    val glow by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "brkA",
    )
    val color = Color(0xFFB02A45).copy(alpha = glow)
    Box(modifier.drawBehind {
        val w = size.width
        val h = size.height
        val t = 4f
        val horizTop = when (corner) { CornerQ.TL, CornerQ.TR -> 0f; else -> h - t }
        val vertLeft = when (corner) { CornerQ.TL, CornerQ.BL -> 0f; else -> w - t }
        drawRect(color, Offset(0f, horizTop), Size(w, t))
        drawRect(color, Offset(vertLeft, 0f), Size(t, h))
    })
}

@Composable
private fun Viewfinder(flow: ReceivingFlowState, onDetected: () -> Unit, feedback: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = com.veritransit.inspector.ui.theme.LocalHapticsEnabled.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var torch by remember { mutableStateOf(false) }

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

    // The live path needs both halves: a reachable model (NPU or the cloud
    // fallback) and a camera to feed it. Without either, the frame stays the
    // scripted demo scene.
    val live = LlmGateway.isAvailable && cameraGranted
    var reading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }

    // Real label decoding on the live frames, so the printed code locks the
    // step on its own — the capture button stays for the full-document OCR
    // read, which the label alone (often just the PO) cannot replace.
    // The analyzer is bound while the scan segment is showing; the guards inside
    // keep a stale frame from re-locking an already-resolved step.
    // No format filter: the printed code on a carton label may be a QR or a
    // 1D barcode, and the default scanner covers every supported format.
    val scanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    fun onLabelScanned(payload: String) {
        if (reading || flow.detected) return
        val fields = QrLabel.parse(payload)
        val p = Presets.DEFAULT
        flow.packingList = PackingList(
            purchaseOrderId = fields.purchaseOrderId ?: p.purchaseOrderId,
            packingListId = fields.packingListId ?: p.packingListId,
            supplier = p.supplier,
            goods = p.goods,
            dock = p.dock,
            carrier = p.carrier,
            items = p.items.map { it.copy(received = it.expected) },
        )
        // The label carries references, not the goods table — the receiver
        // confirms the packed lines on the packing-list step, exactly as after
        // a manual entry.
        flow.poInput = fields.purchaseOrderId ?: ""
        flow.packingListInput = fields.packingListId ?: ""
        flow.listSections = null
        flow.labelResolved = true
        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        flow.detected = true
    }

    val labelAnalyzer = remember(cameraGranted) {
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
                        val payload = codes.firstOrNull()?.rawValue
                        if (!payload.isNullOrBlank()) onLabelScanned(payload)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
        }
    }

    fun captureAndRead() {
        if (reading || !camera.ready) return
        reading = true
        readError = null
        scope.launch {
            try {
                // A packing list is a document: fit the whole page in rather
                // than centre-cropping the goods table off the bottom.
                val frame = camera.capture(context, EvidenceCamera.Fit.CONTAIN)
                if (frame == null) {
                    readError = camera.error ?: "Camera could not take the shot."
                    return@launch
                }
                flow.listEvidence = frame.absolutePath
                ReceivingAi.readPackingList(frame.absolutePath)
                    .onSuccess { reading ->
                        when {
                            // The model judged the document unreadable. However
                            // many fields squeaked out, trusting them would
                            // build a packing list off a misread — reject and
                            // have the receiver retake or enter it manually.
                            !reading.legible ->
                                readError =
                                    "This packing list could not be read clearly enough to trust — " +
                                        "retake it in better light or enter the references manually."
                            reading.usable -> {
                                flow.packingList = reading.toPackingList()
                                flow.listFromAi = true
                                flow.listSections = ListSections(
                                    header = reading.headerConfidence,
                                    supplier = reading.supplierConfidence,
                                    items = reading.itemsConfidence,
                                )
                                if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                flow.detected = true
                            }
                            else ->
                                readError = "Nothing legible in frame — fill it with the packing list and hold steady."
                        }
                    }
                    .onFailure { readError = it.message ?: "The model could not parse that document." }
            } finally {
                reading = false
            }
        }
    }

    LaunchedEffect(live) {
        // Scripted resolve only on the demo path; the live path waits for a shot.
        if (!live && !flow.detected) {
            delay(3600)
            flow.detected = true
        }
    }
    LaunchedEffect(flow.detected) {
        if (flow.detected) {
            feedback(
                when {
                    flow.labelResolved -> "Carton label scanned · PO ${flow.packingList?.purchaseOrderId}"
                    !flow.listFromAi -> "Packing list loaded · ready to count"
                    LlmGateway.lastBackend == LlmGateway.Backend.CLOUD ->
                        "Packing list read · GLM-5.3-Flash (cloud)"
                    else -> "Packing list read on-device · NPU"
                },
            )
            onDetected()
        }
    }
    LaunchedEffect(torch, camera.ready) { if (live) camera.setTorch(torch) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.18f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF141A22))
                .clickable(enabled = !live && !flow.detected) {
                    flow.detected = true
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
        ) {
            if (live) {
                EvidenceViewfinder(camera, Modifier.fillMaxSize(), analyzer = labelAnalyzer)
            } else Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(Brush.verticalGradient(listOf(Color(0xFF232B36), Color(0xFF161C25), Color(0xFF0F141B))))
                drawRect(Color(0xFF1D242E), Offset(0f, h * 0.78f), Size(w, h * 0.22f))
                drawRoundRect(Color(0xFF2A333F), Offset(w * 0.62f, h * 0.30f), Size(w * 0.30f, h * 0.34f), CornerRadius(10f))
                drawRoundRect(Color(0xFF33404E), Offset(w * 0.64f, h * 0.36f), Size(w * 0.10f, h * 0.10f), CornerRadius(6f))
                drawCircle(Color(0xFF0A0E13), radius = h * 0.055f, center = Offset(w * 0.70f, h * 0.66f))
                drawCircle(Color(0xFF0A0E13), radius = h * 0.055f, center = Offset(w * 0.86f, h * 0.66f))
                drawRoundRect(Color(0xFFEDEBE4), Offset(w * 0.12f, h * 0.18f), Size(w * 0.34f, h * 0.56f), CornerRadius(8f))
                drawRect(Color(0xFFC9C4B8), Offset(w * 0.16f, h * 0.24f), Size(w * 0.26f, h * 0.02f))
                drawRect(Color(0xFFC9C4B8), Offset(w * 0.16f, h * 0.30f), Size(w * 0.20f, h * 0.02f))
                drawRoundRect(Color(0xFF1B222B), Offset(w * 0.17f, h * 0.40f), Size(w * 0.24f, w * 0.24f), CornerRadius(6f))
                val cell = (w * 0.24f) / 7f
                val qr = intArrayOf(
                    1, 1, 1, 0, 1, 1, 1,
                    1, 0, 1, 0, 1, 0, 1,
                    1, 1, 0, 0, 0, 1, 1,
                    0, 0, 1, 1, 0, 1, 0,
                    1, 0, 1, 1, 0, 1, 1,
                    1, 0, 1, 0, 1, 1, 1,
                )
                qr.forEachIndexed { i, bit ->
                    if (bit == 1) {
                        val cx = w * 0.17f + (i % 7) * cell
                        val cy = h * 0.40f + (i / 7) * cell
                        drawRect(Color(0xFFE8E5DE), Offset(cx, cy), Size(cell * 0.86f, cell * 0.86f))
                    }
                }
                if (torch) drawRect(Color(0x14FFFFFF), Offset.Zero, size)
            }
            if (!flow.detected) {
                val transition = rememberInfiniteTransition(label = "scan")
                val y by transition.animateFloat(
                    initialValue = 0.06f,
                    targetValue = 0.94f,
                    animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Reverse),
                    label = "scanY",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = y * size.height - 1.dp.toPx() },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Brush.horizontalGradient(listOf(Color(0x00FF899A), Color(0xFFFF899A), Color(0x00FF899A)))),
                    )
                }
            }
            Box(Modifier.matchParentSize().padding(24.dp)) {
                Bracket(CornerQ.TL, Modifier.align(Alignment.TopStart).size(34.dp))
                Bracket(CornerQ.TR, Modifier.align(Alignment.TopEnd).size(34.dp))
                Bracket(CornerQ.BL, Modifier.align(Alignment.BottomStart).size(34.dp))
                Bracket(CornerQ.BR, Modifier.align(Alignment.BottomEnd).size(34.dp))
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable {
                        torch = !torch
                        feedback(if (torch) "Assist light on" else "Assist light off")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Bolt, null, tint = if (torch) Color(0xFFFFD54F) else Color(0xFFCFD8E3), modifier = Modifier.size(22.dp))
            }
            androidx.compose.animation.AnimatedVisibility(flow.detected, enter = fadeIn(tween(180)), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color(0x2E059669)))
            }
            androidx.compose.animation.AnimatedVisibility(
                flow.detected,
                modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 380f)) + fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(VT.Emerald)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        "LABEL LOCKED",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.1.sp),
                        color = Color.White,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    reading -> "Reading document…"
                    flow.detected -> "Packing list captured"
                    live -> "Hold the carton label in frame — or capture to read the list"
                    else -> "Align the carton label within frame"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VT.Slate,
                textAlign = TextAlign.Center,
            )
            if (!flow.detected && !live) {
                Text(
                    "Demo build — tap the frame to capture instantly",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp),
                    color = VT.Faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (live) {
            if (!NpuEngine.isReady) {
                NoticeStrip(
                    if (LlmGateway.mode == LlmGateway.Mode.CLOUD) {
                        "Cloud engine selected — the packing-list photo is sent to " +
                            OpenRouterClient.DISPLAY_NAME + " on OpenRouter."
                    } else {
                        "The on-device model is not loaded — the packing-list photo will be " +
                            "sent to ${OpenRouterClient.DISPLAY_NAME} on OpenRouter."
                    },
                )
            }
            PrimaryButton(
                text = when {
                    reading -> "Reading…"
                    flow.detected -> "Re-read packing list"
                    else -> "Capture & read packing list"
                },
                onClick = ::captureAndRead,
                enabled = camera.ready && !reading,
                icon = Icons.Rounded.QrCodeScanner,
            )
        } else if (LlmGateway.isAvailable && !cameraGranted) {
            SecondaryButton(
                "Enable camera for live reading",
                { askCamera.launch(AndroidPermission.permission.CAMERA) },
                icon = Icons.Rounded.QrCodeScanner,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!LlmGateway.isAvailable) {
            NoticeStrip("No AI backend — this is a scripted demo scene, not a live read.")
        }
        readError?.let { NoticeStrip(it) }
    }

    DetectedCard(flow)
}

@Composable
private fun DetectedCard(flow: ReceivingFlowState) {
    AnimatedVisibility(
        visible = flow.detected,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 240f)) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        VTCard(Modifier.padding(top = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(VT.Emerald))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "PACKING LIST LOADED",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.06.sp),
                        color = VT.Slate,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("Just now", style = com.veritransit.inspector.ui.theme.mono().dataSmall, color = VT.Muted)
                }
                Spacer(Modifier.height(12.dp))
                VTCard(bg = VT.Inset, border = false, radius = 6.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                FieldLabel("Purchase Order")
                                Spacer(Modifier.height(4.dp))
                                Text(flow.packingList?.purchaseOrderId ?: "—", style = com.veritransit.inspector.ui.theme.mono().data, color = VT.Ink)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                FieldLabel("Packing List")
                                Spacer(Modifier.height(4.dp))
                                Text(flow.packingList?.packingListId ?: "—", style = com.veritransit.inspector.ui.theme.mono().data, color = VT.Ink)
                            }
                        }
                        Row {
                            Column(Modifier.weight(1f)) {
                                FieldLabel("Supplier")
                                Spacer(Modifier.height(4.dp))
                                Text(flow.packingList?.supplier ?: "—", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                FieldLabel("Units Packed")
                                Spacer(Modifier.height(4.dp))
                                Text("${flow.packingList?.totalUnits ?: 0} Pcs", style = com.veritransit.inspector.ui.theme.mono().data, color = VT.Ink)
                            }
                        }
                    }
                }
                // On a label scan the supplier and lines above are the preset's,
                // not the code's — say so where they are shown.
                if (flow.labelResolved) {
                    Spacer(Modifier.height(10.dp))
                    Text(QrLabel.HEADERS_ONLY, style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                }
            }
        }
    }
}

/* ------------------------------ Manual entry ------------------------------ */

@Composable
private fun ManualEntry(
    flow: ReceivingFlowState,
    feedback: (String) -> Unit,
    onContinue: () -> Unit,
) {
    var po by remember { mutableStateOf(flow.poInput) }
    var packingRef by remember { mutableStateOf(flow.packingListInput) }
    var presetIdx by remember { mutableStateOf(flow.presetIndex) }

    val poValid = po.filter { it.isDigit() }.length >= 6
    val refValid = packingRef.count { it.isLetterOrDigit() } >= 6

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        VTCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    FieldLabel("Purchase Order Number")
                    Spacer(Modifier.height(6.dp))
                    MonoField(
                        value = po,
                        onValueChange = { raw ->
                            po = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(14)
                            flow.poInput = po
                        },
                        hint = "PO-2025-4471",
                    )
                    Text(
                        when {
                            po.isEmpty() -> "Printed on the PO and the carton label"
                            poValid -> "✓ Valid format"
                            else -> "Enter the PO reference"
                        },
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp),
                        color = if (poValid) VT.Emerald else VT.Faint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Column {
                    FieldLabel("Packing List Reference")
                    Spacer(Modifier.height(6.dp))
                    MonoField(
                        value = packingRef,
                        onValueChange = { raw ->
                            packingRef = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(16)
                            flow.packingListInput = packingRef
                        },
                        hint = "PL-2025-4471-A",
                    )
                }
            }
        }
        VTCard {
            Column(Modifier.padding(16.dp)) {
                FieldLabel("Goods Category")
                Spacer(Modifier.height(8.dp))
                Presets.ALL.forEachIndexed { i, p ->
                    val active = presetIdx == i
                    val bg by animateColorAsState(if (active) Color(0xFFFDF2F4) else Color.Transparent, tween(160), label = "preBg")
                    val borderC by animateColorAsState(if (active) VT.Primary else Color.Transparent, tween(160), label = "preBd")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .background(bg)
                            .border(1.2.dp, borderC, RoundedCornerShape(5.dp))
                            .clickable { presetIdx = i; flow.presetIndex = i }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(1.6.dp, if (active) VT.Primary else VT.Border, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (active) Box(Modifier.size(8.dp).clip(CircleShape).background(VT.Primary))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(p.supplier, style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                            Text("${p.short} · ${p.totalUnits} pcs packed", style = com.veritransit.inspector.ui.theme.mono().dataSmall, color = VT.Muted)
                        }
                    }
                    if (i < Presets.ALL.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
        val ok = poValid && refValid
        PrimaryButton(
            text = "Open Packing List",
            enabled = ok,
            trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
            onClick = {
                if (ok) {
                    val p = Presets.ALL[presetIdx]
                    flow.packingList = PackingList(
                        purchaseOrderId = po,
                        packingListId = packingRef,
                        supplier = p.supplier,
                        goods = p.goods,
                        dock = p.dock,
                        carrier = p.carrier,
                        items = p.items,
                    )
                    // Manual entry: any confidence markers from an earlier
                    // on-device read in this flow no longer apply.
                    flow.listSections = null
                    flow.detected = true
                    feedback("Packing list drafted from manual entry")
                    // Straight through to the packing-list step — the pinned
                    // "Continue" bar below only exists on the scan path, so
                    // without this call manual entry dead-ends on this screen.
                    onContinue()
                }
            },
        )
    }
}

@Composable
private fun MonoField(value: String, onValueChange: (String) -> Unit, hint: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VT.Ink, letterSpacing = 0.03.sp),
        cursorBrush = SolidColor(VT.Primary),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(VT.Surface)
                    .border(1.dp, VT.Border, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 16.sp), color = VT.Faint)
                }
                inner()
            }
        },
    )
}
