package com.veritransit.inspector.ui.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.veritransit.core.DocumentKind
import com.veritransit.core.DocumentReader
import com.veritransit.core.PackageKind
import com.veritransit.core.ShipmentDocument
import com.veritransit.inspector.ai.EvidenceCamera
import com.veritransit.inspector.ai.EvidenceViewfinder
import com.veritransit.inspector.ai.InspectorAi
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.data.DeviceSettings
import com.veritransit.inspector.data.VeriTransitRepo
import com.veritransit.inspector.data.local.DocumentFactEntity
import com.veritransit.inspector.net.ApiClient
import com.veritransit.inspector.ui.common.QrImage
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sender flow — the phone is the pack station (replaces the web page).
 *
 * Three short steps, same order as §4.5: shipment → cartons + QRs →
 * paperwork. The QR grid is the output: 1 master box QR plus N inner QRs,
 * each rendered on-device by [QrImage] so labels print even offline.
 */
@Composable
fun SenderFlowScreen(vm: SenderViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val shipments by vm.shipments.collectAsState()
    var step by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("‹") }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Sender — pack & label", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when (step) {
                        0 -> "Step 1 of 3 · Shipment"
                        1 -> "Step 2 of 3 · Cartons + QRs"
                        else -> "Step 3 of 3 · Paperwork (VLM)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (step) {
            0 -> ShipmentStep(vm, shipments.map { it.ref }, onNext = { step = 1 })
            1 -> PackStep(vm, onNext = { step = 2 }, onPrev = { step = 0 })
            else -> DocStep(vm, onPrev = { step = 1 }, context = context)
        }
    }
}

@Composable
private fun ShipmentStep(vm: SenderViewModel, cached: List<String>, onNext: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = vm.shipmentRef, onValueChange = { vm.shipmentRef = it }, label = { Text("Shipment ref (e.g. SHP-2026-090231)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (cached.isNotEmpty()) {
            Text("Cached on this phone:", style = MaterialTheme.typography.labelLarge)
            cached.take(8).forEach { ref ->
                OutlinedButton(onClick = { vm.useShipment(ref) }, modifier = Modifier.fillMaxWidth()) { Text(ref, fontFamily = FontFamily.Monospace) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = vm.supplier, onValueChange = { vm.supplier = it }, label = { Text("Supplier") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = vm.buyer, onValueChange = { vm.buyer = it }, label = { Text("Buyer") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = { vm.createShipment(); onNext() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        vm.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PackStep(vm: SenderViewModel, onNext: () -> Unit, onPrev: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Shipment: ${vm.shipmentRef.ifBlank { "—" }}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = vm.contents, onValueChange = { vm.contents = it }, label = { Text("What is in ONE inner box?") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = vm.sku, onValueChange = { vm.sku = it }, label = { Text("SKU") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = vm.masters, onValueChange = { vm.masters = it.filter(Char::isDigit).take(2) }, label = { Text("Masters") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = vm.unitsPerMaster, onValueChange = { vm.unitsPerMaster = it.filter(Char::isDigit).take(2) }, label = { Text("Inners / master") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Label each inner box", modifier = Modifier.weight(1f))
            Switch(checked = vm.labelInners, onCheckedChange = { vm.labelInners = it })
        }
        Button(onClick = { vm.issueLabels() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), content = { Text(if (vm.busy) "Issuing…" else "Generate QRs  (1 master + N inners)") })

        if (vm.draft && vm.issued.isNotEmpty()) {
            Text("DRAFT labels — scannable, but they verify only after sync + server signing.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Close-master: the master declares exactly the inners scanned in.
        OutlinedTextField(value = vm.childCodesInput, onValueChange = { vm.childCodesInput = it }, label = { Text("Inner codes in this master (paste / scan)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("${vm.childCodes().size} inner codes", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.closeMaster() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Close master over these inners") }

        vm.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (vm.issued.isNotEmpty()) {
            val masters = vm.issued.filter { it.kind == PackageKind.MASTER }
            val inners = vm.issued.filter { it.kind != PackageKind.MASTER }
            Text("${masters.size} master QRs · ${inners.size} inner QRs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            masters.forEach { m ->
                QrLabelCard(code = m.packageCode, kind = "MASTER BOX", detail = m.contents ?: "", payload = m.payload,vm = vm, context = context)
                val kids = vm.issued.filter { it.parentCode == m.packageCode }
                if (kids.isNotEmpty()) {
                    Text("↳ ${kids.size} inners inside ${m.packageCode}", style = MaterialTheme.typography.bodySmall)
                    kids.take(10).forEach { k ->
                        QrLabelCard(code = k.packageCode, kind = "INNER", detail = k.contents ?: "", payload = k.payload, vm = vm, context = context, compact = true)
                    }
                    if (kids.size > 10) Text("…and ${kids.size - 10} more", style = MaterialTheme.typography.bodySmall)
                }
            }
            // Orphan inners (close-master result shows master first already)
            val orphan = inners.filter { it.parentCode == null }
            orphan.take(5).forEach { k ->
                QrLabelCard(code = k.packageCode, kind = "INNER", detail = k.contents ?: "", payload = k.payload, vm = vm, context = context, compact = true)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPrev, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Paperwork ›") }
        }
    }
}

@Composable
private fun QrLabelCard(code: String, kind: String, detail: String, payload: String, vm: SenderViewModel, context: Context, compact: Boolean = false) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            QrImage(payload, modifier = Modifier.size(if (compact) 84.dp else 128.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
                Text(payload.take(42) + "…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("qr", payload))
                    vm.status = "Copied $code payload."
                }) { Text("Copy payload", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// ------------------------------------------------------- paperwork (VLM)

@Composable
private fun DocStep(vm: SenderViewModel, onPrev: () -> Unit, context: Context) {
    val scope = rememberCoroutineScope()
    val repo = remember { VeriTransitRepo.get(context) }
    val settings = remember { DeviceSettings(context) }
    var kind by remember { mutableStateOf(DocumentKind.INVOICE) }
    var reading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<InspectorAi.DocumentReading?>(null) }
    val camera = remember { EvidenceCamera() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Photograph the PO / invoice / E-Way Bill — the on-device VLM reads it, you confirm, then it attaches to ${vm.shipmentRef.ifBlank { "the shipment" }}.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DocumentKind.PO, DocumentKind.INVOICE, DocumentKind.EWB).forEach { k ->
                    OutlinedButton(onClick = { kind = k }) { Text(k.name, fontWeight = if (kind == k) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }
        item {
            if (NpuEngine.isReady) {
                EvidenceViewfinder(camera, Modifier.fillMaxWidth().height(300.dp))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            reading = true
                            result = null
                            pending = null
                            try {
                                val frame = camera.capture(context, EvidenceCamera.Fit.CONTAIN)
                                if (frame == null) {
                                    result = "Camera shot failed."
                                } else {
                                    InspectorAi.readDocument(frame.absolutePath, kind)
                                        .onSuccess {
                                            pending = it
                                            result = if (it.usable) "Read: doc ${it.docNo.ifBlank { "?" }} · ${it.lines.size} lines · conf ${it.confidence}. Confirm below."
                                            else "Nothing legible — retake with the page filling the frame."
                                        }
                                        .onFailure { result = "VLM read failed: ${it.message}" }
                                }
                            } finally {
                                reading = false
                            }
                        }
                    },
                    enabled = camera.ready && !reading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (reading) "Reading on NPU…" else "Capture & read ${kind.name}  (VLM)", modifier = Modifier.padding(2.dp)) }
            } else {
                Text("NPU model not loaded — paperwork attaches after Settings → On-device AI download. The QR flow above works without it.", style = MaterialTheme.typography.bodySmall)
            }
        }
        pending?.let { p ->
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Confirm before it counts", fontWeight = FontWeight.Bold)
                        Text("Doc no: ${p.docNo.ifBlank { "—" }} · Date: ${p.date.ifBlank { "—" }}")
                        Text("Seller: ${p.sellerName}  Buyer: ${p.buyerName}", style = MaterialTheme.typography.bodySmall)
                        p.lines.take(6).forEach { l -> Text("• ${l.description} — qty ${l.qty} @ ${l.rate}", style = MaterialTheme.typography.bodySmall) }
                        Text("Taxable ₹${p.taxableValue}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    val fact = p.toFact(kind)
                                    val json = Json { encodeDefaults = true }
                                    repo.saveDocumentFact(
                                        DocumentFactEntity(vm.shipmentRef, kind.name, p.docNo.ifBlank { "NOP-${System.currentTimeMillis() % 10000}" }, p.date.ifBlank { null }, json.encodeToString(fact), p.confidence.toDouble(), DocumentReader.DEVICE_NPU.name, true)
                                    )
                                    // Best-effort server attach; the Room row is the record.
                                    settings.serverUrl?.let { base ->
                                        val client = ApiClient(base, settings.apiKey, settings.officerName)
                                        try {
                                            client.attachDocument(vm.shipmentRef, ShipmentDocument(shipmentRef = vm.shipmentRef, kind = kind, docNo = p.docNo, docDate = p.date.ifBlank { null }, fact = fact, readBy = DocumentReader.DEVICE_NPU, confidence = p.confidence.toDouble()))
                                            result = "Attached + synced to server."
                                        } catch (_: Throwable) {
                                            result = "Saved on phone — syncs with the outbox."
                                        } finally {
                                            client.close()
                                        }
                                    } ?: run { result = "Saved on phone (no server configured)." }
                                    pending = null
                                }
                            }) { Text("Confirm & attach") }
                            OutlinedButton(onClick = { pending = null }) { Text("Retake") }
                        }
                    }
                }
            }
        }
        result?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPrev, modifier = Modifier.weight(1f)) { Text("‹ QRs") }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                IconsBox(Icons.Rounded.AddBox, "QRs print first")
                Spacer(Modifier.width(8.dp))
                IconsBox(Icons.Rounded.QrCode2, "DB before paper")
                Spacer(Modifier.width(8.dp))
                IconsBox(Icons.Rounded.Description, "VLM + confirm")
            }
        }
    }
}

@Composable
private fun IconsBox(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        androidx.compose.material3.Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
