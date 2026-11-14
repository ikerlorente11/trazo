package dev.pistation.trazo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pistation.trazo.engine.*
import kotlin.math.*

private val toolNames = mapOf(Tool.BRUSH to "Pinceles", Tool.SMUDGE to "Difuminar", Tool.ERASER to "Borrar")

@Composable
fun BrushPanel(st: EditorState, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val current = st.brush
    var category by remember { mutableStateOf(BrushLibrary.byId(st.brushIds[st.tool]!!).category) }
    PanelCard("Biblioteca · ${toolNames[st.tool]}", modifier, actions = {
        ToolIcon(Icons.Filled.Tune, "Ajustes del pincel", selected = editing) { editing = !editing }
    }) {
        if (editing) BrushSettings(st, current)
        else Row(Modifier.height(460.dp)) {
            LazyColumn(Modifier.width(120.dp)) {
                items(BrushLibrary.categories) { c ->
                    Text(c, color = if (c == category) Ui.accent else Ui.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (c == category) Ui.accent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { category = c }.padding(10.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(BrushLibrary.all.filter { it.category == category }) { b ->
                    val sel = b.id == current.id
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Ui.accent.copy(alpha = 0.22f) else Ui.panel2)
                        .clickable { st.selectBrush(b.id) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(b.name + if (st.brushEdits.containsKey(b.id)) " ·" else "", color = Ui.text, fontSize = 13.sp)
                        StrokePreview(st.brushEdits[b.id] ?: b, Modifier.fillMaxWidth().height(38.dp))
                    }
                }
            }
        }
    }
}

/** Rough stroke sample drawn with circles (an S curve with a pressure swell). */
@Composable
fun StrokePreview(b: Brush, modifier: Modifier) {
    Canvas(modifier) {
        val n = 70
        val maxR = size.height * 0.36f
        for (i in 0..n) {
            val t = i / n.toFloat()
            val p = sin(t * PI).toFloat()
            val x = size.width * (0.04f + 0.92f * t)
            val y = size.height / 2 + sin(t * 2 * PI).toFloat() * size.height * 0.22f
            val f = 1f + (b.minSizeFraction + (1 - b.minSizeFraction) * p - 1f) * b.pressureSize
            val a = (b.flow * (1f + (p - 1f) * b.pressureOpacity)).coerceIn(0.03f, 1f)
            val soft = b.shape == Shape.ROUND_SOFT
            val r = maxR * f
            if (b.shape == Shape.SPRAY || b.scatter > 0.5f) {
                if (i % 3 == 0) repeat(3) { k ->
                    val ang = (i * 37 + k * 101) % 360 * PI.toFloat() / 180f
                    drawCircle(Color.White.copy(alpha = a * 0.8f), r * 0.25f, Offset(x + cos(ang) * r, y + sin(ang) * r))
                }
            } else drawCircle(Color.White.copy(alpha = if (soft) a * 0.25f else min(1f, a * 0.6f)), r, Offset(x, y))
        }
    }
}

@Composable
private fun BrushSettings(st: EditorState, b: Brush) {
    fun upd(nb: Brush) { st.brushEdits[b.id] = nb }
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.name, color = Ui.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            TextButton({ st.brushEdits.remove(b.id) }) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(4.dp)); Text("Restablecer") }
        }
        StrokePreview(b, Modifier.fillMaxWidth().height(50.dp))
        Section("Trazo")
        LabeledSlider("Estabilización (StreamLine)", b.streamline) { upd(b.copy(streamline = it)) }
        LabeledSlider("Espaciado", b.spacing, 0.01f..1f) { upd(b.copy(spacing = it)) }
        LabeledSlider("Afinado inicial", b.taperStart, 0f..200f, { "${it.toInt()} px" }) { upd(b.copy(taperStart = it)) }
        Section("Forma y grano")
        LabeledSlider("Redondez", b.roundness, 0.05f..1f) { upd(b.copy(roundness = it)) }
        LabeledSlider("Ángulo", b.angle, 0f..180f, { "${it.toInt()}°" }) { upd(b.copy(angle = it)) }
        SettingSwitch("Girar con el trazo", b.rotateWithStroke) { upd(b.copy(rotateWithStroke = it)) }
        LabeledSlider("Profundidad del grano", b.grainDepth) { upd(b.copy(grainDepth = it)) }
        LabeledSlider("Escala del grano", b.grainScale, 30f..600f, { "${it.toInt()} px" }) { upd(b.copy(grainScale = it)) }
        Section("Dinámica")
        LabeledSlider("Dispersión", b.scatter, 0f..2f) { upd(b.copy(scatter = it)) }
        LabeledSlider("Variación de tamaño", b.sizeJitter) { upd(b.copy(sizeJitter = it)) }
        LabeledSlider("Variación de giro", b.rotationJitter) { upd(b.copy(rotationJitter = it)) }
        LabeledSlider("Variación de opacidad", b.opacityJitter) { upd(b.copy(opacityJitter = it)) }
        LabeledSlider("Flujo", b.flow, 0.02f..1f) { upd(b.copy(flow = it)) }
        Section("Lápiz")
        LabeledSlider("Presión → tamaño", b.pressureSize) { upd(b.copy(pressureSize = it)) }
        LabeledSlider("Tamaño mínimo", b.minSizeFraction) { upd(b.copy(minSizeFraction = it)) }
        LabeledSlider("Presión → opacidad", b.pressureOpacity) { upd(b.copy(pressureOpacity = it)) }
        Section("Límites")
        LabeledSlider("Tamaño máximo", b.sizeMax, 4f..1500f, { "${it.toInt()} px" }) { upd(b.copy(sizeMax = max(it, b.sizeMin + 1))) }
        LabeledSlider("Tamaño mínimo absoluto", b.sizeMin, 0.5f..100f, { "%.1f px".format(it) }) { upd(b.copy(sizeMin = min(it, b.sizeMax - 1))) }
        LabeledSlider("Fuerza de difuminado", b.smudge) { upd(b.copy(smudge = it)) }
    }
}

@Composable
private fun Section(t: String) {
    Text(t.uppercase(), color = Ui.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}
