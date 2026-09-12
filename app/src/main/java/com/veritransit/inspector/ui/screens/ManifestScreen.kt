package com.veritransit.inspector.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.ui.InspectionFlowState
import com.veritransit.inspector.ui.components.FieldLabel
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.StepBadge
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.components.VTCheckbox
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

@Composable
fun ManifestScreen(
    flow: InspectionFlowState,
    onToggleSeal: () -> Unit,
    onToggleDriver: () -> Unit,
    onStartScan: () -> Unit,
    onBack: () -> Unit,
) {
    val manifest = flow.manifest
    val ready = flow.sealOk && flow.driverOk

    FlowScaffold(
        bottomBar = {
            Column {
                AnimatedVisibility(visible = !ready, enter = fadeIn() + expandVertically(), exit = fadeOut()) {
                    Text(
                        "Complete both pre-scan checks to continue",
                        style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                        color = VT.Muted,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PrimaryButton(
                    text = "Start Cargo Scan (${manifest?.totalUnits ?: 0} Items Expected)",
                    enabled = ready && manifest != null,
                    trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = onStartScan,
                )
            }
        },
    ) {
        FlowHeader(
            title = "Manifest Details",
            subtitle = "Ref ${manifest?.ref ?: "#—"}",
            onBack = onBack,
            trailing = { StepBadge(2) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard(manifest?.ewb ?: "—", manifest?.totalUnits ?: 0, manifest)

            Column {
                SectionLabel(
                    "Declared Items",
                    trailing = {
                        Text(
                            "${manifest?.items?.size ?: 0} Types • ${manifest?.totalUnits ?: 0} Pcs",
                            style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                            color = VT.Muted,
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                VTCard {
                    Column {
                        manifest?.items?.forEachIndexed { i, item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(VT.Inset),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${i + 1}",
                                        style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                        color = VT.Slate,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Default, fontSize = 15.sp, color = VT.Ink), maxLines = 1)
                                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                                }
                                Spacer(Modifier.width(10.dp))
                                Box(
                                    Modifier
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
                                        .background(VT.Inset)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        "${item.expected} Units",
                                        style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                        color = VT.Slate,
                                    )
                                }
                            }
                            if (i < (manifest?.items?.size ?: 0) - 1) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 56.dp)
                                        .height(1.dp)
                                        .background(VT.Hairline),
                                )
                            }
                        }
                    }
                }
            }

            NoticeStrip(
                text = "Depot Notice: Thermal Label Printer observed during weighbridge check. Flagged for optical visual verification during scan.",
            )

            Column {
                SectionLabel("Pre-Scan Verification")
                Spacer(Modifier.height(10.dp))
                VTCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        VTCheckbox(
                            checked = flow.sealOk,
                            onToggle = onToggleSeal,
                            label = "Vehicle Physical Seal Intact",
                            sub = "Seal tag #VT-9921",
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
                        VTCheckbox(
                            checked = flow.driverOk,
                            onToggle = onToggleDriver,
                            label = "Driver Identity Verified",
                            sub = "Licence TN-38-2019-0012",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(ewb: String, units: Int, manifest: com.veritransit.inspector.data.Manifest?) {
    VTCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("E-Way Bill")
                    Spacer(Modifier.height(5.dp))
                    Text(ewb, style = mono().data.copy(fontSize = 17.sp), color = VT.Ink)
                }
                Column(horizontalAlignment = Alignment.End) {
                    FieldLabel("Expected Cargo")
                    Spacer(Modifier.height(5.dp))
                    Text("$units Units", style = mono().data.copy(fontSize = 17.sp), color = VT.Primary)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
            Spacer(Modifier.height(14.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Vehicle")
                    Spacer(Modifier.height(5.dp))
                    Text(manifest?.vehicle ?: "—", style = mono().data, color = VT.Ink)
                    Text(manifest?.vehicleModel ?: "", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                }
                Column(Modifier.weight(1.2f)) {
                    FieldLabel("Route")
                    Spacer(Modifier.height(5.dp))
                    Text(manifest?.route ?: "—", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                    Text("${manifest?.distanceKm ?: 0} km", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                }
            }
        }
    }
}
