package com.veritransit.inspector.ui.warehouse

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.veritransit.core.ScanKind
import com.veritransit.core.ScanResult
import com.veritransit.inspector.scan.BarcodeAnalyzer
import com.veritransit.inspector.scan.VerificationEngine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * §6.1 warehouse screens.
 *
 * The design language of the existing inspection flow is reused on purpose —
 * verdict colour, haptics, motion — because an officer moves between gate and
 * warehouse mode in one shift and a second visual grammar would cost accuracy.
 */

// ------------------------------------------------------------- shipment list

@Composable
fun ShipmentListScreen(
    vm: WarehouseViewModel,
    onOpen: (String) -> Unit,
    onSetup: () -> Unit,
) {
    val shipments by vm.shipments.collectAsState()
    val outbox by vm.outboxDepth.collectAsState()
    val syncing by vm.syncing.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today's dispatches", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            if (outbox > 0) {
                AssistChip(onClick = { vm.sync() }, enabled = !syncing,
                    label = { Text(if (syncing) "Syncing…" else "$outbox queued") })
                Spacer(Modifier.width(6.dp))
            }
            AssistChip(onClick = onSetup, label = { Text("Setup") })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Scans are recorded on this phone first. The queue above is what has not " +
                "reached the server yet — nothing is lost while it waits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (shipments.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No shipments cached", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Connect to the server in Settings and sync before the shift.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onSetup) { Text("Set up this device") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shipments, key = { it.ref }) { s ->
                    ElevatedCard(onClick = { vm.select(s.ref); onOpen(s.ref) }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.ref, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                s.riskBand?.let { RiskBadge(it, s.riskScore) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${s.supplier ?: "—"} → ${s.buyer ?: "—"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("${s.vehicle ?: "no vehicle"} · ${s.expectedCount} cartons",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // §6.1 — the finance chip travels with the shipment, so a
                            // loader can see a held payment without leaving the dock.
                            s.financeStatus?.let { fin ->
                                Spacer(Modifier.height(6.dp))
                                FinanceChip(fin, s.heldValue)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(band: String, score: Int?) {
    val color = when (band) {
        "HIGH" -> Color(0xFFC5341F); "MEDIUM" -> Color(0xFFB7791F); else -> Color(0xFF0F8A4D)
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
        Text("${score ?: ""} $band".trim(), color = color, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
private fun FinanceChip(status: String, held: Double?) {
    val color = when (status) {
        "HELD" -> Color(0xFF7C3AED); "RELEASED" -> Color(0xFF0F8A4D); else -> Color(0xFF626873)
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(
            if (status == "HELD" && held != null && held > 0)
                "PAYMENT HELD ₹${"%,.0f".format(held)}" else status,
            color = color, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ------------------------------------------------------------- package scan

/**
 * §7.1 hot path. The viewfinder decodes QR and Code128 from the same frame and
 * the verdict card answers before the officer lowers the phone.
 */
@Composable
fun PackageScanScreen(
    vm: WarehouseViewModel,
    kind: ScanKind,
    onDone: () -> Unit,
    onAiCheck: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val verdict by vm.verdict.collectAsState()
    val aiNote by vm.aiNote.collectAsState()
    val voiceAlert by vm.voiceAlert.collectAsState()
    val (done, total) = vm.accounted.collectAsState().value

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    // A live camera that decodes nothing looks identical to a broken app. After
    // a few seconds of silence the officer is told what to change rather than
    // being left to wonder whether the scanner is working at all.
    var lastDecodeAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var struggling by remember { mutableStateOf(false) }
    LaunchedEffect(hasCamera) {
        while (hasCamera) {
            kotlinx.coroutines.delay(1_000)
            struggling = System.currentTimeMillis() - lastDecodeAt > NO_DECODE_HINT_MS
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (hasCamera) {
            CameraViewfinder(torch = torch) { codes ->
                lastDecodeAt = System.currentTimeMillis()
                struggling = false
                vm.onFrame(codes, kind)
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("Camera permission is needed to scan labels.", color = Color.White)
            }
        }

        // Live count — §2.1's "147/148, box P-…07 missing, named".
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(14.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("$done/$total", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(if (kind == ScanKind.LOAD) "loaded" else "received",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        verdict?.let { v ->
            VerdictCard(
                v, Modifier.align(Alignment.BottomCenter),
                aiNote = aiNote,
                onAiCheck = v.token?.let { t -> { onAiCheck(t.packageCode) } },
                onDismiss = { vm.clearVerdict() },
            )
        }

        // Tamper voice note: the exact audio the dock just heard — one tap
        // sends it to the supervisor Telegram chat (or it queues on sync).
        voiceAlert?.let { alert ->
            VoiceNoteBanner(
                alert = alert,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 220.dp),
                onDismiss = { vm.clearVoiceAlert() },
            )
        }

        if (hasCamera && struggling && verdict == null) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Searching for a label…", color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Fill the frame with the QR, hold steady, tap the screen " +
                            "to focus. Turn the lamp on if the label is glaring.",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasCamera) {
                FilledTonalButton(onClick = { torch = !torch }) {
                    Text(if (torch) "Lamp on" else "Lamp")
                }
                Spacer(Modifier.width(8.dp))
            }
            FilledTonalButton(onClick = onDone) { Text("Done") }
        }
    }
}

/** How long the viewfinder stays silent before it offers the officer advice. */
private const val NO_DECODE_HINT_MS = 4_000L

/**
 * The scanning viewfinder.
 *
 * Three settings here are the difference between a scanner that works on a dock
 * and one that does not:
 *
 *  * **[ANALYSIS_WIDTH]×[ANALYSIS_HEIGHT], not CameraX's 640×480 default.** A
 *    signed label token is ~140 bytes, which is a version-7-or-higher QR at
 *    45+ modules across. At 640×480 that needs the label to fill most of the
 *    frame and be perfectly sharp before it will decode at all — which is
 *    exactly the "sometimes it just doesn't scan" the dock reports.
 *  * **Tap to focus.** Continuous AF hunts on a flat carton with no contrast,
 *    and a QR that is soft by two pixels per module does not decode.
 *  * **Torch.** Dock lighting is side-lit and a laminated label glares.
 *
 * The use cases are also unbound on dispose. Leaving them bound kept the camera
 * hot after the officer navigated away and made the very next `EvidenceCamera`
 * bind — the AI photo check — fail on devices that allow only one open session.
 */
@Composable
private fun CameraViewfinder(
    torch: Boolean,
    onFrame: (List<String>) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { mutableStateOf<BarcodeAnalyzer?>(null) }
    val analysisRef = remember { mutableStateOf<ImageAnalysis?>(null) }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val cameraRef = remember { mutableStateOf<Camera?>(null) }

    // AndroidView's factory runs once, so the analyser would otherwise hold the
    // first lambda for ever and keep reporting against a stale ScanKind.
    val latestOnFrame by rememberUpdatedState(onFrame)

    DisposableEffect(Unit) {
        onDispose {
            analysisRef.value?.clearAnalyzer()
            analyzer.value?.close()
            runCatching { providerRef.value?.unbindAll() }
            executor.shutdown()
        }
    }

    LaunchedEffect(torch, cameraRef.value) {
        runCatching { cameraRef.value?.cameraControl?.enableTorch(torch) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                providerRef.value = provider

                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()

                val barcodeAnalyzer = BarcodeAnalyzer { codes -> latestOnFrame(codes) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    // Only the newest frame matters; a backlog would show the
                    // officer a verdict for a carton they have already moved.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, barcodeAnalyzer) }

                analyzer.value = barcodeAnalyzer
                analysisRef.value = analysis

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                }.onSuccess { cameraRef.value = it }

                // Tap to focus: a flat carton gives continuous AF nothing to
                // lock onto, and a soft QR at this module density will not decode.
                previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = previewView.meteringPointFactory
                            .createPoint(event.x, event.y)
                        runCatching {
                            cameraRef.value?.cameraControl?.startFocusAndMetering(
                                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                            )
                        }
                        view.performClick()
                    }
                    true
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * 1280×720 for the analysis stream. Enough pixels for a version-7 QR read at
 * arm's length; still cheap enough to keep ML Kit inside the §7.1 frame budget.
 */
private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 720

@Composable
private fun VerdictCard(
    verdict: VerificationEngine.Verdict,
    modifier: Modifier = Modifier,
    aiNote: String? = null,
    onAiCheck: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val color = when (verdict.result) {
        ScanResult.VERIFIED -> Color(0xFF0F8A4D)
        ScanResult.SUSPECT_REVIEW -> Color(0xFFB7791F)
        ScanResult.REJECTED -> Color(0xFFC5341F)
    }

    Surface(
        color = color,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(verdict.headline, color = Color.White, fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.headlineMedium)
            verdict.token?.let {
                Text(it.packageCode, color = Color.White.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            verdict.known?.contents?.let {
                Text(it, color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (verdict.reasons.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // §12 — the verdict always names why. An officer under time
                // pressure who is shown only a colour will treat amber as green.
                verdict.reasons.forEach { reason ->
                    Text("• ${reason.message}", color = Color.White,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (verdict.declaredInners > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Master declares ${verdict.declaredInners} inner boxes — open & scan all $verdict.declaredInners inners, then AI-check the contents.",
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall)
            }
            aiNote?.let {
                Spacer(Modifier.height(6.dp))
                Text("AI: $it", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onAiCheck != null && com.veritransit.inspector.ai.NpuEngine.isReady) {
                    TextButton(onClick = onAiCheck) {
                        Text("AI photo check", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Next", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ------------------------------------------------------- tamper voice note

/**
 * The dock just heard this; the supervisor reads it as a Telegram voice
 * message. [alert.queued] means the server already holds it for bot
 * forwarding — the share button covers the offline case in one tap.
 */
@Composable
private fun VoiceNoteBanner(
    alert: WarehouseViewModel.VoiceAlert,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (alert.queued) "🔊 Voice note sent — supervisor Telegram will get it"
                else "🔊 Voice note recorded — send it to the supervisor Telegram",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(alert.caption, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.files", alert.file,
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "audio/wav"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_TEXT, alert.caption)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Send voice alert"))
                }) { Text("Send to Telegram") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

// ------------------------------------------------------- load reconciliation

/** §7.3 — the release gate. Dispatch is blocked while anything is unaccounted. */
@Composable
fun LoadReconciliationScreen(vm: WarehouseViewModel, onBack: () -> Unit) {
    val (done, total) = vm.accounted.collectAsState().value
    val missing by vm.missing.collectAsState()
    val outbox by vm.outboxDepth.collectAsState()

    LaunchedEffect(Unit) { vm.refreshCounts() }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text("Load reconciliation", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Surface(
            color = if (missing.isEmpty()) Color(0xFF0F8A4D).copy(alpha = 0.12f)
            else Color(0xFFC5341F).copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("$done of $total cartons accounted",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (missing.isEmpty()) "Complete — the truck can be released."
                    else "${missing.size} not yet scanned. Dispatch is blocked until every line is accounted for.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (outbox > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("$outbox scans still queued for sync — the count above is this device's view.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (missing.isNotEmpty()) {
            Text("Not yet scanned", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(missing, key = { it.packageCode }) { p ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.packageCode, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            p.contents?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(p.kind, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = { vm.sync() }, modifier = Modifier.weight(1f)) { Text("Sync") }
        }
    }
}
