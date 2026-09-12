package com.veritransit.inspector.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT

/** Header for multi-step inspection flow screens. */
@Composable
fun FlowHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.background(VT.Alabaster).statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = VT.Ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = VT.Ink)
                Text(subtitle, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp), color = VT.Muted)
            }
            if (trailing != null) trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
    }
}

/** Small mono step badge shown at the right edge of a flow header. */
@Composable
fun StepBadge(step: Int) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(VT.Surface)
            .border(1.dp, VT.Hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(VT.Primary))
        Text(
            "Step $step of 3",
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
            color = VT.Slate,
        )
    }
}

/** Scrollable body + sticky bottom action bar with a soft fade into the canvas. */
@Composable
fun FlowScaffold(
    bottomBar: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(VT.Alabaster)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = if (bottomBar != null) 128.dp else 24.dp),
        ) {
            content()
        }
        if (bottomBar != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(VT.Alabaster.copy(alpha = 0f), VT.Alabaster, VT.Alabaster))
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                bottomBar()
            }
        }
    }
}

/** Mono field label used inside forms and summary cards. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, color: Color = VT.Muted) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.07.sp),
        color = color,
    )
}

/** Amber informational strip (depot notices). */
@Composable
fun NoticeStrip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VT.AmberBg)
            .border(1.dp, VT.AmberLine, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Info, null, tint = VT.AmberDot, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF78350F), lineHeight = 21.sp)
    }
}
