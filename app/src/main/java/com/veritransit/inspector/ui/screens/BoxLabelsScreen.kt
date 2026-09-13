package com.veritransit.inspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.InnerBox
import com.veritransit.inspector.data.MasterBox
import com.veritransit.inspector.ui.components.FieldLabel
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.QrCodeImage
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.StatusChip
import com.veritransit.inspector.ui.components.StepBadge
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

/**
 * Sender step 3: a QR label for the master box and one for every box inside
 * it. [onFinish] is null when the labels are reopened from Home — the box is
 * already packed, and printing is all that is left to do.
 */
@Composable
fun BoxLabelsScreen(
    box: MasterBox,
    onPrint: () -> Unit,
    onFinish: (() -> Unit)?,
    onBack: () -> Unit,
) {
    FlowScaffold(
        bottomBar = {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Print", onPrint, icon = Icons.Rounded.Print, modifier = Modifier.weight(1f).fillMaxHeight())
                if (onFinish != null) {
                    PrimaryButton("Finish Packing", onFinish, icon = Icons.Rounded.Check, modifier = Modifier.weight(1.6f))
                }
            }
        },
    ) {
        FlowHeader(
            title = "QR Labels",
            subtitle = "${box.id} · ${box.boxes.size + 1} labels",
            onBack = onBack,
            trailing = if (onFinish != null) ({ StepBadge(3) }) else null,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MasterLabelCard(box)
            SectionLabel(
                "Boxes Inside",
                trailing = {
                    Text(
                        "${box.boxes.size} ${if (box.boxes.size == 1) "box" else "boxes"} · ${box.units} units",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                        color = VT.Muted,
                    )
                },
            )
            box.boxes.forEach { inner -> InnerLabelCard(box, inner) }
            Text(
                "Receive → Scan Label opens this packing list from any of these codes. On the dock count, a box's " +
                    "code books what its label declares, once per box; the master box's code counts nothing.",
                style = MaterialTheme.typography.bodySmall,
                color = VT.Muted,
            )
        }
    }
}

@Composable
private fun MasterLabelCard(box: MasterBox) {
    VTCard {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("Master box", bg = Color(0xFFFDF2F4), fg = VT.Primary, line = Color(0xFFF3C6D0))
                Spacer(Modifier.weight(1f))
                Text(
                    "${box.boxes.size} inside · ${box.units} units",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    color = VT.Muted,
                )
            }
            Spacer(Modifier.height(14.dp))
            QrCodeImage(
                box.masterLabel().payload(),
                Modifier.size(208.dp).border(1.dp, VT.Hairline),
                description = "QR code for master box ${box.id}",
            )
            Spacer(Modifier.height(12.dp))
            Text(box.id, style = mono().data.copy(fontSize = 20.sp), color = VT.Ink)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                LabelField("Purchase Order", box.purchaseOrderId, Modifier.weight(1f))
                LabelField("Packing List", box.packingListId, Modifier.weight(1f), end = true)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                LabelField("Supplier", box.supplier, Modifier.weight(1f), monospace = false)
                LabelField("Ship To", box.shipTo, Modifier.weight(1f), end = true, monospace = false)
            }
        }
    }
}

@Composable
private fun InnerLabelCard(master: MasterBox, box: InnerBox) {
    VTCard {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            QrCodeImage(
                master.boxLabel(box).payload(),
                Modifier.size(128.dp).border(1.dp, VT.Hairline),
                description = "QR code for box ${box.seq} of ${master.boxes.size}, ${box.id}",
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "BOX ${box.seq} OF ${master.boxes.size}",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.08.sp),
                    color = VT.Primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(box.id, style = mono().data, color = VT.Ink)
                Text("In ${master.id}", style = mono().dataSmall, color = VT.Muted)
                Spacer(Modifier.height(10.dp))
                box.lines.forEach { line ->
                    Text(
                        "${line.qty} × ${master.itemNames[line.sku] ?: line.sku}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VT.Slate,
                    )
                    Text(line.sku, style = mono().dataSmall, color = VT.Muted, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun LabelField(label: String, value: String, modifier: Modifier, end: Boolean = false, monospace: Boolean = true) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        FieldLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = if (monospace) mono().dataSmall else MaterialTheme.typography.titleSmall,
            color = VT.Ink,
            textAlign = if (end) TextAlign.End else TextAlign.Start,
        )
    }
}
