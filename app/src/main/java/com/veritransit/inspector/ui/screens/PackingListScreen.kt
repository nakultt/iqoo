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
import com.veritransit.inspector.ai.ReceivingAi
import com.veritransit.inspector.ui.ReceivingFlowState
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
fun PackingListScreen(
    flow: ReceivingFlowState,
    onToggleCartonCount: () -> Unit,
    onToggleLabel: () -> Unit,
    onStartCount: () -> Unit,
    onBack: () -> Unit,
) {
    val list = flow.packingList
    val sections = flow.listSections
    val ready = flow.cartonCountOk && flow.labelOk

    FlowScaffold(
        bottomBar = {
            Column {
                AnimatedVisibility(visible = !ready, enter = fadeIn() + expandVertically(), exit = fadeOut()) {
                    Text(
                        "Complete both pre-count checks to continue",
                        style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                        color = VT.Muted,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PrimaryButton(
                    text = "Start Dock Count (${list?.totalUnits ?: 0} Units Packed)",
                    enabled = ready && list != null,
                    trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = onStartCount,
                )
            }
        },
    ) {
        FlowHeader(
            title = "Packing List",
            subtitle = "PO ${list?.purchaseOrderId ?: "—"}",
            onBack = onBack,
            trailing = { StepBadge(2) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard(
                list = list,
                headerLow = (sections?.header ?: 1f) < ReceivingAi.LOW_SECTION_CONFIDENCE,
                supplierLow = (sections?.supplier ?: 1f) < ReceivingAi.LOW_SECTION_CONFIDENCE,
            )

            Column {
                SectionLabel(
                    "Packed Lines",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if ((sections?.items ?: 1f) < ReceivingAi.LOW_SECTION_CONFIDENCE) {
                                LowConfidenceMark()
                            }
                            Text(
                                "${list?.items?.size ?: 0} SKUs • ${list?.totalUnits ?: 0} Units",
                                style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                                color = VT.Muted,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                VTCard {
                    Column {
                        list?.items?.forEachIndexed { i, item ->
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
                                    Text(
                                        if (item.sku.isNotBlank()) "${item.sku} · ${item.detail}" else item.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = VT.Muted,
                                    )
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
                            if (i < (list?.items?.size ?: 0) - 1) {
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
                text = "Dock note: the supplier flagged a substitution on one carton. Check the carton labels against the packed lines before counting.",
            )

            Column {
                SectionLabel("Pre-Count Checks")
                Spacer(Modifier.height(10.dp))
                VTCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        VTCheckbox(
                            checked = flow.cartonCountOk,
                            onToggle = onToggleCartonCount,
                            label = "Carton Count Matches the Delivery Note",
                            sub = "Seal/tamper tag #VT-9921",
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
                        VTCheckbox(
                            checked = flow.labelOk,
                            onToggle = onToggleLabel,
                            label = "Carton Labels Readable & Matched to SKUs",
                            sub = "Batch/lot printed on each label",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    list: com.veritransit.inspector.data.PackingList?,
    headerLow: Boolean,
    supplierLow: Boolean,
) {
    VTCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Purchase Order")
                    Spacer(Modifier.height(5.dp))
                    Text(list?.purchaseOrderId ?: "—", style = mono().data.copy(fontSize = 17.sp), color = VT.Ink)
                    if (headerLow) {
                        Spacer(Modifier.height(4.dp))
                        LowConfidenceMark()
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    FieldLabel("Units Packed")
                    Spacer(Modifier.height(5.dp))
                    Text("${list?.totalUnits ?: 0} Units", style = mono().data.copy(fontSize = 17.sp), color = VT.Primary)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
            Spacer(Modifier.height(14.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Packing List")
                    Spacer(Modifier.height(5.dp))
                    Text(list?.packingListId ?: "—", style = mono().data, color = VT.Ink)
                }
                Column(Modifier.weight(1.2f)) {
                    FieldLabel("Supplier")
                    Spacer(Modifier.height(5.dp))
                    Text(list?.supplier ?: "—", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                    Text(list?.goods ?: "", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                    if (supplierLow) {
                        Spacer(Modifier.height(4.dp))
                        LowConfidenceMark()
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
            Spacer(Modifier.height(14.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Receiving At")
                    Spacer(Modifier.height(5.dp))
                    Text(list?.dock ?: "—", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("Carrier")
                    Spacer(Modifier.height(5.dp))
                    Text(list?.carrier ?: "—", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                }
            }
        }
    }
}

/**
 * Amber flag under a section the model itself was unsure about. Only ever
 * shown when the model reported a confidence — an unreported section is not
 * the same as a confident one, but inventing a warning for it would cry wolf
 * on every demo and manual packing list.
 */
@Composable
private fun LowConfidenceMark() {
    Text(
        "LOW CONFIDENCE — VERIFY",
        style = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.08.sp),
        color = VT.Amber,
    )
}
