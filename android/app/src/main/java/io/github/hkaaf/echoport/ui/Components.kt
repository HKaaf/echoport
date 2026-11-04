package io.github.hkaaf.echoport.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EpCard(content: @Composable ColumnScope.() -> Unit) {
    val ep = LocalEp.current
    Surface(
        color = ep.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ep.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

@Composable
fun Hint(text: String, color: Color = LocalEp.current.muted) = Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun PageHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Hint(subtitle)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: (Int) -> Boolean = { true }) {
    val ep = LocalEp.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                enabled = enabled(i),
                shape = RoundedCornerShape(10.dp),
                color = if (on) ep.accent else ep.surface2,
                contentColor = if (on) ep.onAccent else if (enabled(i)) ep.text else ep.muted,
                border = BorderStroke(1.dp, if (on) ep.accent else ep.border),
                modifier = Modifier.weight(1f).height(46.dp).semantics { role = Role.RadioButton },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String = "", enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            if (hint.isNotEmpty()) Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
                  onChange: (Float) -> Unit, hint: String = "", steps: Int = 0) {
    Column {
        Row {
            Text(label, color = LocalEp.current.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        if (hint.isNotEmpty()) Hint(hint)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors()) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(44.dp), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, LocalEp.current.border)) {
        Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Badge(text: String) {
    val ep = LocalEp.current
    Surface(color = ep.surface2, shape = RoundedCornerShape(6.dp)) {
        Text(text.uppercase(), color = ep.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** The Echoport mark, always in the fixed brand colors. */
@Composable
fun Logo(size: Dp = 32.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 64f
        drawRoundRect(Brush.linearGradient(listOf(Brand.blue, Brand.purple), Offset.Zero, Offset(this.size.width, this.size.height)),
            cornerRadius = CornerRadius(16 * u, 16 * u), size = Size(this.size.width, this.size.height))
        drawCircle(Color.White, radius = 6 * u, center = Offset(22 * u, 32 * u))
        val stroke = Stroke(width = 4.5f * u, cap = StrokeCap.Round)
        drawArc(Color.White, -45f, 90f, false, Offset(16 * u, 15 * u), Size(34 * u, 34 * u), style = stroke)
        drawArc(Color.White.copy(alpha = 0.6f), -45f, 90f, false, Offset(4 * u, -1 * u), Size(54 * u, 66 * u), style = stroke)
    }
}

/** Parses the QR payload `echoport://ip1,ip2:port?name=..&pin=..`. */
fun parseEchoportUri(text: String): Triple<List<String>, Int, String?>? {
    if (!text.startsWith("echoport://")) return null
    val rest = text.removePrefix("echoport://")
    val hostPart = rest.substringBefore('?')
    val query = rest.substringAfter('?', "")
    val port = hostPart.substringAfterLast(':', "").toIntOrNull() ?: 47210
    val ips = hostPart.substringBeforeLast(':').split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val pin = query.split('&').firstOrNull { it.startsWith("pin=") }?.removePrefix("pin=")
    return if (ips.isEmpty()) null else Triple(ips, port, pin)
}
