package dev.pistation.trazo.ui

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import dev.pistation.trazo.data.*
import dev.pistation.trazo.engine.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

enum class Panel { BRUSHES, LAYERS, COLOR, ACTIONS, ADJUST }
enum class Mode { NONE, SELECT, TRANSFORM, ADJUST, LIQUIFY, CLONE, GUIDE_EDIT }

data class PickState(val color: Int?, val x: Float, val y: Float, val previous: Int)

/** Everything the editor screen shows, plus the bridge to the GL canvas. */
class EditorState(
    val context: Context, val store: ArtworkStore, val brushStore: BrushStore, initial: ArtworkMeta,
    private val reopen: (ArtworkMeta) -> Unit,
) : CanvasCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("trazo", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    var meta by mutableStateOf(initial)
    var view: CanvasView? = null

    // ------------------------------------------------------------------ tools & brushes
    var tool by mutableStateOf(Tool.BRUSH)
    val brushIds = mutableStateMapOf(
        Tool.BRUSH to prefs.getString("brush.BRUSH", "studiopen")!!,
        Tool.SMUDGE to prefs.getString("brush.SMUDGE", "blender")!!,
        Tool.ERASER to prefs.getString("brush.ERASER", "softair")!!,
        Tool.CLONE to prefs.getString("brush.CLONE", "roundbrush")!!,
    )
    val sizes = mutableStateMapOf(*Tool.entries.map { it to prefs.getFloat("size.${it.name}", if (it == Tool.BRUSH) 0.25f else 0.35f) }.toTypedArray())
    val opacities = mutableStateMapOf(Tool.BRUSH to 1f, Tool.SMUDGE to 0.8f, Tool.ERASER to 1f, Tool.CLONE to 1f)
    /** Edits to brushes (built-in or custom), persisted. */
    val brushEdits = mutableStateMapOf<String, Brush>()
    val customBrushes = mutableStateListOf<Brush>()

    var color by mutableIntStateOf(prefs.getInt("color", 0xFF1F2A44.toInt()))
    val recent = mutableStateListOf<Int>().apply { addAll(loadInts("recent")) }
    val palette = mutableStateListOf<Int>().apply { addAll(loadInts("palette").ifEmpty { DEFAULT_PALETTE }) }

    // ------------------------------------------------------------------ document mirror
    var layers by mutableStateOf(listOf<LayerInfo>())
    var activeId by mutableLongStateOf(0L)
    var editingMask by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var bgColor by mutableIntStateOf(0xFFFFFFFF.toInt())
    var bgVisible by mutableStateOf(true)
    var hasSelection by mutableStateOf(false)
    var ready by mutableStateOf(false)

    // ------------------------------------------------------------------ UI state
    var panel by mutableStateOf<Panel?>(null)
    var mode by mutableStateOf(Mode.NONE)
    var uiHidden by mutableStateOf(false)
    var pick by mutableStateOf<PickState?>(null)
    var message by mutableStateOf<String?>(null)
    var showBrushSize by mutableStateOf(false)
    var hover by mutableStateOf<Offset2?>(null)
    var viewVersion by mutableIntStateOf(0)
    var copyPasteMenu by mutableStateOf(false)
    var textDialog by mutableStateOf(false)
    var resizeDialog by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)

    val selection = SelectionController(this)
    var transform by mutableStateOf<TransformController?>(null)
    val liquify = LiquifyController(this)
    val guides = GuidesController(this)

    // Adjustments
    var adjustKind by mutableStateOf<AdjustKind?>(null)
    var adjustParams by mutableStateOf(FloatArray(12))
    var curveChannel by mutableIntStateOf(0)
    val curves = mutableStateListOf(*Array(4) { listOf(Offset2(0f, 0f), Offset2(1f, 1f)) })
    var gradientIndex by mutableIntStateOf(0)

    // Clone
    var cloneSource by mutableStateOf<Offset2?>(null)
    var cloneOffset: FloatArray? = null
    var placingClone by mutableStateOf(false)

    // Reference window
    var refVisible by mutableStateOf(false)
    var refImage by mutableStateOf<Bitmap?>(null)
    var refFromCanvas by mutableStateOf(true)

    // Animation
    var animOn by mutableStateOf(false)
    var animFrame by mutableIntStateOf(0)
    var animFps by mutableIntStateOf(prefs.getInt("animFps", 12))
    var animLoop by mutableIntStateOf(0)  // 0 loop, 1 ping-pong, 2 once
    var animOnion by mutableIntStateOf(1)
    var animOnionOpacity by mutableFloatStateOf(0.35f)
    var animBgFirst by mutableStateOf(false)
    var animFgLast by mutableStateOf(false)
    var playing by mutableStateOf(false)
    private var playDir = 1

    // 3D
    var show3d by mutableStateOf(initial.model != null)
    val is3d get() = meta.model != null

    // Preferences
    var fingerPaint by mutableStateOf(prefs.getBoolean("fingerPaint", false))
    var quickShape by mutableStateOf(prefs.getBoolean("quickShape", true))
    var predict by mutableStateOf(prefs.getBoolean("predict", true))
    var fillFromCanvas by mutableStateOf(prefs.getBoolean("fillFromCanvas", false))
    var fillThreshold by mutableFloatStateOf(prefs.getFloat("fillThreshold", 0.12f))
    var rightHanded by mutableStateOf(prefs.getBoolean("rightHanded", false))
    var timelapseOn by mutableStateOf(prefs.getBoolean("timelapse", true))
    /** Global pressure curve: outputs at inputs 0, .25, .5, .75, 1. */
    var pressureCurve by mutableStateOf(loadCurve())

    private var dirty = false
    private val autosave = Runnable { save() }

    init {
        val (custom, edits) = brushStore.load()
        customBrushes.addAll(custom); brushEdits.putAll(edits)
        BrushLibrary.custom = custom
        guides.fromJson(initial.extra.optJSONObject("guides"))
        guides.init(initial.width, initial.height)
        initial.extra.optJSONObject("anim")?.let { a ->
            animOn = a.optBoolean("on"); animFps = a.optInt("fps", animFps); animLoop = a.optInt("loop"); animOnion = a.optInt("onion", 1)
            animOnionOpacity = a.optDouble("onionOpacity", 0.35).toFloat(); animBgFirst = a.optBoolean("bgFirst"); animFgLast = a.optBoolean("fgLast")
        }
        initial.extra.optString("timelapse").takeIf { it.isNotEmpty() }?.let { timelapseOn = it == "on" }
    }

    fun brushFor(t: Tool): Brush = brushIds[t]!!.let { id -> brushEdits[id] ?: customBrushes.firstOrNull { it.id == id } ?: BrushLibrary.byId(id) }
    val brush get() = brushFor(tool)

    fun toolState(x: Float, y: Float): ToolState {
        if (panel != null) panel = null
        val b = brush
        var off: FloatArray? = null
        if (tool == Tool.CLONE) {
            val src = cloneSource ?: Offset2(x + 200f, y).also { cloneSource = it; main.post { flash("Origen del clonado colocado; muévelo desde la barra") } }
            off = cloneOffset ?: floatArrayOf(src.x - x, src.y - y).also { cloneOffset = it }
        }
        return ToolState(tool, b, b.sizeFor(sizes[tool]!!), opacities[tool]!!, color, off)
    }

    fun attach(v: CanvasView) {
        view = v
        v.toolState = { toolState(0f, 0f) }
        v.toolStateAt = { x, y -> toolState(x, y) }
        v.source = { docSource() }
        applyPrefsToView()
        guides.apply()
    }

    private fun docSource(): DocSource {
        val m = runCatching { store.readMeta(meta.id) }.getOrDefault(meta)
        loadBrushImages()
        return DocSource(m.width, m.height, m.bgColor, m.bgVisible,
            m.layers.map { l -> DocLayer(l.id, l.props, l.isGroup, l.parent, { store.loadPixels(m, "L${l.id}") },
                { if (l.hasMask) store.loadPixels(m, "M${l.id}") else null }) },
            m.activeId, store.loadModel(m))
    }

    /** Custom brush images into the engine's table (GL thread reads them lazily). */
    private fun loadBrushImages() {
        val keys = (customBrushes + brushEdits.values).flatMap { listOfNotNull(it.shapeImage, it.grainImage) }.toSet()
        val imgs = keys.mapNotNull { k -> brushStore.imageBytes(k)?.let { k to it } }
        view?.gl { e -> imgs.forEach { (k, v) -> e.customImages[k] = v } }
    }

    // ------------------------------------------------------------------ tools

    fun selectTool(t: Tool) {
        if (mode != Mode.NONE && mode != Mode.CLONE) exitMode(apply = true)
        if (t != Tool.CLONE && mode == Mode.CLONE) { mode = Mode.NONE; updateSink() }
        if (tool == t) panel = if (panel == Panel.BRUSHES) null else Panel.BRUSHES
        else { tool = t; panel = null }
    }

    fun selectBrush(id: String) {
        brushIds[tool] = id
        prefs.edit().putString("brush.${tool.name}", id).apply()
    }

    fun editBrush(b: Brush) {
        if (b.custom) { val i = customBrushes.indexOfFirst { it.id == b.id }; if (i >= 0) customBrushes[i] = b }
        else brushEdits[b.id] = b
        persistBrushes()
    }

    fun resetBrush(id: String) { brushEdits.remove(id); persistBrushes() }

    fun duplicateBrush(b: Brush) {
        val nb = b.copy(id = "u" + System.currentTimeMillis(), name = b.name + " copia", category = "Mis pinceles", custom = true)
        customBrushes.add(nb); persistBrushes(); selectBrush(nb.id)
    }

    fun deleteBrush(b: Brush) {
        if (!b.custom) return
        customBrushes.removeAll { it.id == b.id }; brushStore.deleteImages(b); persistBrushes()
        if (brushIds[tool] == b.id) selectBrush(BrushLibrary.all[0].id)
    }

    /** Imported image as a brush's shape or grain. */
    fun setBrushImage(b: Brush, uri: Uri, grain: Boolean, invert: Boolean) {
        io.execute {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@execute
            val key = brushStore.saveImage("${b.id}-${if (grain) "grain" else "shape"}-${System.currentTimeMillis()}", bmp, invert)
            val bytes = brushStore.imageBytes(key) ?: return@execute
            main.post {
                view?.gl { it.customImages[key] = bytes }
                editBrush(if (grain) b.copy(grainImage = key, grainDepth = if (b.grainDepth == 0f) 0.7f else b.grainDepth) else b.copy(shapeImage = key))
            }
        }
    }

    fun importBrushes(uri: Uri) {
        busy = "Importando pinceles…"
        io.execute {
            val res = runCatching { context.contentResolver.openInputStream(uri)!!.use { BrushImporter.read(it, "imp${System.currentTimeMillis()}") } }
            main.post {
                busy = null
                res.onSuccess { list ->
                    if (list.isEmpty()) { flash("No se encontró ningún pincel"); return@onSuccess }
                    io.execute {
                        val made = list.map { imp ->
                            var b = imp.brush
                            imp.shape?.let { b = b.copy(shapeImage = brushStore.saveImage("${b.id}-shape", it, false)) }
                            imp.grain?.let { b = b.copy(grainImage = brushStore.saveImage("${b.id}-grain", it, false)) }
                            b
                        }
                        main.post { customBrushes.addAll(made); persistBrushes(); loadBrushImages(); flash("${made.size} pinceles importados") }
                    }
                }.onFailure { flash("No se pudo importar: ${it.message}") }
            }
        }
    }

    private fun persistBrushes() {
        BrushLibrary.custom = customBrushes.toList()
        val c = customBrushes.toList(); val e = brushEdits.toMap()
        io.execute { brushStore.save(c, e) }
    }

    fun setSize(v: Float) { sizes[tool] = v; prefs.edit().putFloat("size.${tool.name}", v).apply() }
    fun setOpacity(v: Float) { opacities[tool] = v }

    fun setColor(c: Int, remember: Boolean = false) {
        color = c or (0xFF shl 24)
        prefs.edit().putInt("color", color).apply()
        if (remember) rememberColor()
    }

    fun rememberColor() {
        recent.remove(color); recent.add(0, color)
        while (recent.size > 10) recent.removeAt(recent.lastIndex)
        saveInts("recent", recent)
    }

    fun addSwatch() { if (color !in palette) { palette.add(color); saveInts("palette", palette) } }
    fun removeSwatch(c: Int) { palette.remove(c); saveInts("palette", palette) }

    fun updatePrefs() {
        prefs.edit().putBoolean("fingerPaint", fingerPaint).putBoolean("quickShape", quickShape).putBoolean("predict", predict)
            .putBoolean("fillFromCanvas", fillFromCanvas).putFloat("fillThreshold", fillThreshold).putBoolean("rightHanded", rightHanded)
            .putBoolean("timelapse", timelapseOn).putString("pressureCurve", pressureCurve.joinToString(",")).apply()
        applyPrefsToView()
    }

    private fun applyPrefsToView() {
        val v = view ?: return
        val curve = pressureCurve.copyOf()
        v.pressureMap = { p ->
            val x = p.coerceIn(0f, 1f) * 4f
            val i = x.toInt().coerceAtMost(3)
            curve[i] + (curve[i + 1] - curve[i]) * (x - i)
        }
        v.fingerPaints = fingerPaint
        v.quickShape = quickShape
        v.predict = predict
    }

    private fun loadCurve(): FloatArray = prefs.getString("pressureCurve", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        ?.takeIf { it.size == 5 }?.toFloatArray() ?: floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    // ------------------------------------------------------------------ document commands

    fun gl(block: (PaintEngine) -> Unit) = view?.gl(block)

    fun undo() = gl { it.undo() }
    fun redo() = gl { it.redo() }
    fun addLayer() = gl { it.addLayer() }
    fun setActive(id: Long) = gl { it.setActive(id); it.setEditMask(false) }
    fun editMask(id: Long) = gl { it.setActive(id); it.setEditMask(true) }
    fun deleteLayer(id: Long) = gl { it.deleteLayer(id) }
    fun duplicateLayer(id: Long) = gl { it.duplicateLayer(id) }
    fun mergeDown(id: Long) = gl { it.mergeDown(id) }
    fun mergeGroup(id: Long) = gl { it.mergeGroup(id) }
    fun flattenAll() = gl { it.flattenAll() }
    fun moveLayer(from: Int, to: Int) = gl { it.moveLayer(from, to) }
    fun newGroup(id: Long) = gl { it.newGroup(id) }
    fun groupWithBelow(id: Long) = gl { it.groupWithBelow(id) }
    fun ungroup(id: Long) = gl { it.ungroup(id) }
    fun addMask(id: Long) = gl { it.addMask(id) }
    fun deleteMask(id: Long) = gl { it.deleteMask(id) }
    fun applyMask(id: Long) = gl { it.applyMask(id) }
    fun invertMask(id: Long) = gl { it.invertMask(id) }
    fun setProps(id: Long, p: LayerProps, record: Boolean = true, before: LayerProps? = null) = gl { it.setProps(id, p, record, before) }
    fun clearLayer() = gl { it.clearLayer() }
    fun fillLayer() { val c = color; gl { it.fillLayer(c) } }
    fun flipLayer(h: Boolean) = gl { it.flipLayer(h) }
    fun flipCanvas(h: Boolean) = gl { it.flipCanvas(h) }
    fun setBackground(c: Int, visible: Boolean) = gl { it.setBackground(c, visible) }
    fun selectLayerContent(id: Long) = gl { it.setActive(id); it.selectLayerContent(0) }
    fun invertColors() = gl { if (it.beginAdjust(AdjustKind.INVERT)) it.commitAdjust() }

    fun colorDrop(x: Float, y: Float) {
        rememberColor()
        view?.fillAt(x, y, color, fillThreshold, fillFromCanvas)
    }

    fun startPickNext() { view?.pickNext = true; flash("Toca el lienzo para tomar un color") }

    fun flash(text: String) {
        message = text
        main.removeCallbacks(clearMessage)
        main.postDelayed(clearMessage, 1600)
    }
    private val clearMessage = Runnable { message = null }

    fun toScreen(x: Float, y: Float) = view?.toScreen(x, y) ?: floatArrayOf(x, y)
    fun zoom() = view?.zoom ?: 1f

    // ------------------------------------------------------------------ modes

    fun updateSink() {
        view?.sink = when {
            placingClone -> CloneSourceSink(this)
            mode == Mode.SELECT -> selection
            mode == Mode.TRANSFORM -> transform
            mode == Mode.LIQUIFY -> liquify
            mode == Mode.GUIDE_EDIT -> guides
            mode == Mode.ADJUST && adjustKind in listOf(AdjustKind.PERSPECTIVE, AdjustKind.CHROMATIC) -> CenterSink { x, y ->
                val p = adjustParams.copyOf(); p[1] = x; p[2] = y; setAdjust(p)
            }
            else -> null
        }
    }

    fun enterSelect() {
        if (mode == Mode.SELECT) { exitMode(true); return }
        exitMode(true); panel = null
        mode = Mode.SELECT; updateSink()
    }

    fun enterTransform() {
        if (mode == Mode.TRANSFORM) { exitMode(true); return }
        exitMode(true); panel = null
        gl { e ->
            val box = e.beginTransform()
            main.post {
                if (box != null) { transform = TransformController(this, box); mode = Mode.TRANSFORM; updateSink() }
            }
        }
    }

    fun startAdjust(kind: AdjustKind) {
        exitMode(true); panel = null
        if (kind == AdjustKind.LIQUIFY) {
            gl { e -> if (e.beginAdjust(kind)) main.post { mode = Mode.LIQUIFY; adjustKind = kind; updateSink() } }
            return
        }
        adjustKind = kind
        adjustParams = defaultParams(kind)
        if (kind == AdjustKind.CURVES) for (i in 0..3) curves[i] = listOf(Offset2(0f, 0f), Offset2(1f, 1f))
        val p = adjustParams; val lut = lutFor(kind)
        gl { e -> if (e.beginAdjust(kind)) { e.setAdjust(p, lut); main.post { mode = Mode.ADJUST; updateSink() } } else main.post { adjustKind = null } }
    }

    fun setAdjust(p: FloatArray) {
        adjustParams = p
        val lut = adjustKind?.let { lutFor(it) }
        gl { it.setAdjust(p, lut) }
    }

    fun refreshLut() = setAdjust(adjustParams)

    fun startClone() {
        exitMode(true); panel = null
        tool = Tool.CLONE; mode = Mode.CLONE
        if (cloneSource == null) { placingClone = true; flash("Toca el lienzo para elegir el origen") }
        updateSink()
    }

    fun moveCloneSource() { placingClone = true; updateSink() }

    fun startGuideEdit() { exitMode(true); panel = null; mode = Mode.GUIDE_EDIT; updateSink() }

    /** Leaves the current mode, applying (or cancelling) what it was doing. */
    fun exitMode(apply: Boolean) {
        when (mode) {
            Mode.TRANSFORM -> { gl { if (apply) it.commitTransform() else it.cancelTransform() }; transform = null }
            Mode.ADJUST, Mode.LIQUIFY -> { gl { if (apply) it.commitAdjust() else it.cancelAdjust() }; adjustKind = null }
            Mode.SELECT -> selection.cancel()
            Mode.CLONE -> { tool = Tool.BRUSH; placingClone = false }
            Mode.GUIDE_EDIT -> saveExtras()
            Mode.NONE -> {}
        }
        mode = Mode.NONE
        updateSink()
    }

    fun defaultParams(k: AdjustKind): FloatArray {
        val p = FloatArray(12)
        when (k) {
            AdjustKind.GAUSSIAN -> p[0] = 8f
            AdjustKind.MOTION -> { p[0] = 40f; p[1] = 0f }
            AdjustKind.PERSPECTIVE, AdjustKind.CHROMATIC -> { p[0] = if (k == AdjustKind.CHROMATIC) 6f else 0.3f; p[1] = meta.width / 2f; p[2] = meta.height / 2f; p[3] = 1.5f }
            AdjustKind.SHARPEN -> { p[0] = 0.8f; p[1] = 2f }
            AdjustKind.NOISE -> { p[0] = 0.25f; p[1] = 6f; p[2] = 3f }
            AdjustKind.BLOOM -> { p[0] = 0.7f; p[1] = 0.8f; p[2] = 24f }
            AdjustKind.GLITCH -> { p[0] = 0.5f; p[1] = 12f; p[2] = 1f }
            AdjustKind.HALFTONE -> { p[0] = 10f; p[1] = 0f }
            AdjustKind.GRADIENT_MAP -> p[0] = 1f
            else -> {}
        }
        return p
    }

    /** Lookup table for curves and gradient maps (256 RGBA). */
    fun lutFor(k: AdjustKind): ByteArray? = when (k) {
        AdjustKind.CURVES -> {
            val master = Curves.table(curves[0]); val r = Curves.table(curves[1]); val g = Curves.table(curves[2]); val b = Curves.table(curves[3])
            ByteArray(1024) { i -> val x = i / 4; when (i % 4) { 0 -> r[master[x]]; 1 -> g[master[x]]; 2 -> b[master[x]]; else -> 255 }.toByte() }
        }
        AdjustKind.GRADIENT_MAP -> {
            val stops = GRADIENTS[gradientIndex].second
            ByteArray(1024) { i ->
                val t = (i / 4) / 255f
                val seg = (t * (stops.size - 1)).coerceAtMost(stops.size - 1.001f)
                val a = stops[seg.toInt()]; val b = stops[seg.toInt() + 1]; val f = seg - seg.toInt()
                val ch = 16 - (i % 4) * 8
                if (i % 4 == 3) (-1).toByte() else ((((a shr ch) and 255) * (1 - f) + ((b shr ch) and 255) * f).toInt()).toByte()
            }
        }
        else -> null
    }

    // ------------------------------------------------------------------ clipboard, text, photos

    fun copy() = gl { e -> val ok = e.copy(); main.post { flash(if (ok) "Copiado" else "Nada que copiar") } }
    fun copyAll() = gl { e -> val ok = e.copyMerged(); main.post { flash(if (ok) "Copiado todo" else "Nada que copiar") } }
    fun cut() = gl { e -> val ok = e.cut(); main.post { flash(if (ok) "Cortado" else "Nada que cortar") } }
    fun paste() = gl { e -> val id = e.paste(); main.post { if (id == 0L) flash("El portapapeles está vacío") } }
    fun duplicateSelection() = gl { e -> if (e.copy()) e.paste() }
    fun cutAndPaste() = gl { e -> if (e.cut()) e.paste() }

    /** Adds a bitmap as a new layer, centred and fitted, then opens Transform on it. */
    fun addBitmapLayer(name: String, src: Bitmap) {
        val w = meta.width; val h = meta.height
        val s = minOf(1f, w.toFloat() / src.width, h.toFloat() / src.height)
        val bw = (src.width * s).toInt().coerceAtLeast(1); val bh = (src.height * s).toInt().coerceAtLeast(1)
        val scaled = if (s < 1f) Bitmap.createScaledBitmap(src, bw, bh, true) else src
        val argb = if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
        val buf = ByteBuffer.allocateDirect(bw * bh * 4).order(ByteOrder.nativeOrder())
        argb.copyPixelsToBuffer(buf); buf.position(0)
        val x = (w - bw) / 2; val y = (h - bh) / 2
        gl { e ->
            val id = e.addLayerWith(name, buf, x, y, bw, bh)
            if (id != 0L) main.post { enterTransform() }
        }
    }

    fun insertPhoto(uri: Uri) {
        io.execute {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= meta.width && opts.outHeight / (sample * 2) >= meta.height) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            main.post { if (bmp != null) addBitmapLayer("Foto", bmp) else flash("No se pudo abrir la imagen") }
        }
    }

    fun addText(text: String, family: String, sizePx: Float, bold: Boolean, italic: Boolean, align: Int) {
        if (text.isBlank()) return
        val style = (if (bold) android.graphics.Typeface.BOLD else 0) or (if (italic) android.graphics.Typeface.ITALIC else 0)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = this@EditorState.color; textSize = sizePx
            typeface = android.graphics.Typeface.create(family, style)
        }
        val maxW = (meta.width * 0.9f).toInt()
        val width = minOf(maxW, text.lines().maxOf { paint.measureText(it) }.toInt() + 4)
        val al = when (align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(al).build()
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        layout.draw(android.graphics.Canvas(bmp))
        addBitmapLayer("Texto: ${text.take(20)}", bmp)
    }

    fun setReferenceImage(uri: Uri) {
        io.execute {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            main.post { refImage = bmp; refFromCanvas = false; refVisible = true }
        }
    }

    private fun refreshReference() {
        if (!refVisible || !refFromCanvas) return
        gl { e -> val b = e.capture(700); main.post { if (refFromCanvas) refImage = b } }
    }

    // ------------------------------------------------------------------ animation

    /** Frames = top-level layers or groups, bottom first. */
    val frames: List<LayerInfo> get() = layers.filter { it.parent == 0L }

    fun setAnim(on: Boolean = animOn) {
        animOn = on
        val f = animFrame; val onion = if (playing) 0 else animOnion; val op = animOnionOpacity; val bg = animBgFirst; val fg = animFgLast
        gl { e -> e.anim.enabled = on; e.anim.frame = f; e.anim.onion = onion; e.anim.onionOpacity = op; e.anim.bgFirst = bg; e.anim.fgLast = fg }
        saveExtras()
    }

    fun goToFrame(i: Int) {
        val fr = frames
        if (fr.isEmpty()) return
        animFrame = i.coerceIn(0, fr.size - 1)
        val target = fr[animFrame]
        val leaf = if (target.isGroup) layers.lastOrNull { it.parent == target.id } ?: target else target
        setActive(leaf.id)
        setAnim()
    }

    fun addFrame() {
        gl { e ->
            // New top-level layer at the very top.
            e.setActive(e.layers.last().id)
            val l = e.addLayer()
            if (l != null && l.parent != 0L) e.moveLayer(e.layers.indexOf(l), e.layers.size - 1)
        }
        main.postDelayed({ goToFrame(frames.size - 1) }, 120)
    }

    fun togglePlay() {
        playing = !playing
        if (playing) { playDir = 1; setAnim(); main.post(playTick) } else { main.removeCallbacks(playTick); setAnim() }
    }

    private val playTick = object : Runnable {
        override fun run() {
            if (!playing) return
            val n = frames.size
            if (n > 0) {
                var f = animFrame + playDir
                when (animLoop) {
                    0 -> if (f >= n) f = 0
                    1 -> if (f >= n || f < 0) { playDir = -playDir; f = (animFrame + playDir).coerceIn(0, n - 1) }
                    2 -> if (f >= n) { playing = false; setAnim(); return }
                }
                animFrame = f.coerceIn(0, n - 1)
                val ff = animFrame
                gl { it.anim.frame = ff }
            }
            main.postDelayed(this, 1000L / animFps.coerceIn(1, 60))
        }
    }

    // ------------------------------------------------------------------ saving & export

    private fun extras(): JSONObject = JSONObject(meta.extra.toString())
        .put("guides", guides.toJson())
        .put("anim", JSONObject().put("on", animOn).put("fps", animFps).put("loop", animLoop).put("onion", animOnion)
            .put("onionOpacity", animOnionOpacity.toDouble()).put("bgFirst", animBgFirst).put("fgLast", animFgLast))
        .put("timelapse", if (timelapseOn) "on" else "off")

    fun saveExtras() {
        meta = meta.copy(extra = extras())
        prefs.edit().putInt("animFps", animFps).apply()
        dirty = true
    }

    /** Saves layers + thumbnail in the background; `then` runs on the main thread. */
    fun save(then: (() -> Unit)? = null) {
        main.removeCallbacks(autosave)
        val v = view
        if (v == null || !ready) { then?.let { main.post(it) }; return }
        val extra = extras()
        v.gl { e ->
            val m = meta.copy(
                bgColor = e.bgColor, bgVisible = e.bgVisible, activeId = e.activeId, extra = extra,
                layers = e.layers.map { StoredLayer(it.id, it.props, it.isGroup, it.parent, it.mask != null) })
            val px = HashMap<String, ByteBuffer>()
            e.layers.filter { !it.isGroup }.forEach { l -> px["L${l.id}"] = e.readLayer(l); e.readMask(l)?.let { px["M${l.id}"] = it } }
            val flat = e.flatten()
            dirty = false
            io.execute {
                runCatching { store.save(m, px, flat) }
                main.post { meta = m; then?.invoke() }
            }
        }
    }

    private fun exportDone(uri: Uri?, what: String) = main.post { busy = null; flash(if (uri != null) "$what guardado en ${what.folder()}" else "No se pudo exportar") }
    private fun String.folder() = when { startsWith("Vídeo") || startsWith("Time") -> "Películas/Trazo"; startsWith("PSD") -> "Descargas/Trazo"; else -> "Imágenes/Trazo" }

    fun export(png: Boolean) {
        busy = "Exportando…"
        gl { e -> val bmp = e.flatten(); io.execute { exportDone(runCatching { store.exportImage(meta.name, bmp, png) }.getOrNull(), if (png) "PNG" else "JPEG") } }
    }

    fun exportPsd() {
        busy = "Exportando PSD…"
        gl { e ->
            val w = e.width; val h = e.height
            val list = ArrayList<PsdWriter.PsdLayer>()
            for (b in e.blocks()) {
                val last = e.layers[b.last]
                fun leaf(l: Layer) = PsdWriter.PsdLayer(l.props.name, e.readLayer(l), e.readMask(l), l.props.opacity, l.props.visible,
                    l.props.blend.psd, l.props.clip, l.props.alphaLock)
                if (last.isGroup) {
                    list.add(PsdWriter.PsdLayer("</Layer group>", null, null, 1f, true, "norm", false, false, section = 3))
                    for (i in b.first until b.last) list.add(leaf(e.layers[i]))
                    list.add(PsdWriter.PsdLayer(last.props.name, null, null, last.props.opacity, last.props.visible, last.props.blend.psd, false, false, section = 1))
                } else for (i in b) list.add(leaf(e.layers[i]))
            }
            e.composite()
            val merged = e.frame.read(0, 0, w, h)
            io.execute {
                val uri = runCatching { store.exportStream("${meta.name}.psd", "image/vnd.adobe.photoshop") { PsdWriter.write(it, w, h, list, merged) } }.getOrNull()
                exportDone(uri, "PSD")
            }
        }
    }

    fun share() {
        gl { e ->
            val bmp = e.flatten()
            io.execute {
                val f = store.shareFile("${meta.name}.png") { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                shareFile(f, "image/png")
            }
        }
    }

    private fun shareFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "dev.pistation.trazo.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        main.post { context.startActivity(Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Animation as GIF or MP4, frame by frame (GL capture, then encode). */
    fun exportAnimation(gif: Boolean) {
        val n = frames.size
        if (n == 0) return
        busy = "Exportando animación…"
        val fps = animFps
        val maxSide = if (gif) 720 else 1920
        val tmp = File(context.cacheDir, "anim.${if (gif) "gif" else "mp4"}")
        var mp4: Mp4Encoder? = null
        var gifEnc: GifEncoder? = null
        var gifOut: java.io.OutputStream? = null
        fun step(i: Int) {
            if (i >= n) {
                io.execute {
                    runCatching { mp4?.finish(); gifEnc?.finish(); gifOut?.close() }
                    val uri = runCatching { store.exportStream("${meta.name}.${if (gif) "gif" else "mp4"}", if (gif) "image/gif" else "video/mp4") { tmp.inputStream().use { s -> s.copyTo(it) } } }.getOrNull()
                    exportDone(uri, if (gif) "GIF" else "Vídeo")
                }
                return
            }
            gl { e ->
                val full = e.flattenFrame(i)
                val s = minOf(1f, maxSide.toFloat() / maxOf(full.width, full.height))
                val bmp = if (s < 1f) Bitmap.createScaledBitmap(full, (full.width * s).toInt() / 2 * 2, (full.height * s).toInt() / 2 * 2, true) else full
                io.execute {
                    runCatching {
                        if (gif) {
                            if (gifEnc == null) { gifOut = tmp.outputStream().buffered(); gifEnc = GifEncoder(gifOut!!, bmp.width, bmp.height, animLoop != 2) }
                            gifEnc!!.add(store.onWhite(bmp).takeIf { bgVisible } ?: bmp, 1000 / fps)
                        } else {
                            if (mp4 == null) mp4 = Mp4Encoder(tmp, bmp.width, bmp.height, fps)
                            mp4!!.add(bmp)
                        }
                    }
                    main.post { busy = "Exportando animación… ${i + 1}/$n"; step(i + 1) }
                }
            }
        }
        step(0)
    }

    // ------------------------------------------------------------------ time-lapse

    private var lastCapture = 0L
    private fun captureTimelapse() {
        if (!timelapseOn || !ready) return
        val now = System.currentTimeMillis()
        if (now - lastCapture < 400) return
        lastCapture = now
        val dir = store.timelapseDir(meta.id)
        gl { e ->
            val bmp = e.capture(1280)
            io.execute {
                dir.mkdirs()
                val n = dir.list()?.size ?: 0
                File(dir, "f%06d.jpg".format(n)).outputStream().use { store.onWhite(bmp).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
    }

    fun timelapseFrames() = store.timelapseDir(meta.id).listFiles()?.size ?: 0

    /** Encodes the recorded frames; `then` gets the file (or null). */
    fun buildTimelapse(then: (File?) -> Unit) {
        val files = store.timelapseDir(meta.id).listFiles()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { flash("Todavía no hay time-lapse: pinta un poco"); return }
        busy = "Creando el time-lapse…"
        io.execute {
            val out = File(context.cacheDir, "timelapse.mp4")
            val res = runCatching {
                val first = BitmapFactory.decodeFile(files[0].path)
                val enc = Mp4Encoder(out, first.width, first.height, 30, 1280)
                // Long recordings are sped up to about 30 s.
                val stepN = maxOf(1, files.size / 900)
                files.filterIndexed { i, _ -> i % stepN == 0 }.forEachIndexed { i, f ->
                    BitmapFactory.decodeFile(f.path)?.let { enc.add(it) }
                    if (i % 30 == 0) main.post { busy = "Creando el time-lapse… ${i * stepN * 100 / files.size} %" }
                }
                BitmapFactory.decodeFile(files.last().path)?.let { enc.add(it, 60) }
                enc.finish()
                out
            }
            main.post { busy = null; then(res.getOrNull()); res.onFailure { flash("Error: ${it.message}") } }
        }
    }

    fun exportTimelapse() = buildTimelapse { f ->
        f ?: return@buildTimelapse
        io.execute { exportDone(store.exportStream("${meta.name} time-lapse.mp4", "video/mp4") { o -> f.inputStream().use { it.copyTo(o) } }, "Time-lapse") }
    }

    var timelapseVideo by mutableStateOf<File?>(null)
    fun playTimelapse() = buildTimelapse { timelapseVideo = it }

    // ------------------------------------------------------------------ canvas size

    fun resizeCanvas(w: Int, h: Int, scale: Boolean) {
        busy = "Cambiando el tamaño…"
        val cur = meta
        save {
            io.execute {
                runCatching { store.resize(cur.id, w, h, scale, (w - cur.width) / 2, (h - cur.height) / 2) }
                val m = store.readMeta(cur.id)
                main.post { busy = null; reopen(m) }
            }
        }
    }

    // ------------------------------------------------------------------ 3D

    fun toggle3d() {
        show3d = !show3d
        val s = show3d
        gl { it.show3d = s }
        flash(if (s) "Vista 3D: pinta sobre el modelo" else "Vista de la textura (UV)")
    }

    // ------------------------------------------------------------------ external display

    private var presentation: Presentation? = null
    private var presentationImage: ImageView? = null
    var externalOn by mutableStateOf(false)

    fun toggleExternal() {
        if (externalOn) { presentation?.dismiss(); presentation = null; externalOn = false; return }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (d == null) { flash("No hay ninguna pantalla externa conectada"); return }
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF000000.toInt()) }
        presentation = Presentation(context, d).apply { setContentView(iv); show() }
        presentationImage = iv
        externalOn = true
        refreshExternal()
    }

    private fun refreshExternal() {
        if (!externalOn) return
        gl { e -> val b = e.capture(1920); main.post { presentationImage?.setImageBitmap(b) } }
    }

    // ------------------------------------------------------------------ callbacks from the canvas (GL thread)

    override fun onLayers(layers: List<LayerInfo>, activeId: Long) {
        val em = view?.engine?.editingMask ?: false
        main.post { this.layers = layers; this.activeId = activeId; this.editingMask = em }
    }

    override fun onHistory(canUndo: Boolean, canRedo: Boolean) {
        main.post {
            this.canUndo = canUndo; this.canRedo = canRedo
            if (ready) {
                dirty = true; main.removeCallbacks(autosave); main.postDelayed(autosave, 15_000)
                captureTimelapse(); refreshReference(); refreshExternal()
            }
        }
    }

    override fun onBackground(color: Int, visible: Boolean) { main.post { bgColor = color; bgVisible = visible } }
    override fun onSelection(has: Boolean) { main.post { hasSelection = has } }
    override fun onMessage(text: String) { main.post { flash(text) } }
    override fun onView() { main.post { viewVersion++ } }
    override fun onHover(x: Float, y: Float, visible: Boolean) { hover = if (visible) Offset2(x, y) else null }

    override fun onPick(color: Int?, screenX: Float, screenY: Float, done: Boolean) {
        val prev = pick?.previous ?: this.color
        if (done) {
            pick = null
            if (color != null) setColor(color, remember = true)
        } else pick = PickState(color ?: prev, screenX, screenY, prev)
    }

    override fun onGesture(kind: String) {
        when (kind) {
            "undo" -> flash("Deshacer")
            "redo" -> flash("Rehacer")
            "toggleUi" -> uiHidden = !uiHidden
            "fit" -> flash("Ajustado a pantalla")
            "scrub" -> { clearLayer(); flash("Capa borrada") }
            "copyPaste" -> copyPasteMenu = true
        }
    }

    override fun onReady() {
        ready = true
        setAnim(animOn)
        if (is3d) gl { it.show3d = show3d }
    }

    fun isDirty() = dirty

    fun dispose() {
        playing = false
        main.removeCallbacksAndMessages(null)
        presentation?.dismiss()
        io.shutdown()
    }

    private fun loadInts(key: String) = prefs.getString(key, "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }
    private fun saveInts(key: String, l: List<Int>) = prefs.edit().putString(key, l.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()

    companion object {
        val DEFAULT_PALETTE = listOf(
            0xFF000000, 0xFF3A3A3A, 0xFF7A7A7A, 0xFFBDBDBD, 0xFFFFFFFF,
            0xFF7A1F1F, 0xFFD63A2F, 0xFFF07B3F, 0xFFF6C344, 0xFFFFF0B3,
            0xFF1F4D2B, 0xFF3F9A4F, 0xFF9CCB5A, 0xFF1F3A6B, 0xFF3A78D6,
            0xFF7CC4F0, 0xFF4B2A7A, 0xFF9A5AD6, 0xFFE58AB8, 0xFF8A5A3A,
            0xFFC79A6B, 0xFFF2D3B3, 0xFF5A3A2A, 0xFF2A4A5A, 0xFFE8E2D0,
        ).map { it.toInt() }

        val GRADIENTS = listOf(
            "Noir" to listOf(0xFF000000.toInt(), 0xFF5A5A5A.toInt(), 0xFFFFFFFF.toInt()),
            "Atardecer" to listOf(0xFF1A0A3A.toInt(), 0xFFB0305A.toInt(), 0xFFF29A3A.toInt(), 0xFFFFF0B0.toInt()),
            "Océano" to listOf(0xFF02101E.toInt(), 0xFF0A4A7A.toInt(), 0xFF4AB0D0.toInt(), 0xFFE0FAFF.toInt()),
            "Sepia" to listOf(0xFF20140A.toInt(), 0xFF7A5230.toInt(), 0xFFE8D2A8.toInt()),
            "Neón" to listOf(0xFF100020.toInt(), 0xFF7A00FF.toInt(), 0xFFFF2AD0.toInt(), 0xFF30FFF0.toInt()),
            "Bosque" to listOf(0xFF081408.toInt(), 0xFF2A5A2A.toInt(), 0xFFA0C860.toInt(), 0xFFF0F0C0.toInt()),
            "Fuego" to listOf(0xFF000000.toInt(), 0xFF8A0A00.toInt(), 0xFFFF6A00.toInt(), 0xFFFFF060.toInt(), 0xFFFFFFFF.toInt()),
        )
    }
}

/** Monotone curve through points (x, y in 0..1) as a 256-entry table. */
object Curves {
    fun table(points: List<Offset2>): IntArray {
        val p = points.sortedBy { it.x }
        val out = IntArray(256)
        for (i in 0 until 256) {
            val x = i / 255f
            val k = p.indexOfFirst { it.x >= x }
            val y = when {
                k <= 0 -> p.first().y
                else -> {
                    val a = p[k - 1]; val b = p[k]
                    // smoothstep-eased segment
                    val t = ((x - a.x) / maxOf(1e-4f, b.x - a.x)).coerceIn(0f, 1f)
                    val s = t * t * (3 - 2 * t)
                    a.y + (b.y - a.y) * (0.5f * t + 0.5f * s)
                }
            }
            out[i] = (y.coerceIn(0f, 1f) * 255).toInt()
        }
        return out
    }
}
