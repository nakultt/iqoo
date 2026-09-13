package com.veritransit.inspector.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.Presets
import com.veritransit.inspector.ui.SenderFlowState
import com.veritransit.inspector.ui.components.CountStepper
import com.veritransit.inspector.ui.components.FieldLabel
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.FlowScaffold
import com.veritransit.inspector.ui.components.PrimaryButton
import com.veritransit.inspector.ui.components.SectionLabel
import com.veritransit.inspector.ui.components.StepBadge
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sender step 1: the packing list being packed, the master box's ID, and how
 * many boxes go inside it.
 */
@Composable
fun PackMasterBoxScreen(
    sender: SenderFlowState,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val idProblem = sender.masterIdProblem

    FlowScaffold(
        bottomBar = {
            PrimaryButton(
                text = "Continue to Box Contents",
                onClick = onContinue,
                enabled = idProblem == null,
                trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
            )
        },
    ) {
        FlowHeader(
            title = "Pack Master Box",
            subtitle = "Pick a list, name the big box, add little boxes",
            onBack = onBack,
            trailing = { StepBadge(1) },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                SectionLabel("Packing List")
                Spacer(Modifier.height(10.dp))
                VTCard {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Presets.ALL.forEachIndexed { i, preset ->
                            PresetRow(
                                supplier = preset.supplier,
                                detail = "${preset.purchaseOrderId} · ${preset.totalUnits} units",
                                selected = i == sender.presetIndex,
                                onClick = { sender.selectPreset(i) },
                            )
                        }
                    }
                }
            }

            Column {
                SectionLabel("Big Box (Master · Outer)")
                Spacer(Modifier.height(10.dp))
                VTCard {
                    Column(Modifier.padding(16.dp)) {
                        FieldLabel("Big Box ID — written on the outer box")
                        Spacer(Modifier.height(6.dp))
                        IdField(sender.masterId, sender::editMasterId, hint = "MB-4471-01")
                        Text(
                            idProblem ?: "✓ Printed on the big box, and part of every little box's ID",
                            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp),
                            color = if (idProblem == null) VT.Emerald else VT.Amber,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Little boxes inside the big one", style = MaterialTheme.typography.titleSmall, color = VT.Ink)
                                Text(
                                    "${sender.preset.totalUnits} items fill at most ${sender.maxBoxes} little boxes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VT.Muted,
                                )
                            }
                            CountStepper(
                                value = sender.boxCount,
                                onValueChange = sender::changeBoxCount,
                                range = 1..sender.maxBoxes,
                                what = "little box inside",
                            )
                        }
                    }
                }
            }

            LabelCountCard(boxCount = sender.boxCount, masterId = sender.masterId)
        }
    }
}

@Composable
private fun PresetRow(supplier: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Color(0xFFFDF2F4) else Color.Transparent, tween(160), label = "packBg")
    val line by animateColorAsState(if (selected) VT.Primary else Color.Transparent, tween(160), label = "packLine")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .border(1.2.dp, line, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .border(1.6.dp, if (selected) VT.Primary else VT.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(VT.Primary))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(supplier, style = MaterialTheme.typography.titleSmall, color = VT.Ink)
            Text(detail, style = mono().dataSmall, color = VT.Muted)
        }
    }
}

@Composable
private fun IdField(value: String, onValueChange: (String) -> Unit, hint: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
        ),
        textStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = VT.Ink, letterSpacing = 0.03.sp),
        cursorBrush = SolidColor(VT.Primary),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(VT.Surface)
                    .border(1.dp, VT.Border, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 16.sp), color = VT.Faint)
                }
                inner()
            }
        },
    )
}

/** The master box drawn with its boxes inside, and the label count that follows from it. */
@Composable
private fun LabelCountCard(boxCount: Int, masterId: String) {
    VTCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxesInside(boxCount)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${boxCount + 1} QR labels", style = MaterialTheme.typography.titleMedium, color = VT.Ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    "One for the big box ${masterId.ifEmpty { "—" }}, and one for " +
                        (if (boxCount == 1) "the little box inside it" else "each of the $boxCount little boxes inside it") +
                        " — scan any of them on the receiving side to open this packing list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VT.Muted,
                )
                if (boxCount > MAX_DRAWN) {
                    Text(
                        "First $MAX_DRAWN boxes drawn",
                        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp),
                        color = VT.Faint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private const val MAX_DRAWN = 25

@Composable
private fun BoxesInside(count: Int) {
    val drawn = count.coerceIn(1, MAX_DRAWN)
    val columns = ceil(sqrt(drawn.toDouble())).toInt()
    val rows = ceil(drawn / columns.toDouble()).toInt()
    Canvas(Modifier.size(84.dp)) {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = VT.Primary,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(stroke),
        )
        val pad = 11.dp.toPx()
        val gap = 3.dp.toPx()
        val cell = min(
            (size.width - 2 * pad - (columns - 1) * gap) / columns,
            (size.height - 2 * pad - (rows - 1) * gap) / rows,
        )
        val left = (size.width - (columns * cell + (columns - 1) * gap)) / 2
        val top = (size.height - (rows * cell + (rows - 1) * gap)) / 2
        val corner = CornerRadius(2.dp.toPx())
        repeat(drawn) { i ->
            val topLeft = Offset(left + (i % columns) * (cell + gap), top + (i / columns) * (cell + gap))
            drawRoundRect(Color(0xFFFDF2F4), topLeft, Size(cell, cell), corner)
            drawRoundRect(VT.Primary.copy(alpha = 0.7f), topLeft, Size(cell, cell), corner, style = Stroke(1.dp.toPx()))
        }
    }
}
