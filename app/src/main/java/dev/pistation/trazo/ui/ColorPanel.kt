@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.pistation.trazo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

fun hsvOf(c: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
fun colorOf(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
fun hex(c: Int) = String.format("%06X", c and 0xFFFFFF)

private val hueColors = (0..6).map { Color(colorOf(it * 60f, 1f, 1f)) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorPanel(st: EditorState, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    // Keep hue/saturation while the colour is grey or black.
    var hsv by remember { mutableStateOf(hsvOf(st.color)) }
    LaunchedEffect(st.color) {
        if (colorOf(hsv[0], hsv[1], hsv[2]) != st.color or (0xFF shl 24)) hsv = hsvOf(st.color)
    }
    fun set(h: Float, s: Float, v: Float) { hsv = floatArrayOf(h, s, v); st.setColor(colorOf(h, s, v)) }

    PanelCard("Colores", modifier, actions = {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(st.color)).border(2.dp, Color.White, CircleShape))
    }) {
        // Recent colours
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            st.recent.take(10).forEach { c -> Swatch(c, 24.dp) { st.setColor(c) } }
        }
        Spacer(Modifier.height(10.dp))
        val tabs = listOf("Disco", "Clásico", "Armonía", "Valores", "Paletas")
        ScrollableTabRow(tab, edgePadding = 0.dp, containerColor = Color.Transparent, divider = {}) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> DiscPicker(hsv) { h, s, v -> set(h, s, v) }
            1 -> ClassicPicker(hsv) { h, s, v -> set(h, s, v) }
            2 -> Harmony(hsv) { h, s, v -> set(h, s, v) }
            3 -> Values(st, hsv) { h, s, v -> set(h, s, v) }
            4 -> Palettes(st)
        }
    }
}

@Composable
private fun DiscPicker(hsv: FloatArray, onChange: (Float, Float, Float) -> Unit) {
    val cur by rememberUpdatedState(hsv)
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    var ring = false
                    fun handle(p: Offset, start: Boolean) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = size.width / 2f
                        val d = p - c
                        val dist = d.getDistance()
                        if (start) ring = dist > r * 0.78f
                        if (ring) {
                            val ang = (Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat() + 360f) % 360f
                            onChange(ang, cur[1], cur[2])
                        } else {
                            val half = r * 0.66f / sqrt(2f) * 1.0f
                            val sx = ((d.x + half) / (2 * half)).coerceIn(0f, 1f)
                            val sy = ((d.y + half) / (2 * half)).coerceIn(0f, 1f)
                            onChange(cur[0], sx, 1f - sy)
                        }
                    }
                    detectDragGestures(onDragStart = { handle(it, true) }) { ch, _ -> ch.consume(); handle(ch.position, false) }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val c = Offset(size.width / 2f, size.height / 2f); val r = size.width / 2f
                        val d = p - c
                        if (d.getDistance() > r * 0.78f) onChange((Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat() + 360f) % 360f, cur[1], cur[2])
                        else {
                            val half = r * 0.66f / sqrt(2f)
                            onChange(cur[0], ((d.x + half) / (2 * half)).coerceIn(0f, 1f), 1f - ((d.y + half) / (2 * half)).coerceIn(0f, 1f))
                        }
                    }
                }
        ) {
            val r = size.width / 2f
            val c = center
            val ringW = r * 0.18f
            drawCircle(Brush.sweepGradient(hueColors, c), radius = r - ringW / 2, style = Stroke(ringW))
            val half = r * 0.66f / sqrt(2f)
            val tl = Offset(c.x - half, c.y - half)
            val sz = Size(half * 2, half * 2)
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(colorOf(cur[0], 1f, 1f))), tl.x, tl.x + sz.width), tl, sz)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), tl.y, tl.y + sz.height), tl, sz)
            val a = Math.toRadians(cur[0].toDouble())
            val hp = Offset(c.x + cos(a).toFloat() * (r - ringW / 2), c.y + sin(a).toFloat() * (r - ringW / 2))
            drawCircle(Color.White, ringW * 0.42f, hp, style = Stroke(4f))
            val sp = Offset(tl.x + cur[1] * sz.width, tl.y + (1 - cur[2]) * sz.height)
            drawCircle(Color.White, 14f, sp, style = Stroke(4f))
            drawCircle(Color.Black, 18f, sp, style = Stroke(1.5f))
        }
    }
}

@Composable
private fun ClassicPicker(hsv: FloatArray, onChange: (Float, Float, Float) -> Unit) {
    val cur by rememberUpdatedState(hsv)
    Column {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    fun h(p: Offset) = onChange(cur[0], (p.x / size.width).coerceIn(0f, 1f), 1f - (p.y / size.height).coerceIn(0f, 1f))
                    detectDragGestures(onDragStart = { h(it) }) { ch, _ -> ch.consume(); h(ch.position) }
                }
                .pointerInput(Unit) { detectTapGestures { p -> onChange(cur[0], (p.x / size.width).coerceIn(0f, 1f), 1f - (p.y / size.height).coerceIn(0f, 1f)) } }
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(colorOf(cur[0], 1f, 1f)))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(cur[1] * size.width, (1 - cur[2]) * size.height)
            drawCircle(Color.White, 14f, p, style = Stroke(4f)); drawCircle(Color.Black, 18f, p, style = Stroke(1.5f))
        }
        Spacer(Modifier.height(12.dp))
        HueBar(cur[0]) { onChange(it, cur[1], cur[2]) }
    }
}

@Composable
fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { onChange(it.x / size.width * 360f) }) { ch, _ -> ch.consume(); onChange((ch.position.x / size.width).coerceIn(0f, 1f) * 360f) }
            }
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f) * 360f) } }
    ) {
        drawRect(Brush.horizontalGradient(hueColors))
        val x = hue / 360f * size.width
        drawCircle(Color.White, size.height * 0.38f, Offset(x, size.height / 2), style = Stroke(4f))
    }
}

@Composable
private fun Harmony(hsv: FloatArray, onChange: (Float, Float, Float) -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    val modes = listOf("Complementario" to listOf(0f, 180f), "Compl. dividido" to listOf(0f, 150f, 210f),
        "Análogo" to listOf(0f, -30f, 30f), "Triádico" to listOf(0f, 120f, 240f), "Tetrádico" to listOf(0f, 90f, 180f, 270f))
    Column {
        DiscPicker(hsv, onChange)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modes.forEachIndexed { i, (n, _) -> FilterChip(mode == i, { mode = i }, label = { Text(n, fontSize = 12.sp) }) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            modes[mode].second.forEach { off ->
                val c = colorOf(hsv[0] + off, hsv[1], hsv[2])
                Swatch(c, 44.dp, off == 0f) { onChange(hsv[0] + off, hsv[1], hsv[2]) }
            }
        }
    }
}

@Composable
private fun Values(st: EditorState, hsv: FloatArray, onChange: (Float, Float, Float) -> Unit) {
    val c = st.color
    Column {
        LabeledSlider("Tono", hsv[0], 0f..360f, { "${it.toInt()}°" }) { onChange(it, hsv[1], hsv[2]) }
        LabeledSlider("Saturación", hsv[1]) { onChange(hsv[0], it, hsv[2]) }
        LabeledSlider("Brillo", hsv[2]) { onChange(hsv[0], hsv[1], it) }
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        LabeledSlider("Rojo", r.toFloat(), 0f..255f, { it.toInt().toString() }) { st.setColor((it.toInt() shl 16) or (g shl 8) or b) }
        LabeledSlider("Verde", g.toFloat(), 0f..255f, { it.toInt().toString() }) { st.setColor((r shl 16) or (it.toInt() shl 8) or b) }
        LabeledSlider("Azul", b.toFloat(), 0f..255f, { it.toInt().toString() }) { st.setColor((r shl 16) or (g shl 8) or it.toInt()) }
        var text by remember(c) { mutableStateOf(hex(c)) }
        OutlinedTextField(text, { v ->
            text = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
            if (text.length == 6) st.setColor(text.toLong(16).toInt())
        }, label = { Text("Hexadecimal") }, prefix = { Text("#") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Palettes(st: EditorState) {
    Column {
        Text("Toca para usar · mantén pulsado para quitar", color = Ui.dim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            st.palette.forEach { c ->
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(c)).border(1.dp, Ui.line, RoundedCornerShape(6.dp))
                    .combinedClickable(onLongClick = { st.removeSwatch(c) }) { st.setColor(c, remember = true) })
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Ui.dim, RoundedCornerShape(6.dp))
                .combinedClickable { st.addSwatch() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, "Añadir color", tint = Ui.text)
            }
        }
    }
}
