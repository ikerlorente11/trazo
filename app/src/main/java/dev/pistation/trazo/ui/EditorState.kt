package dev.pistation.trazo.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import dev.pistation.trazo.data.ArtworkMeta
import dev.pistation.trazo.data.ArtworkStore
import dev.pistation.trazo.data.StoredLayer
import dev.pistation.trazo.engine.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

enum class Panel { BRUSHES, LAYERS, COLOR, ACTIONS }

data class PickState(val color: Int?, val x: Float, val y: Float, val previous: Int)

/** Everything the editor screen shows, plus the bridge to the GL canvas. */
class EditorState(private val context: Context, val store: ArtworkStore, initial: ArtworkMeta) : CanvasCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("trazo", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    var meta by mutableStateOf(initial)
    var view: CanvasView? = null

    var tool by mutableStateOf(Tool.BRUSH)
    val brushIds = mutableStateMapOf(
        Tool.BRUSH to prefs.getString("brush.BRUSH", "studiopen")!!,
        Tool.SMUDGE to prefs.getString("brush.SMUDGE", "blender")!!,
        Tool.ERASER to prefs.getString("brush.ERASER", "softair")!!,
    )
    val sizes = mutableStateMapOf(Tool.BRUSH to prefs.getFloat("size.BRUSH", 0.25f), Tool.SMUDGE to prefs.getFloat("size.SMUDGE", 0.35f), Tool.ERASER to prefs.getFloat("size.ERASER", 0.35f))
    val opacities = mutableStateMapOf(Tool.BRUSH to 1f, Tool.SMUDGE to 0.8f, Tool.ERASER to 1f)
    /** Per-brush edits from the brush settings sheet. */
    val brushEdits = mutableStateMapOf<String, Brush>()

    var color by mutableIntStateOf(prefs.getInt("color", 0xFF1F2A44.toInt()))
    val recent = mutableStateListOf<Int>().apply { addAll(loadInts("recent")) }
    val palette = mutableStateListOf<Int>().apply { addAll(loadInts("palette").ifEmpty { DEFAULT_PALETTE }) }

    var layers by mutableStateOf(listOf<LayerInfo>())
    var activeId by mutableLongStateOf(0L)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var bgColor by mutableIntStateOf(0xFFFFFFFF.toInt())
    var bgVisible by mutableStateOf(true)
    var ready by mutableStateOf(false)

    var panel by mutableStateOf<Panel?>(null)
    var uiHidden by mutableStateOf(false)
    var pick by mutableStateOf<PickState?>(null)
    var message by mutableStateOf<String?>(null)
    var showBrushSize by mutableStateOf(false)

    var fingerPaint by mutableStateOf(prefs.getBoolean("fingerPaint", false))
    var pressureGamma by mutableFloatStateOf(prefs.getFloat("pressureGamma", 1f))
    var fillFromCanvas by mutableStateOf(prefs.getBoolean("fillFromCanvas", false))
    var fillThreshold by mutableFloatStateOf(prefs.getFloat("fillThreshold", 0.12f))

    private var dirty = false
    private val autosave = Runnable { save() }

    fun brushFor(t: Tool): Brush = brushIds[t]!!.let { brushEdits[it] ?: BrushLibrary.byId(it) }
    val brush get() = brushFor(tool)

    fun toolState(): ToolState {
        val b = brush
        return ToolState(tool, b, b.sizeFor(sizes[tool]!!), opacities[tool]!!, color)
    }

    fun attach(v: CanvasView) {
        view = v
        v.toolState = { toolState() }
        v.pressureGamma = pressureGamma
        v.fingerPaints = fingerPaint
        v.source = { docSource() }
    }

    private fun docSource(): DocSource {
        val m = runCatching { store.readMeta(meta.id) }.getOrDefault(meta)
        return DocSource(m.width, m.height, m.bgColor, m.bgVisible,
            m.layers.map { l -> Triple(l.id, l.props, { store.loadLayer(m, l.id) }) }, m.activeId)
    }

    // ------------------------------------------------------------------ tools

    fun selectTool(t: Tool, panelForIt: Panel = Panel.BRUSHES) {
        if (tool == t) panel = if (panel == panelForIt) null else panelForIt
        else { tool = t; panel = null }
    }

    fun selectBrush(id: String) {
        brushIds[tool] = id
        prefs.edit().putString("brush.${tool.name}", id).apply()
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
        prefs.edit().putBoolean("fingerPaint", fingerPaint).putFloat("pressureGamma", pressureGamma)
            .putBoolean("fillFromCanvas", fillFromCanvas).putFloat("fillThreshold", fillThreshold).apply()
        view?.pressureGamma = pressureGamma
        view?.fingerPaints = fingerPaint
    }

    // ------------------------------------------------------------------ document commands

    fun gl(block: (PaintEngine) -> Unit) = view?.gl(block)

    fun undo() = gl { it.undo() }
    fun redo() = gl { it.redo() }
    fun addLayer() = gl { it.addLayer() }
    fun setActive(id: Long) = gl { it.setActive(id) }
    fun deleteLayer(id: Long) = gl { it.deleteLayer(id) }
    fun duplicateLayer(id: Long) = gl { it.duplicateLayer(id) }
    fun mergeDown(id: Long) = gl { it.mergeDown(id) }
    fun moveLayer(from: Int, to: Int) = gl { it.moveLayer(from, to) }
    fun setProps(id: Long, p: LayerProps, record: Boolean = true, before: LayerProps? = null) = gl { it.setProps(id, p, record, before) }
    fun clearLayer() = gl { it.clearLayer() }
    fun fillLayer() { val c = color; gl { it.fillLayer(c) } }
    fun flipLayer(h: Boolean) = gl { it.flipLayer(h) }
    fun setBackground(c: Int, visible: Boolean) = gl { it.setBackground(c, visible) }

    fun colorDrop(x: Float, y: Float) {
        rememberColor()
        view?.fillAt(x, y, color, fillThreshold, fillFromCanvas)
    }

    fun startPickNext() { view?.pickNext = true; flash("Toca el lienzo para tomar un color") }

    fun flash(text: String) {
        message = text
        main.removeCallbacks(clearMessage)
        main.postDelayed(clearMessage, 1400)
    }
    private val clearMessage = Runnable { message = null }

    // ------------------------------------------------------------------ saving & export

    /** Saves layers + thumbnail in the background; `then` runs on the main thread. */
    fun save(then: (() -> Unit)? = null) {
        main.removeCallbacks(autosave)
        val v = view
        if (v == null || !ready) { then?.let { main.post(it) }; return }
        v.gl { e ->
            val m = meta.copy(
                bgColor = e.bgColor, bgVisible = e.bgVisible, activeId = e.activeId,
                layers = e.layers.map { StoredLayer(it.id, it.props) })
            val px: Map<Long, ByteBuffer> = e.layers.associate { it.id to e.readLayer(it) }
            val flat = e.flatten()
            dirty = false
            io.execute {
                runCatching { store.save(m, px, flat) }
                main.post { meta = m; then?.invoke() }
            }
        }
    }

    fun export(png: Boolean) {
        gl { e ->
            val bmp = e.flatten()
            io.execute {
                val uri = runCatching { store.exportImage(meta.name, bmp, png) }.getOrNull()
                main.post { flash(if (uri != null) "Guardado en Imágenes/Trazo" else "No se pudo exportar") }
            }
        }
    }

    fun share() {
        gl { e ->
            val bmp = e.flatten()
            io.execute {
                val f = store.shareFile(meta.name, bmp)
                val uri = FileProvider.getUriForFile(context, "dev.pistation.trazo.files", f)
                val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                main.post { context.startActivity(Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    // ------------------------------------------------------------------ callbacks from the canvas (GL thread)

    override fun onLayers(layers: List<LayerInfo>, activeId: Long) {
        main.post { this.layers = layers; this.activeId = activeId }
    }

    override fun onHistory(canUndo: Boolean, canRedo: Boolean) {
        main.post {
            this.canUndo = canUndo; this.canRedo = canRedo
            if (ready) { dirty = true; main.removeCallbacks(autosave); main.postDelayed(autosave, 15_000) }
        }
    }

    override fun onBackground(color: Int, visible: Boolean) { main.post { bgColor = color; bgVisible = visible } }

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
        }
    }

    override fun onReady() { ready = true }

    fun isDirty() = dirty

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
    }
}
