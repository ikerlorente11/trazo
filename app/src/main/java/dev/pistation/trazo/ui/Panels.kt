package dev.pistation.trazo.ui

import android.widget.MediaController
import android.widget.VideoView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import dev.pistation.trazo.engine.AdjustKind
import java.io.File
import kotlin.math.*

// ====================================================================== actions

@Composable
fun ActionsPanel(st: EditorState, launchers: Launchers, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    PanelCard("Acciones", modifier) {
        val tabs = listOf("Añadir", "Lienzo", "Compartir", "Vídeo", "Preferencias", "Ayuda")
        ScrollableTabRow(tab, edgePadding = 0.dp, containerColor = Color.Transparent, divider = {}) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> {
                    MenuRow("Insertar una foto", Icons.Filled.AddPhotoAlternate) { st.panel = null; launchers.photo() }
                    MenuRow("Añadir texto", Icons.Filled.TextFields) { st.panel = null; st.textDialog = true }
                    HorizontalDivider(color = Ui.line)
                    MenuRow("Cortar", Icons.Filled.ContentCut) { st.cut() }
                    MenuRow("Copiar", Icons.Filled.ContentCopy) { st.copy() }
                    MenuRow("Copiar todo (lo visible)", Icons.Filled.CopyAll) { st.copyAll() }
                    MenuRow("Pegar", Icons.Filled.ContentPaste) { st.paste() }
                }
                1 -> CanvasTab(st, launchers)
                2 -> {
                    MenuRow("Guardar PNG en la galería", Icons.Filled.Image) { st.export(true) }
                    MenuRow("Guardar JPEG en la galería", Icons.Filled.Photo) { st.export(false) }
                    MenuRow("Exportar PSD (capas, grupos y máscaras)", Icons.Filled.Layers) { st.exportPsd() }
                    MenuRow("Exportar animación GIF", Icons.Filled.Gif) { st.exportAnimation(gif = true) }
                    MenuRow("Exportar animación MP4", Icons.Filled.Movie) { st.exportAnimation(gif = false) }
                    MenuRow("Compartir PNG…", Icons.Filled.Share) { st.share() }
                    MenuRow("Guardar ahora", Icons.Filled.Save) { st.save { st.flash("Guardado") } }
                }
                3 -> {
                    Text("${st.timelapseFrames()} fotogramas grabados", color = Ui.dim, fontSize = 13.sp)
                    SettingSwitch("Grabar time-lapse de este lienzo", st.timelapseOn) { st.timelapseOn = it; st.saveExtras() }
                    MenuRow("Reproducir time-lapse", Icons.Filled.PlayCircle) { st.panel = null; st.playTimelapse() }
                    MenuRow("Exportar time-lapse (MP4)", Icons.Filled.VideoFile) { st.exportTimelapse() }
                }
                4 -> {
                    SettingSwitch("Barra lateral a la derecha", st.rightHanded) { st.rightHanded = it; st.updatePrefs() }
                    SettingSwitch("Pintar con el dedo (si no hay lápiz)", st.fingerPaint) { st.fingerPaint = it; st.updatePrefs() }
                    SettingSwitch("QuickShape (mantén al final del trazo)", st.quickShape) { st.quickShape = it; st.updatePrefs() }
                    SettingSwitch("Predicción del trazo (menos latencia)", st.predict) { st.predict = it; st.updatePrefs() }
                    Text("Curva de presión", color = Ui.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    PressureCurveEditor(st)
                    Text("Relleno de color (arrastrar el color al lienzo)", color = Ui.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    SettingSwitch("Usar todo el lienzo como referencia", st.fillFromCanvas) { st.fillFromCanvas = it; st.updatePrefs() }
                    LabeledSlider("Umbral", st.fillThreshold, 0f..0.6f) { st.fillThreshold = it; st.updatePrefs() }
                }
                5 -> listOf(
                    "Dos dedos: mover, girar y zoom · pellizco rápido: ajustar a pantalla",
                    "Toque con dos dedos: deshacer · tres: rehacer · cuatro: pantalla completa",
                    "Frotar con tres dedos: borrar la capa · deslizar tres dedos hacia abajo: copiar y pegar",
                    "Mantener un dedo: cuentagotas · arrastrar el color al lienzo: rellenar",
                    "Mantener el lápiz al final de un trazo: QuickShape (línea, elipse, polígono); sin levantar, arrastra para ajustarla",
                    "Tocar otra vez la herramienta activa: biblioteca de pinceles",
                    "Capas: arrastra para reordenar (mantén pulsado), toca la activa para su menú, toca la letra para fusión/opacidad, toca la miniatura de la máscara para pintarla",
                    "Selección: dibuja el lazo, o toca puntos y vuelve al primero para cerrar",
                    "Transformar: arrastra las esquinas, los lados, el punto verde (girar) o el interior (mover)",
                ).forEach { Text("• $it", color = Ui.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
    }
}

@Composable
private fun CanvasTab(st: EditorState, launchers: Launchers) {
    Text("${st.meta.width} × ${st.meta.height} px · ${st.layers.count { !it.isGroup }} capas" + if (st.is3d) " · 3D" else "", color = Ui.dim, fontSize = 13.sp)
    MenuRow("Recortar y cambiar tamaño", Icons.Filled.Crop) { st.panel = null; st.resizeDialog = true }
    MenuRow("Voltear lienzo horizontalmente", Icons.Filled.Flip) { st.flipCanvas(true) }
    MenuRow("Voltear lienzo verticalmente", Icons.Filled.Flip) { st.flipCanvas(false) }
    MenuRow("Voltear la vista (sin cambiar el dibujo)", Icons.Filled.FlipCameraAndroid) { st.view?.flipView() }
    MenuRow("Ajustar a pantalla", Icons.Filled.FitScreen) { st.view?.fit() }
    MenuRow("Aplanar todas las capas", Icons.Filled.Compress) { st.flattenAll() }
    MenuRow(if (st.bgVisible) "Fondo transparente" else "Mostrar fondo", Icons.Filled.Texture) { st.setBackground(st.bgColor, !st.bgVisible) }
    HorizontalDivider(color = Ui.line, modifier = Modifier.padding(vertical = 6.dp))
    SettingSwitch("Ventana de referencia", st.refVisible) { st.refVisible = it; if (it && st.refImage == null) { st.refFromCanvas = true; st.gl { e -> val b = e.capture(700); android.os.Handler(android.os.Looper.getMainLooper()).post { st.refImage = b } } } }
    if (st.refVisible) MenuRow("Referencia: elegir una imagen…", Icons.Filled.Image) { launchers.reference() }
    SettingSwitch("Pantalla externa (mostrar el lienzo)", st.externalOn) { st.toggleExternal() }
    HorizontalDivider(color = Ui.line, modifier = Modifier.padding(vertical = 6.dp))
    SettingSwitch("Asistente de animación", st.animOn) { st.setAnim(it); if (it) st.goToFrame(st.frames.size - 1) }
    if (st.animOn) {
        LabeledSlider("Fotogramas por segundo", st.animFps.toFloat(), 1f..30f, { "${it.toInt()}" }) { st.animFps = it.toInt(); st.saveExtras() }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Bucle", "Ida y vuelta", "Una vez").forEachIndexed { i, l -> FilterChip(st.animLoop == i, { st.animLoop = i; st.saveExtras() }, label = { Text(l, fontSize = 12.sp) }) }
        }
        LabeledSlider("Papel cebolla (fotogramas)", st.animOnion.toFloat(), 0f..5f, { "${it.toInt()}" }) { st.animOnion = it.toInt(); st.setAnim() }
        LabeledSlider("Opacidad del papel cebolla", st.animOnionOpacity) { st.animOnionOpacity = it; st.setAnim() }
        SettingSwitch("Primer fotograma = fondo", st.animBgFirst) { st.animBgFirst = it; st.setAnim() }
        SettingSwitch("Último fotograma = primer plano", st.animFgLast) { st.animFgLast = it; st.setAnim() }
    }
    HorizontalDivider(color = Ui.line, modifier = Modifier.padding(vertical = 6.dp))
    GuidesSettings(st)
}

@Composable
private fun GuidesSettings(st: EditorState) {
    val g = st.guides
    Text("Guía de dibujo", color = Ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GuideType.entries.forEach { t -> FilterChip(g.type == t, { g.type = t; g.apply(); st.saveExtras(); st.viewVersion++ }, label = { Text(t.label, fontSize = 12.sp) }) }
    }
    if (g.type == GuideType.NONE) return
    SettingSwitch("Dibujo asistido", g.assisted) { g.assisted = it; g.apply(); st.saveExtras() }
    if (g.type == GuideType.GRID || g.type == GuideType.ISO) LabeledSlider("Tamaño", g.gridSize, 20f..600f, { "${it.toInt()} px" }) { g.gridSize = it; st.saveExtras() }
    if (g.type == GuideType.SYMMETRY) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SymKind.entries.forEach { k -> FilterChip(g.symKind == k, { g.symKind = k; g.apply(); st.saveExtras() }, label = { Text(k.label, fontSize = 12.sp) }) }
        }
        if (g.symKind == SymKind.RADIAL) LabeledSlider("Segmentos", g.radialN.toFloat(), 2f..16f, { "${it.toInt()}" }) { g.radialN = it.toInt(); g.apply(); st.saveExtras() }
        SettingSwitch("Simetría rotacional", g.rotational) { g.rotational = it; g.apply(); st.saveExtras() }
    }
    LabeledSlider("Opacidad", g.opacity) { g.opacity = it; st.saveExtras() }
    LabeledSlider("Grosor", g.thickness, 0.5f..6f, { "%.1f".format(it) }) { g.thickness = it; st.saveExtras() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        listOf(0xFF3A8DFF, 0xFFFF4A6A, 0xFF39C27A, 0xFF000000, 0xFFFFFFFF).forEach { c -> Swatch(c.toInt(), 28.dp, g.color == c.toInt()) { g.color = c.toInt(); st.saveExtras() } }
    }
    if (g.type == GuideType.PERSPECTIVE || g.type == GuideType.SYMMETRY)
        MenuRow("Editar la guía en el lienzo", Icons.Filled.Edit) { st.startGuideEdit() }
}

/** Global pressure curve: five points, drag vertically. */
@Composable
fun PressureCurveEditor(st: EditorState) {
    val curve = st.pressureCurve
    Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B2B2F))) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures { ch, _ ->
                ch.consume()
                val i = ((ch.position.x / size.width) * 4).roundToInt().coerceIn(1, 3)
                val c = st.pressureCurve.copyOf()
                c[i] = (1f - ch.position.y / size.height).coerceIn(0f, 1f)
                st.pressureCurve = c
            }
        }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { st.pressureCurve = floatArrayOf(0f, .25f, .5f, .75f, 1f); st.updatePrefs() }) }) {
            for (k in 1..3) { drawLine(Ui.line, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height)); drawLine(Ui.line, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4)) }
            val pts = curve.mapIndexed { i, y -> Offset(size.width * i / 4, size.height * (1 - y)) }
            for (i in 0 until 4) drawLine(Ui.accent, pts[i], pts[i + 1], 4f)
            pts.forEach { drawCircle(Color.White, 9f, it) }
        }
    }
    Row {
        Text("Arrastra los puntos · doble toque: restablecer", color = Ui.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        TextButton({ st.updatePrefs() }) { Text("Guardar curva", fontSize = 12.sp) }
    }
}

// ====================================================================== adjustments

@Composable
fun AdjustPanel(st: EditorState, modifier: Modifier) {
    PanelCard("Ajustes", modifier) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            val groups = listOf(
                "Color" to listOf(AdjustKind.HSB, AdjustKind.BALANCE, AdjustKind.CURVES, AdjustKind.GRADIENT_MAP, AdjustKind.BRIGHT_CONTRAST, AdjustKind.INVERT),
                "Desenfoque" to listOf(AdjustKind.GAUSSIAN, AdjustKind.MOTION, AdjustKind.PERSPECTIVE),
                "Efectos" to listOf(AdjustKind.SHARPEN, AdjustKind.NOISE, AdjustKind.BLOOM, AdjustKind.GLITCH, AdjustKind.HALFTONE, AdjustKind.CHROMATIC),
                "Herramientas" to listOf(AdjustKind.LIQUIFY),
            )
            groups.forEach { (title, kinds) ->
                Text(title.uppercase(), color = Ui.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                kinds.forEach { k ->
                    MenuRow(k.label) { if (k == AdjustKind.INVERT) { st.panel = null; st.invertColors() } else st.startAdjust(k) }
                }
                if (title == "Herramientas") MenuRow("Clonar") { st.startClone() }
            }
        }
    }
}

/** Sliders of the adjustment in progress, with apply / cancel. */
@Composable
fun AdjustSheet(st: EditorState) {
    val k = st.adjustKind ?: return
    val p = st.adjustParams
    fun set(i: Int, v: Float) { val q = p.copyOf(); q[i] = v; st.setAdjust(q) }
    Column(Modifier.padding(horizontal = 12.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(16.dp)).background(Ui.panel)
        .border(1.dp, Ui.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(k.label, color = Ui.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton({ st.setAdjust(st.defaultParams(k)) }) { Text("Restablecer") }
            TextButton({ st.exitMode(apply = false) }) { Text("Cancelar") }
            Button({ st.exitMode(apply = true) }) { Text("Aplicar") }
        }
        if (st.hasSelection) Text("Solo dentro de la selección", color = Ui.dim, fontSize = 12.sp)
        when (k) {
            AdjustKind.HSB -> {
                LabeledSlider("Tono", p[0], -180f..180f, { "${it.toInt()}°" }) { set(0, it) }
                LabeledSlider("Saturación", p[1], -1f..1f, { "${(it * 100).toInt()} %" }) { set(1, it) }
                LabeledSlider("Brillo", p[2], -1f..1f, { "${(it * 100).toInt()} %" }) { set(2, it) }
            }
            AdjustKind.BALANCE -> {
                val names = listOf("Sombras", "Medios tonos", "Luces")
                for (r in 0..2) {
                    Text(names[r], color = Ui.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    LabeledSlider("Cian ↔ Rojo", p[r * 3], -1f..1f, { "%.2f".format(it) }) { set(r * 3, it) }
                    LabeledSlider("Magenta ↔ Verde", p[r * 3 + 1], -1f..1f, { "%.2f".format(it) }) { set(r * 3 + 1, it) }
                    LabeledSlider("Amarillo ↔ Azul", p[r * 3 + 2], -1f..1f, { "%.2f".format(it) }) { set(r * 3 + 2, it) }
                }
            }
            AdjustKind.CURVES -> CurvesEditor(st)
            AdjustKind.GRADIENT_MAP -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorState.GRADIENTS.forEachIndexed { i, (name, stops) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { st.gradientIndex = i; st.refreshLut() }) {
                            Box(Modifier.size(90.dp, 26.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(stops.map { Color(it) }))
                                .border(if (st.gradientIndex == i) 2.dp else 0.dp, Color.White, RoundedCornerShape(6.dp)))
                            Text(name, color = Ui.text, fontSize = 11.sp)
                        }
                    }
                }
                LabeledSlider("Intensidad", p[0]) { set(0, it) }
            }
            AdjustKind.BRIGHT_CONTRAST -> {
                LabeledSlider("Brillo", p[0], -0.5f..0.5f, { "%.2f".format(it) }) { set(0, it) }
                LabeledSlider("Contraste", p[1], -1f..2f, { "%.2f".format(it) }) { set(1, it) }
            }
            AdjustKind.GAUSSIAN -> LabeledSlider("Radio", p[0], 0.5f..120f, { "${it.toInt()} px" }) { set(0, it) }
            AdjustKind.MOTION -> {
                LabeledSlider("Distancia", p[0], 0f..400f, { "${it.toInt()} px" }) { set(0, it) }
                LabeledSlider("Ángulo", p[1], 0f..PI.toFloat(), { "${Math.toDegrees(it.toDouble()).toInt()}°" }) { set(1, it) }
            }
            AdjustKind.PERSPECTIVE -> {
                Text("Toca el lienzo para mover el centro", color = Ui.dim, fontSize = 12.sp)
                LabeledSlider("Cantidad", p[0], 0f..1f) { set(0, it) }
            }
            AdjustKind.SHARPEN -> {
                LabeledSlider("Cantidad", p[0], 0f..3f, { "%.2f".format(it) }) { set(0, it) }
                LabeledSlider("Radio", p[1], 0.5f..10f, { "%.1f px".format(it) }) { set(1, it) }
            }
            AdjustKind.NOISE -> {
                LabeledSlider("Cantidad", p[0], 0f..1f) { set(0, it) }
                LabeledSlider("Escala", p[1], 1f..80f, { "${it.toInt()} px" }) { set(1, it) }
                LabeledSlider("Octavas", p[2], 1f..6f, { "${it.toInt()}" }) { set(2, it) }
                SettingSwitch("Ruido de color", p[3] > 0.5f) { set(3, if (it) 1f else 0f) }
            }
            AdjustKind.CHROMATIC -> {
                Text("Toca el lienzo para mover el centro", color = Ui.dim, fontSize = 12.sp)
                LabeledSlider("Desplazamiento", p[0], 0f..60f, { "${it.toInt()} px" }) { set(0, it) }
                LabeledSlider("Caída", p[3], 0f..4f, { "%.1f".format(it) }) { set(3, it) }
            }
            AdjustKind.BLOOM -> {
                LabeledSlider("Umbral", p[0]) { set(0, it) }
                LabeledSlider("Intensidad", p[1], 0f..2f, { "%.2f".format(it) }) { set(1, it) }
                LabeledSlider("Extensión", p[2], 2f..120f, { "${it.toInt()} px" }) { set(2, it) }
            }
            AdjustKind.GLITCH -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Artefacto", "Onda", "Señal").forEachIndexed { i, l -> FilterChip(p[3].roundToInt() == i, { set(3, i.toFloat()) }, label = { Text(l, fontSize = 12.sp) }) }
                }
                LabeledSlider("Cantidad", p[0]) { set(0, it) }
                LabeledSlider("Tamaño de bloque", p[1], 2f..80f, { "${it.toInt()} px" }) { set(1, it) }
                LabeledSlider("Semilla", p[2], 0f..100f, { "${it.toInt()}" }) { set(2, it.roundToInt().toFloat()) }
            }
            AdjustKind.HALFTONE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Color", "Serigrafía", "Periódico").forEachIndexed { i, l -> FilterChip(p[1].roundToInt() == i, { set(1, i.toFloat()) }, label = { Text(l, fontSize = 12.sp) }) }
                }
                LabeledSlider("Tamaño del punto", p[0], 3f..60f, { "${it.toInt()} px" }) { set(0, it) }
            }
            else -> {}
        }
    }
}

@Composable
private fun CurvesEditor(st: EditorState) {
    val names = listOf("RGB", "Rojo", "Verde", "Azul")
    val cols = listOf(Color.White, Color(0xFFFF5A5A), Color(0xFF5AE06A), Color(0xFF5A8AFF))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n -> FilterChip(st.curveChannel == i, { st.curveChannel = i }, label = { Text(n, fontSize = 12.sp) }) }
    }
    val ch = st.curveChannel
    var dragging by remember { mutableIntStateOf(-1) }
    Box(Modifier.fillMaxWidth().aspectRatio(1.4f).padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B2B2F))) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(ch) {
                detectDragGestures(
                    onDragStart = { o ->
                        val pts = st.curves[ch]
                        val x = o.x / size.width; val y = 1 - o.y / size.height
                        dragging = pts.indexOfFirst { hypot((it.x - x) * size.width, (it.y - y) * size.height) < 40f }
                        if (dragging < 0) { st.curves[ch] = (pts + Offset2(x, y)).sortedBy { it.x }; dragging = st.curves[ch].indexOfFirst { it.x == x } }
                    },
                    onDragEnd = { dragging = -1 },
                ) { c, _ ->
                    c.consume()
                    if (dragging < 0) return@detectDragGestures
                    val pts = st.curves[ch].toMutableList()
                    val x = (c.position.x / size.width).coerceIn(0f, 1f); val y = (1 - c.position.y / size.height).coerceIn(0f, 1f)
                    val nx = if (dragging == 0) 0f else if (dragging == pts.lastIndex) 1f else x.coerceIn(pts[dragging - 1].x + 0.01f, pts[dragging + 1].x - 0.01f)
                    pts[dragging] = Offset2(nx, y)
                    st.curves[ch] = pts
                    st.refreshLut()
                }
            }
            .pointerInput(ch) { detectTapGestures(onDoubleTap = { st.curves[ch] = listOf(Offset2(0f, 0f), Offset2(1f, 1f)); st.refreshLut() }) }) {
            for (k in 1..3) { drawLine(Ui.line, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height)); drawLine(Ui.line, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4)) }
            val t = Curves.table(st.curves[ch])
            for (i in 1 until 256) drawLine(cols[ch], Offset(size.width * (i - 1) / 255, size.height * (1 - t[i - 1] / 255f)), Offset(size.width * i / 255, size.height * (1 - t[i] / 255f)), 3f)
            st.curves[ch].forEach { drawCircle(Color.White, 9f, Offset(it.x * size.width, (1 - it.y) * size.height)) }
        }
    }
    Text("Arrastra para añadir o mover puntos · doble toque: restablecer", color = Ui.dim, fontSize = 11.sp)
}

// ====================================================================== animation timeline

@Composable
fun AnimationTimeline(st: EditorState) {
    val frames = st.frames
    var holdFor by remember { mutableStateOf<dev.pistation.trazo.engine.LayerInfo?>(null) }
    holdFor?.let { f ->
        var hold by remember(f.id) { mutableFloatStateOf(f.props.hold.toFloat()) }
        AlertDialog({ holdFor = null }, confirmButton = { TextButton({ st.setProps(f.id, f.props.copy(hold = hold.toInt())); holdFor = null }) { Text("Aceptar") } },
            dismissButton = { TextButton({ holdFor = null }) { Text("Cancelar") } },
            title = { Text("Fotograma") },
            text = { Column {
                LabeledSlider("Duración (fotogramas)", hold, 1f..12f, { "${it.toInt()}" }) { hold = it }
                TextButton({ holdFor = null; st.duplicateLayer(f.id) }) { Text("Duplicar fotograma") }
                TextButton({ holdFor = null; st.deleteLayer(f.id) }) { Text("Eliminar fotograma", color = Color(0xFFFF6B6B)) }
            } })
    }
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.panel).border(1.dp, Ui.line, RoundedCornerShape(16.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton({ st.togglePlay() }) { Icon(if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Reproducir", tint = Ui.text) }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            frames.forEachIndexed { i, f ->
                val thumb = if (f.isGroup) st.layers.lastOrNull { it.parent == f.id }?.thumb else f.thumb
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (i == st.animFrame) Ui.accent.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable { if (st.playing) st.togglePlay(); if (i == st.animFrame) holdFor = f else st.goToFrame(i) }.padding(4.dp)) {
                    Box(Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(4.dp)).background(Color.White)) {
                        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                    }
                    Text("${i + 1}" + if (f.props.hold > 1) " ×${f.props.hold}" else "", color = Ui.text, fontSize = 11.sp)
                }
            }
        }
        IconButton({ st.addFrame() }) { Icon(Icons.Filled.Add, "Añadir fotograma", tint = Ui.text) }
    }
}

// ====================================================================== dialogs

@Composable
fun CopyPasteMenu(st: EditorState) {
    AlertDialog({ st.copyPasteMenu = false }, confirmButton = {}, title = { Text("Copiar y pegar") }, text = {
        Column {
            listOf("Cortar" to st::cut, "Copiar" to st::copy, "Copiar todo" to st::copyAll, "Duplicar" to st::duplicateSelection,
                "Cortar y pegar" to st::cutAndPaste, "Pegar" to st::paste).forEach { (l, f) ->
                MenuRow(l) { st.copyPasteMenu = false; f() }
            }
        }
    })
}

@Composable
fun TextDialog(st: EditorState) {
    var text by remember { mutableStateOf("") }
    var family by remember { mutableStateOf("sans-serif") }
    var size by remember { mutableFloatStateOf((st.meta.height / 12f).coerceIn(24f, 400f)) }
    var bold by remember { mutableStateOf(false) }
    var italic by remember { mutableStateOf(false) }
    var align by remember { mutableIntStateOf(0) }
    val fonts = listOf("sans-serif" to "Sans", "serif" to "Serif", "monospace" to "Mono", "sans-serif-condensed" to "Condensada",
        "cursive" to "Manuscrita", "casual" to "Informal", "sans-serif-black" to "Negra", "sans-serif-light" to "Fina")
    AlertDialog({ st.textDialog = false },
        confirmButton = { TextButton({ st.textDialog = false; st.addText(text, family, size, bold, italic, align) }, enabled = text.isNotBlank()) { Text("Añadir") } },
        dismissButton = { TextButton({ st.textDialog = false }) { Text("Cancelar") } },
        title = { Text("Añadir texto") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("Texto") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fonts.forEach { (f, n) -> FilterChip(family == f, { family = f }, label = { Text(n) }) }
                }
                LabeledSlider("Tamaño", size, 12f..600f, { "${it.toInt()} px" }) { size = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(bold, { bold = !bold }, label = { Text("Negrita") })
                    FilterChip(italic, { italic = !italic }, label = { Text("Cursiva") })
                    listOf("Izq.", "Centro", "Der.").forEachIndexed { i, l -> FilterChip(align == i, { align = i }, label = { Text(l) }) }
                }
                Text("Usa el color actual. Después puedes moverlo y escalarlo.", color = Ui.dim, fontSize = 12.sp)
            }
        })
}

@Composable
fun ResizeDialog(st: EditorState) {
    var w by remember { mutableStateOf(st.meta.width.toString()) }
    var h by remember { mutableStateOf(st.meta.height.toString()) }
    var scale by remember { mutableStateOf(true) }
    var lock by remember { mutableStateOf(true) }
    val ratio = st.meta.width.toFloat() / st.meta.height
    val wi = w.toIntOrNull() ?: 0; val hi = h.toIntOrNull() ?: 0
    val valid = wi in 64..4096 && hi in 64..4096
    AlertDialog({ st.resizeDialog = false },
        confirmButton = { TextButton({ st.resizeDialog = false; st.resizeCanvas(wi, hi, scale) }, enabled = valid) { Text("Aplicar") } },
        dismissButton = { TextButton({ st.resizeDialog = false }) { Text("Cancelar") } },
        title = { Text("Recortar y cambiar tamaño") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { v -> w = v.filter(Char::isDigit).take(4); if (lock) w.toIntOrNull()?.let { h = (it / ratio).roundToInt().toString() } },
                        label = { Text("Ancho") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(h, { v -> h = v.filter(Char::isDigit).take(4); if (lock) h.toIntOrNull()?.let { w = (it * ratio).roundToInt().toString() } },
                        label = { Text("Alto") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                SettingSwitch("Mantener proporción", lock) { lock = it }
                SettingSwitch("Escalar el dibujo (si no, recorta o amplía el lienzo centrado)", scale) { scale = it }
                Text("Se guarda y se vuelve a abrir el lienzo; el historial de deshacer se pierde.", color = Ui.dim, fontSize = 12.sp)
            }
        })
}

@Composable
fun VideoDialog(file: File, onClose: () -> Unit) {
    Dialog(onClose) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Ui.panel).padding(12.dp)) {
            Text("Time-lapse", color = Ui.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AndroidView({ ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.path)
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setOnPreparedListener { it.isLooping = true; start() }
                }
            }, Modifier.fillMaxWidth().aspectRatio(16f / 10f).padding(top = 8.dp))
            TextButton(onClose, Modifier.align(Alignment.End)) { Text("Cerrar") }
        }
    }
}
