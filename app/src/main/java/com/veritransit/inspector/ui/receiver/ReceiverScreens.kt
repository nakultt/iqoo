package com.veritransit.inspector.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veritransit.core.PackageKind
import com.veritransit.inspector.ai.EvidenceCamera
import com.veritransit.inspector.ai.EvidenceViewfinder
import com.veritransit.inspector.ai.InspectorAi
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.data.VeriTransitRepo
import com.veritransit.inspector.ui.warehouse.WarehouseViewModel
import kotlinx.coroutines.launch

/**
 * Receiver flow — open the big box, prove all N inners, VLM-check the
 * label-vs-content, then sign.
 *
 * 1. Pick shipment → 2. Scan MASTER (big-box QR) → 3. Guided inner scan:
 *    "scan 7 of 10" with each inner QR ticked live → 4. AI photo check
 *    (label + contents vs scanned QR details) → 5. Sign / PoD.
 */
@Composable
fun ReceiverFlowScreen(
    vm: WarehouseViewModel,
    onScanMaster: () -> Unit,
    onScanInner: () -> Unit,
    onAiCheck: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { VeriTransitRepo.get(context) }
    val scope = rememberCoroutineScope()
    val shipments by vm.shipments.collectAsState()
    val selected by vm.selected.collectAsState()
    val verdict by vm.verdict.collectAsState()
    val masterCode by vm.masterCode.collectAsState()
    val children by vm.masterChildren.collectAsState()
    val innerScanned by vm.innerScanned.collectAsState()
    val aiNote by vm.aiNote.collectAsState()
    var manual by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    var podMsg by remember { mutableStateOf<String?>(null) }

    // A MASTER verdict auto-opens its inner session.
    LaunchedEffect(verdict) {
        val v = verdict
        if (v?.token != null && v.known?.kind != PackageKind.UNIT.name && masterCode == null) {
            vm.startMasterSession(v.token.packageCode)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("‹") }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Receiver — verify & sign", style = MaterialTheme.typography.headlineSmall)
                Text("Big box first, then all N inners, then photo proof.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            item {
                Text("1 · Shipment", fontWeight = FontWeight.Bold)
                if (shipments.isEmpty()) {
                    Text("No shipments cached — sync from Device setup first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    shipments.take(10).forEach { s ->
                        OutlinedButton(
                            onClick = { vm.select(s.ref); vm.clearMasterSession(); vm.clearVerdict() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Text(
                                (if (selected == s.ref) "● " else "") + s.ref,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (selected == s.ref) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            item {
                Text("2 · Scan the big-box (MASTER) QR", fontWeight = FontWeight.Bold)
                Text("The master declares exactly how many inners must come out — e.g. 10 iPhone boxes.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Button(onClick = onScanMaster, enabled = selected != null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan MASTER QR")
                }
                verdict?.let { v ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(v.headline, fontWeight = FontWeight.Black, color = when (v.headline) {
                                "VERIFIED" -> MaterialTheme.colorScheme.primary
                                "SUSPECT" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.error
                            })
                            v.token?.let { Text(it.packageCode, fontFamily = FontFamily.Monospace) }
                            if (v.reasons.isNotEmpty()) v.reasons.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                            v.known?.let { Text("Declares ${it.qty} inner boxes — open and verify.", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall) }
                            aiNote?.let { Text("AI: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            if (masterCode != null) {
                val expected = children.map { it.packageCode }.toSet()
                val total = if (expected.isNotEmpty()) expected.size else (verdict?.known?.qty ?: 0)
                val done = innerScanned.intersect(expected).size + innerScanned.minus(expected).size
                val missing = expected - innerScanned
                val extra = innerScanned - expected
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("3 · Scan the $total inners inside $masterCode", fontWeight = FontWeight.Bold)
                            Text("$done of $total scanned", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (missing.isNotEmpty()) {
                                Text("Still inside, unscanned:", style = MaterialTheme.typography.labelLarge)
                                missing.take(10).forEach { Text("○ $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                if (missing.size > 10) Text("…and ${missing.size - 10} more", style = MaterialTheme.typography.bodySmall)
                            } else if (total > 0) {
                                Text("All declared inners scanned ✓", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            if (extra.isNotEmpty()) {
                                Text("Extra (not declared by master):", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                                extra.forEach { Text("● $it — INNER_UNLISTED evidence", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onScanInner, modifier = Modifier.weight(1f)) { Text("Scan inner QR") }
                                OutlinedButton(onClick = { masterCode?.let { onAiCheck(it) } }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.PhotoCamera, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("AI check")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = manual, onValueChange = { manual = it }, label = { Text("Type inner code") }, singleLine = true, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { if (vm.onInnerCode(manual)) manual = "" }) { Text("Add") }
                            }
                        }
                    }
                }
                item {
                    Text("Inner checklist", fontWeight = FontWeight.Bold)
                }
                items(children, key = { it.packageCode }) { p ->
                    val hit = innerScanned.contains(p.packageCode)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (hit) Icons.Rounded.CheckCircle else Icons.Rounded.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.packageCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                p.contents?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (!hit) OutlinedButton(onClick = { onAiCheck(p.packageCode) }) { Text("AI") }
                        }
                    }
                }
            }

            item {
                Text("4 · Photo proof + sign (VLM verifies label ↔ contents)", fontWeight = FontWeight.Bold)
                Text("Photograph the goods: the NPU confirms the box matches what its QR declares, then the signature seals the PoD.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = receiver, onValueChange = { receiver = it }, label = { Text("Receiver name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val ref = selected
                            if (ref == null || receiver.isBlank()) {
                                podMsg = "Pick a shipment and enter the receiver name."
                            } else {
                                val ok = repo.submitPod(ref, receiver, mapOf("inners" to innerScanned.joinToString(",")), null, null)
                                podMsg = if (ok) "PoD submitted — certificate queued on server." else "Saved on phone — submits on next sync."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign & submit PoD") }
                podMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ------------------------------------------------------- VLM package check

/**
 * AI photo check: reads the printed code off the label, judges contents vs
 * the QR's declared contents, and flags tamper — then folds the flags into
 * the live scan verdict (demote-only, never a pass).
 */
@Composable
fun AiVisualCheckScreen(vm: WarehouseViewModel, packageCode: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VeriTransitRepo.get(context) }
    val scope = rememberCoroutineScope()
    val camera = remember { EvidenceCamera() }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var declared by remember { mutableStateOf("") }
    var declaredQty by remember { mutableStateOf(1) }

    LaunchedEffect(packageCode) {
        repo.packageByCode(packageCode)?.let {
            declared = it.contents ?: ""
            declaredQty = it.qty
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("‹") }
            Spacer(Modifier.width(10.dp))
            Text("AI label ↔ content check", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Package $packageCode declares: ${declared.ifBlank { "—" }} (qty $declaredQty). Photograph it — the VLM reads the printed code, compares the contents, and checks the seal.", style = MaterialTheme.typography.bodySmall)
        Text(packageCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

        if (NpuEngine.isReady) {
            EvidenceViewfinder(camera, Modifier.fillMaxWidth().height(320.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        result = null
                        try {
                            val frame = camera.capture(context)
                            if (frame == null) {
                                result = "Shot failed."
                            } else {
                                InspectorAi.verifyPackage(frame.absolutePath, declared.ifBlank { "general goods" }, declaredQty)
                                    .onSuccess { check ->
                                        val flags = check.flags()
                                        val disagree = check.printedCodeDisagrees(packageCode)
                                        vm.applyAiFlags(flags, check.observation)
                                        result = buildString {
                                            append("VLM: ${check.observation.ifBlank { "no remark" }} (conf ${check.confidence})\n")
                                            if (check.printedCode.isNotBlank()) append("Printed code reads: ${check.printedCode}\n")
                                            if (disagree) append("⚠ Printed code disagrees with scanned QR — possible swapped label.\n")
                                            if (flags.isEmpty()) append("No visual flags — deterministic verdict stands.")
                                            else append("Flags: ${flags.keys.joinToString()} → verdict demoted if it was VERIFIED.")
                                        }
                                    }
                                    .onFailure { result = "VLM failed: ${it.message}" }
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = camera.ready && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Verifying on NPU…" else "Capture & verify package (VLM)") }
        } else {
            Text("NPU model not loaded — enable it under Settings → On-device AI. Deterministic checks (signature, binding, duplicates) still apply.", style = MaterialTheme.typography.bodySmall)
        }
        result?.let {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to receiver") }
    }
}
