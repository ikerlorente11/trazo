package dev.pistation.trazo.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pistation.trazo.engine.CanvasView
import dev.pistation.trazo.engine.Tool
import kotlin.math.*

@Composable
fun EditorScreen(st: EditorState, onExit: () -> Unit) {
    BackHandler {
        when {
            st.panel != null -> st.panel = null
            st.mode != Mode.NONE -> st.exitMode(apply = false)
            else -> st.save(onExit)
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(st::insertPhoto) }
    val pickReference = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(st::setReferenceImage) }
    val launchers = remember { Launchers() }
    launchers.photo = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    launchers.reference = { pickReference.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(Modifier.fillMaxSize().background(Ui.canvasBg)) {
        AndroidView(factory = { ctx -> CanvasView(ctx, st).also { st.attach(it) } }, modifier = Modifier.fillMaxSize(),
            onRelease = { it.onPause() })

        if (!st.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))

        // Overlays drawn over the canvas (no touch handling: the canvas gets the input).
        GuidesOverlay(st)
        when (st.mode) {
            Mode.SELECT -> SelectionPreview(st)
            Mode.TRANSFORM -> st.transform?.let { TransformOverlay(st, it) }
            Mode.LIQUIFY -> {}
            Mode.ADJUST -> AdjustCenterMarker(st)
            else -> {}
        }
        if (st.tool == Tool.CLONE) CloneMarker(st)
        st.hover?.let { HoverCursor(st, it) }

        AnimatedVisibility(!st.uiHidden, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                TopBar(st, onExit, Modifier.align(Alignment.TopCenter))
                if (st.mode == Mode.NONE || st.mode == Mode.CLONE)
                    Sidebar(st, Modifier.align(if (st.rightHanded) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 8.dp))
                val panelMod = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 10.dp).width(390.dp)
                when (st.panel) {
                    Panel.BRUSHES -> BrushPanel(st, panelMod)
                    Panel.LAYERS -> LayersPanel(st, panelMod)
                    Panel.COLOR -> ColorPanel(st, panelMod)
                    Panel.ACTIONS -> ActionsPanel(st, launchers, Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 10.dp).width(400.dp))
                    Panel.ADJUST -> AdjustPanel(st, Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 10.dp).width(330.dp))
                    null -> {}
                }
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (st.mode) {
                        Mode.SELECT -> SelectBar(st)
                        Mode.TRANSFORM -> st.transform?.let { TransformBar(st, it) }
                        Mode.ADJUST -> AdjustSheet(st)
                        Mode.LIQUIFY -> LiquifyBar(st)
                        Mode.CLONE -> CloneBar(st)
                        Mode.GUIDE_EDIT -> GuideEditBar(st)
                        Mode.NONE -> {}
                    }
                    if (st.animOn) { Spacer(Modifier.height(8.dp)); AnimationTimeline(st) }
                }
            }
        }

        if (st.refVisible) ReferenceWindow(st)
        if (st.showBrushSize) BrushSizePreview(st, Modifier.align(Alignment.Center))
        st.pick?.let { Loupe(it) }
        ColorDropGhost.current?.let { (x, y) ->
            val d = LocalDensity.current
            Box(Modifier.offset { IntOffset((x - with(d) { 22.dp.toPx() }).roundToInt(), (y - with(d) { 22.dp.toPx() }).roundToInt()) }
                .size(44.dp).clip(CircleShape).background(Color(st.color)).border(3.dp, Color.White, CircleShape))
        }
        st.message?.let {
            Text(it, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xCC000000)).padding(horizontal = 18.dp, vertical = 8.dp))
        }
        st.busy?.let {
            Box(Modifier.fillMaxSize().background(Color(0x88000000)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(it, color = Color.White)
                }
            }
        }
        if (st.copyPasteMenu) CopyPasteMenu(st)
        if (st.textDialog) TextDialog(st)
        if (st.resizeDialog) ResizeDialog(st)
        st.timelapseVideo?.let { VideoDialog(it) { st.timelapseVideo = null } }
    }
}

/** Activity-result launchers the panels can trigger. */
class Launchers {
    var photo: () -> Unit = {}
    var reference: () -> Unit = {}
}

/** ColorDrop drag position in root coordinates while dragging the colour swatch. */
object ColorDropGhost { var current by mutableStateOf<Pair<Float, Float>?>(null) }

@Composable
private fun TopBar(st: EditorState, onExit: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().background(Color(0xE61C1C1E)).statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton({ st.exitMode(true); st.save(onExit) }) { Text("Galería", color = Ui.text, fontSize = 16.sp) }
        ToolIcon(Icons.Filled.Build, "Acciones", st.panel == Panel.ACTIONS) { st.panel = if (st.panel == Panel.ACTIONS) null else Panel.ACTIONS }
        ToolIcon(Icons.Filled.AutoFixHigh, "Ajustes", st.panel == Panel.ADJUST || st.mode in listOf(Mode.ADJUST, Mode.LIQUIFY, Mode.CLONE)) {
            if (st.mode in listOf(Mode.ADJUST, Mode.LIQUIFY)) st.exitMode(true)
            st.panel = if (st.panel == Panel.ADJUST) null else Panel.ADJUST
        }
        ToolIcon(Icons.Filled.HighlightAlt, "Selección", st.mode == Mode.SELECT || st.hasSelection) { st.enterSelect() }
        ToolIcon(Icons.Filled.OpenWith, "Transformar", st.mode == Mode.TRANSFORM) { st.enterTransform() }
        if (st.is3d) ToolIcon(if (st.show3d) Icons.Filled.ViewInAr else Icons.Filled.GridOn, "3D / textura", st.show3d) { st.toggle3d() }
        Spacer(Modifier.weight(1f))
        ToolIcon(Icons.Filled.Brush, "Pintar", st.tool == Tool.BRUSH) { st.selectTool(Tool.BRUSH) }
        ToolIcon(Icons.Filled.Gesture, "Difuminar", st.tool == Tool.SMUDGE) { st.selectTool(Tool.SMUDGE) }
        ToolIcon(Icons.Filled.CleaningServices, "Borrar", st.tool == Tool.ERASER) { st.selectTool(Tool.ERASER) }
        ToolIcon(Icons.Filled.Layers, "Capas", st.panel == Panel.LAYERS) { st.panel = if (st.panel == Panel.LAYERS) null else Panel.LAYERS }
        Spacer(Modifier.width(6.dp))
        ColorButton(st)
        Spacer(Modifier.width(6.dp))
    }
}

/** Tap: colour panel. Drag onto the canvas: ColorDrop fill. */
@Composable
private fun ColorButton(st: EditorState) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.size(36.dp)
        .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
        .clip(CircleShape).background(Color(st.color)).border(2.dp, Color.White, CircleShape)
        .pointerInput(Unit) {
            var pos = Offset.Zero
            detectDragGestures(
                onDragStart = { pos = origin + it; ColorDropGhost.current = pos.x to pos.y },
                onDragEnd = { ColorDropGhost.current = null; st.colorDrop(pos.x, pos.y) },
                onDragCancel = { ColorDropGhost.current = null },
            ) { ch, d -> ch.consume(); pos += d; ColorDropGhost.current = pos.x to pos.y }
        }
        .pointerInput(Unit) { detectTapGestures { st.panel = if (st.panel == Panel.COLOR) null else Panel.COLOR } })
}

@Composable
private fun Sidebar(st: EditorState, modifier: Modifier) {
    Column(modifier.width(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xCC1C1C1E)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        VSlider(st.sizes[st.tool]!!, { st.setSize(it) }, Modifier.height(190.dp), onActive = { st.showBrushSize = it })
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x55FFFFFF)).clickable { st.startPickNext() },
            contentAlignment = Alignment.Center) { Icon(Icons.Filled.Colorize, "Cuentagotas", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.height(10.dp))
        VSlider(st.opacities[st.tool]!!, { st.setOpacity(it) }, Modifier.height(150.dp))
        Spacer(Modifier.height(10.dp))
        ToolIcon(Icons.AutoMirrored.Filled.Undo, "Deshacer", enabled = st.canUndo, size = 40.dp) { st.undo() }
        ToolIcon(Icons.AutoMirrored.Filled.Redo, "Rehacer", enabled = st.canRedo, size = 40.dp) { st.redo() }
    }
}

// ====================================================================== mode bars

@Composable
fun ModeBar(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(Ui.panel).border(1.dp, Ui.line, RoundedCornerShape(16.dp))
        .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected, onClick, label = { Text(label, fontSize = 13.sp) })

@Composable
private fun SelectBar(st: EditorState) {
    val s = st.selection
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (s.kind == SelectKind.AUTO) ModeBar {
            Text("Umbral", color = Ui.text, fontSize = 13.sp)
            Slider(s.threshold, { s.threshold = it }, valueRange = 0f..0.6f, modifier = Modifier.width(220.dp))
            Chip("Todo el lienzo", s.fromCanvas) { s.fromCanvas = !s.fromCanvas }
        } else ModeBar {
            Text("Calado", color = Ui.text, fontSize = 13.sp)
            Slider(s.feather, { s.feather = it }, valueRange = 0f..80f, modifier = Modifier.width(200.dp))
            Text("${s.feather.toInt()} px", color = Ui.dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        ModeBar {
            SelectKind.entries.forEach { k -> Chip(k.label, s.kind == k) { s.kind = k; s.cancel() } }
            VerticalDivider(Modifier.height(28.dp))
            Chip("Nueva", s.combine == 0) { s.combine = 0 }
            Chip("Añadir", s.combine == 1) { s.combine = 1 }
            Chip("Restar", s.combine == 2) { s.combine = 2 }
        }
        Spacer(Modifier.height(6.dp))
        ModeBar {
            Button({ st.exitMode(true) }) { Text("Listo") }
            TextButton({ st.gl { it.invertSelection() } }) { Text("Invertir") }
            TextButton({ st.gl { it.selectAll() } }) { Text("Todo") }
            TextButton({ val f = s.feather.toInt().coerceAtLeast(8); st.gl { it.featherSelection(f) } }) { Text("Calar") }
            TextButton({ st.copy() }) { Text("Copiar") }
            TextButton({ st.duplicateSelection() }) { Text("Copiar y pegar") }
            TextButton({ st.fillLayer() }) { Text("Rellenar") }
            TextButton({ st.clearLayer() }) { Text("Borrar contenido") }
            TextButton({ st.gl { it.clearSelection() } }) { Text("Deseleccionar") }
        }
    }
}

@Composable
private fun TransformBar(st: EditorState, t: TransformController) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ModeBar {
            TransformMode.entries.forEach { m -> Chip(m.label, t.mode == m) { t.changeMode(m) } }
            VerticalDivider(Modifier.height(28.dp))
            Chip("Píxeles nítidos", t.nearest) { t.nearest = !t.nearest; t.send() }
        }
        Spacer(Modifier.height(6.dp))
        ModeBar {
            TextButton({ t.flip(true) }) { Text("Voltear H") }
            TextButton({ t.flip(false) }) { Text("Voltear V") }
            TextButton({ t.rotate45() }) { Text("Girar 45°") }
            TextButton({ t.fitToCanvas() }) { Text("Ajustar al lienzo") }
            TextButton({ t.reset() }) { Text("Restablecer") }
            TextButton({ st.exitMode(apply = false) }) { Text("Cancelar") }
            Button({ st.exitMode(apply = true) }) { Text("Aplicar") }
        }
    }
}

@Composable
private fun LiquifyBar(st: EditorState) {
    val l = st.liquify
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ModeBar { dev.pistation.trazo.engine.LiquifyMode.entries.forEach { m -> Chip(m.label, l.mode == m) { l.mode = m } } }
        Spacer(Modifier.height(6.dp))
        ModeBar {
            Text("Tamaño", color = Ui.text, fontSize = 13.sp); Slider(l.size, { l.size = it }, valueRange = 20f..800f, modifier = Modifier.width(160.dp))
            Text("Fuerza", color = Ui.text, fontSize = 13.sp); Slider(l.strength, { l.strength = it }, modifier = Modifier.width(140.dp))
            Chip("Presión", l.pressureOn) { l.pressureOn = !l.pressureOn }
            TextButton({ st.gl { it.resetLiquify() } }) { Text("Restablecer") }
            TextButton({ st.exitMode(apply = false) }) { Text("Cancelar") }
            Button({ st.exitMode(apply = true) }) { Text("Aplicar") }
        }
    }
}

@Composable
private fun CloneBar(st: EditorState) {
    ModeBar {
        Text("Clonar", color = Ui.text, fontSize = 14.sp)
        Text(if (st.placingClone) "Toca el lienzo para colocar el origen" else "Pinta para copiar desde el círculo", color = Ui.dim, fontSize = 12.sp)
        TextButton({ st.moveCloneSource() }) { Text("Mover origen") }
        TextButton({ st.panel = Panel.BRUSHES }) { Text("Pincel") }
        Button({ st.exitMode(true) }) { Text("Listo") }
    }
}

@Composable
private fun GuideEditBar(st: EditorState) {
    val g = st.guides
    ModeBar {
        Text(if (g.type == GuideType.PERSPECTIVE) "Arrastra los puntos de fuga; toca para añadir (máx. 3)" else "Arrastra el centro de la guía", color = Ui.dim, fontSize = 12.sp)
        if (g.type == GuideType.PERSPECTIVE && g.vps.size > 1) TextButton({ g.vps.removeAt(g.vps.lastIndex); g.apply(); st.viewVersion++ }) { Text("Quitar punto") }
        Button({ st.exitMode(true) }) { Text("Listo") }
    }
}

// ====================================================================== overlays

@Composable
private fun GuidesOverlay(st: EditorState) {
    val g = st.guides
    if (g.type == GuideType.NONE) return
    @Suppress("UNUSED_VARIABLE") val v = st.viewVersion
    val col = Color(g.color).copy(alpha = g.opacity)
    val w = st.meta.width.toFloat(); val h = st.meta.height.toFloat()
    Canvas(Modifier.fillMaxSize()) {
        if (v == Int.MIN_VALUE) return@Canvas
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            val a = st.toScreen(x0, y0); val b = st.toScreen(x1, y1)
            drawLine(col, Offset(a[0], a[1]), Offset(b[0], b[1]), g.thickness)
        }
        when (g.type) {
            GuideType.GRID -> {
                var x = 0f; while (x <= w) { line(x, 0f, x, h); x += g.gridSize }
                var y = 0f; while (y <= h) { line(0f, y, w, y); y += g.gridSize }
            }
            GuideType.ISO -> {
                val s = g.gridSize
                var x = 0f; while (x <= w) { line(x, 0f, x, h); x += s * 0.866f }
                val t = tan(PI / 6).toFloat()
                var c = -w * t; while (c <= h + w * t) { line(0f, c, w, c + w * t); line(0f, c + w * t, w, c); c += s }
            }
            GuideType.PERSPECTIVE -> {
                for (vp in g.vps) for (k in 0 until 36) {
                    val a = k * PI.toFloat() / 18
                    line(vp.x, vp.y, vp.x + cos(a) * (w + h) * 2, vp.y + sin(a) * (w + h) * 2)
                }
                if (g.vps.size >= 2) { val a = g.vps[0]; val b = g.vps[1]; val dx = b.x - a.x; val dy = b.y - a.y
                    line(a.x - dx * 10, a.y - dy * 10, b.x + dx * 10, b.y + dy * 10) }
                else g.vps.firstOrNull()?.let { line(-w, it.y, 2 * w, it.y) }
                if (st.mode == Mode.GUIDE_EDIT) g.vps.forEach { val p = st.toScreen(it.x, it.y); drawCircle(Color.White, 16f, Offset(p[0], p[1])); drawCircle(col.copy(alpha = 1f), 11f, Offset(p[0], p[1])) }
            }
            GuideType.SYMMETRY -> {
                val c = g.center
                val axes = when (g.symKind) {
                    SymKind.VERTICAL -> listOf(PI / 2)
                    SymKind.HORIZONTAL -> listOf(0.0)
                    SymKind.QUADRANT -> listOf(0.0, PI / 2)
                    SymKind.RADIAL -> (0 until g.radialN).map { PI * it / g.radialN }
                }
                for (a in axes) { val L = (w + h); line(c.x - cos(a).toFloat() * L, c.y - sin(a).toFloat() * L, c.x + cos(a).toFloat() * L, c.y + sin(a).toFloat() * L) }
                val p = st.toScreen(c.x, c.y)
                drawCircle(Color.White, 14f, Offset(p[0], p[1])); drawCircle(col.copy(alpha = 1f), 9f, Offset(p[0], p[1]))
            }
            GuideType.NONE -> {}
        }
    }
}

@Composable
private fun SelectionPreview(st: EditorState) {
    @Suppress("UNUSED_VARIABLE") val v = st.viewVersion
    val s = st.selection
    Canvas(Modifier.fillMaxSize()) {
        if (v == Int.MIN_VALUE) return@Canvas
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        if (s.path.size > 1) {
            val p = androidx.compose.ui.graphics.Path()
            s.path.forEachIndexed { i, q -> val sp = st.toScreen(q.x, q.y); if (i == 0) p.moveTo(sp[0], sp[1]) else p.lineTo(sp[0], sp[1]) }
            drawPath(p, Color.White, style = Stroke(3f)); drawPath(p, Color.Black, style = Stroke(1.5f, pathEffect = dash))
            s.path.firstOrNull()?.let { val sp = st.toScreen(it.x, it.y); drawCircle(Color.White, 10f, Offset(sp[0], sp[1]), style = Stroke(3f)) }
        }
        val a = s.dragStart; val b = s.dragEnd
        if (a != null && b != null) {
            val corners = listOf(a.x to a.y, b.x to a.y, b.x to b.y, a.x to b.y).map { st.toScreen(it.first, it.second) }
            if (s.kind == SelectKind.ELLIPSE) {
                val p = androidx.compose.ui.graphics.Path()
                val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2; val rx = abs(b.x - a.x) / 2; val ry = abs(b.y - a.y) / 2
                for (k in 0..64) { val t = k * 2 * PI.toFloat() / 64; val sp = st.toScreen(cx + cos(t) * rx, cy + sin(t) * ry); if (k == 0) p.moveTo(sp[0], sp[1]) else p.lineTo(sp[0], sp[1]) }
                drawPath(p, Color.White, style = Stroke(3f)); drawPath(p, Color.Black, style = Stroke(1.5f, pathEffect = dash))
            } else for (i in 0..3) { val p = corners[i]; val q = corners[(i + 1) % 4]
                drawLine(Color.White, Offset(p[0], p[1]), Offset(q[0], q[1]), 3f); drawLine(Color.Black, Offset(p[0], p[1]), Offset(q[0], q[1]), 1.5f, pathEffect = dash) }
        }
    }
}

@Composable
private fun TransformOverlay(st: EditorState, t: TransformController) {
    @Suppress("UNUSED_VARIABLE") val v = st.viewVersion + t.version
    Canvas(Modifier.fillMaxSize()) {
        if (v == Int.MIN_VALUE) return@Canvas
        val blue = Color(0xFF4C8DFF)
        if (t.mode == TransformMode.WARP) {
            val pts = (0 until 16).map { st.toScreen(t.ctrl[it * 2], t.ctrl[it * 2 + 1]) }
            for (j in 0..3) for (i in 0..3) {
                val p = pts[j * 4 + i]
                if (i < 3) { val q = pts[j * 4 + i + 1]; drawLine(blue, Offset(p[0], p[1]), Offset(q[0], q[1]), 2f) }
                if (j < 3) { val q = pts[(j + 1) * 4 + i]; drawLine(blue, Offset(p[0], p[1]), Offset(q[0], q[1]), 2f) }
            }
            // The mesh itself, to show the bend.
            val m = t.mesh(8)
            for (j in 0..8) for (i in 0 until 8) {
                val a = st.toScreen(m[(j * 9 + i) * 2], m[(j * 9 + i) * 2 + 1]); val b = st.toScreen(m[(j * 9 + i + 1) * 2], m[(j * 9 + i + 1) * 2 + 1])
                drawLine(blue.copy(alpha = 0.35f), Offset(a[0], a[1]), Offset(b[0], b[1]), 1f)
            }
        } else {
            val c = (0..3).map { st.toScreen(t.corners[it * 2], t.corners[it * 2 + 1]) }
            for (i in 0..3) { val p = c[i]; val q = c[(i + 1) % 4]; drawLine(blue, Offset(p[0], p[1]), Offset(q[0], q[1]), 2.5f) }
        }
        val hs = t.handles()
        hs.forEachIndexed { i, p ->
            val knob = t.mode != TransformMode.WARP && t.mode != TransformMode.DISTORT && i == 8
            drawCircle(Color.White, if (knob) 15f else 13f, Offset(p[0], p[1]))
            drawCircle(if (knob) Color(0xFF39C27A) else blue, if (knob) 11f else 9f, Offset(p[0], p[1]))
        }
    }
}

@Composable
private fun AdjustCenterMarker(st: EditorState) {
    val k = st.adjustKind ?: return
    if (k != dev.pistation.trazo.engine.AdjustKind.PERSPECTIVE && k != dev.pistation.trazo.engine.AdjustKind.CHROMATIC) return
    @Suppress("UNUSED_VARIABLE") val v = st.viewVersion
    val p = st.toScreen(st.adjustParams[1], st.adjustParams[2])
    Canvas(Modifier.fillMaxSize()) {
        if (v == Int.MIN_VALUE) return@Canvas
        drawCircle(Color.White, 22f, Offset(p[0], p[1]), style = Stroke(4f)); drawCircle(Color(0xFF4C8DFF), 8f, Offset(p[0], p[1]))
    }
}

@Composable
private fun CloneMarker(st: EditorState) {
    val src = st.cloneSource ?: return
    @Suppress("UNUSED_VARIABLE") val v = st.viewVersion
    val p = st.toScreen(src.x, src.y)
    val r = st.brush.sizeFor(st.sizes[Tool.CLONE]!!) * st.zoom() / 2
    Canvas(Modifier.fillMaxSize()) {
        if (v == Int.MIN_VALUE) return@Canvas
        drawCircle(Color.White, max(10f, r), Offset(p[0], p[1]), style = Stroke(3f))
        drawCircle(Color.Black, max(10f, r) - 3f, Offset(p[0], p[1]), style = Stroke(1f))
        drawLine(Color.White, Offset(p[0] - 10, p[1]), Offset(p[0] + 10, p[1]), 2f); drawLine(Color.White, Offset(p[0], p[1] - 10), Offset(p[0], p[1] + 10), 2f)
    }
}

/** Brush outline where the pen hovers. */
@Composable
private fun HoverCursor(st: EditorState, at: Offset2) {
    val r = when (st.mode) {
        Mode.LIQUIFY -> st.liquify.size / 2 * st.zoom()
        Mode.NONE, Mode.CLONE -> st.brush.sizeFor(st.sizes[st.tool]!!) * st.zoom() / 2
        else -> return
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Color.White, max(3f, r), Offset(at.x, at.y), style = Stroke(2f))
        drawCircle(Color.Black.copy(alpha = 0.6f), max(3f, r) + 1.5f, Offset(at.x, at.y), style = Stroke(1f))
    }
}

@Composable
private fun BrushSizePreview(st: EditorState, modifier: Modifier) {
    val px = st.brush.sizeFor(st.sizes[st.tool]!!)
    val zoom = st.zoom()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(with(LocalDensity.current) { (px * zoom).coerceIn(4f, 900f).toDp() })) {
            drawCircle(Color.White, size.minDimension / 2, style = Stroke(3f))
            drawCircle(Color.Black, size.minDimension / 2 - 3f, style = Stroke(1f))
        }
        Text("${px.toInt()} px · ${(st.sizes[st.tool]!! * 100).toInt()} %", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun Loupe(p: PickState) {
    val d = LocalDensity.current
    val r = with(d) { 56.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(p.x, p.y - r * 1.4f)
        drawArc(Color(p.color ?: p.previous), 180f, 180f, true, c - Offset(r, r), Size(r * 2, r * 2))
        drawArc(Color(p.previous), 0f, 180f, true, c - Offset(r, r), Size(r * 2, r * 2))
        drawCircle(Color.White, r, c, style = Stroke(6f))
        drawCircle(Color.White, 10f, Offset(p.x, p.y), style = Stroke(3f))
    }
}

/** Floating window with the canvas or an image; tap the image to pick a colour from it. */
@Composable
private fun ReferenceWindow(st: EditorState) {
    var pos by remember { mutableStateOf(Offset(40f, 400f)) }
    var width by remember { mutableFloatStateOf(520f) }
    val img = st.refImage
    val d = LocalDensity.current
    Column(Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }.width(with(d) { width.toDp() })
        .clip(RoundedCornerShape(12.dp)).background(Ui.panel).border(1.dp, Ui.line, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().pointerInput(Unit) { detectDragGestures { ch, dd -> ch.consume(); pos += dd } }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Referencia", color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton({ st.refFromCanvas = true; st.refImage = null; st.gl { e -> val b = e.capture(700); android.os.Handler(android.os.Looper.getMainLooper()).post { st.refImage = b } } }) { Text("Lienzo", fontSize = 12.sp) }
            IconButton({ st.refVisible = false }, Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Cerrar", tint = Ui.text) }
        }
        if (img != null) {
            var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            Image(img.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height)
                    .onGloballyPositioned { boxSize = it.size }
                    .pointerInput(img) {
                        detectTapGestures { o ->
                            val x = (o.x / boxSize.width * img.width).toInt().coerceIn(0, img.width - 1)
                            val y = (o.y / boxSize.height * img.height).toInt().coerceIn(0, img.height - 1)
                            st.setColor(img.getPixel(x, y), remember = true)
                        }
                    })
        } else Text("Sin imagen", color = Ui.dim, modifier = Modifier.padding(16.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).pointerInput(Unit) { detectDragGestures { ch, dd -> ch.consume(); width = (width + dd.x).coerceIn(240f, 1400f) } },
            contentAlignment = Alignment.CenterEnd) { Box(Modifier.padding(end = 6.dp).size(18.dp, 4.dp).background(Ui.dim)) }
    }
}
