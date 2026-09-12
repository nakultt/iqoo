package com.veritransit.inspector.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.InspectionRecord
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.Repo
import com.veritransit.inspector.data.Verdict
import com.veritransit.inspector.data.relativeLabel
import com.veritransit.inspector.ui.components.Bounds
import com.veritransit.inspector.ui.components.EvidenceCanvas
import com.veritransit.inspector.ui.components.StatusChip
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.components.VerdictChip
import com.veritransit.inspector.ui.components.stagger
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

private enum class Filter { ALL, MATCHED, FLAGGED }

@Composable
fun RecordsScreen(
    now: Long,
    onOpenRecord: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }

    val filtered = Repo.records
        .filter { r ->
            val pass = when (filter) {
                Filter.ALL -> true
                Filter.MATCHED -> r.verdict == Verdict.PASSED
                Filter.FLAGGED -> r.flagged
            }
            val q = query.trim()
            pass && (q.isEmpty() || r.ewb.contains(q, true) || r.vehicle.contains(q, true) || r.cargo.contains(q, true))
        }
        .sortedByDescending { it.timestamp }

    Column(
        Modifier
            .fillMaxWidth()
            .background(VT.Alabaster)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Inspection Records", style = MaterialTheme.typography.headlineLarge, color = VT.Ink)
                    Text(
                        "Field Authority Vault · ${Repo.total} Total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VT.Muted,
                    )
                }
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(VT.Primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("SJ", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp), color = Color.White)
                }
            }
            Spacer(Modifier.height(14.dp))
            SearchField(query, onQuery = { query = it }, onScanHint = { onToast("Use the Scan tab to capture a new E-Way Bill") })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All (${Repo.total})", filter == Filter.ALL, VT.Primary, Color.White) { filter = Filter.ALL }
                FilterChip("Matched (${Repo.passed})", filter == Filter.MATCHED, VT.Surface, VT.Slate) { filter = Filter.MATCHED }
                FilterChip("Flagged (${Repo.flagged})", filter == Filter.FLAGGED, VT.Surface, VT.Slate, dotColor = VT.AmberDot) { filter = Filter.FLAGGED }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (filtered.isEmpty()) {
            EmptyState(query)
        } else {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                filtered.forEachIndexed { i, record ->
                    val anim = Modifier.stagger(i.coerceAtMost(8), record.id)
                    if (record.flagged) {
                        AuditCard(record, now, anim, onOpenRecord, onToast)
                    } else {
                        HistoryRow(record, now, anim, onOpenRecord)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onScanHint: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(VT.Surface)
            .border(1.dp, VT.Hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = VT.Faint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search by EWB, vehicle, or item…", style = MaterialTheme.typography.bodyMedium, color = VT.Faint)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = VT.Ink),
                cursorBrush = SolidColor(VT.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(9.dp))
        Icon(
            Icons.Rounded.QrCodeScanner, null,
            tint = VT.Muted,
            modifier = Modifier
                .size(19.dp)
                .clickable(onClick = onScanHint),
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    bgActive: Color,
    fgActive: Color,
    dotColor: Color? = null,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) bgActive else VT.Surface, spring(dampingRatio = 0.9f), label = "fBg")
    val fg by animateColorAsState(if (active) fgActive else VT.Slate, spring(dampingRatio = 0.9f), label = "fFg")
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, if (active) Color.Transparent else VT.Hairline, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dotColor != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

@Composable
private fun EmptyState(query: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Search, null, tint = VT.Faint, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            if (query.isBlank()) "No records yet" else "Nothing matches “$query”",
            style = MaterialTheme.typography.titleMedium,
            color = VT.Slate,
        )
        Text("Adjust the search or filters", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
    }
}

/** Expanded audit card for flagged consignments — the vault's centerpiece. */
@Composable
private fun AuditCard(
    record: InspectionRecord,
    now: Long,
    anim: Modifier,
    onOpenRecord: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    VTCard(modifier = anim.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(VT.AmberDot))
                Spacer(Modifier.width(9.dp))
                Text("AUDIT ${record.id}", style = mono().dataSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = VT.Slate)
                Spacer(Modifier.weight(1f))
                StatusChip("Review Required", VT.AmberBg, VT.Amber, VT.AmberLine)
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("E-WAY BILL", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.08.sp), color = VT.Muted)
                    Spacer(Modifier.height(3.dp))
                    Text(record.ewb, style = mono().data, color = VT.Ink)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("VEHICLE", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.08.sp), color = VT.Muted)
                    Spacer(Modifier.height(3.dp))
                    Text(record.vehicle, style = mono().data, color = VT.Ink)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(VT.AmberBg)
                    .border(1.dp, VT.AmberLine, RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null, tint = VT.AmberDot, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Discrepancy Summary", style = MaterialTheme.typography.titleSmall, color = Color(0xFF78350F), modifier = Modifier.weight(1f))
                    Text(
                        if (record.discrepancyCount == 1) "1 item flagged" else "${record.discrepancyCount} items flagged",
                        style = MaterialTheme.typography.bodySmall,
                        color = VT.Amber,
                    )
                }
                Spacer(Modifier.height(8.dp))
                record.items.filter { it.status != ItemStatus.MATCHED }.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, color = VT.Slate, modifier = Modifier.weight(1f))
                        when (item.status) {
                            ItemStatus.SHORTAGE -> StatusChip("-${item.expected - item.found} Shortage", VT.CrimsonBg, VT.Crimson, VT.CrimsonLine, withDot = false)
                            else -> StatusChip("+${item.found} Unlisted", VT.AmberBg, VT.Amber, VT.AmberLine, withDot = false)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("EVIDENCE PHOTOS", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.09.sp), color = VT.Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                EvidenceCanvas(Modifier.width(96.dp).height(72.dp), boxes = listOf(Bounds(0.1f, 0.25f, 0.5f, 0.5f, Color(0xFFF43F5E), "NEW")), timestamp = "Cargo Bay")
                EvidenceCanvas(Modifier.width(96.dp).height(72.dp), boxes = listOf(Bounds(0.2f, 0.2f, 0.6f, 0.55f, Color(0xFFF59E0B), "-1")), timestamp = "Label #4")
                Box(
                    Modifier
                        .width(96.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(VT.Inset)
                        .border(1.dp, VT.Border, RoundedCornerShape(8.dp))
                        .clickable { onToast("Camera capture is disabled in the demo build") },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.PhotoCamera, null, tint = VT.Muted, modifier = Modifier.size(20.dp))
                        Text("Add Photo", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (record.noticeIssued) {
                    StatusChip("Notice MOV-04 Issued", VT.EmeraldBg, VT.Emerald, VT.EmeraldLine, withDot = false)
                } else {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(VT.Primary)
                            .clickable {
                                Repo.records[Repo.records.indexOfFirst { it.id == record.id }] =
                                    record.copy(noticeIssued = true)
                                onToast("Notice MOV-04 issued & logged")
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Rounded.Gavel, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("Issue Notice MOV-04", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        }
                    }
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(VT.Surface)
                        .border(1.dp, VT.Border, RoundedCornerShape(4.dp))
                        .clickable { onToast("Summary queued for field printer") }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.Print, null, tint = VT.Slate, modifier = Modifier.size(16.dp))
                        Text("Print", style = MaterialTheme.typography.titleSmall, color = VT.Slate)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onOpenRecord(record.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Open full verification trail", style = MaterialTheme.typography.titleSmall, color = VT.Primary, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = VT.Primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun HistoryRow(record: InspectionRecord, now: Long, anim: Modifier, onOpenRecord: (String) -> Unit) {
    VTCard(modifier = anim.fillMaxWidth().clickable { onOpenRecord(record.id) }) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.vehicle, style = mono().data, color = VT.Ink)
                    Spacer(Modifier.width(8.dp))
                    Text("· ${record.ewb}", style = mono().dataSmall, color = VT.Muted)
                }
                Spacer(Modifier.height(3.dp))
                Text(record.cargo, style = MaterialTheme.typography.bodySmall, color = VT.Slate)
                Spacer(Modifier.height(3.dp))
                Text(relativeLabel(now, record.timestamp), style = MaterialTheme.typography.bodySmall, color = VT.Muted)
            }
            Spacer(Modifier.width(10.dp))
            VerdictChip(record.verdict)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = VT.Faint, modifier = Modifier.size(20.dp))
        }
    }
}
