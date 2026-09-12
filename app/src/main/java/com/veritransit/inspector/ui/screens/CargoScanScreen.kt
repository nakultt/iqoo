package com.veritransit.inspector.ui.screens

import android.Manifest as AndroidPermission
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.veritransit.inspector.ai.EvidenceCamera
import com.veritransit.inspector.ai.EvidenceViewfinder
import com.veritransit.inspector.ai.InspectorAi
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.data.CargoItem
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.ui.InspectionFlowState
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.PulseDot
import com.veritransit.inspector.ui.components.StepBadge
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CargoScanScreen(
    flow: InspectionFlowState,
    onScanComplete: () -> Unit,
    onBack: () -> Unit,
) {
    val manifest = flow.manifest
    val total = flow.scannedItems.size
    val progress = if (total == 0) 0f else flow.scanProgress / total.toFloat()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val live = NpuEngine.isReady && cameraGranted && manifest != null
    var counting by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    /** Walks the result list in, one row at a time, then hands over. */
    suspend fun revealAndFinish() {
        val target = flow.scannedItems.size
        while (flow.scanProgress < target) {
            delay(if (flow.reconciledByAi) 320 else 760)
            flow.scanProgress++
        }
        delay(820)
        onScanComplete()
    }

    fun captureAndCount() {
        val m = manifest ?: return
        if (counting || !camera.ready) return
        counting = true
        scanError = null
        scope.launch {
            try {
                val frame = camera.capture(context)
                if (frame == null) {
                    scanError = camera.error ?: "Camera could not take the shot."
                    return@launch
                }
                flow.cargoEvidence = frame.absolutePath
                InspectorAi.reconcileCargo(frame.absolutePath, m)
                    .onSuccess { scan ->
                        flow.scannedItems = scan.items
                        flow.aiObservation = scan.observation
                        flow.aiConfidence = scan.confidence
                        flow.reconciledByAi = true
                        flow.scanProgress = 0
                        revealAndFinish()
                    }
                    .onFailure { scanError = it.message ?: "The model could not read that bay." }
            } finally {
                counting = false
            }
        }
    }

    LaunchedEffect(live) {
        // The live path is officer-driven: it waits for a photo of the bay.
        // The demo path keeps its scripted walk-through.
        if (live) return@LaunchedEffect
        if (flow.scanProgress == 0 && flow.scannedItems.isEmpty()) {
            delay(650)
            flow.scannedItems = flow.manifest?.let { m ->
                com.veritransit.inspector.data.Repo.nextScenario(m)
            } ?: emptyList()
        }
        revealAndFinish()
    }

    FlowScaffold(bottomBar = null) {
        FlowHeader(
            title = "Cargo Scan",
            subtitle = "Step 3 of 3 · Bay Rig #3",
            onBack = onBack,
            trailing = { StepBadge(3) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (live && flow.scannedItems.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.18f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141A22)),
                ) {
                    EvidenceViewfinder(camera, Modifier.fillMaxSize())
                }
                PrimaryButton(
                    text = if (counting) "Counting on NPU…" else "Capture cargo bay",
                    onClick = ::captureAndCount,
                    enabled = camera.ready && !counting,
                )
                scanError?.let { NoticeStrip(it) }
            } else if (NpuEngine.isReady && !cameraGranted && flow.scannedItems.isEmpty()) {
                SecondaryButton(
                    "Enable camera for live reconciliation",
                    { askCamera.launch(AndroidPermission.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            VTCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(progress, "${flow.scanProgress}", "$total")
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("Reconciling consignment", style = MaterialTheme.typography.titleMedium, color = VT.Ink)
                        Text(
                            if (flow.reconciledByAi) {
                                "Qwen3-VL vision count against declared manifest"
                            } else {
                                "Optical match against declared manifest"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = VT.Muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            PulseDot(VT.Azure, 7.dp)
                            Text(
                                if (flow.reconciledByAi) "NPU · HTP0" else "SCANNER LIVE",
                                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.08.sp),
                                color = VT.Azure,
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                flow.scannedItems.take(flow.scanProgress).forEachIndexed { i, item ->
                    ScanRow(item, i)
                }
                if (!(live && flow.scannedItems.isEmpty()) && (flow.scanProgress < total || total == 0)) {
                    VTCard(border = true) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            ScanningDot()
                            Spacer(Modifier.width(12.dp))
                            Text("Scanning next parcel…", style = MaterialTheme.typography.bodyMedium, color = VT.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanningDot() {
    val transition = rememberInfiniteTransition(label = "sd")
    val a by transition.animateFloat(
        0.25f, 1f,
        infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "sdA",
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
            Text("verified", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
        }
    }
}

@Composable
private fun ScanRow(item: CargoItem, index: Int) {
    val status = item.status
    val bg by animateColorAsState(
        when (status) {
            ItemStatus.UNLISTED -> Color(0xFFFDF3F3)
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
    VTCard(bg = bg, border = status != ItemStatus.UNLISTED) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        when (status) {
                            ItemStatus.MATCHED -> VT.EmeraldBg
                            ItemStatus.SHORTAGE -> VT.AmberBg
                            ItemStatus.UNLISTED -> VT.CrimsonBg
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (status) {
                        ItemStatus.MATCHED -> Icons.Rounded.Check
                        ItemStatus.SHORTAGE -> Icons.Rounded.Remove
                        ItemStatus.UNLISTED -> Icons.Rounded.Close
                    },
                    null,
                    tint = when (status) {
                        ItemStatus.MATCHED -> VT.Emerald
                        ItemStatus.SHORTAGE -> VT.AmberDot
                        ItemStatus.UNLISTED -> VT.Crimson
                    },
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, color = if (status == ItemStatus.UNLISTED) VT.Crimson else VT.Ink)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = VT.Muted)
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(status, item.expected, item.found, Modifier.graphicsLayer { scaleX = pop; scaleY = pop })
        }
    }
}

@Composable
fun StatusPill(status: ItemStatus, expected: Int, found: Int, modifier: Modifier = Modifier) {
    val (bg, fg, line, label) = when (status) {
        ItemStatus.MATCHED -> Quadruple(VT.EmeraldBg, VT.Emerald, VT.EmeraldLine, "Matched")
        ItemStatus.SHORTAGE -> Quadruple(VT.AmberBg, VT.Amber, VT.AmberLine, "Shortage (-${expected - found})")
        ItemStatus.UNLISTED -> Quadruple(VT.CrimsonBg, VT.Crimson, VT.CrimsonLine, "Unlisted (+$found)")
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
