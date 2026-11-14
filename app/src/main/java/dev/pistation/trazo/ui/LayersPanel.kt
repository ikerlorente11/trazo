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
import dev.pistation.trazo.engine.LayerProps
import kotlin.math.roundToInt

private val ROW = 64.dp
private val DANGER = Color(0xFFFF6B6B)

@Composable
fun LayersPanel(st: EditorState, modifier: Modifier = Modifier) {
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var blendFor by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<LayerInfo?>(null) }
    var bgPicker by remember { mutableStateOf(false) }
    // Top layer first, like the stack looks; children of folded groups hidden.
    val collapsed = st.layers.filter { it.isGroup && it.props.collapsed }.map { it.id }.toSet()
    val rows = st.layers.asReversed().filter { it.parent == 0L || it.parent !in collapsed }

    PanelCard("Capas", modifier, actions = {
        ToolIcon(Icons.Filled.CreateNewFolder, "Nuevo grupo") { st.newGroup(st.activeId) }
        ToolIcon(Icons.Filled.Add, "Nueva capa") { st.addLayer() }
    }) {
        val rowPx = with(LocalDensity.current) { (ROW + 6.dp).toPx() }
        var dragId by remember { mutableStateOf<Long?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { visualIndex, l ->
                val dragging = dragId == l.id
                Box(Modifier.zIndex(if (dragging) 1f else 0f)
                    .padding(start = if (l.parent != 0L) 18.dp else 0.dp)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 16f else 0f }
                    .pointerInput(l.id, rows.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragId = l.id; dragOffset = 0f },
                            onDragEnd = {
                                val steps = (dragOffset / rowPx).roundToInt()
                                val toVisual = (visualIndex + steps).coerceIn(0, rows.size - 1)
                                val from = st.layers.indexOfFirst { it.id == l.id }
                                val to = st.layers.indexOfFirst { it.id == rows[toVisual].id }
                                if (from != to && from >= 0 && to >= 0) st.moveLayer(from, to)
                                dragId = null; dragOffset = 0f
                            },
                            onDragCancel = { dragId = null; dragOffset = 0f },
                        ) { ch, d -> ch.consume(); dragOffset += d.y }
                    }
                ) {
                    if (l.isGroup) GroupRow(st, l, l.id == st.activeId, onTap = { if (l.id == st.activeId) menuFor = l.id else st.setActive(l.id) })
                    else LayerRow(st, l, l.id == st.activeId,
                        onTap = { if (l.id == st.activeId && !st.editingMask) menuFor = l.id else st.setActive(l.id) },
                        onBlend = { blendFor = if (blendFor == l.id) null else l.id })
                    LayerMenu(st, l, menuFor == l.id, { menuFor = null }, { renaming = it })
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
                    listOf(0xFFFFFFFF, 0xFFF3EEE3, 0xFF808080, 0xFF000000).forEach { c -> Swatch(c.toInt(), 34.dp) { st.setBackground(c.toInt(), true) } }
                }
            }
        }
    }

    renaming?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.props.name) }
        AlertDialog(onDismissRequest = { renaming = null },
            confirmButton = { TextButton({ st.setProps(l.id, l.props.copy(name = name.ifBlank { l.props.name })); renaming = null }) { Text("Aceptar") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancelar") } },
            title = { Text("Renombrar") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) })
    }
}

@Composable
private fun LayerMenu(st: EditorState, l: LayerInfo, open: Boolean, close: () -> Unit, rename: (LayerInfo) -> Unit) {
    DropdownMenu(open, close) {
        val idx = st.layers.indexOfFirst { it.id == l.id }
        val below = st.layers.getOrNull(idx - 1)
        @Composable fun item(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, action: () -> Unit) =
            DropdownMenuItem({ Text(text, color = if (danger) DANGER else Color.Unspecified) }, { close(); action() },
                leadingIcon = { Icon(icon, null, tint = if (danger) DANGER else LocalContentColor.current) })
        item("Renombrar", Icons.Filled.Edit) { rename(l) }
        item("Duplicar", Icons.Filled.ContentCopy) { st.duplicateLayer(l.id) }
        if (l.isGroup) {
            item(if (l.props.collapsed) "Desplegar" else "Plegar", Icons.Filled.UnfoldLess) { st.setProps(l.id, l.props.copy(collapsed = !l.props.collapsed), record = false) }
            item("Combinar el grupo en una capa", Icons.Filled.MergeType) { st.mergeGroup(l.id) }
            item("Desagrupar", Icons.Filled.FolderOff) { st.ungroup(l.id) }
        } else {
            if (below != null && !below.isGroup && below.parent == l.parent) item("Combinar hacia abajo", Icons.Filled.MergeType) { st.mergeDown(l.id) }
            if (l.parent == 0L) {
                item("Meter en un grupo nuevo", Icons.Filled.CreateNewFolder) { st.newGroup(l.id) }
                if (below != null && !below.isGroup && below.parent == 0L) item("Agrupar con la de abajo", Icons.Filled.Folder) { st.groupWithBelow(l.id) }
            }
            item("Seleccionar su contenido", Icons.Filled.HighlightAlt) { st.selectLayerContent(l.id) }
            item("Copiar", Icons.Filled.CopyAll) { st.setActive(l.id); st.copy() }
            item(if (l.props.alphaLock) "Quitar bloqueo alfa" else "Bloqueo alfa", Icons.Filled.Lock) { st.setProps(l.id, l.props.copy(alphaLock = !l.props.alphaLock)) }
            if (below != null && !below.isGroup && below.parent == l.parent)
                item(if (l.props.clip) "Quitar máscara de recorte" else "Máscara de recorte", Icons.Filled.SubdirectoryArrowRight) { st.setProps(l.id, l.props.copy(clip = !l.props.clip)) }
            if (l.hasMask) {
                item("Pintar la máscara", Icons.Filled.Brush) { st.editMask(l.id) }
                item(if (l.props.maskEnabled) "Desactivar la máscara" else "Activar la máscara", Icons.Filled.VisibilityOff) { st.setProps(l.id, l.props.copy(maskEnabled = !l.props.maskEnabled)) }
                item("Invertir la máscara", Icons.Filled.InvertColors) { st.invertMask(l.id) }
                item("Aplicar la máscara", Icons.Filled.Done) { st.applyMask(l.id) }
                item("Eliminar la máscara", Icons.Filled.Delete, danger = true) { st.deleteMask(l.id) }
            } else item("Máscara de capa", Icons.Filled.Masks) { st.addMask(l.id) }
            item(if (l.props.reference) "Dejar de ser referencia" else "Referencia (para rellenar)", Icons.Filled.Pageview) { st.setProps(l.id, l.props.copy(reference = !l.props.reference)) }
            item("Invertir colores", Icons.Filled.InvertColors) { st.setActive(l.id); st.invertColors() }
            item("Rellenar capa", Icons.Filled.FormatColorFill) { st.setActive(l.id); st.fillLayer() }
            item("Borrar contenido", Icons.Filled.LayersClear) { st.setActive(l.id); st.clearLayer() }
        }
        if (st.layers.count { !it.isGroup } > 1 || l.isGroup) item("Eliminar", Icons.Filled.Delete, danger = true) { st.deleteLayer(l.id) }
    }
}

@Composable
private fun GroupRow(st: EditorState, l: LayerInfo, active: Boolean, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp))
        .background(if (active) Ui.accent.copy(alpha = 0.85f) else Color(0xFF3A3A40))
        .clickable(onClick = onTap).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ st.setProps(l.id, l.props.copy(collapsed = !l.props.collapsed), record = false) }, Modifier.size(36.dp)) {
            Icon(if (l.props.collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, "Plegar", tint = Color.White)
        }
        Icon(Icons.Filled.Folder, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(l.props.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(l.props.blend.short, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Checkbox(l.props.visible, { st.setProps(l.id, l.props.copy(visible = it)) },
            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Ui.accent))
    }
}

@Composable
private fun LayerRow(st: EditorState, l: LayerInfo, active: Boolean, onTap: () -> Unit, onBlend: () -> Unit) {
    val maskActive = active && st.editingMask
    Row(Modifier.fillMaxWidth().height(ROW).clip(RoundedCornerShape(10.dp))
        .background(if (active) Ui.accent.copy(alpha = 0.85f) else Ui.panel2)
        .clickable(onClick = onTap).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (l.props.clip) Icon(Icons.Filled.SubdirectoryArrowRight, "Recorte", tint = Ui.text, modifier = Modifier.size(18.dp))
        Box(Modifier.size(width = 58.dp, height = 44.dp).clip(RoundedCornerShape(6.dp)).background(Checker)
            .border(if (active && !maskActive) 2.dp else 0.dp, Color.White, RoundedCornerShape(6.dp))
            .clickable { st.setActive(l.id) }, contentAlignment = Alignment.Center) {
            l.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        }
        if (l.hasMask) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(width = 44.dp, height = 44.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                .border(if (maskActive) 2.dp else 0.dp, Color.White, RoundedCornerShape(6.dp))
                .clickable { st.editMask(l.id) }, contentAlignment = Alignment.Center) {
                l.maskThumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(l.props.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (l.props.alphaLock) Icon(Icons.Filled.Lock, "Bloqueo alfa", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                if (l.props.reference) Text(" Ref.", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                if (maskActive) Text(" Máscara", color = Color.White, fontSize = 11.sp)
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
    var startProps by remember(l.id) { mutableStateOf<LayerProps?>(null) }
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
