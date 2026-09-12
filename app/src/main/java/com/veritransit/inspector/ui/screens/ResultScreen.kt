package com.veritransit.inspector.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove

import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.veritransit.inspector.data.InspectionRecord
import com.veritransit.inspector.data.ItemStatus
import androidx.compose.material.icons.rounded.AutoAwesome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.veritransit.inspector.ai.InspectorAi
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.data.OfficerAction
import com.veritransit.inspector.data.Verdict
import com.veritransit.inspector.ui.InspectionFlowState
import com.veritransit.inspector.ui.components.Bounds
import com.veritransit.inspector.ui.components.EvidenceCanvas
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.StatusChip
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.components.VerdictChip
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

@Composable
fun ResultScreen(
    record: InspectionRecord,
    flow: InspectionFlowState?,
    onConfirm: (OfficerAction, String) -> Unit,
    onRescan: (() -> Unit)?,
    onPrint: () -> Unit,
    onIssueNotice: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var action by remember(record.id) {
        mutableStateOf(flow?.action ?: if (record.flagged) OfficerAction.RECOUNT else OfficerAction.CLEAR)
    }
    var showStamp by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(false) }
    val active = flow != null

    Box(Modifier.fillMaxSize().background(VT.Alabaster)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = 40.dp),
        ) {
            Header(record, onBack)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Banner(record)
                ReconciliationCard(record)
                EvidenceSection(record, onZoom = { showZoom = true })

                if (record.verdict != Verdict.PENDING && (active || record.flagged)) {
                    ActionCard(record, active, action, onAction = {
                        action = it
                        flow?.action = it
                    })
                }

                if (record.note.isNotBlank()) {
                    VTCard {
                        Column(Modifier.padding(14.dp)) {
                            Text("OFFICER NOTE", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.08.sp), color = VT.Muted)
                            Spacer(Modifier.height(6.dp))
                            Text(record.note, style = MaterialTheme.typography.bodyMedium, color = VT.Slate)
                        }
                    }
                }

                if (active) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.veritransit.inspector.ui.components.PrimaryButton(
                            text = "Confirm Verdict & Sign",
                            icon = Icons.Rounded.HistoryEdu,
                            onClick = { showStamp = true },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Rescan Cargo", onRescan ?: {}, icon = Icons.Rounded.Refresh, modifier = Modifier.weight(1f))
                            SecondaryButton("Add Note", { showNote = true }, icon = Icons.Rounded.Edit, modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (record.flagged && !record.noticeIssued && onIssueNotice != null) {
                            com.veritransit.inspector.ui.components.PrimaryButton(
                                text = "Issue Notice MOV-04",
                                icon = Icons.Rounded.Gavel,
                                onClick = onIssueNotice,
                                modifier = Modifier.weight(1.2f),
                            )
                        }
                        SecondaryButton("Print Summary", onPrint, icon = Icons.Rounded.Print, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        StampOverlay(
            visible = showStamp,
            action = action,
            onDone = {
                showStamp = false
                onConfirm(action, flow?.note ?: record.note)
            },
        )
    }

    if (showZoom) {
        Dialog(onDismissRequest = { showZoom = false }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C131C))
                    .padding(10.dp),
            ) {
                EvidenceCanvas(
                    Modifier.fillMaxWidth().height(320.dp),
                    boxes = evidenceBoxes(record),
                    timestamp = "14:22:09 · GPS Verified · Bay Rig #3",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Evidence frame · tap outside to close",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp),
                    color = Color(0xFFB9C4D2),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
        }
    }

    if (showNote && flow != null) {
        NoteDialog(
            initial = flow.note,
            record = record,
            onSave = { flow.note = it; showNote = false },
            onDismiss = { showNote = false },
        )
    }
}

@Composable
private fun Header(record: InspectionRecord, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(VT.Alabaster)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Back", tint = VT.Ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text("Verification Result", style = MaterialTheme.typography.headlineSmall, color = VT.Ink, modifier = Modifier.weight(1f))
        Text(record.ewb, style = mono().dataSmall, color = VT.Muted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
}

@Composable
private fun Banner(record: InspectionRecord) {
    val (bg, line, accent, badge, badgeFg, title) = when {
        record.verdict == Verdict.PENDING -> Quint(VT.Surface, VT.Hairline, VT.Slate, "PENDING", VT.Slate, "Verdict awaiting — consignment held at gate pending officer review.")
        record.discrepancyCount > 0 -> Quint(VT.AmberBg, VT.AmberLine, VT.Amber, "REVIEW REQUIRED", VT.AmberDot, "Manifest discrepancy detected: item count mismatch & unlisted cargo.")
        else -> Quint(VT.EmeraldBg, VT.EmeraldLine, VT.Emerald, "CLEARED", VT.Emerald, "Cargo reconciled with manifest. No discrepancies found.")
    }
    VTCard(bg = bg, border = false) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, line, RoundedCornerShape(8.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        badge,
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.08.sp),
                        color = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (record.confidence > 0f) {
                    val low = record.confidence < 0.5f
                    Text(
                        "Conf. ${(record.confidence * 100).format1()}%",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                        color = if (low) VT.Amber else VT.Slate,
                    )
                    if (low) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "LOW — VERIFY MANUALLY",
                            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.08.sp),
                            color = VT.Amber,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = Color(0xFF78350F).takeIf { record.discrepancyCount > 0 } ?: VT.Slate, lineHeight = 21.sp)
        }
    }
}

private fun Float.format1(): String {
    val rounded = (this * 10).toInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}

private data class Quint(val bg: Color, val line: Color, val accent: Color, val badge: String, val badgeFg: Color, val title: String)

@Composable
private fun ReconciliationCard(record: InspectionRecord) {
    VTCard {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(VT.Surface)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "CARGO RECONCILIATION",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.09.sp),
                    color = VT.Slate,
                    modifier = Modifier.weight(1f),
                )
                Text("${record.items.size} Items Checked", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
            record.items.forEachIndexed { i, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                            color = if (item.status == ItemStatus.UNLISTED) VT.Crimson else VT.Ink,
                        )
                        Text("Expected ${item.expected} · Found ${item.found}", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                    }
                    when (item.status) {
                        ItemStatus.MATCHED -> MatchBadge()
                        ItemStatus.SHORTAGE -> StatusChip("Shortage (-${item.expected - item.found})", VT.AmberBg, VT.Amber, VT.AmberLine, withDot = false)
                        ItemStatus.OVERAGE -> StatusChip("Overage (+${item.found - item.expected})", VT.AmberBg, VT.Amber, VT.AmberLine, withDot = false)
                        ItemStatus.UNLISTED -> StatusChip("Unlisted (+${item.found})", VT.CrimsonBg, VT.Crimson, VT.CrimsonLine, withDot = false)
                    }
                }
                if (i < record.items.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(VT.Hairline))
                }
            }
        }
    }
}

@Composable
private fun MatchBadge() {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(VT.EmeraldBg)
            .border(1.dp, VT.EmeraldLine, RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Rounded.Check, null, tint = VT.Emerald, modifier = Modifier.size(13.dp))
        Text("Matched", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp), color = VT.Emerald)
    }
}

private fun evidenceBoxes(record: InspectionRecord): List<Bounds> {
    val boxes = mutableListOf(
        Bounds(0.05f, 0.28f, 0.22f, 0.34f, Color(0xFF34D399), "OK"),
    )
    record.items.forEach { item ->
        when (item.status) {
            ItemStatus.SHORTAGE -> boxes += Bounds(0.70f, 0.32f, 0.24f, 0.32f, Color(0xFFF59E0B), "-${item.expected - item.found}")
            ItemStatus.OVERAGE -> boxes += Bounds(0.70f, 0.32f, 0.24f, 0.32f, Color(0xFFF59E0B), "+${item.found - item.expected}")
            ItemStatus.UNLISTED -> boxes += Bounds(0.42f, 0.55f, 0.26f, 0.30f, Color(0xFFF43F5E), "NEW")
            else -> Unit
        }
    }
    return boxes
}

@Composable
private fun EvidenceSection(record: InspectionRecord, onZoom: () -> Unit) {
    Column {
        SectionLabel(
            "Evidence Scan",
            trailing = {
                Text("Bay Rig #3", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp), color = VT.Muted)
            },
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.clickable(onClick = onZoom)) {
            EvidenceCanvas(Modifier.fillMaxWidth().height(215.dp), boxes = evidenceBoxes(record))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("Tap to expand", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp), color = Color.White)
            }
        }
    }
}

@Composable
private fun ActionCard(record: InspectionRecord, active: Boolean, action: OfficerAction, onAction: (OfficerAction) -> Unit) {
    VTCard {
        Column(Modifier.padding(16.dp)) {
            Text("Officer Statutory Action", style = MaterialTheme.typography.titleLarge, color = VT.Ink)
            Text("Select determination for this consignment", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OfficerAction.entries.forEach { a ->
                    ActionOption(
                        selected = action == a,
                        title = a.title,
                        detail = a.detail,
                        onClick = { onAction(a) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Color(0xFFFDF2F4) else VT.Surface, tween(160), label = "optBg")
    val line by animateColorAsState(if (selected) VT.Primary else VT.Hairline, tween(160), label = "optLine")
    val ringScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.5f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "ring",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, line, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.8.dp, if (selected) VT.Primary else VT.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer { scaleX = ringScale; scaleY = ringScale }
                    .clip(CircleShape)
                    .background(VT.Primary),
            )
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = if (selected) VT.Primary else VT.Ink,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = VT.Muted, lineHeight = 18.sp)
        }
    }
}

/* ------------------------------ Stamp overlay ------------------------------ */

@Composable
private fun StampOverlay(visible: Boolean, action: OfficerAction, onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(1450)
            onDone()
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(200)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66222A33)),
            contentAlignment = Alignment.Center,
        ) {
            val stampColor = when (action) {
                OfficerAction.CLEAR -> VT.Emerald
                OfficerAction.RECOUNT -> VT.AmberDot
                OfficerAction.DETAIN -> VT.Crimson
            }
            val stampText = when (action) {
                OfficerAction.CLEAR -> "CLEARED"
                OfficerAction.RECOUNT -> "RE-COUNT ORDERED"
                OfficerAction.DETAIN -> "DETAINED"
            }
            var stampIn by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { stampIn = true }
            val s by animateFloatAsState(
                targetValue = if (stampIn) 1f else 1.7f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
                label = "stamp",
            )
            Column(
                Modifier
                    .graphicsLayer { scaleX = s; scaleY = s; rotationZ = -7f; alpha = if (s < 1.05f) 1f else 0.4f }
                    .border(3.dp, stampColor, RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.96f))
                    .padding(horizontal = 26.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Check, null, tint = stampColor, modifier = Modifier.size(20.dp))
                    Text(
                        stampText,
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.1.sp),
                        color = stampColor,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "Signed · ${com.veritransit.inspector.data.Repo.INSPECTOR} ${com.veritransit.inspector.data.Repo.BADGE}",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp),
                    color = VT.Muted,
                )
            }
        }
    }
}

/* ------------------------------- Note dialog ------------------------------- */

@Composable
private fun NoteDialog(
    initial: String,
    record: InspectionRecord,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var drafting by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun draft() {
        if (drafting) return
        drafting = true
        text = ""
        scope.launch {
            try {
                // Streamed so the officer watches it appear rather than waiting
                // on a spinner — decode is ~30 tok/s, long enough to notice.
                InspectorAi.draftNote(record) { token ->
                    scope.launch(Dispatchers.Main) { text += token }
                }.onFailure { text = "" }
            } finally {
                drafting = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        VTCard(radius = 12.dp) {
            Column(Modifier.padding(18.dp)) {
                Text("Add Officer Note", style = MaterialTheme.typography.titleLarge, color = VT.Ink)
                Spacer(Modifier.height(4.dp))
                Text("Appended to the permanent audit trail", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                Spacer(Modifier.height(14.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = VT.Ink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(VT.Inset)
                        .padding(12.dp)
                        .height(90.dp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(VT.Primary),
                )
                if (LlmGateway.isAvailable) {
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        if (drafting) "Drafting…" else "Draft with AI",
                        { draft() },
                        icon = Icons.Rounded.AutoAwesome,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Cancel", onDismiss, modifier = Modifier.weight(1f))
                    com.veritransit.inspector.ui.components.PrimaryButton("Save Note", { onSave(text.trim()) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
