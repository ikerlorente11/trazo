package dev.pistation.trazo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

private val toolNames = mapOf(Tool.BRUSH to "Pinceles", Tool.SMUDGE to "Difuminar", Tool.ERASER to "Borrar", Tool.CLONE to "Clonar")

@Composable
fun BrushPanel(st: EditorState, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val current = st.brush
    val all = BrushLibrary.all + st.customBrushes
    var category by remember { mutableStateOf(current.category) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(st::importBrushes) }
    PanelCard("${toolNames[st.tool]}", modifier, actions = {
        ToolIcon(Icons.Filled.FileDownload, "Importar pinceles (.brush, .brushset)") { importLauncher.launch(arrayOf("*/*")) }
        ToolIcon(Icons.Filled.ContentCopy, "Duplicar pincel") { st.duplicateBrush(current) }
        ToolIcon(Icons.Filled.Tune, "Estudio de pinceles", selected = editing) { editing = !editing }
    }) {
        if (editing) BrushStudio(st, current)
        else Row(Modifier.height(480.dp)) {
            LazyColumn(Modifier.width(120.dp)) {
                items(all.map { it.category }.distinct()) { c ->
                    Text(c, color = if (c == category) Ui.accent else Ui.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (c == category) Ui.accent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { category = c }.padding(10.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(all.filter { it.category == category }) { b0 ->
                    val b = st.brushEdits[b0.id] ?: b0
                    val sel = b.id == current.id
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Ui.accent.copy(alpha = 0.22f) else Ui.panel2)
                        .clickable { st.selectBrush(b.id) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name + if (st.brushEdits.containsKey(b.id)) " ·" else "", color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (b.wet > 0f) Text("húmedo", color = Ui.dim, fontSize = 10.sp)
                            if (b.custom && sel) IconButton({ st.deleteBrush(b) }, Modifier.size(24.dp)) { Icon(Icons.Filled.Delete, "Eliminar", tint = Ui.dim, modifier = Modifier.size(16.dp)) }
                        }
                        StrokePreview(b, Modifier.fillMaxWidth().height(38.dp))
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
private fun BrushStudio(st: EditorState, b: Brush) {
    fun upd(nb: Brush) = st.editBrush(nb)
    var invert by remember { mutableStateOf(false) }
    val shapePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> st.setBrushImage(b, u, grain = false, invert = invert) } }
    val grainPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> st.setBrushImage(b, u, grain = true, invert = invert) } }
    var name by remember(b.id) { mutableStateOf(b.name) }
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b.custom) OutlinedTextField(name, { name = it; upd(b.copy(name = it)) }, singleLine = true, modifier = Modifier.weight(1f))
            else Text(b.name, color = Ui.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (!b.custom) TextButton({ st.resetBrush(b.id) }) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(4.dp)); Text("Restablecer") }
        }
        StrokePreview(b, Modifier.fillMaxWidth().height(50.dp))
        Section("Trazo")
        LabeledSlider("Estabilización (StreamLine)", b.streamline) { upd(b.copy(streamline = it)) }
        LabeledSlider("Espaciado", b.spacing, 0.01f..1f) { upd(b.copy(spacing = it)) }
        LabeledSlider("Afinado inicial", b.taperStart, 0f..200f, { "${it.toInt()} px" }) { upd(b.copy(taperStart = it)) }
        LabeledSlider("Sellos por paso", b.count.toFloat(), 1f..8f, { "${it.toInt()}" }) { upd(b.copy(count = it.toInt())) }
        Section("Forma")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Shape.entries.forEach { s -> FilterChip(b.shape == s && b.shapeImage == null, { upd(b.copy(shape = s, shapeImage = null)) }, label = { Text(s.label, fontSize = 12.sp) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ shapePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (b.shapeImage != null) "Cambiar forma importada" else "Importar forma…") }
            FilterChip(invert, { invert = !invert }, label = { Text("Invertir al importar", fontSize = 12.sp) })
        }
        LabeledSlider("Redondez", b.roundness, 0.05f..1f) { upd(b.copy(roundness = it)) }
        LabeledSlider("Ángulo", b.angle, 0f..180f, { "${it.toInt()}°" }) { upd(b.copy(angle = it)) }
        SettingSwitch("Girar con el trazo", b.rotateWithStroke) { upd(b.copy(rotateWithStroke = it)) }
        Section("Grano")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Grain.entries.forEach { g -> FilterChip(b.grain == g && b.grainImage == null, { upd(b.copy(grain = g, grainImage = null, grainDepth = if (g == Grain.NONE) 0f else max(b.grainDepth, 0.5f))) }, label = { Text(g.label, fontSize = 12.sp) }) }
        }
        TextButton({ grainPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (b.grainImage != null) "Cambiar grano importado" else "Importar grano…") }
        LabeledSlider("Profundidad del grano", b.grainDepth) { upd(b.copy(grainDepth = it)) }
        LabeledSlider("Escala del grano", b.grainScale, 30f..600f, { "${it.toInt()} px" }) { upd(b.copy(grainScale = it)) }
        Section("Dinámica")
        LabeledSlider("Dispersión", b.scatter, 0f..2f) { upd(b.copy(scatter = it)) }
        LabeledSlider("Variación de tamaño", b.sizeJitter) { upd(b.copy(sizeJitter = it)) }
        LabeledSlider("Variación de giro", b.rotationJitter) { upd(b.copy(rotationJitter = it)) }
        LabeledSlider("Variación de opacidad", b.opacityJitter) { upd(b.copy(opacityJitter = it)) }
        LabeledSlider("Variación de tono", b.hueJitter) { upd(b.copy(hueJitter = it)) }
        LabeledSlider("Flujo", b.flow, 0.02f..1f) { upd(b.copy(flow = it)) }
        Section("Mezcla húmeda")
        LabeledSlider("Arrastre de pintura (0 = seco)", b.wet) { upd(b.copy(wet = it)) }
        Section("Lápiz")
        LabeledSlider("Presión → tamaño", b.pressureSize) { upd(b.copy(pressureSize = it)) }
        LabeledSlider("Tamaño mínimo", b.minSizeFraction) { upd(b.copy(minSizeFraction = it)) }
        LabeledSlider("Presión → opacidad", b.pressureOpacity) { upd(b.copy(pressureOpacity = it)) }
        LabeledSlider("Respuesta a la presión", b.pressureGamma, 0.3f..3f, { "%.2f".format(it) }) { upd(b.copy(pressureGamma = it)) }
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
