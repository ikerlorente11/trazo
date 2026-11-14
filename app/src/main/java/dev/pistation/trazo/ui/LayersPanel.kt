package dev.pistation.trazo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.pistation.trazo.engine.BlendMode
import dev.pistation.trazo.engine.LayerInfo
import kotlin.math.roundToInt

private val ROW = 64.dp

@Composable
fun LayersPanel(st: EditorState, modifier: Modifier = Modifier) {
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var blendFor by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<LayerInfo?>(null) }
    var bgPicker by remember { mutableStateOf(false) }
    val top = st.layers.asReversed()   // top layer first, like the stack looks

    PanelCard("Capas", modifier, actions = {
        ToolIcon(Icons.Filled.Add, "Nueva capa") { st.addLayer() }
    }) {
        val rowPx = with(LocalDensity.current) { (ROW + 6.dp).toPx() }
        var dragId by remember { mutableStateOf<Long?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            top.forEachIndexed { visualIndex, l ->
                val dragging = dragId == l.id
                Box(Modifier.zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 16f else 0f }
                    .pointerInput(l.id, top.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragId = l.id; dragOffset = 0f },
                            onDragEnd = {
                                val steps = (dragOffset / rowPx).roundToInt()
                                val toVisual = (visualIndex + steps).coerceIn(0, top.size - 1)
                                val from = top.size - 1 - visualIndex
                                val to = top.size - 1 - toVisual
                                if (from != to) st.moveLayer(from, to)
                                dragId = null; dragOffset = 0f
                            },
                            onDragCancel = { dragId = null; dragOffset = 0f },
                        ) { ch, d -> ch.consume(); dragOffset += d.y }
                    }
                ) {
                    LayerRow(st, l, l.id == st.activeId,
                        onTap = { if (l.id == st.activeId) menuFor = l.id else st.setActive(l.id) },
                        onBlend = { blendFor = if (blendFor == l.id) null else l.id })
                    DropdownMenu(menuFor == l.id, { menuFor = null }) {
                        val idx = st.layers.indexOfFirst { it.id == l.id }
                        DropdownMenuItem({ Text("Renombrar") }, { menuFor = null; renaming = l }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                        DropdownMenuItem({ Text("Duplicar") }, { menuFor = null; st.duplicateLayer(l.id) }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                        if (idx > 0) DropdownMenuItem({ Text("Combinar hacia abajo") }, { menuFor = null; st.mergeDown(l.id) }, leadingIcon = { Icon(Icons.Filled.MergeType, null) })
                        DropdownMenuItem({ Text(if (l.props.alphaLock) "Quitar bloqueo alfa" else "Bloqueo alfa") },
                            { menuFor = null; st.setProps(l.id, l.props.copy(alphaLock = !l.props.alphaLock)) }, leadingIcon = { Icon(Icons.Filled.Lock, null) })
                        if (idx > 0) DropdownMenuItem({ Text(if (l.props.clip) "Quitar máscara de recorte" else "Máscara de recorte") },
                            { menuFor = null; st.setProps(l.id, l.props.copy(clip = !l.props.clip)) }, leadingIcon = { Icon(Icons.Filled.SubdirectoryArrowRight, null) })
                        DropdownMenuItem({ Text("Rellenar capa") }, { menuFor = null; st.fillLayer() }, leadingIcon = { Icon(Icons.Filled.FormatColorFill, null) })
                        DropdownMenuItem({ Text("Borrar contenido") }, { menuFor = null; st.clearLayer() }, leadingIcon = { Icon(Icons.Filled.LayersClear, null) })
                        DropdownMenuItem({ Text("Voltear horizontal") }, { menuFor = null; st.flipLayer(true) }, leadingIcon = { Icon(Icons.Filled.Flip, null) })
                        DropdownMenuItem({ Text("Voltear vertical") }, { menuFor = null; st.flipLayer(false) }, leadingIcon = { Icon(Icons.Filled.Flip, null, Modifier.graphicsLayer { rotationZ = 90f }) })
                        if (st.layers.size > 1) DropdownMenuItem({ Text("Eliminar", color = Color(0xFFFF6B6B)) }, { menuFor = null; st.deleteLayer(l.id) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF6B6B)) })
                    }
                }
                if (blendFor == l.id) BlendEditor(st, l)
                Spacer(Modifier.height(6.dp))
            }
            // Background colour layer
            Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(Ui.panel2)
                .clickable { bgPicker = !bgPicker }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(Color(st.bgColor)).border(1.dp, Ui.line, RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(10.dp))
                Text("Color de fondo", color = Ui.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Checkbox(st.bgVisible, { st.setBackground(st.bgColor, it) })
            }
            if (bgPicker) {
                Spacer(Modifier.height(8.dp))
                Text("Toca para usar el color actual como fondo", color = Ui.dim, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Swatch(st.color, 34.dp) { st.setBackground(st.color, true) }
                    listOf(0xFFFFFFFF, 0xFFF3EEE3, 0xFF808080, 0xFF000000).forEach { c ->
                        Swatch(c.toInt(), 34.dp) { st.setBackground(c.toInt(), true) }
                    }
                }
            }
        }
    }

    renaming?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.props.name) }
        AlertDialog(onDismissRequest = { renaming = null },
            confirmButton = { TextButton({ st.setProps(l.id, l.props.copy(name = name.ifBlank { l.props.name })); renaming = null }) { Text("Aceptar") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancelar") } },
            title = { Text("Renombrar capa") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) })
    }
}

@Composable
private fun LayerRow(st: EditorState, l: LayerInfo, active: Boolean, onTap: () -> Unit, onBlend: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(ROW).clip(RoundedCornerShape(10.dp))
        .background(if (active) Ui.accent.copy(alpha = 0.85f) else Ui.panel2)
        .clickable(onClick = onTap).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (l.props.clip) Icon(Icons.Filled.SubdirectoryArrowRight, "Recorte", tint = Ui.text, modifier = Modifier.size(18.dp))
        Box(Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(Checker), contentAlignment = Alignment.Center) {
            l.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(l.props.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                if (l.props.alphaLock) Icon(Icons.Filled.Lock, "Bloqueo alfa", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                if (l.props.opacity < 1f) Text(" ${(l.props.opacity * 100).toInt()} %", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        }
        Text(l.props.blend.short, color = Color.White, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBlend).padding(horizontal = 10.dp, vertical = 8.dp))
        Checkbox(l.props.visible, { st.setProps(l.id, l.props.copy(visible = it)) },
            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Ui.accent))
    }
}

private val Checker = Color(0xFFDADADA)

@Composable
private fun BlendEditor(st: EditorState, l: LayerInfo) {
    var opacity by remember(l.id) { mutableFloatStateOf(l.props.opacity) }
    var startProps by remember(l.id) { mutableStateOf<dev.pistation.trazo.engine.LayerProps?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B2B2F)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacidad", color = Ui.text, fontSize = 13.sp, modifier = Modifier.width(80.dp))
            Slider(opacity, { if (startProps == null) startProps = l.props; opacity = it; st.setProps(l.id, l.props.copy(opacity = it), record = false) },
                onValueChangeFinished = { st.setProps(l.id, l.props.copy(opacity = opacity), before = startProps); startProps = null }, modifier = Modifier.weight(1f))
            Text("${(opacity * 100).toInt()}", color = Ui.dim, fontSize = 13.sp, modifier = Modifier.width(34.dp))
        }
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            BlendMode.entries.groupBy { it.group }.values.forEach { group ->
                HorizontalDivider(color = Ui.line, modifier = Modifier.padding(vertical = 4.dp))
                group.forEach { m ->
                    Text(m.label, color = if (m == l.props.blend) Ui.accent else Ui.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { st.setProps(l.id, l.props.copy(blend = m)) }.padding(vertical = 6.dp, horizontal = 4.dp))
                }
            }
        }
    }
}
