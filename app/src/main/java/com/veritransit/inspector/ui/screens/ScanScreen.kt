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
import androidx.core.content.ContextCompat
import com.veritransit.inspector.ai.EvidenceCamera
import com.veritransit.inspector.ai.EvidenceViewfinder
import com.veritransit.inspector.ai.InspectorAi
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.data.Manifest
import com.veritransit.inspector.data.Presets
import com.veritransit.inspector.ui.BillSections
import com.veritransit.inspector.ui.InspectionFlowState
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
    flow: InspectionFlowState,
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
                PrimaryButton("Continue to Manifest Details", onContinue, trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward)
            }
        },
    ) {
        FlowHeader(
            title = "Load E-Way Bill",
            subtitle = "Step 1 of 3",
            onBack = onBack,
            trailing = { StepBadge(1) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(mode) { mode = it }
            if (mode == 0) Viewfinder(flow, onDetected, feedback) else ManualEntry(flow, feedback)
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
            SegTab("Scan QR Code", Icons.Rounded.QrCodeScanner, selected == 0, Modifier.weight(1f)) { onSelect(0) }
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
private fun Viewfinder(flow: InspectionFlowState, onDetected: () -> Unit, feedback: (String) -> Unit) {
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

    // The live path needs both halves: a resident model and a camera to feed it.
    // Without either, the frame stays the scripted demo scene.
    val live = NpuEngine.isReady && cameraGranted
    var reading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }

    fun captureAndRead() {
        if (reading || !camera.ready) return
        reading = true
        readError = null
        scope.launch {
            try {
                // A bill is a document: fit the whole page in rather than
                // centre-cropping the goods table off the bottom.
                val frame = camera.capture(context, EvidenceCamera.Fit.CONTAIN)
                if (frame == null) {
                    readError = camera.error ?: "Camera could not take the shot."
                    return@launch
                }
                flow.billEvidence = frame.absolutePath
                InspectorAi.readEwayBill(frame.absolutePath)
                    .onSuccess { bill ->
                        if (bill.usable) {
                            flow.manifest = bill.toManifest()
                            flow.manifestFromAi = true
                            flow.billSections = BillSections(
                                header = bill.headerConfidence,
                                route = bill.routeConfidence,
                                items = bill.itemsConfidence,
                            )
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            flow.detected = true
                        } else {
                            readError = "Nothing legible in frame — fill it with the bill and hold steady."
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
                if (flow.manifestFromAi) {
                    "E-Way Bill read on-device · NPU"
                } else {
                    "Manifest resolved · E-Way Bill locked"
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
                EvidenceViewfinder(camera, Modifier.fillMaxSize())
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
                        "QR LOCKED",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.1.sp),
                        color = Color.White,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    reading -> "Reading document on the NPU…"
                    flow.detected -> "Manifest captured"
                    live -> "Fill the frame with the E-Way Bill"
                    else -> "Align QR code or barcode within frame"
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
            PrimaryButton(
                text = when {
                    reading -> "Reading…"
                    flow.detected -> "Re-read document"
                    else -> "Capture & read bill"
                },
                onClick = ::captureAndRead,
                enabled = camera.ready && !reading,
                icon = Icons.Rounded.QrCodeScanner,
            )
        } else if (NpuEngine.isReady && !cameraGranted) {
            SecondaryButton(
                "Enable camera for live reading",
                { askCamera.launch(AndroidPermission.permission.CAMERA) },
                icon = Icons.Rounded.QrCodeScanner,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        readError?.let { NoticeStrip(it) }
    }

    DetectedCard(flow)
}

@Composable
private fun DetectedCard(flow: InspectionFlowState) {
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
                        "MANIFEST DETECTED",
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
                                FieldLabel("E-Way Bill")
                                Spacer(Modifier.height(4.dp))
                                Text(flow.manifest?.ewb ?: "—", style = com.veritransit.inspector.ui.theme.mono().data, color = VT.Ink)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                FieldLabel("Vehicle")
                                Spacer(Modifier.height(4.dp))
                                Text(flow.manifest?.vehicle ?: "—", style = com.veritransit.inspector.ui.theme.mono().data, color = VT.Ink)
                            }
                        }
                        Row {
                            Column(Modifier.weight(1f)) {
                                FieldLabel("Consignment")
                                Spacer(Modifier.height(4.dp))
                                Text(flow.manifest?.consignment ?: "—", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                FieldLabel("Items")
                                Spacer(Modifier.height(4.dp))
                                Text("${flow.manifest?.totalUnits ?: 0} Pcs", style = com.veritransit.inspector.ui.theme.mono().data, color = VT.Ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------ Manual entry ------------------------------ */

@Composable
private fun ManualEntry(flow: InspectionFlowState, feedback: (String) -> Unit) {
    var ewb by remember { mutableStateOf(flow.ewbInput) }
    var vehicle by remember { mutableStateOf(flow.vehicleInput) }
    var presetIdx by remember { mutableStateOf(flow.presetIndex) }

    val ewbValid = ewb.filter { it.isDigit() }.length == 12
    val vehicleValid = vehicle.filter { it.isLetterOrDigit() }.length >= 6

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        VTCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    FieldLabel("E-Way Bill Number")
                    Spacer(Modifier.height(6.dp))
                    MonoField(
                        value = ewb,
                        onValueChange = { raw ->
                            val d = raw.filter { it.isDigit() }.take(12)
                            ewb = d.chunked(4).joinToString("-")
                            flow.ewbInput = ewb
                        },
                        hint = "7819-2044-8831",
                    )
                    Text(
                        when {
                            ewb.isEmpty() -> "12 digits, printed beside the QR"
                            ewbValid -> "✓ Valid format"
                            else -> "${ewb.filter { it.isDigit() }.length}/12 digits"
                        },
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp),
                        color = if (ewbValid) VT.Emerald else VT.Faint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Column {
                    FieldLabel("Vehicle Number")
                    Spacer(Modifier.height(6.dp))
                    MonoField(
                        value = vehicle,
                        onValueChange = { raw ->
                            vehicle = raw.uppercase().filter { it.isLetterOrDigit() || it == ' ' }.take(12)
                            flow.vehicleInput = vehicle
                        },
                        hint = "TN 38 BX 4491",
                    )
                }
            }
        }
        VTCard {
            Column(Modifier.padding(16.dp)) {
                FieldLabel("Consignment Type")
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
                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                            Text("${p.route} · ${p.distanceKm} km · ${p.totalUnits} pcs", style = com.veritransit.inspector.ui.theme.mono().dataSmall, color = VT.Muted)
                        }
                    }
                    if (i < Presets.ALL.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
        val ok = ewbValid && vehicleValid
        PrimaryButton(
            text = "Open Manifest Details",
            enabled = ok,
            trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
            onClick = {
                if (ok) {
                    val p = Presets.ALL[presetIdx]
                    flow.manifest = Manifest(
                        ewb = ewb,
                        vehicle = vehicle,
                        vehicleModel = p.vehicleModel,
                        consignment = p.name,
                        route = p.route,
                        distanceKm = p.distanceKm,
                        items = p.items,
                        ref = "#" + ewb.filter { it.isDigit() }.take(4) + "-A",
                    )
                    // Manual entry: any confidence markers from an earlier
                    // on-device read in this flow no longer apply.
                    flow.billSections = null
                    flow.detected = true
                    feedback("Manifest drafted from manual entry")
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
