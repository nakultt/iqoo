package com.veritransit.inspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.ai.OpenRouterClient
import com.veritransit.inspector.data.Repo
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT

class AppSettings {
    var sound by androidx.compose.runtime.mutableStateOf(true)
    var haptics by androidx.compose.runtime.mutableStateOf(true)
    var autoSync by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onToast: (String) -> Unit,
    onOpenNpu: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VT.Alabaster)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge, color = VT.Ink)
                Text("Field console preferences", style = MaterialTheme.typography.bodyMedium, color = VT.Muted)
            }
        }
        VTCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(VT.Primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("SJ", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp), color = Color.White)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(Repo.INSPECTOR, style = MaterialTheme.typography.titleMedium, color = VT.Ink)
                    Text(
                        "Badge ${Repo.BADGE} · ${Repo.STATION}",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
                        color = VT.Muted,
                    )
                }
                Icon(Icons.Rounded.Person, null, tint = VT.Faint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        SectionLabel("Inference")
        Spacer(Modifier.height(10.dp))
        VTCard {
            NpuRow(onClick = onOpenNpu)
        }
        Spacer(Modifier.height(22.dp))
        SectionLabel("Preferences")
        Spacer(Modifier.height(10.dp))
        VTCard {
            Column {
                ToggleRow(Icons.Rounded.VolumeUp, "Scan sound", "Beep on capture & verdict", settings.sound) { settings.sound = it }
                Divider()
                ToggleRow(Icons.Rounded.Vibration, "Haptic feedback", "Subtle taps on state changes", settings.haptics) { settings.haptics = it }
                Divider()
                ToggleRow(Icons.Rounded.Sync, "Auto-sync records", "Upload vault over mobile data", settings.autoSync) { settings.autoSync = it }
            }
        }
        Spacer(Modifier.height(22.dp))
        SectionLabel("About")
        Spacer(Modifier.height(10.dp))
        VTCard {
            Column {
                InfoRow("App version", "1.1.0")
                Divider()
                InfoRow("Build", "VT-100 · Field Release")
                Divider()
                InfoRow(
                    "Data storage",
                    if (LlmGateway.cloudReady) {
                        "On-device · fallback turns send the prompt & photo"
                    } else {
                        "On-device only"
                    },
                )
                Divider()
                InfoRow(
                    "Inference",
                    if (LlmGateway.cloudReady) {
                        "Snapdragon NPU · " + OpenRouterClient.DISPLAY_NAME + " fallback"
                    } else {
                        "Snapdragon NPU · offline"
                    },
                )
            }
        }
        Spacer(Modifier.height(26.dp))
        SecondaryButton("Sign Out", { onToast("Signed out — demo build keeps you at the gate") }, icon = Icons.AutoMirrored.Rounded.Logout, modifier = Modifier.fillMaxWidth())
    }
}

/** Entry point to the on-device model manager, with live engine status. */
@Composable
private fun NpuRow(onClick: () -> Unit) {
    val (label, tint) = when (NpuEngine.status) {
        NpuEngine.Status.READY, NpuEngine.Status.BUSY -> "Loaded on NPU" to VT.Emerald
        NpuEngine.Status.DOWNLOADING -> "Downloading ${NpuEngine.downloadPercent}%" to VT.Azure
        NpuEngine.Status.LOADING -> "Loading…" to VT.Azure
        NpuEngine.Status.DOWNLOADED -> "Installed · not loaded" to VT.Amber
        NpuEngine.Status.ERROR -> "Unavailable" to VT.Crimson
        else -> "Not installed" to VT.Muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Memory, null, tint = VT.Slate, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("On-device AI", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
            Text(
                NpuEngine.DISPLAY_NAME,
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                color = VT.Muted,
            )
        }
        Text(
            label,
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
            color = tint,
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = VT.Faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 50.dp)
            .height(1.dp)
            .background(VT.Hairline),
    )
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = VT.Slate, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = VT.Ink)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = VT.Muted)
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = VT.Primary,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE2E8F0),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = VT.Border,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = VT.Ink, modifier = Modifier.weight(1f))
        Text(value, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp), color = VT.Muted)
    }
}
