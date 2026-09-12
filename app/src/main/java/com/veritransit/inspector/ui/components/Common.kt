package com.veritransit.inspector.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.data.Verdict
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import com.veritransit.inspector.ui.theme.mono

/* ---------------------------------- Chips ---------------------------------- */

@Composable
fun verdictChipColor(verdict: Verdict): Triple<Color, Color, Color> = when (verdict) {
    Verdict.PASSED -> Triple(VT.EmeraldBg, VT.Emerald, VT.EmeraldLine)
    Verdict.REVIEW -> Triple(VT.AmberBg, VT.Amber, VT.AmberLine)
    Verdict.PENDING -> Triple(Color(0xFFF1F5F9), VT.Slate, Color(0xFFE2E8F0))
}

/** Mono uppercase status badge with leading dot — the app's signature state marker. */
@Composable
fun StatusChip(
    text: String,
    bg: Color,
    fg: Color,
    line: Color,
    withDot: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = bg,
        border = BorderStroke(1.dp, line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (withDot) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
            }
            Text(
                text.uppercase(),
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.04.sp),
                color = fg,
            )
        }
    }
}

@Composable
fun VerdictChip(verdict: Verdict, modifier: Modifier = Modifier, label: String? = null) {
    val (bg, fg, line) = verdictChipColor(verdict)
    StatusChip(
        text = label ?: when (verdict) {
            Verdict.PASSED -> "Passed"
            Verdict.REVIEW -> "Review"
            Verdict.PENDING -> "Pending"
        },
        bg = bg, fg = fg, line = line, modifier = modifier,
    )
}

/* ------------------------------- Typography bits ------------------------------ */

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = TextStyle(fontFamily = com.veritransit.inspector.ui.theme.Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.09.sp),
            color = VT.Slate,
        )
        if (trailing != null) trailing()
    }
}

/* --------------------------------- Buttons --------------------------------- */

private val BtnShape = RoundedCornerShape(4.dp)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFFD7DCE3)
            pressed -> VT.PrimaryPressed
            else -> VT.Primary
        },
        animationSpec = tween(140),
        label = "btnBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (pressed) 0.985f else 1f
                scaleX = s; scaleY = s
            }
            .clip(BtnShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 17.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = VT.OnPrimary, modifier = Modifier.size(20.dp))
            Box(Modifier.size(10.dp))
        }
        Text(text, color = VT.OnPrimary, style = MaterialTheme.typography.labelLarge)
        if (trailingIcon != null) {
            Box(Modifier.size(10.dp))
            Icon(trailingIcon, null, tint = VT.OnPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) Color(0xFFF8FAFC) else VT.Surface, tween(140), label = "secBg")
    Row(
        modifier = modifier
            .clip(BtnShape)
            .background(bg)
            .border(1.dp, VT.Border, BtnShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = VT.Slate, modifier = Modifier.size(19.dp))
            Box(Modifier.size(9.dp))
        }
        Text(text, color = VT.Slate, style = MaterialTheme.typography.titleSmall)
    }
}

/* ---------------------------------- Cards ---------------------------------- */

@Composable
fun VTCard(
    modifier: Modifier = Modifier,
    bg: Color = VT.Surface,
    border: Boolean = true,
    radius: Dp = 8.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    var base = modifier.clip(shape).background(bg)
    if (border) base = base.border(1.dp, VT.Hairline, shape)
    Box(base.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        content()
    }
}

/* --------------------------- Animated check control --------------------------- */

@Composable
fun VTCheckbox(checked: Boolean, onToggle: () -> Unit, label: String, sub: String?, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (checked) VT.Primary else VT.Surface, tween(160), label = "cbBg")
    val border by animateColorAsState(if (checked) VT.Primary else VT.Border, tween(160), label = "cbBorder")
    val checkScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "cbCheck",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(bg)
                .border(1.5.dp, border, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check, null,
                tint = Color.White,
                modifier = Modifier.size(17.dp).graphicsLayer { scaleX = checkScale; scaleY = checkScale },
            )
        }
        Column {
            Text(label, style = MaterialTheme.typography.titleMedium, color = VT.Ink)
            if (sub != null) Text(sub, style = mono().dataSmall, color = VT.Muted)
        }
    }
}

/* --------------------------- Staggered list entrance --------------------------- */

fun Modifier.stagger(index: Int, key: Any? = null): Modifier = composed {
    var visible by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(index * 55L)
        visible = true
    }
    val t by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "stagger",
    )
    graphicsLayer { alpha = t; translationY = (1f - t) * 26f }
}

/* -------------------------------- Pulsing dot -------------------------------- */

@Composable
fun PulseDot(color: Color, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "pulseR",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "pulseA",
    )
    Box(contentAlignment = Alignment.Center) {
        // The halo scales on the draw layer only — a layout-affecting size here
        // re-measures the host row every frame and jitters everything beside it.
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                    this.alpha = alpha
                }
                .clip(CircleShape)
                .background(color)
        )
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

/* ------------------------------ Toast / snackbar ------------------------------ */

@Composable
fun ToastBar(message: String?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible && message != null,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.75f)) { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2F3038),
            shadowElevation = 6.dp,
        ) {
            Text(
                message.orEmpty(),
                color = Color(0xFFF1EFFA),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/* ------------------------------- Evidence art ------------------------------- */

data class Bounds(val x: Float, val y: Float, val w: Float, val h: Float, val color: Color, val tag: String)

/**
 * Stylized vector "evidence photo" of a cargo bay with AI bounding boxes.
 * Drawn entirely in code — zero image assets shipped.
 */
@Composable
fun EvidenceCanvas(modifier: Modifier = Modifier, boxes: List<Bounds>, timestamp: String = "14:22:09 · GPS Verified") {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(8.dp))) {
        val wDp = maxWidth
        val hDp = maxHeight
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2531), Color(0xFF0C131C)), startY = 0f, endY = h))
            drawLine(Color(0xFF2A3848), Offset(0f, h * 0.16f), Offset(w * 0.22f, h * 0.30f), 3f)
            drawLine(Color(0xFF2A3848), Offset(w, h * 0.16f), Offset(w * 0.78f, h * 0.30f), 3f)
            val crate = listOf(
                Triple(0.09f, 0.34f, 0.20f), Triple(0.30f, 0.30f, 0.24f), Triple(0.56f, 0.33f, 0.20f),
                Triple(0.12f, 0.58f, 0.17f), Triple(0.33f, 0.56f, 0.23f), Triple(0.58f, 0.57f, 0.18f),
                Triple(0.76f, 0.36f, 0.17f), Triple(0.76f, 0.60f, 0.17f),
            )
            crate.forEachIndexed { i, (rx, ry, rh) ->
                val bw = if (i % 3 == 1) w * 0.17f else w * 0.13f
                val bh = h * rh
                val left = w * rx
                val top = h * ry
                drawRoundRect(
                    Brush.linearGradient(
                        listOf(Color(0xFFB08D5F), Color(0xFF8A6A45)),
                        start = Offset(left, top), end = Offset(left + bw, top + bh),
                    ),
                    Offset(left, top), Size(bw, bh), CornerRadius(4f),
                )
                drawRect(Color(0x33FFFFFF), Offset(left + bw * 0.44f, top), Size(bw * 0.12f, bh))
                drawRect(Color(0x26000000), Offset(left, top + bh * 0.82f), Size(bw, bh * 0.18f))
            }
            val pl = w * 0.47f
            val pt = h * 0.60f
            drawRoundRect(Color(0xFF232B36), Offset(pl, pt), Size(w * 0.20f, h * 0.17f), CornerRadius(6f))
            drawRect(Color(0xFF63E3DC), Offset(pl + w * 0.03f, pt + h * 0.03f), Size(w * 0.10f, h * 0.045f))
            drawRect(Color(0xFF11161D), Offset(pl + w * 0.03f, pt + h * 0.10f), Size(w * 0.14f, h * 0.035f))
            drawRect(Color(0x55000000), Offset(0f, h * 0.86f), Size(w, h * 0.14f))
            drawRect(
                Brush.verticalGradient(listOf(Color(0x00000000), Color(0x99000000)), startY = h * 0.55f, endY = h),
                Offset.Zero, size,
            )
        }
        boxes.forEach { b ->
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRoundRect(
                            b.color,
                            topLeft = Offset(b.x * size.width, b.y * size.height),
                            size = Size(b.w * size.width, b.h * size.height),
                            cornerRadius = CornerRadius(6f),
                            style = Stroke(width = 5f),
                        )
                    }
            )
            Box(
                Modifier
                    .offset(x = wDp * b.x, y = hDp * b.y)
                    .background(b.color)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    b.tag,
                    style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    color = Color.White,
                )
            }
        }
        Text(
            timestamp,
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp),
            color = Color(0xFFE7ECF3),
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
    }
}
