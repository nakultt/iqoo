package com.veritransit.inspector.ui.screens

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.ReceivingRecord
import com.veritransit.inspector.data.Repo
import com.veritransit.inspector.data.relativeLabel
import com.veritransit.inspector.ui.components.OutcomeChip
import com.veritransit.inspector.ui.components.PulseDot
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.components.stagger
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

@Composable
fun HomeScreen(
    now: Long,
    onStartReceiving: () -> Unit,
    onLookup: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenReceipts: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VT.Alabaster)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = 28.dp),
    ) {
        Header()
        Spacer(Modifier.height(18.dp))
        StatusCard(Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(18.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Start Receiving", onStartReceiving, icon = Icons.Rounded.QrCodeScanner)
            SecondaryButton("Lookup Packing List", onLookup, icon = Icons.Rounded.Search, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel(
            "Recent Receipts",
            Modifier.padding(horizontal = 16.dp),
            trailing = {
                Text(
                    "Receipt Log",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                    color = VT.Muted,
                    modifier = Modifier.clickable(onClick = onOpenReceipts),
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        val recent = Repo.records.sortedByDescending { it.timestamp }.take(4)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            recent.forEachIndexed { i, record ->
                RecentCard(record, now, i, Modifier.stagger(i), onOpenRecord)
            }
        }
    }
}
@Composable
private fun Header() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(VT.Alabaster)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VT.Primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("VeriTransit", style = MaterialTheme.typography.titleLarge, color = VT.Ink)
            Text(
                "RECEIVING",
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.12.sp),
                color = VT.Muted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Repo.RECEIVER, style = MaterialTheme.typography.titleSmall, color = VT.Ink)
            Text(
                Repo.WAREHOUSE,
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                color = VT.Muted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8E7F1)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Person, null, tint = VT.Slate, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun animatedCount(target: Int): Int {
    val v by animateIntAsState(target, tween(900), label = "count")
    return v
}

@Composable
private fun StatusCard(modifier: Modifier = Modifier) {
    VTCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(VT.Emerald)
                Spacer(Modifier.width(9.dp))
                Text(
                    "ONLINE & SYNCED",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.06.sp),
                    color = VT.Emerald,
                )
                Spacer(Modifier.weight(1f))
                Text(Repo.WAREHOUSE, style = MaterialTheme.typography.bodyMedium, color = VT.Slate)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("TOTAL", animatedCount(Repo.total), VT.Ink, "This shift", Modifier.weight(1f))
                Stat("ACCEPTED", animatedCount(Repo.accepted), VT.Emerald, "Booked in", Modifier.weight(1f))
                Stat("FLAGGED", animatedCount(Repo.flagged), VT.AmberDot, "On hold", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, sub: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.08.sp),
            color = VT.Muted,
        )
        Spacer(Modifier.height(6.dp))
        Text("$value", style = MaterialTheme.typography.headlineLarge, color = color)
        Spacer(Modifier.height(4.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = VT.Muted)
    }
}

@Composable
private fun RecentCard(record: ReceivingRecord, now: Long, index: Int, anim: Modifier, onOpen: (String) -> Unit) {
    VTCard(modifier = anim.fillMaxWidth().clickable { onOpen(record.id) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.id, style = mono().data, color = VT.Ink)
                Spacer(Modifier.weight(1f))
                OutcomeChip(record.outcome)
            }
            Spacer(Modifier.height(8.dp))
            Text(record.purchaseOrderId, style = mono().dataSmall, color = VT.Ink)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${record.supplier} · ${record.goods}", style = MaterialTheme.typography.bodySmall, color = VT.Slate, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(relativeLabel(now, record.timestamp), style = MaterialTheme.typography.bodySmall, color = VT.Muted)
            }
        }
    }
}
