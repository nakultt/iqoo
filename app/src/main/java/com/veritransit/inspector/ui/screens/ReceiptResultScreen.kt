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
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.ReceiptOutcome
import com.veritransit.inspector.data.ReceivingAction
import com.veritransit.inspector.data.ReceivingRecord
import androidx.compose.material.icons.rounded.AutoAwesome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.veritransit.inspector.ai.ReceivingAi
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.ui.ReceivingFlowState
import com.veritransit.inspector.ui.components.Bounds
import com.veritransit.inspector.ui.components.EvidenceCanvas
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.StatusChip
import com.veritransit.inspector.ui.components.SecondaryButton
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.components.OutcomeChip
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

@Composable
fun ReceiptResultScreen(
    record: ReceivingRecord,
    flow: ReceivingFlowState?,
    onConfirm: (ReceivingAction, String) -> Unit,
    onRecount: (() -> Unit)?,
    onPrint: () -> Unit,
    onBack: () -> Unit,
    onPaperwork: (() -> Unit)? = null,
) {
    var action by remember(record.id) {
        mutableStateOf(flow?.action ?: if (record.flagged) ReceivingAction.RECOUNT else ReceivingAction.ACCEPT)
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
                CountCard(record)
                EvidenceSection(record, evidencePath = flow?.dockEvidence, onZoom = { showZoom = true })

                if (record.outcome != ReceiptOutcome.PENDING && (active || record.flagged)) {
                    ActionCard(record, active, action, onAction = {
                        action = it
                        flow?.action = it
                    })
                }

                if (record.note.isNotBlank()) {
                    VTCard {
                        Column(Modifier.padding(14.dp)) {
                            Text("RECEIVER NOTE", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.08.sp), color = VT.Muted)
                            Spacer(Modifier.height(6.dp))
                            Text(record.note, style = MaterialTheme.typography.bodyMedium, color = VT.Slate)
                        }
                    }
                }

                if (active) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.veritransit.inspector.ui.components.PrimaryButton(
                            text = "File Goods-Received Note",
                            icon = Icons.Rounded.HistoryEdu,
                            onClick = { showStamp = true },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Re-count Delivery", onRecount ?: {}, icon = Icons.Rounded.Refresh, modifier = Modifier.weight(1f))
                            SecondaryButton("Add Note", { showNote = true }, icon = Icons.Rounded.Edit, modifier = Modifier.weight(1f))
                        }
                        if (onPaperwork != null) {
                            SecondaryButton("Paperwork Checklist", onPaperwork, icon = Icons.Rounded.FactCheck, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Print Summary", onPrint, icon = Icons.Rounded.Print, modifier = Modifier.weight(1f))
                        if (onPaperwork != null) {
                            SecondaryButton("Paperwork", onPaperwork, icon = Icons.Rounded.FactCheck, modifier = Modifier.weight(1f))
                        }
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
                val captured = flow?.dockEvidence
                if (captured != null) {
                    EvidencePhoto(captured, Modifier.fillMaxWidth().height(320.dp))
                } else {
                    EvidenceCanvas(
                        Modifier.fillMaxWidth().height(320.dp),
                        boxes = evidenceBoxes(record),
                        timestamp = evidenceTimestamp(record),
                    )
                }
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
private fun Header(record: ReceivingRecord, onBack: () -> Unit) {
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
        Text("Receipt Result", style = MaterialTheme.typography.headlineSmall, color = VT.Ink, modifier = Modifier.weight(1f))
        Text(record.id, style = mono().dataSmall, color = VT.Muted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
}

@Composable
private fun Banner(record: ReceivingRecord) {
    val (bg, line, accent, badge, title) = when (record.outcome) {
        ReceiptOutcome.PENDING -> Quint(
            VT.Surface, VT.Hairline, VT.Slate, "PENDING",
            "Awaiting dock count — the delivery is still on the dock pending the receiver's count.",
        )
        ReceiptOutcome.OK -> Quint(
            VT.EmeraldBg, VT.EmeraldLine, VT.Emerald, "ACCEPTED",
            "Received quantities agree with the packing list. Book the goods into stock.",
        )
        ReceiptOutcome.SHORT -> Quint(
            VT.AmberBg, VT.AmberLine, VT.Amber, "SHORT",
            "Fewer units arrived than the packing list declares — hold the delivery for a supervisor.",
        )
        ReceiptOutcome.OVER -> Quint(
            VT.AmberBg, VT.AmberLine, VT.Amber, "OVER",
            "More units arrived than the packing list declares — confirm the extra against the PO.",
        )
        ReceiptOutcome.MISMATCH -> Quint(
            VT.CrimsonBg, VT.CrimsonLine, VT.Crimson, "MISMATCH",
            "A line does not match the packing list: unlisted or damaged goods on the delivery.",
        )
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
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = when (record.outcome) {
                    ReceiptOutcome.MISMATCH -> Color(0xFF9F1239)
                    ReceiptOutcome.SHORT, ReceiptOutcome.OVER -> Color(0xFF78350F)
                    else -> VT.Slate
                },
                lineHeight = 21.sp,
            )
        }
    }
}

private fun Float.format1(): String {
    val rounded = (this * 10).toInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}

private data class Quint(
    val bg: Color,
    val line: Color,
    val accent: Color,
    val badge: String,
    val title: String,
)

@Composable
private fun CountCard(record: ReceivingRecord) {
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
                    "PACKED VS RECEIVED",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.09.sp),
                    color = VT.Slate,
                    modifier = Modifier.weight(1f),
                )
                Text("${record.items.size} Lines Checked", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
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
                            color = if (item.status == ItemStatus.UNLISTED || item.status == ItemStatus.DAMAGED) VT.Crimson else VT.Ink,
                        )
                        Text(
                            "Packed ${item.expected} · Received ${item.received}" +
                                if (item.damaged > 0) " · Damaged ${item.damaged}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = VT.Muted,
                        )
                    }
                    when (item.status) {
                        ItemStatus.MATCHED -> MatchBadge()
                        ItemStatus.SHORT -> StatusChip("Short (-${item.expected - item.received})", VT.AmberBg, VT.Amber, VT.AmberLine, withDot = false)
                        ItemStatus.OVER -> StatusChip("Over (+${item.received - item.expected})", VT.AmberBg, VT.Amber, VT.AmberLine, withDot = false)
                        ItemStatus.UNLISTED -> StatusChip("Unlisted (+${item.received})", VT.CrimsonBg, VT.Crimson, VT.CrimsonLine, withDot = false)
                        ItemStatus.DAMAGED -> StatusChip("Damaged (${item.damaged})", VT.CrimsonBg, VT.Crimson, VT.CrimsonLine, withDot = false)
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

/**
 * Caption for the vector illustration shown when no captured photo exists.
 * The clock time comes from the record itself — no fixed sample string.
 */
private fun evidenceTimestamp(record: ReceivingRecord): String {
    val time = runCatching {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(record.timestamp))
    }.getOrNull() ?: "--:--:--"
    return "$time · Dock"
}

private fun evidenceBoxes(record: ReceivingRecord): List<Bounds> {
    val boxes = mutableListOf(
        Bounds(0.05f, 0.28f, 0.22f, 0.34f, Color(0xFF34D399), "OK"),
    )
    record.items.forEach { item ->
        when (item.status) {
            ItemStatus.SHORT -> boxes += Bounds(0.70f, 0.32f, 0.24f, 0.32f, Color(0xFFF59E0B), "-${item.expected - item.received}")
            ItemStatus.OVER -> boxes += Bounds(0.70f, 0.32f, 0.24f, 0.32f, Color(0xFFF59E0B), "+${item.received - item.expected}")
            ItemStatus.UNLISTED -> boxes += Bounds(0.42f, 0.55f, 0.26f, 0.30f, Color(0xFFF43F5E), "NEW")
            ItemStatus.DAMAGED -> boxes += Bounds(0.42f, 0.55f, 0.26f, 0.30f, Color(0xFFF43F5E), "DMG")
            else -> Unit
        }
    }
    return boxes
}

@Composable
private fun EvidencePhoto(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) {
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Evidence photo captured for this receipt",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF141A22)))
    }
}

@Composable
private fun EvidenceSection(record: ReceivingRecord, evidencePath: String?, onZoom: () -> Unit) {
    Column {
        SectionLabel(
            "Evidence Photo",
            trailing = {
                Text(
                    if (evidencePath != null) "This receipt" else "Illustration",
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    color = VT.Muted,
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.clickable(onClick = onZoom)) {
            if (evidencePath != null) {
                EvidencePhoto(evidencePath, Modifier.fillMaxWidth().height(215.dp))
            } else {
                EvidenceCanvas(
                    Modifier.fillMaxWidth().height(215.dp),
                    boxes = evidenceBoxes(record),
                    timestamp = evidenceTimestamp(record),
                )
            }
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
private fun ActionCard(record: ReceivingRecord, active: Boolean, action: ReceivingAction, onAction: (ReceivingAction) -> Unit) {
    VTCard {
        Column(Modifier.padding(16.dp)) {
            Text("Receiving Action", style = MaterialTheme.typography.titleLarge, color = VT.Ink)
            Text("What to do with this delivery", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReceivingAction.entries.forEach { a ->
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
private fun StampOverlay(visible: Boolean, action: ReceivingAction, onDone: () -> Unit) {
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
                ReceivingAction.ACCEPT -> VT.Emerald
                ReceivingAction.RECOUNT -> VT.AmberDot
                ReceivingAction.FLAG, ReceivingAction.NOT_RECEIVED -> VT.Crimson
            }
            val stampText = when (action) {
                ReceivingAction.ACCEPT -> "ACCEPTED"
                ReceivingAction.RECOUNT -> "RE-COUNT REQUESTED"
                ReceivingAction.FLAG -> "FLAGGED"
                ReceivingAction.NOT_RECEIVED -> "NOT RECEIVED"
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
                    "Filed by ${com.veritransit.inspector.data.Repo.RECEIVER} · ${com.veritransit.inspector.data.Repo.WAREHOUSE}",
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
    record: ReceivingRecord,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var drafting by remember { mutableStateOf(false) }
    var draftError by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun draft() {
        if (drafting) return
        drafting = true
        draftError = null
        // Whatever the receiver had written survives a failed draft — with the
        // cloud leg in the path, failures are ordinary (offline, out of credit).
        val previous = text
        text = ""
        scope.launch {
            try {
                // Streamed so the receiver watches it appear rather than waiting
                // on a spinner — decode is ~30 tok/s on the NPU, long enough
                // to notice.
                ReceivingAi.draftNote(
                    record,
                    onToken = { token ->
                        scope.launch(Dispatchers.Main) { text += token }
                    },
                    // A mid-stream switch from the NPU leg to the cloud
                    // fallback must not leave the dead leg's tokens behind.
                    onLegSwitch = { scope.launch(Dispatchers.Main) { text = "" } },
                ).onSuccess { note ->
                    // Commit the backend's reply, not the streamed
                    // accumulation — the two agree unless a leg switch
                    // interleaved with a straggling token.
                    scope.launch(Dispatchers.Main) { text = note }
                }.onFailure {
                    scope.launch(Dispatchers.Main) {
                        text = previous
                        draftError = it.message ?: "The model could not draft a note."
                    }
                }
            } finally {
                drafting = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        VTCard(radius = 12.dp) {
            Column(Modifier.padding(18.dp)) {
                Text("Add Receiver Note", style = MaterialTheme.typography.titleLarge, color = VT.Ink)
                Spacer(Modifier.height(4.dp))
                Text("Saved with the goods-received note", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
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
                draftError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = VT.Crimson)
                }
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
