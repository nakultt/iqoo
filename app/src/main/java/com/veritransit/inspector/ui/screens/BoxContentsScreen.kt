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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.BoxLabel
import com.veritransit.inspector.data.BoxLine
import com.veritransit.inspector.data.BoxPacking
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.ui.SenderFlowState
import com.veritransit.inspector.ui.components.CountStepper
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.StepBadge
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

/**
 * Sender step 2: what each box inside the master box holds. It opens on an
 * even split of the packing list; any line can be moved by hand, and the card
 * on top keeps the boxes honest against the list.
 */
@Composable
fun BoxContentsScreen(
    sender: SenderFlowState,
    onGenerate: () -> Unit,
    onBack: () -> Unit,
) {
    val preset = sender.preset
    val contents = sender.contents
    val empty = BoxPacking.emptyBoxes(contents)
    val lines = BoxPacking.reconcile(preset.items, contents)
    val packed = lines.sumOf { it.received }
    val listed = lines.sumOf { it.expected }
    val skus = BoxPacking.packable(preset.items).map { it.sku }
    val names = preset.items.associate { it.sku to it.name }

    FlowScaffold(
        bottomBar = {
            Column {
                if (empty.isNotEmpty()) {
                    Text(
                        (if (empty.size == 1) "Box ${empty.single()} is empty" else "Boxes ${empty.joinToString()} are empty") +
                            " — put something in, or go back and pack fewer boxes",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                        color = VT.Amber,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PrimaryButton(
                    text = "Generate ${contents.size + 1} QR Labels",
                    onClick = onGenerate,
                    enabled = contents.isNotEmpty() && empty.isEmpty(),
                    icon = Icons.Rounded.QrCode2,
                )
            }
        },
    ) {
        FlowHeader(
            title = "Box Contents",
            subtitle = "${sender.masterId} · ${contents.size} ${if (contents.size == 1) "box" else "boxes"} inside",
            onBack = onBack,
            trailing = { StepBadge(2) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                SectionLabel(
                    "Against the Packing List",
                    trailing = {
                        Text(
                            "$packed of $listed units packed",
                            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                            color = VT.Muted,
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                VTCard {
                    Column {
                        lines.forEachIndexed { i, line ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(line.name, style = MaterialTheme.typography.titleSmall, color = VT.Ink, maxLines = 1)
                                    Text(
                                        "${line.sku} · ${line.received} of ${line.expected} packed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = VT.Muted,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                CountPill(line.status, line.expected, line.received)
                            }
                            if (i < lines.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(VT.Hairline))
                            }
                        }
                    }
                }
            }

            if (lines.any { it.status != ItemStatus.MATCHED }) {
                NoticeStrip(
                    "These boxes hold $packed units against the packing list's $listed. Each label declares " +
                        "what its own box holds, and the receiving dock counts that against the packing list.",
                )
            }

            Column {
                SectionLabel(
                    "Boxes Inside",
                    trailing = {
                        Text(
                            "Split evenly",
                            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                            color = VT.Primary,
                            modifier = Modifier.clickable { sender.replan() },
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    contents.forEachIndexed { index, box ->
                        BoxCard(
                            seq = index + 1,
                            of = contents.size,
                            id = BoxPacking.innerBoxId(sender.masterId, index + 1),
                            box = box,
                            skus = skus,
                            names = names,
                            onAdjust = { sku, delta -> sender.adjust(index, sku, delta) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxCard(
    seq: Int,
    of: Int,
    id: String,
    box: List<BoxLine>,
    skus: List<String>,
    names: Map<String, String>,
    onAdjust: (sku: String, delta: Int) -> Unit,
) {
    val units = box.sumOf { it.qty }
    VTCard(bg = if (units == 0) VT.AmberBg else VT.Surface) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "BOX $seq OF $of",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.08.sp),
                    color = VT.Primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(id, style = mono().dataSmall, color = VT.Slate, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    if (units == 0) "Empty" else "$units units",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                    color = if (units == 0) VT.Amber else VT.Ink,
                )
            }
            Spacer(Modifier.height(6.dp))
            skus.forEach { sku ->
                val qty = box.firstOrNull { it.sku == sku }?.qty ?: 0
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            names[sku] ?: sku,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (qty > 0) VT.Ink else VT.Muted,
                            maxLines = 1,
                        )
                        Text(sku, style = mono().dataSmall, color = VT.Muted)
                    }
                    CountStepper(
                        value = qty,
                        onValueChange = { onAdjust(sku, it - qty) },
                        range = 0..BoxLabel.MAX_QTY,
                        what = "$sku in box $seq",
                    )
                }
            }
        }
    }
}
