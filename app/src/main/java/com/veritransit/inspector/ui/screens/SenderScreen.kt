package com.veritransit.inspector.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.veritransit.inspector.data.VeriTransitRepo
import kotlinx.coroutines.launch

/**
 * Demo sender: 3 fields pre-filled, one tap, QRs appear.
 * No login, no server, no paperwork — labels are signed on this phone
 * so the Receive tab verifies them offline.
 */
@Composable
fun SenderScreen(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var supplier by remember { androidx.compose.runtime.mutableStateOf("Kumar Electronics") }
    var buyer by remember { androidx.compose.runtime.mutableStateOf("Zen Digital") }
    var item by remember { androidx.compose.runtime.mutableStateOf("LED panel 32in") }
    var cartons by remember { androidx.compose.runtime.mutableStateOf("3") }
    var busy by remember { androidx.compose.runtime.mutableStateOf(false) }
    var result by remember { androidx.compose.runtime.mutableStateOf<VeriTransitRepo.QuickSend?>(null) }
    var error by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val send: () -> Unit = {
        val n = cartons.toIntOrNull()
        when {
            supplier.isBlank() || buyer.isBlank() || item.isBlank() ->
                error = "Fill supplier, buyer and item."
            n == null || n < 1 || n > 50 -> error = "Cartons must be 1–50."
            else -> {
                busy = true; error = null
                scope.launch {
                    try {
                        // Bare minimum: names + item + count. Rate/vehicle/route
                        // default — the demo cares about QRs, not paperwork.
                        result = VeriTransitRepo.get(context).sendQuickShipment(
                            supplier.trim(), buyer.trim(), item.trim(), n,
                            0.0, null, null, null,
                        )
                        onToast("Shipment ${result?.ref} created — show the QRs")
                    } catch (t: Throwable) {
                        error = t.message ?: "Could not create the shipment"
                    } finally { busy = false }
                }
            }
        }
    }

    val created = result
    if (created != null) {
        QrCarousel(created) { result = null }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(20.dp)
    ) {
        Text("Send a shipment", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Step 1 of 2 — one tap creates signed QRs. No login, works offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))

        Field("From (supplier)", supplier, { supplier = it }, "Kumar Electronics")
        Field("To (receiver)", buyer, { buyer = it }, "Zen Digital")
        Field("What's inside", item, { item = it }, "LED panel 32in")
        Field("Cartons", cartons, { cartons = it }, "3")

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = send, enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (busy) "Creating…" else "Create & show QRs",
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "QRs are auto-generated and signed on this phone. Go to Receive to scan them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = value, onValueChange = onChange, placeholder = { Text(placeholder) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
    }
    Spacer(Modifier.height(12.dp))
}

/** Full-screen, one QR per carton — big enough for another phone's camera. */
@Composable
private fun QrCarousel(send: VeriTransitRepo.QuickSend, onDone: () -> Unit) {
    var index by remember { androidx.compose.runtime.mutableStateOf(0) }
    if (send.payloads.isEmpty()) {
        // Server-side guard (1..500) makes this near-unreachable; kept as a rail.
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No labels were issued for ${send.ref}.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding().padding(20.dp),
        horizontalAlignment = CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(send.ref, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Carton ${index + 1} of ${send.payloads.size} — let the receiver scan this",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                // Encoding happens off the main thread — a 900 px matrix costs
                // real frames, and a demo that janks looks broken.
                val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, send.payloads[index]) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        qrBitmap(send.payloads[index], 900).asImageBitmap()
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Label QR ${send.payloads[index]}",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(send.payloads[index], fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1)

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (send.payloads.size > 1) {
                OutlinedButton(
                    onClick = { if (index > 0) index-- },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("◀ Prev") }
                OutlinedButton(
                    onClick = { if (index < send.payloads.lastIndex) index++ },
                    enabled = index < send.payloads.lastIndex,
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("Next ▶") }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("New shipment")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Pure-ZXing QR rendering — no camera needed to *show* a code, only to read one. */
private fun qrBitmap(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size)
        pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
