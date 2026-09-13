package com.veritransit.inspector.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT

/**
 * − value + for a whole number of [what]. The value can be typed too: a typed
 * number is held inside [range], and a cleared field keeps the last value
 * until a digit goes in.
 */
@Composable
fun CountStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    what: String,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepperButton(Icons.Rounded.Remove, "One fewer $what", enabled = value > range.first) {
            onValueChange(value - 1)
        }
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val typed = raw.filter { it.isDigit() }.take(range.last.toString().length).toIntOrNull()
                if (typed == null) {
                    text = ""
                } else {
                    val held = typed.coerceIn(range)
                    text = held.toString()
                    if (held != value) onValueChange(held)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = style.copy(color = VT.Ink),
            cursorBrush = SolidColor(VT.Primary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(VT.Surface)
                        .border(1.dp, VT.Border, RoundedCornerShape(4.dp))
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (text.isEmpty()) Text("$value", style = style, color = VT.Faint)
                    inner()
                }
            },
            modifier = Modifier.width(66.dp),
        )
        StepperButton(Icons.Rounded.Add, "One more $what", enabled = value < range.last) {
            onValueChange(value + 1)
        }
    }
}

@Composable
private fun StepperButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(VT.Inset)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) VT.Slate else VT.Faint, modifier = Modifier.size(18.dp))
    }
}
