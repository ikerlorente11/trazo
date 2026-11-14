package dev.pistation.trazo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ui {
    val bg = Color(0xFF1C1C1E)
    val canvasBg = Color(0xFF2A2A2E)
    val panel = Color(0xF2252528)
    val panel2 = Color(0xFF323236)
    val line = Color(0x33FFFFFF)
    val text = Color(0xFFF2F2F2)
    val dim = Color(0xFF9A9AA0)
    val accent = Color(0xFF4C8DFF)
}

val TrazoColors = darkColorScheme(
    primary = Ui.accent, background = Ui.bg, surface = Ui.panel2, onSurface = Ui.text, onBackground = Ui.text,
    surfaceVariant = Ui.panel2, onSurfaceVariant = Ui.dim,
)

@Composable
fun ToolIcon(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Ui.accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = when { !enabled -> Ui.dim.copy(alpha = 0.4f); selected -> Ui.accent; else -> Ui.text }, modifier = Modifier.size(24.dp))
    }
}

/** Vertical slider like the canvas sidebar: drag anywhere on the track. */
@Composable
fun VSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, onActive: (Boolean) -> Unit = {}) {
    var h by remember { mutableIntStateOf(1) }
    val v by rememberUpdatedState(value)
    Box(
        modifier.width(34.dp).clip(RoundedCornerShape(17.dp)).background(Color(0x55000000))
            .onSizeChanged { h = it.height }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onActive(true) },
                    onDragEnd = { onActive(false) },
                    onDragCancel = { onActive(false) },
                ) { change, drag ->
                    change.consume()
                    onChange((v - drag.y / h * 0.8f).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) { detectTapGestures { o -> onChange((1f - o.y / h).coerceIn(0f, 1f)) } },
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0.02f, 1f)).align(Alignment.BottomCenter)
            .background(Color(0x33FFFFFF)))
        val travel = with(LocalDensity.current) { (h.toDp() - 18.dp).coerceAtLeast(0.dp) }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 4.dp)
            .offset(y = -(travel * value.coerceIn(0f, 1f)) - 4.dp)
            .height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
    }
}

@Composable
fun PanelCard(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Ui.panel).border(1.dp, Ui.line, RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f, format: (Float) -> String = { "${(it * 100).toInt()} %" }, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(label, color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(format(value), color = Ui.dim, fontSize = 13.sp)
        }
        Slider(value, onChange, valueRange = range, modifier = Modifier.height(30.dp))
    }
}

@Composable
fun Swatch(color: Int, size: Dp = 30.dp, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Ui.line, CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ui.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun MenuRow(label: String, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (danger) Color(0xFFFF6B6B) else Ui.text, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
        Text(label, color = if (danger) Color(0xFFFF6B6B) else Ui.text, fontSize = 15.sp)
    }
}
