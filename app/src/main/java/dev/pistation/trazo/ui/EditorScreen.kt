package dev.pistation.trazo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pistation.trazo.engine.CanvasView
import dev.pistation.trazo.engine.Tool
import kotlin.math.roundToInt

@Composable
fun EditorScreen(st: EditorState, onExit: () -> Unit) {
    BackHandler {
        if (st.panel != null) st.panel = null else st.save(onExit)
    }
    Box(Modifier.fillMaxSize().background(Ui.canvasBg)) {
        AndroidView(factory = { ctx -> CanvasView(ctx, st).also { st.attach(it) } }, modifier = Modifier.fillMaxSize(),
            onRelease = { it.onPause() })

        if (!st.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))

        AnimatedVisibility(!st.uiHidden, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                TopBar(st, onExit, Modifier.align(Alignment.TopCenter))
                Sidebar(st, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                val panelMod = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 10.dp).width(380.dp)
                when (st.panel) {
                    Panel.BRUSHES -> BrushPanel(st, panelMod)
                    Panel.LAYERS -> LayersPanel(st, panelMod)
                    Panel.COLOR -> ColorPanel(st, panelMod)
                    Panel.ACTIONS -> ActionsPanel(st, Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 10.dp).width(360.dp))
                    null -> {}
                }
            }
        }

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
    }
}

/** ColorDrop drag position in root coordinates while dragging the colour swatch. */
private object ColorDropGhost { var current by mutableStateOf<Pair<Float, Float>?>(null) }

@Composable
private fun TopBar(st: EditorState, onExit: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().background(Color(0xE61C1C1E)).statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton({ st.save(onExit) }) { Text("Galería", color = Ui.text, fontSize = 16.sp) }
        ToolIcon(Icons.Filled.Build, "Acciones", st.panel == Panel.ACTIONS) { st.panel = if (st.panel == Panel.ACTIONS) null else Panel.ACTIONS }
        ToolIcon(Icons.Filled.AutoFixHigh, "Ajustes") { st.flash("Ajustes de imagen: fase 2") }
        ToolIcon(Icons.Filled.HighlightAlt, "Selección") { st.flash("Selección: fase 2") }
        ToolIcon(Icons.Filled.OpenWith, "Transformar") { st.flash("Transformar: fase 2") }
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
                onDragEnd = {
                    ColorDropGhost.current = null
                    st.colorDrop(pos.x, pos.y)
                },
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

@Composable
private fun BrushSizePreview(st: EditorState, modifier: Modifier) {
    val px = st.brush.sizeFor(st.sizes[st.tool]!!)
    val zoom = st.view?.let { v -> v.toCanvas(0f, 0f).let { a -> v.toCanvas(100f, 0f).let { b -> 100f / kotlin.math.hypot(b[0] - a[0], b[1] - a[1]) } } } ?: 1f
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
        drawArc(Color(p.color ?: p.previous), 180f, 180f, true, c - Offset(r, r), androidx.compose.ui.geometry.Size(r * 2, r * 2))
        drawArc(Color(p.previous), 0f, 180f, true, c - Offset(r, r), androidx.compose.ui.geometry.Size(r * 2, r * 2))
        drawCircle(Color.White, r, c, style = Stroke(6f))
        drawCircle(Color.White, 10f, Offset(p.x, p.y), style = Stroke(3f))
    }
}

@Composable
private fun ActionsPanel(st: EditorState, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    PanelCard("Acciones", modifier) {
        val tabs = listOf("Compartir", "Lienzo", "Preferencias", "Ayuda")
        ScrollableTabRow(tab, edgePadding = 0.dp, containerColor = Color.Transparent, divider = {}) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> {
                    MenuRow("Guardar PNG en la galería", Icons.Filled.Image) { st.export(true) }
                    MenuRow("Guardar JPEG en la galería", Icons.Filled.Photo) { st.export(false) }
                    MenuRow("Compartir PNG…", Icons.Filled.Share) { st.share() }
                    MenuRow("Guardar ahora", Icons.Filled.Save) { st.save { st.flash("Guardado") } }
                }
                1 -> {
                    Text("${st.meta.width} × ${st.meta.height} px · ${st.layers.size} capas", color = Ui.dim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    MenuRow("Ajustar a pantalla", Icons.Filled.FitScreen) { st.view?.fit() }
                    MenuRow("Voltear capa horizontalmente", Icons.Filled.Flip) { st.flipLayer(true) }
                    MenuRow("Voltear capa verticalmente", Icons.Filled.Flip) { st.flipLayer(false) }
                    MenuRow(if (st.bgVisible) "Ocultar fondo (transparente)" else "Mostrar fondo", Icons.Filled.Texture) { st.setBackground(st.bgColor, !st.bgVisible) }
                }
                2 -> {
                    SettingSwitch("Pintar con el dedo (si no hay lápiz)", st.fingerPaint) { st.fingerPaint = it; st.updatePrefs() }
                    LabeledSlider("Curva de presión (suave ↔ dura)", st.pressureGamma, 0.4f..2.5f, { "%.2f".format(it) }) { st.pressureGamma = it; st.updatePrefs() }
                    Text("Relleno de color (arrastrar el color al lienzo)", color = Ui.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    SettingSwitch("Usar todo el lienzo como referencia", st.fillFromCanvas) { st.fillFromCanvas = it; st.updatePrefs() }
                    LabeledSlider("Umbral", st.fillThreshold, 0f..0.6f) { st.fillThreshold = it; st.updatePrefs() }
                }
                3 -> {
                    listOf(
                        "Dos dedos: mover, girar y hacer zoom",
                        "Pellizco rápido: ajustar a pantalla",
                        "Toque con dos dedos: deshacer",
                        "Toque con tres dedos: rehacer",
                        "Toque con cuatro dedos: pantalla completa",
                        "Mantener un dedo: cuentagotas",
                        "Arrastrar el círculo de color al lienzo: rellenar",
                        "Tocar otra vez la herramienta activa: biblioteca de pinceles",
                        "Capas: mantén pulsada una capa y arrástrala para reordenar; toca la capa activa para su menú; toca la letra para modo de fusión y opacidad",
                    ).forEach { Text("• $it", color = Ui.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        }
    }
}
