package com.veritransit.inspector.ui.warehouse

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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

/**
 * Demo receive screens: two taps to a verdict, no login, no server.
 * Same phone that sent the QRs verifies them offline.
 */

// ------------------------------------------------------------- shipment list

@Composable
fun ShipmentListScreen(
    vm: WarehouseViewModel,
    onOpen: (String) -> Unit,
) {
    val shipments by vm.shipments.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text("Receive", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "Step 2 of 2 — pick a shipment, scan its QRs. Works offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (shipments.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing to receive yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Go to Send and tap Create & show QRs first.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shipments, key = { it.ref }) { s ->
                    ElevatedCard(onClick = { vm.select(s.ref); onOpen(s.ref) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(s.ref, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("${s.supplier ?: "—"} → ${s.buyer ?: "—"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("${s.expectedCount} cartons — tap to scan",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------- package scan

/**
 * Scan hot path: point camera at the sender's QR, or tap Simulate scan
 * when both sides share one phone. Verdict is instant and offline.
 */
@Composable
fun PackageScanScreen(vm: WarehouseViewModel, kind: ScanKind, onDone: () -> Unit) {
    val context = LocalContext.current
    val verdict by vm.verdict.collectAsState()
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

    Box(Modifier.fillMaxSize()) {
        if (hasCamera) {
            CameraViewfinder { qr, barcode -> vm.onCodes(qr, barcode, kind) }
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

        // The verdict card owns the bottom strip while it is up — its own Next
        // dismisses it and these controls come back.
        verdict?.let { VerdictCard(it, Modifier.align(Alignment.BottomCenter)) { vm.clearVerdict() } }
            ?: Column(
                Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // One-phone demo: the sender's QRs are on this screen, so
                // there is no second camera — this runs the same verdict path.
                FilledTonalButton(onClick = { vm.simulateScan(kind) }) { Text("Tap to scan next carton") }
                FilledTonalButton(onClick = onDone) { Text("Done — see result") }
            }
    }
}

@Composable
private fun CameraViewfinder(onCodes: (String?, String?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    // Only the newest frame matters; a backlog would show the
                    // officer a verdict for a carton they have already moved.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, BarcodeAnalyzer(onCodes)) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

@Composable
private fun VerdictCard(
    verdict: VerificationEngine.Verdict,
    modifier: Modifier = Modifier,
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
                Text("Master declares ${verdict.declaredInners} inner boxes — open and verify.",
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDismiss) {
                Text("Next", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------- reconciliation (result)

/** Final screen: how many cartons verified, what's missing. That's the demo. */
@Composable
fun LoadReconciliationScreen(vm: WarehouseViewModel, onBack: () -> Unit) {
    val (done, total) = vm.accounted.collectAsState().value
    val missing by vm.missing.collectAsState()

    LaunchedEffect(Unit) { vm.refreshCounts() }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text(if (missing.isEmpty()) "Delivered ✓" else "Receiving…",
            style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Surface(
            color = if (missing.isEmpty()) Color(0xFF0F8A4D).copy(alpha = 0.12f)
            else Color(0xFFC5341F).copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("$done of $total cartons verified",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (missing.isEmpty()) "All cartons match — demo complete."
                    else "${missing.size} still to scan. Go back and keep scanning.",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to scanning") }
    }
}
