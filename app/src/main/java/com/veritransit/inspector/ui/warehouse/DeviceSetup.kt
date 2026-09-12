package com.veritransit.inspector.ui.warehouse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.veritransit.inspector.data.DeviceSettings
import com.veritransit.inspector.data.VeriTransitRepo
import com.veritransit.inspector.net.ApiClient
import kotlinx.coroutines.launch

/**
 * §9 Phase 1 — device activation (Settings → activation).
 *
 * The one-time activation code is exchanged for an API key that the device
 * stores and the server only ever holds as a digest. Everything below the
 * activation is a bootstrap: after one successful sync the phone can scan a
 * whole shift with the network unplugged.
 */
@Composable
fun DeviceSetupPanel(onSynced: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { DeviceSettings(context) }
    val repo = remember { VeriTransitRepo.get(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(settings.serverUrl ?: "http://localhost:8080") }
    var officer by remember { mutableStateOf(settings.officerName ?: "Deepak Shah") }
    var site by remember { mutableStateOf(settings.site ?: "") }
    var activationCode by remember { mutableStateOf("veritransit-pilot") }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Device setup", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Point this phone at the VeriTransit backend, activate it once, then " +
                "sync. After that the scanner works with no network at all.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Server URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = officer, onValueChange = { officer = it },
            label = { Text("Officer name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = site, onValueChange = { site = it },
            label = { Text("Site (optional — filters the bootstrap)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        if (apiKey == null) {
            OutlinedTextField(
                value = activationCode, onValueChange = { activationCode = it },
                label = { Text("One-time activation code") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        settings.serverUrl = url.trimEnd('/')
                        settings.officerName = officer
                        settings.site = site.ifBlank { null }
                        status = activate(url.trimEnd('/'), activationCode, officer, settings)
                        apiKey = settings.apiKey
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Activating…" else "Activate this device") }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Activated", style = MaterialTheme.typography.labelLarge)
                    Text(
                        apiKey!!.take(12) + "…", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        settings.serverUrl = url.trimEnd('/')
                        settings.officerName = officer
                        settings.site = site.ifBlank { null }
                        val ok = repo.refreshBootstrap()
                        status = if (ok) {
                            onSynced()
                            "Synced — shipments, packages, signing keys and risk bands cached."
                        } else {
                            "Sync failed. Check the server URL and that the backend is running."
                        }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Syncing…" else "Sync now") }

            OutlinedButton(
                onClick = { settings.apiKey = null; apiKey = null; status = "Device de-activated." },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("De-activate") }
        }

        status?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private suspend fun activate(
    url: String, code: String, officer: String, settings: DeviceSettings,
): String {
    val client = ApiClient(url, apiKey = null, officer = officer)
    return try {
        val response = client.activate(code, "Handheld ${android.os.Build.MODEL}")
        settings.apiKey = response.apiKey
        "Activated. The key is stored on this device; the server keeps only its digest."
    } catch (t: Throwable) {
        "Activation failed: ${t.message ?: t.javaClass.simpleName}"
    } finally {
        client.close()
    }
}
