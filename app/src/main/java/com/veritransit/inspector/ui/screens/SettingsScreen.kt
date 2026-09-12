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
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.data.Repo
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.VTCard
import kotlinx.coroutines.launch
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
        SectionLabel("Voice & gate announcements")
        Spacer(Modifier.height(10.dp))
        VTCard {
            VoiceRows()
        }
        Spacer(Modifier.height(22.dp))
        SectionLabel("Kokoro neural voice")
        Spacer(Modifier.height(10.dp))
        VTCard {
            KokoroRows()
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
                InfoRow("Data storage", "On-device only")
                Divider()
                InfoRow("Inference", "Snapdragon NPU · offline")
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
    Box(        Modifier
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

/**
 * Voice & gate announcements — every switch here is live immediately, works
 * offline, and the master switch silences everything including the gate.
 */
@Composable
private fun VoiceRows() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { com.veritransit.inspector.data.DeviceSettings(context) }
    var master by remember { mutableStateOf(prefs.voiceAlerts) }
    var gate by remember { mutableStateOf(prefs.gateAnnouncements) }
    var passes by remember { mutableStateOf(prefs.announcePasses) }
    var tgVoice by remember { mutableStateOf(prefs.telegramVoice) }

    Column {
        ToggleRow(
            Icons.Rounded.Mic, "Voice announcements",
            "Kokoro voice speaks every suspect / rejected scan",
            master,
        ) {
            master = it
            prefs.voiceAlerts = it
            if (it) com.veritransit.inspector.ai.KokoroVoice.speakSystem("Voice announcements on.")
        }
        Divider()
        ToggleRow(
            Icons.Rounded.VolumeUp, "Gate auto-announcement",
            "Speak discrepant inspection results at the gate",
            gate,
        ) {
            gate = it
            prefs.gateAnnouncements = it
        }
        Divider()
        ToggleRow(
            Icons.Rounded.Vibration, "Announce verified scans",
            "Also speak passes — noisy on rapid scanning",
            passes,
        ) {
            passes = it
            prefs.announcePasses = it
        }
        Divider()
        ToggleRow(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Telegram voice alerts",
            "Voice note on tamper → supervisor Telegram chat",
            tgVoice,
        ) {
            tgVoice = it
            prefs.telegramVoice = it
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Voice engine", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                Text(
                    com.veritransit.inspector.ai.KokoroVoice.voiceLabel,
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    color = VT.Muted,
                )
            }
        }
    }
}

/**
 * Kokoro neural voice console: 92 MB quantized model + voice pack download
 * once, then verdicts speak in true Kokoro voices with no network. Until the
 * download finishes the phone speaks over system TTS — the switches above
 * keep working either way.
 */
@Composable
private fun KokoroRows() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { com.veritransit.inspector.data.DeviceSettings(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var voice by remember { mutableStateOf(prefs.kokoroVoice) }
    var modelPct by remember { mutableStateOf(if (com.veritransit.inspector.ai.KokoroDownload.hasModel(context)) 100 else 0) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var hasVoice by remember { mutableStateOf(com.veritransit.inspector.ai.KokoroDownload.hasVoice(context, voice)) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text("Neural voice (Kokoro-82M, on-device)", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                modelPct >= 100 && hasVoice -> "Ready — verdicts speak as Kokoro $voice, offline."
                modelPct >= 100 -> "Model on disk — pick a voice below to finish."
                modelPct > 0 -> "Model downloading… $modelPct%"
                else -> "Not installed — 92 MB one-time download, then true Kokoro voices."
            },
            style = MaterialTheme.typography.bodySmall, color = VT.Muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.veritransit.inspector.ai.KokoroDownload.VOICES.forEach { v ->
                SecondaryButton(
                    (if (v == voice) "● " else "") + v.removePrefix("af_").replaceFirstChar { it.uppercase() },
                    {
                        voice = v
                        prefs.kokoroVoice = v
                        hasVoice = com.veritransit.inspector.ai.KokoroDownload.hasVoice(context, v)
                        scope.launch {
                            if (!hasVoice) {
                                note = "Fetching $v voice pack…"
                                hasVoice = com.veritransit.inspector.ai.KokoroDownload.fetchVoice(context, v)
                                note = if (hasVoice) "$v ready." else "Voice fetch failed — check network."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                if (modelPct >= 100) "Model ✓" else if (busy) "Downloading $modelPct%" else "Download model (92 MB)",
                {
                    if (modelPct >= 100 || busy) return@SecondaryButton
                    busy = true
                    scope.launch {
                        val ok = com.veritransit.inspector.ai.KokoroDownload.fetchModel(context) { modelPct = it }
                        busy = false
                        note = if (ok) "Model ready — Kokoro speaks from here on." else "Download failed — retry on Wi-Fi."
                        if (ok && !hasVoice) {
                            hasVoice = com.veritransit.inspector.ai.KokoroDownload.fetchVoice(context, voice)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                "Play test",
                {
                    com.veritransit.inspector.ai.KokoroVoice.testAnnouncement(context)
                    note = "Speaking a sample reject alert…"
                },
                modifier = Modifier.weight(1f),
            )
        }
        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = VT.Muted)
        }
    }
}
