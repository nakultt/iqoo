package com.veritransit.inspector.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.ai.OpenRouterClient
import com.veritransit.inspector.ui.components.FieldLabel
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.PulseDot
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import java.util.Locale

/**
 * Manages the on-device model: pull the chipset bundle, make it resident on the
 * NPU, and show what the last inference actually cost.
 */
@Composable
fun NpuScreen(onBack: () -> Unit, onToast: (String) -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val status = NpuEngine.status

    LaunchedEffect(Unit) { NpuEngine.initialize(context) }

    FlowScaffold(
        bottomBar = {
            when (status) {
                NpuEngine.Status.NOT_DOWNLOADED -> PrimaryButton(
                    "Download model · ${gib(NpuEngine.BUNDLE_BYTES)}",
                    { NpuEngine.download(context) },
                    icon = Icons.Rounded.CloudDownload,
                )

                NpuEngine.Status.DOWNLOADING -> SecondaryButton(
                    "Cancel download",
                    { NpuEngine.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                )

                NpuEngine.Status.DOWNLOADED -> PrimaryButton(
                    "Load onto NPU",
                    { NpuEngine.load() },
                    icon = Icons.Rounded.Bolt,
                )

                NpuEngine.Status.READY, NpuEngine.Status.BUSY -> SecondaryButton(
                    "Unload from NPU",
                    {
                        NpuEngine.unload()
                        onToast("Model unloaded — inspections fall back to demo data")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                NpuEngine.Status.ERROR -> PrimaryButton(
                    "Retry",
                    { NpuEngine.retry(context) },
                )

                else -> Unit
            }
        },
    ) {
        FlowHeader(
            title = "On-device AI",
            subtitle = "Vision & language accelerator",
            onBack = onBack,
        )

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            VTCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (NpuEngine.isReady) VT.EmeraldBg else VT.Inset),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Memory,
                                null,
                                tint = if (NpuEngine.isReady) VT.Emerald else VT.Muted,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                NpuEngine.DISPLAY_NAME,
                                style = MaterialTheme.typography.titleMedium,
                                color = VT.Ink,
                            )
                            Text(
                                "Qualcomm GenieX · QAIRT · w4a16",
                                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
                                color = VT.Muted,
                            )
                        }
                        StatusBadge(status)
                    }

                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
                    Spacer(Modifier.height(12.dp))

                    SpecRow("Compute unit", "Hexagon NPU")
                    SpecRow("Chipset", NpuEngine.chipset ?: "detecting…")
                    SpecRow(
                        "Context",
                        "${NpuEngine.bundleContextTokens} tokens" +
                            if (NpuEngine.contextFromBundle) " · bundle" else " · default",
                    )
                    SpecRow("Bundle", gib(NpuEngine.BUNDLE_BYTES))
                    SpecRow("Residency", "Auto-release · auto-reload")
                    SpecRow(
                        "Engine mode",
                        when (LlmGateway.mode) {
                            LlmGateway.Mode.CLOUD -> "${OpenRouterClient.DISPLAY_NAME} · cloud first"
                            LlmGateway.Mode.LOCAL -> "Local NPU first"
                        },
                    )
                    SpecRow(
                        "Backup engine",
                        when (LlmGateway.mode) {
                            LlmGateway.Mode.CLOUD ->
                                if (NpuEngine.isReady) {
                                    "Local NPU"
                                } else {
                                    "Local NPU · not loaded"
                                }
                            LlmGateway.Mode.LOCAL ->
                                if (OpenRouterClient.isConfigured) {
                                    "${OpenRouterClient.DISPLAY_NAME} · OpenRouter"
                                } else {
                                    "Not configured"
                                }
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = status == NpuEngine.Status.DOWNLOADING,
                enter = fadeIn(tween(200)),
                exit = fadeOut(),
            ) {
                VTCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(VT.Azure, 8.dp)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                "Pulling chipset bundle",
                                style = MaterialTheme.typography.titleSmall,
                                color = VT.Ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${NpuEngine.downloadPercent}%",
                                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                color = VT.Azure,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        ProgressBar(NpuEngine.downloadPercent / 100f)
                        Spacer(Modifier.height(9.dp))
                        Text(
                            if (NpuEngine.totalBytes > 0) {
                                "${gib(NpuEngine.downloadedBytes)} of ${gib(NpuEngine.totalBytes)}"
                            } else {
                                "Resolving assets…"
                            },
                            style = TextStyle(fontFamily = Mono, fontSize = 11.5.sp),
                            color = VT.Muted,
                        )
                    }
                }
            }

            NpuEngine.lastError?.let { err ->
                VTCard(bg = VT.CrimsonBg, border = false) {
                    Column(Modifier.border(1.dp, VT.CrimsonLine, RoundedCornerShape(8.dp)).padding(14.dp)) {
                        FieldLabel("Engine error", color = VT.Crimson)
                        Spacer(Modifier.height(6.dp))
                        Text(err, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7F1D1D), lineHeight = 19.sp)
                    }
                }
            }

            NpuEngine.lastProfile?.let { p ->
                SectionLabel("Last inference")
                VTCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Speed, null, tint = VT.Azure, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(9.dp))
                            Text("Measured on this device", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                        }
                        Spacer(Modifier.height(12.dp))
                        SpecRow("Time to first token", "${fmt(p.ttftMs)} ms")
                        SpecRow("Prefill", "${fmt(p.prefillSpeed)} tok/s")
                        SpecRow("Decode", "${fmt(p.decodingSpeed)} tok/s")
                        SpecRow("Prompt / generated", "${p.promptTokens} / ${p.generatedTokens}")
                    }
                }
            }

            if (NpuEngine.isReady) {
                SecondaryButton(
                    "Chat with the model",
                    onOpenChat,
                    icon = Icons.Rounded.Forum,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionLabel("How it is used")
            VTCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    UseRow("Step 1", "Reads the E-Way Bill straight off the paper — number, vehicle, route, declared lines.")
                    UseRow("Step 3", "Counts visible cargo against the declared manifest and flags anything undeclared.")
                    UseRow("Verdict", "Drafts the statutory remarks from the reconciliation result.")
                }
            }

            NoticeStrip(
                "Weights stay on the handset. The bundle downloads once from Qualcomm's " +
                    "asset store; after that every inspection can run with the radio off. " +
                    "The engine is switchable in Settings → AI engine — local NPU or " +
                    OpenRouterClient.DISPLAY_NAME + " over OpenRouter — and each reply " +
                    "always shows which one answered.",
            )

            if (status == NpuEngine.Status.DOWNLOADED) {
                SecondaryButton(
                    "Delete downloaded model",
                    {
                        NpuEngine.deleteBundle()
                        onToast("Model bundle removed")
                    },
                    icon = Icons.Rounded.DeleteOutline,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: NpuEngine.Status) {
    val (label, fg, bg) = when (status) {
        NpuEngine.Status.COLD, NpuEngine.Status.INITIALIZING -> Triple("STARTING", VT.Muted, VT.Inset)
        NpuEngine.Status.NOT_DOWNLOADED -> Triple("NOT INSTALLED", VT.Muted, VT.Inset)
        NpuEngine.Status.DOWNLOADING -> Triple("DOWNLOADING", VT.Azure, VT.AzureBg)
        NpuEngine.Status.DOWNLOADED -> Triple("INSTALLED", VT.Amber, VT.AmberBg)
        NpuEngine.Status.LOADING -> Triple("LOADING", VT.Azure, VT.AzureBg)
        NpuEngine.Status.READY -> Triple("ON NPU", VT.Emerald, VT.EmeraldBg)
        NpuEngine.Status.BUSY -> Triple("INFERRING", VT.Azure, VT.AzureBg)
        NpuEngine.Status.ERROR -> Triple("ERROR", VT.Crimson, VT.CrimsonBg)
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.06.sp),
            color = fg,
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "dl")
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(VT.Inset),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(VT.Azure),
        )
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VT.Muted, modifier = Modifier.weight(1f))
        Text(
            value,
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
            color = VT.Slate,
        )
    }
}

@Composable
private fun UseRow(tag: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(VT.Inset)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(
                tag.uppercase(),
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp),
                color = VT.Slate,
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = VT.Slate, lineHeight = 19.sp)
    }
}

private fun gib(bytes: Long): String =
    String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)

private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
