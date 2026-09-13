package com.veritransit.inspector.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.ReceivingRecord
import com.veritransit.inspector.data.documents.ConsignmentPaperwork
import com.veritransit.inspector.data.documents.GoodsCategory
import com.veritransit.inspector.data.documents.MovementReason
import com.veritransit.inspector.data.documents.Obligation
import com.veritransit.inspector.data.documents.OpenGap
import com.veritransit.inspector.data.documents.Paperwork
import com.veritransit.inspector.data.documents.PaperworkFacts
import com.veritransit.inspector.data.documents.Provision
import com.veritransit.inspector.data.documents.Requirement
import com.veritransit.inspector.data.documents.TransportMode
import com.veritransit.inspector.data.documents.Verification
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.components.VTCheckbox
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT

private val Facts = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp)
private val Cite = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.04.sp)

/**
 * The consignment's paperwork, resolved live against the document registry.
 *
 * The filed receipt fills in what it knows — the goods family its own words
 * name, whether the count found a discrepancy — and the receiver answers the
 * rest. Anything left unanswered stays unanswered: the resolver reports it as
 * "cannot tell yet", never as "not required". Reference for the receiving
 * team; the app verifies no document and states no legal position.
 */
@Composable
fun PaperworkScreen(record: ReceivingRecord, onBack: () -> Unit) {
    var facts by remember(record.id) { mutableStateOf(PaperworkFacts.from(record)) }
    val consignment = facts.toConsignment()
    val paperwork = remember(consignment) { consignment?.let(ConsignmentPaperwork::resolve) }

    Box(Modifier.fillMaxSize().background(VT.Alabaster)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = 40.dp),
        ) {
            Header(record, onBack)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DerivedCard(record, facts)
                NoticeStrip(
                    "Reference for the receiving team — the app does not verify any document " +
                        "or state a legal position.",
                )
                FactsCard(facts, onChange = { facts = it })

                if (paperwork == null) {
                    GateCard()
                } else {
                    ResolutionSummary(paperwork)
                    RequirementList("REQUIRED BY LAW", paperwork.required)
                    RequirementList("COMMERCIAL PRACTICE", paperwork.customary)
                    UndeterminedCard(paperwork)
                    ProfileCard(paperwork)
                    GapsCard(paperwork.gaps)
                }
            }
        }
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
        Text("Paperwork", style = MaterialTheme.typography.headlineSmall, color = VT.Ink, modifier = Modifier.weight(1f))
        Text(record.id, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.5.sp), color = VT.Muted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
}

/* ------------------------------ what the receipt decided ------------------------------ */

@Composable
private fun DerivedCard(record: ReceivingRecord, facts: PaperworkFacts) {
    VTCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Meta("RECEIPT", "${record.supplier} · ${record.goods}")
            Meta("REFERENCES", "${record.purchaseOrderId} · ${record.packingListId}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FROM THE COUNT", style = Facts, color = VT.Muted, modifier = Modifier.width(110.dp))
                val (label, bg, line, accent) = if (facts.discrepancyAtReceipt) {
                    Quad("DISCREPANCY FOUND", VT.AmberBg, VT.AmberLine, VT.Amber)
                } else {
                    Quad("CLEAN COUNT", VT.EmeraldBg, VT.EmeraldLine, VT.Emerald)
                }
                Text(
                    label,
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.07.sp),
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(bg)
                        .border(1.dp, line, RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private data class Quad(val label: String, val bg: Color, val line: Color, val accent: Color)

@Composable
private fun Meta(label: String, value: String) {
    Row {
        Text(label, style = Facts, color = VT.Muted, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = VT.Slate, modifier = Modifier.weight(1f))
    }
}

/* ------------------------------ the questions ------------------------------ */

@Composable
private fun FactsCard(facts: PaperworkFacts, onChange: (PaperworkFacts) -> Unit) {
    VTCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("CONSIGNMENT FACTS")
            ChoiceRow(
                "Goods family",
                listOf(
                    GoodsCategory.ELECTRONICS.label to (facts.category == GoodsCategory.ELECTRONICS),
                    GoodsCategory.FABRIC.label to (facts.category == GoodsCategory.FABRIC),
                ),
            ) { picked ->
                onChange(facts.copy(category = GoodsCategory.entries[picked]))
            }
            ChoiceRow(
                "Movement",
                listOf("Within the State" to (facts.interState == false), "Inter-State" to (facts.interState == true)),
            ) { picked ->
                onChange(facts.copy(interState = picked == 1))
            }
            ChoiceRow(
                "Why it moves",
                listOf(
                    "Supply" to (facts.reason == MovementReason.SUPPLY),
                    "Job work" to (facts.reason == MovementReason.JOB_WORK),
                    "Not a supply" to (facts.reason == MovementReason.NOT_SUPPLY),
                ),
            ) { picked ->
                onChange(facts.copy(reason = MovementReason.entries[picked]))
            }
            ChoiceRow(
                "Transport",
                listOf(
                    "Road" to (facts.mode == TransportMode.ROAD),
                    "Rail" to (facts.mode == TransportMode.RAIL),
                    "Air" to (facts.mode == TransportMode.AIR),
                    "Vessel" to (facts.mode == TransportMode.VESSEL),
                ),
            ) { picked ->
                onChange(facts.copy(mode = TransportMode.entries[picked]))
            }

            Text(
                "r.138(1) value — declared + tax − exempt. Blank means unknown, never zero.",
                style = TextStyle(fontFamily = Mono, fontSize = 10.5.sp),
                color = VT.Faint,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    MonoEntry("₹ Declared", facts.declaredRupees, RUPEES) { onChange(facts.copy(declaredRupees = it)) }
                }
                Box(Modifier.weight(1f)) {
                    MonoEntry("+ Tax", facts.taxRupees, RUPEES) { onChange(facts.copy(taxRupees = it)) }
                }
                Box(Modifier.weight(1f)) {
                    MonoEntry("− Exempt", facts.exemptRupees, RUPEES) { onChange(facts.copy(exemptRupees = it)) }
                }
            }

            VTCheckbox(
                facts.supplierRegistered,
                { onChange(facts.copy(supplierRegistered = !facts.supplierRegistered)) },
                "Supplier is GST-registered",
                null,
            )
            VTCheckbox(
                facts.recipientRegistered,
                { onChange(facts.copy(recipientRegistered = !facts.recipientRegistered)) },
                "Recipient is GST-registered",
                null,
            )
            VTCheckbox(facts.imported, { onChange(facts.copy(imported = !facts.imported)) }, "Imported goods", "travels with its bill of entry")
            VTCheckbox(
                facts.byCommonCarrier,
                { onChange(facts.copy(byCommonCarrier = !facts.byCommonCarrier)) },
                "Handed to a common carrier",
                "a lorry receipt covers the handover",
            )
            VTCheckbox(
                facts.inLots,
                { onChange(facts.copy(inLots = !facts.inLots, lotIndex = 1)) },
                "Sent SKD/CKD or in lots",
                "the original invoice travels with the last lot",
            )
            if (facts.inLots) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("LOT", style = Facts, color = VT.Muted)
                    RoundStep("−") { onChange(facts.copy(lotIndex = (facts.lotIndex - 1).coerceAtLeast(1))) }
                    Text(
                        "${facts.lotIndex}",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        color = VT.Ink,
                        modifier = Modifier.width(24.dp),
                    )
                    Text("of", style = Facts, color = VT.Faint)
                    RoundStep("−") {
                        val count = (facts.lotCount - 1).coerceAtLeast(1)
                        onChange(facts.copy(lotCount = count, lotIndex = facts.lotIndex.coerceAtMost(count)))
                    }
                    Text(
                        "${facts.lotCount}",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        color = VT.Ink,
                        modifier = Modifier.width(24.dp),
                    )
                    RoundStep("+") { onChange(facts.copy(lotCount = facts.lotCount + 1)) }
                    RoundStep("+") { onChange(facts.copy(lotIndex = (facts.lotIndex + 1).coerceAtMost(facts.lotCount))) }
                }
            }
            VTCheckbox(
                facts.capitalGoods,
                { onChange(facts.copy(capitalGoods = !facts.capitalGoods)) },
                "Job work — capital goods",
                "three years to come back, not one (s.143)",
            )

            Text("Is the supplier in the class notified for e-invoicing (r.48(4))?", style = MaterialTheme.typography.bodySmall, color = VT.Slate)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip("Notified", facts.supplierEInvoicing == true) { onChange(facts.copy(supplierEInvoicing = true)) }
                SmallChip("Not notified", facts.supplierEInvoicing == false) { onChange(facts.copy(supplierEInvoicing = false)) }
                SmallChip("Not known", facts.supplierEInvoicing == null) { onChange(facts.copy(supplierEInvoicing = null)) }
            }
        }
    }
}

/** One selectable chip per entry; [picked] carries the tapped option's index. */
@Composable
private fun ChoiceRow(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = VT.Slate)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, (text, active) ->
                SmallChip(text, active, onClick = { onPick(i) })
            }
        }
    }
}

@Composable
private fun SmallChip(text: String, picked: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (picked) VT.Primary else VT.Border, tween(160), label = "chipBorder")
    val bg by animateColorAsState(if (picked) VT.Primary else VT.Surface, tween(160), label = "chipBg")
    Text(
        text,
        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
        color = if (picked) VT.OnPrimary else VT.Slate,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** `₹`-value entry: digits, comma grouping and up to two decimals — nothing else lands. */
private val RUPEES = Regex("^[0-9,]*\\.?[0-9]{0,2}$")

@Composable
private fun MonoEntry(hint: String, value: String, allowed: Regex, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { raw -> if (allowed.matches(raw)) onValueChange(raw) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = VT.Ink),
        cursorBrush = SolidColor(VT.Primary),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(VT.Surface)
                    .border(1.dp, VT.Border, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp), color = VT.Faint)
                }
                inner()
            }
        },
    )
}

@Composable
private fun RoundStep(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(VT.Inset)
            .border(1.dp, VT.Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            color = VT.Slate,
        )
    }
}

/* ------------------------------ the resolution ------------------------------ */

@Composable
private fun GateCard() {
    VTCard(bg = VT.AmberBg, border = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CANNOT RESOLVE YET", style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.sp), color = VT.Amber)
            Text(
                "Answer the goods family and whether the movement crossed a State border — " +
                    "the registry cannot default either. Everything else may stay unknown.",
                style = MaterialTheme.typography.bodyMedium,
                color = VT.Slate,
            )
        }
    }
}

@Composable
private fun ResolutionSummary(paperwork: Paperwork) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryChip("${paperwork.required.size}", "required by law", VT.EmeraldBg, VT.EmeraldLine, VT.Emerald)
        SummaryChip("${paperwork.customary.size}", "customary", VT.Surface, VT.Hairline, VT.Slate)
        SummaryChip("${paperwork.undetermined.size}", "undetermined", VT.AmberBg, VT.AmberLine, VT.Amber)
    }
}

@Composable
private fun SummaryChip(count: String, label: String, bg: Color, line: Color, accent: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, line, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(count, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = accent)
        Text(label, style = TextStyle(fontFamily = Mono, fontSize = 10.5.sp), color = VT.Muted)
    }
}

@Composable
private fun RequirementList(title: String, requirements: List<Requirement>) {
    if (requirements.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title)
        requirements.forEach { req ->
            VTCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            req.document.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = VT.Ink,
                            modifier = Modifier.weight(1f),
                        )
                        VerificationMark(req)
                    }
                    Text(
                        "${req.holder.label} — ${req.duty.verb}",
                        style = Facts,
                        color = VT.Primary,
                    )
                    Text("when: ${req.trigger.label}", style = Facts, color = VT.Muted)
                    req.provision?.let { p ->
                        Text(p.cite, style = Cite, color = VT.Slate)
                        Text(p.substance, style = MaterialTheme.typography.bodySmall, color = VT.Slate)
                    }
                    req.deadline?.let { Text("⏱ ${it.label}", style = Facts, color = VT.Amber) }
                    req.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VT.Muted) }
                }
            }
        }
    }
}

@Composable
private fun VerificationMark(req: Requirement) {
    if (req.obligation == Obligation.CUSTOMARY) {
        Text("commercial practice", style = Cite, color = VT.Faint)
        return
    }
    val p = req.provision ?: return
    val (text, color) = when (p.verification) {
        Verification.PRIMARY -> "✓ govt text" to VT.Emerald
        Verification.SECONDARY -> "△ secondary source" to VT.Amber
        Verification.UNVERIFIED -> "○ unverified" to VT.Amber
    }
    Text(text, style = Cite, color = color)
}

@Composable
private fun UndeterminedCard(paperwork: Paperwork) {
    if (paperwork.undetermined.isEmpty()) return
    VTCard(bg = VT.AmberBg, border = false) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "CANNOT TELL YET — ${paperwork.undetermined.size}",
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.sp),
                color = VT.Amber,
            )
            Text(
                "These bite or pass on facts this consignment does not carry. They are never " +
                    "counted as \"not required\".",
                style = MaterialTheme.typography.bodySmall,
                color = VT.Slate,
            )
            paperwork.undetermined.forEach { req ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(req.document.title, style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                    Text("bites when: ${req.trigger.label}", style = Facts, color = VT.Amber)
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(paperwork: Paperwork) {
    val profile = paperwork.profile
    VTCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(profile.category.label.uppercase() + " — WHAT THIS DOCK MEETS")
            profile.routine.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = VT.Slate) }
            profile.notes.forEach { note ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(note.text, style = MaterialTheme.typography.bodySmall, color = VT.Slate)
                    ProvisionCite(note.provision)
                }
            }
        }
    }
}

@Composable
private fun GapsCard(gaps: List<OpenGap>) {
    if (gaps.isEmpty()) return
    VTCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("OPEN VERIFICATION GAPS")
            gaps.forEach { gap ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(gap.id, style = Cite, color = VT.Primary)
                        if (gap.intraStateOnly) Text("intra-State only", style = Facts, color = VT.Faint)
                    }
                    Text(gap.question, style = MaterialTheme.typography.bodySmall, color = VT.Slate)
                }
            }
        }
    }
}

@Composable
private fun ProvisionCite(p: Provision) {
    val (mark, color) = when (p.verification) {
        Verification.PRIMARY -> "✓" to VT.Emerald
        Verification.SECONDARY -> "△" to VT.Amber
        Verification.UNVERIFIED -> "○" to VT.Amber
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$mark ${p.cite}", style = Cite, color = color)
    }
}
