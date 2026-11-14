package dev.pistation.trazo.engine

import android.graphics.Bitmap
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

enum class AdjustKind(val label: String, val code: Int) {
    HSB("Tono, saturación, brillo", 0), BALANCE("Balance de color", 1), CURVES("Curvas", 2), GRADIENT_MAP("Mapa de degradado", 3),
    BRIGHT_CONTRAST("Brillo y contraste", 16), GAUSSIAN("Desenfoque gaussiano", 4), MOTION("Desenfoque de movimiento", 5),
    PERSPECTIVE("Desenfoque de perspectiva", 6), SHARPEN("Enfocar", 7), NOISE("Ruido", 8), CHROMATIC("Aberración cromática", 9),
    BLOOM("Resplandor", 10), GLITCH("Falla", 12), HALFTONE("Medios tonos", 13), INVERT("Invertir colores", 14), LIQUIFY("Licuar", 15)
}

enum class LiquifyMode(val label: String) { PUSH("Empujar"), TWIRL_R("Girar a la derecha"), TWIRL_L("Girar a la izquierda"), PINCH("Pellizcar"), EXPAND("Expandir"), CRYSTALS("Cristales"), RECONSTRUCT("Reconstruir") }

class AnimSettings(
    var enabled: Boolean = false, var frame: Int = 0, var onion: Int = 1, var onionOpacity: Float = 0.35f,
    var bgFirst: Boolean = false, var fgLast: Boolean = false,
)

class Clip(val x: Int, val y: Int, val w: Int, val h: Int, val px: ByteBuffer)

/**
 * The document and everything drawn on it. Lives on the GL thread: every method must be called there.
 * Layers are premultiplied RGBA textures the size of the canvas; index 0 is the bottom one.
 */
class PaintEngine(val width: Int, val height: Int, private val listener: Listener) {

    interface Listener {
        fun onLayers(layers: List<LayerInfo>, activeId: Long)
        fun onHistory(canUndo: Boolean, canRedo: Boolean)
        fun onBackground(color: Int, visible: Boolean)
        fun onSelection(has: Boolean) {}
        fun onMessage(text: String) {}
    }

    val layers = ArrayList<Layer>()
    var activeId = 0L; private set
    var bgColor = 0xFFFFFFFF.toInt(); private set
    var bgVisible = true; private set
    private var nextId = 1L
    val history = UndoStack()
    var editingMask = false; private set
    var selection: Mask8? = null; private set
    /** Symmetry copies: affine (a, b, c, d, e, f): x' = a x + b y + e, y' = c x + d y + f. */
    @Volatile var symmetry: List<FloatArray> = emptyList()
    val anim = AnimSettings()
    var model3d: Model3D? = null
    var show3d = false

    // Work targets
    private val stroke = RenderTarget(width, height)
    private var scratch = RenderTarget(width, height)
    private val strokeStart = RenderTarget(width, height)
    private var belowA = RenderTarget(width, height)
    private var belowB = RenderTarget(width, height)
    private var frameA = RenderTarget(width, height, mipmaps = true)
    private var frameB = RenderTarget(width, height, mipmaps = true)
    private val regionDst = RenderTarget(REGION, REGION)
    private val regionPick = RenderTarget(REGION, REGION)
    private val thumb: RenderTarget
    private var groupA: RenderTarget? = null
    private var groupB: RenderTarget? = null
    private var maskTemp: RenderTarget? = null
    private var adjA: RenderTarget? = null
    private var adjB: RenderTarget? = null
    private var tl: RenderTarget? = null
    private var belowDirty = true
    private var cachedBlock = -1
    /** The last composited image (what is on screen). */
    var frame: RenderTarget = frameA; private set

    private val pCopy = Program(Shaders.VS_QUAD, Shaders.FS_COPY)
    private val pColor = Program(Shaders.VS_QUAD, Shaders.FS_COLOR)
    private val pChecker = Program(Shaders.VS_QUAD, Shaders.FS_CHECKER)
    private val pBlend = Program(Shaders.VS_QUAD, Shaders.FS_BLEND)
    private val pMerge = Program(Shaders.VS_QUAD, Shaders.FS_MERGE)
    private val pMasked = Program(Shaders.VS_QUAD, Shaders.FS_MASKED)
    private val pStamp = Program(Shaders.VS_STAMP, Shaders.FS_STAMP)
    private val pSmudge = Program(Shaders.VS_SMUDGE, Shaders.FS_SMUDGE)
    private val pScreen = Program(Shaders.VS_QUAD, Shaders.FS_SCREEN)
    private val pSelOut = Program(Shaders.VS_QUAD, Shaders.FS_SELOUT)
    private val pSelMix = Program(Shaders.VS_QUAD, Shaders.FS_SELMIX)
    private val pAdjust = Program(Shaders.VS_QUAD, Shaders.FS_ADJUST)
    private val pMesh = Program(Shaders.VS_MESH, Shaders.FS_COPY)

    private val quadVbo: Int
    private val dynVbo: Int
    private val cornerVbo: Int
    private val instVbo: Int
    private val meshVbo: Int
    private var dynBuf = floatBuffer(64)
    private var instBuf = floatBuffer(8 * 512)

    private val shapeTex = HashMap<String, Int>()
    private val grainTex = HashMap<String, Int>()
    /** Custom brush images: key -> (width, height, single channel bytes). Filled by the UI before use. */
    val customImages = HashMap<String, Triple<Int, Int, ByteBuffer>>()

    /** Canvas pixels -> NDC of a canvas-sized target. */
    private val canvasM = floatArrayOf(2f / width, 0f, 0f, 0f, 2f / height, 0f, -1f, -1f, 1f)

    init {
        val ids = IntArray(5)
        glGenBuffers(5, ids, 0)
        quadVbo = ids[0]; dynVbo = ids[1]; cornerVbo = ids[2]; instVbo = ids[3]; meshVbo = ids[4]
        upload(quadVbo, floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f), GL_STATIC_DRAW)
        upload(cornerVbo, floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f), GL_STATIC_DRAW)
        val tw = if (width >= height) THUMB else max(1, THUMB * width / height)
        val th = if (height >= width) THUMB else max(1, THUMB * height / width)
        thumb = RenderTarget(tw, th)
        glDisable(GL_DITHER)
    }

    // ================================================================ document structure

    fun layer(id: Long) = layers.firstOrNull { it.id == id }
    fun contains(l: Layer) = layers.any { it === l }
    val activeIndex get() = layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val active get() = layers[activeIndex]
    val memoryPerLayer get() = width.toLong() * height * 4

    /** Where painting goes now: the active layer's pixels or its mask; null for a group. */
    fun paintTarget(): RenderTarget? {
        if (layers.isEmpty()) return null
        val a = active
        if (a.isGroup) return null
        return if (editingMask) a.mask else a.target
    }

    fun newLayer(name: String = "Capa $nextId"): Layer = Layer(nextId++, LayerProps(name), RenderTarget(width, height))

    private fun snapshot() = layers.map { Node(it, it.parent) }

    fun restoreStructure(nodes: List<Node>, active: Long) {
        layers.clear()
        nodes.forEach { it.layer.parent = it.parent; layers.add(it.layer) }
        activeId = if (layer(active) != null) active else layers.lastOrNull()?.id ?: 0
        if (editingMask && this.active.mask == null) editingMask = false
        belowDirty = true
        notifyLayers(true)
    }

    /** Runs a structural change and records it (plus any extra edits) as one undo step. */
    private fun structural(extra: List<Edit> = emptyList(), block: () -> Unit) {
        val b = snapshot(); val a = activeId
        block()
        normalize()
        if (layers.isNotEmpty() && layer(activeId) == null) activeId = layers.last().id
        val after = snapshot()
        val s = if (after != b || activeId != a) Edit.Structure(b, after, a, activeId) else null
        val all = listOfNotNull(s) + extra
        if (all.size == 1) push(all[0]) else if (all.size > 1) push(Edit.Group(all))
        if (editingMask && active.mask == null) editingMask = false
        belowDirty = true
        notifyLayers(false)
    }

    /** Children directly below their group, in order; orphans become top level. */
    private fun normalize() {
        val groups = layers.filter { it.isGroup }.map { it.id }.toSet()
        layers.forEach { if (it.isGroup || it.parent !in groups) it.parent = 0L }
        val out = ArrayList<Layer>(layers.size)
        for (l in layers) {
            if (l.parent != 0L) continue
            if (l.isGroup) out.addAll(layers.filter { it.parent == l.id })
            out.add(l)
        }
        layers.clear(); layers.addAll(out)
    }

    private fun parentAt(index: Int): Long {
        val above = layers.getOrNull(index + 1) ?: return 0L
        return if (above.isGroup) above.id else above.parent
    }

    /** Adds an empty layer above the active one (inside its group, or at the top of the active group). */
    fun addLayer(): Layer? {
        if (!checkMemory()) return null
        var l: Layer? = null
        structural {
            l = newLayer()
            insertNear(l!!)
        }
        return l
    }

    private fun insertNear(l: Layer) {
        if (layers.isEmpty()) { layers.add(l); activeId = l.id; return }
        val a = active
        if (a.isGroup) { layers.add(activeIndex, l); l.parent = a.id } else { layers.add(activeIndex + 1, l); l.parent = a.parent }
        activeId = l.id
    }

    /** A new layer holding `px` (canvas-sized RGBA premultiplied) or a region of it. */
    fun addLayerWith(name: String, px: ByteBuffer, x: Int = 0, y: Int = 0, w: Int = width, h: Int = height): Long {
        if (!checkMemory()) return 0
        val l = newLayer(name)
        l.target.write(x, y, w, h, px)
        structural { insertNear(l) }
        return l.id
    }

    private fun checkMemory(): Boolean {
        val budget = 1500L shl 20
        val used = layers.sumOf { it.target.bytes + (it.mask?.bytes ?: 0) } + memoryPerLayer * 10
        if (used + memoryPerLayer > budget) { listener.onMessage("No caben más capas en memoria"); return false }
        return true
    }

    /** For loading documents: appends without history. */
    fun appendLoaded(id: Long, props: LayerProps, isGroup: Boolean, parent: Long, pixels: ByteBuffer?, mask: ByteBuffer?): Layer {
        val l = Layer(id, props, if (isGroup) RenderTarget(1, 1) else RenderTarget(width, height), isGroup)
        l.parent = parent
        if (pixels != null && !isGroup) l.target.write(0, 0, width, height, pixels)
        if (mask != null && !isGroup) l.mask = RenderTarget(width, height).also { it.write(0, 0, width, height, mask) }
        layers.add(l)
        nextId = max(nextId, id + 1)
        activeId = id
        belowDirty = true
        return l
    }

    fun finishLoading() {
        normalize()
        if (layers.none { !it.isGroup }) { val l = newLayer("Capa 1"); layers.add(0, l) }
        if (layer(activeId)?.isGroup != false) activeId = layers.last { !it.isGroup }.id
        belowDirty = true
        notifyLayers(true)
    }

    fun setActive(id: Long) {
        if (layer(id) == null || id == activeId) return
        activeId = id
        if (editingMask && active.mask == null) editingMask = false
        belowDirty = true; notifyLayers(false)
    }

    fun setEditMask(on: Boolean) {
        editingMask = on && layers.isNotEmpty() && active.mask != null
        notifyLayers(false)
    }

    fun deleteLayer(id: Long) {
        val l = layer(id) ?: return
        val leaves = layers.count { !it.isGroup && (it.id != id && it.parent != id) }
        if (leaves == 0) { listener.onMessage("Tiene que quedar al menos una capa"); return }
        structural {
            if (l.isGroup) layers.removeAll { it.parent == id }
            layers.remove(l)
            if (layer(activeId) == null) activeId = layers.last { !it.isGroup }.id
        }
    }

    fun duplicateLayer(id: Long) {
        val src = layer(id) ?: return
        if (!checkMemory()) return
        fun copyOf(s: Layer): Layer {
            val l = Layer(nextId++, s.props.copy(name = s.props.name + " copia"),
                if (s.isGroup) RenderTarget(1, 1) else RenderTarget(width, height), s.isGroup)
            if (!s.isGroup) { l.target.bind(); glDisable(GL_BLEND); drawTex(pCopy, s.target.tex) }
            s.mask?.let { m -> l.mask = RenderTarget(width, height).also { it.bind(); drawTex(pCopy, m.tex) } }
            return l
        }
        structural {
            val idx = layers.indexOf(src)
            if (src.isGroup) {
                val kids = layers.filter { it.parent == id }
                val g = copyOf(src)
                val copies = kids.map { k -> copyOf(k).also { it.props = it.props.copy(name = k.props.name); it.parent = g.id } }
                layers.addAll(idx + 1, copies + g)
                activeId = g.id
            } else {
                val l = copyOf(src); l.parent = src.parent
                layers.add(idx + 1, l)
                activeId = l.id
            }
        }
    }

    fun moveLayer(from: Int, to: Int) {
        if (from == to || from !in layers.indices) return
        structural {
            val l = layers[from]
            if (l.isGroup) {
                val n = layers.count { it.parent == l.id }
                val block = layers.subList(from - n, from + 1).toList()
                layers.removeAll(block.toSet())
                val at = (to - n).coerceIn(0, layers.size)
                layers.addAll(at, block)
            } else {
                layers.removeAt(from)
                val at = to.coerceIn(0, layers.size)
                layers.add(at, l)
                l.parent = parentAt(at)
            }
        }
    }

    /** Puts the active leaf in a new group. */
    fun newGroup(id: Long) {
        val a = layer(id) ?: return
        if (a.isGroup || a.parent != 0L) { listener.onMessage("Ya está en un grupo"); return }
        structural {
            val g = Layer(nextId++, LayerProps("Grupo ${nextId - 1}"), RenderTarget(1, 1), isGroup = true)
            layers.add(layers.indexOf(a) + 1, g)
            a.parent = g.id
        }
    }

    /** Groups a top-level leaf with the top-level leaf right below it. */
    fun groupWithBelow(id: Long) {
        val a = layer(id) ?: return
        val i = layers.indexOf(a)
        val b = layers.getOrNull(i - 1)
        if (a.isGroup || a.parent != 0L || b == null || b.isGroup || b.parent != 0L) { listener.onMessage("No se puede agrupar"); return }
        structural {
            val g = Layer(nextId++, LayerProps("Grupo ${nextId - 1}"), RenderTarget(1, 1), isGroup = true)
            layers.add(i + 1, g)
            a.parent = g.id; b.parent = g.id
        }
    }

    fun ungroup(id: Long) {
        val g = layer(id) ?: return
        if (!g.isGroup) return
        structural {
            layers.filter { it.parent == id }.forEach { it.parent = 0L }
            layers.remove(g)
            if (activeId == id) activeId = layers.last { !it.isGroup }.id
        }
    }

    fun setProps(id: Long, p: LayerProps, record: Boolean = true, before: LayerProps? = null) {
        val l = layer(id) ?: return
        val b = before ?: l.props
        if (b == p && l.props == p) return
        if (p.reference && !l.props.reference) layers.forEach { if (it !== l && it.props.reference) setPropsRaw(it.id, it.props.copy(reference = false)) }
        if (l.props != p) setPropsRaw(id, p)
        if (record && b != p) push(Edit.Props(id, b, p))
    }

    fun mergeDown(id: Long) {
        val upper = layer(id) ?: return
        val ui = layers.indexOf(upper)
        val lower = layers.getOrNull(ui - 1)
        if (upper.isGroup || lower == null || lower.isGroup || lower.parent != upper.parent) { listener.onMessage("No hay una capa debajo para combinar"); return }
        val before = lower.target.read(0, 0, width, height)
        val src = maskedSource(upper)
        val tmp = RenderTarget(width, height)
        tmp.bind(); glDisable(GL_BLEND)
        pBlend.use()
        pBlend.tex("uDst", 0, lower.target.tex); pBlend.tex("uSrc", 1, src); pBlend.tex("uClip", 2, lower.target.tex)
        glUniform1f(pBlend.u("uOpacity"), if (upper.props.visible) upper.props.opacity else 0f)
        glUniform1i(pBlend.u("uMode"), upper.props.blend.code)
        glUniform1i(pBlend.u("uUseClip"), if (upper.props.clip) 1 else 0)
        drawQuad()
        lower.target.bind(); drawTex(pCopy, tmp.tex)
        tmp.release()
        val after = lower.target.read(0, 0, width, height)
        structural(listOf(Edit.Pixels(lower.id, false, 0, 0, width, height, before, after))) {
            layers.remove(upper); activeId = lower.id
        }
        thumbs.remove(lower.id)
    }

    /** Flattens a group into one layer. */
    fun mergeGroup(id: Long) {
        val g = layer(id) ?: return
        if (!g.isGroup) return
        val gi = layers.indexOf(g)
        val n = layers.count { it.parent == id }
        val l = newLayer(g.props.name)
        l.props = g.props.copy(collapsed = false)
        renderGroupInto(gi - n until gi)
        l.target.bind(); glDisable(GL_BLEND); drawTex(pCopy, groupPair().cur.tex)
        structural {
            val at = layers.indexOf(g) - n
            layers.removeAll { it.parent == id || it === g }
            layers.add(at, l)
            activeId = l.id
        }
    }

    /** Everything visible (no background) into a single layer. */
    fun flattenAll() {
        val l = newLayer("Imagen")
        compositeInto(withBg = false)
        l.target.bind(); glDisable(GL_BLEND); drawTex(pCopy, frame.tex)
        structural { layers.clear(); layers.add(l); activeId = l.id }
    }

    fun setBackground(color: Int, visible: Boolean) {
        val before = bgColor to bgVisible
        if (before == (color to visible)) return
        setBackgroundRaw(color, visible)
        push(Edit.Background(before, color to visible))
    }

    fun setPropsRaw(id: Long, p: LayerProps) { layer(id)?.props = p; belowDirty = true; notifyLayers(false) }

    fun setBackgroundRaw(color: Int, visible: Boolean) {
        bgColor = color; bgVisible = visible; belowDirty = true
        listener.onBackground(color, visible)
    }

    // ---------------------------------------------------------------- masks

    fun addMask(id: Long) {
        val l = layer(id) ?: return
        if (l.isGroup || l.mask != null) return
        val m = RenderTarget(width, height)
        m.clear(1f, 1f, 1f, 1f)
        setMaskRaw(id, m)
        push(Edit.MaskSet(id, null, m))
        setActive(id); editingMask = true; notifyLayers(false)
    }

    fun deleteMask(id: Long) {
        val l = layer(id) ?: return
        val m = l.mask ?: return
        setMaskRaw(id, null)
        push(Edit.MaskSet(id, m, null))
    }

    /** Bakes the mask into the layer's pixels. */
    fun applyMask(id: Long) {
        val l = layer(id) ?: return
        val m = l.mask ?: return
        val before = l.target.read(0, 0, width, height)
        scratch.bind(); glDisable(GL_BLEND)
        pMasked.use(); pMasked.tex("uTex", 0, l.target.tex); pMasked.tex("uMask", 1, m.tex); glUniform1i(pMasked.u("uInvert"), 0); drawQuad()
        swapTarget(l, false)
        val after = l.target.read(0, 0, width, height)
        setMaskRaw(id, null)
        push(Edit.Group(listOf(Edit.Pixels(id, false, 0, 0, width, height, before, after), Edit.MaskSet(id, m, null))))
    }

    fun invertMask(id: Long) {
        val l = layer(id) ?: return
        val m = l.mask ?: return
        val before = m.read(0, 0, width, height)
        adjustPass(14, m.tex, scratch, FloatArray(12))
        l.mask = scratch; scratch = m
        val after = l.mask!!.read(0, 0, width, height)
        push(Edit.Pixels(id, true, 0, 0, width, height, before, after))
        touched(id)
    }

    fun setMaskRaw(id: Long, m: RenderTarget?) {
        layer(id)?.mask = m
        if (m == null && id == activeId) editingMask = false
        belowDirty = true
        maskThumbs.remove(id)
        notifyLayers(false)
    }

    /** Pixels changed: caches and thumbnails. */
    fun touched(id: Long) {
        belowDirty = true
        thumbs.remove(id); maskThumbs.remove(id)
        notifyLayers(false)
    }

    fun undo() { if (stroking || tf != null || adj != null) return; if (history.undo(this)) { belowDirty = true; notifyHistory() } }
    fun redo() { if (stroking || tf != null || adj != null) return; if (history.redo(this)) { belowDirty = true; notifyHistory() } }

    private fun push(e: Edit) { history.push(e, this); notifyHistory() }
    private fun notifyHistory() = listener.onHistory(history.canUndo, history.canRedo)

    /** Swaps the edited target of `l` with scratch (scratch holds the new pixels). */
    private fun swapTarget(l: Layer, mask: Boolean) {
        if (mask) { val old = l.mask!!; l.mask = scratch; scratch = old }
        else { val old = l.target; l.target = scratch; scratch = old }
    }

    // ---------------------------------------------------------------- thumbnails

    private val thumbs = HashMap<Long, Bitmap>()
    private val maskThumbs = HashMap<Long, Bitmap>()

    fun notifyLayers(all: Boolean) {
        if (all) { thumbs.clear(); maskThumbs.clear() }
        val infos = layers.map { l ->
            LayerInfo(l.id, l.props, if (l.isGroup) null else thumbs.getOrPut(l.id) { thumbnailOf(l.target.tex, false) },
                l.isGroup, l.parent, l.mask != null, l.mask?.let { m -> maskThumbs.getOrPut(l.id) { thumbnailOf(m.tex, false) } })
        }
        listener.onLayers(infos, activeId)
        listener.onSelection(selection != null)
    }

    /** Small image of a texture; `opaque` composites over the background colour. */
    fun thumbnailOf(tex: Int, opaque: Boolean): Bitmap {
        thumb.clear()
        if (opaque) {
            val c = bgColor
            thumb.clear(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, 1f)
        }
        thumb.bind()
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        drawTex(pCopy, tex)
        glDisable(GL_BLEND)
        val buf = thumb.read(0, 0, thumb.width, thumb.height)
        val bmp = Bitmap.createBitmap(thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    // ================================================================ strokes

    private var builder: StrokeBuilder? = null
    private var strokeTool = Tool.BRUSH
    private var strokeBrush: Brush? = null
    private var strokeSize = 0f
    private var strokeColor = 0
    private var strokeOpacity = 1f
    private var strokeMap: (Float) -> Float = { it }
    private var strokeDirect = false
    private var strokeOnMask = false
    private var cloneOffX = 0f; private var cloneOffY = 0f
    private var hasPick = false
    private var pickX = 0f; private var pickY = 0f
    private var sMinX = 0f; private var sMinY = 0f; private var sMaxX = 0f; private var sMaxY = 0f
    private var shape: QuickShape? = null
    private val stampsOut = FloatArrayList()
    /** Predicted pen position (x, y) while drawing, or null. */
    @Volatile var prediction: FloatArray? = null
    val stroking get() = builder != null

    /** Starts a stroke; false when there is nothing to paint on (a group). */
    fun beginStroke(tool: Tool, brush: Brush, size: Float, opacity: Float, color: Int, pressureMap: (Float) -> Float,
                    cloneOffset: FloatArray? = null): Boolean {
        val target = paintTarget() ?: run { listener.onMessage("Elige una capa para pintar"); return false }
        if (tf != null || adj != null) return false
        strokeOnMask = editingMask
        var t = tool
        var c = color
        if (strokeOnMask) {
            // On a mask: paint greys; the eraser hides (black).
            val l = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)).toInt().coerceIn(0, 255)
            c = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
            if (t == Tool.ERASER) { t = Tool.BRUSH; c = 0xFF000000.toInt() }
        }
        strokeDirect = t == Tool.SMUDGE || (t == Tool.BRUSH && brush.wet > 0f)
        val sz = if (strokeDirect) min(size, REGION / 1.6f) else size
        strokeTool = t; strokeBrush = brush; strokeSize = sz; strokeColor = c; strokeOpacity = opacity; strokeMap = pressureMap
        cloneOffX = cloneOffset?.get(0) ?: 0f; cloneOffY = cloneOffset?.get(1) ?: 0f
        builder = newBuilder(null)
        hasPick = false; shape = null
        sMinX = Float.MAX_VALUE; sMinY = Float.MAX_VALUE; sMaxX = -Float.MAX_VALUE; sMaxY = -Float.MAX_VALUE
        strokeStart.bind(); glDisable(GL_BLEND); drawTex(pCopy, target.tex)
        stroke.clear()
        return true
    }

    private fun newBuilder(streamline: Float?) = StrokeBuilder(strokeBrush!!, strokeSize,
        ((strokeColor shr 16) and 255) / 255f, ((strokeColor shr 8) and 255) / 255f, (strokeColor and 255) / 255f,
        strokeMap, (System.nanoTime() and 0xffff).toInt(), streamline)

    fun strokePoint(x: Float, y: Float, pressure: Float) {
        val s = shape
        if (s != null) { s.dragTo(x, y); restampShape(); return }
        builder?.add(x, y, pressure)
    }

    fun strokeJump(x: Float, y: Float, pressure: Float) { builder?.jump(x, y, pressure) }

    /** QuickShape: replaces the stroke so far with the recognised shape. Returns its name or null. */
    fun quickShape(): String? {
        val b = builder ?: return null
        if (strokeDirect || shape != null) return null
        val s = QuickShape.detect(b.raw.data, b.raw.size) ?: return null
        shape = s
        restampShape()
        return s.label
    }

    private fun restampShape() {
        val s = shape ?: return
        val old = builder ?: return
        var p = 0f; var n = 0
        for (i in 0 until old.raw.size / 3) { p += old.raw.data[i * 3 + 2]; n++ }
        val avg = if (n > 0) p / n else 1f
        val nb = newBuilder(0f)
        val path = s.path()
        for (i in 0 until path.size / 2) nb.add(path[i * 2], path[i * 2 + 1], avg)
        nb.finish()
        // keep the raw input of the original stroke for later detection
        for (i in 0 until old.raw.size) if (i < old.raw.size) Unit
        builder = nb
        stroke.clear()
        sMinX = Float.MAX_VALUE; sMinY = Float.MAX_VALUE; sMaxX = -Float.MAX_VALUE; sMaxY = -Float.MAX_VALUE
        flushStamps()
    }

    fun endStroke() {
        val b = builder ?: return
        if (shape == null) b.finish()
        flushStamps()
        prediction = null
        val l = active
        val target = paintTarget() ?: run { builder = null; return }
        if (!strokeDirect) {
            merge(target.tex, strokeMode(l), scratch)
            swapTarget(l, strokeOnMask)
        }
        builder = null
        shape = null
        if (sMinX > sMaxX) return
        val x0 = floor(sMinX).toInt().coerceIn(0, width); val y0 = floor(sMinY).toInt().coerceIn(0, height)
        val x1 = ceil(sMaxX).toInt().coerceIn(0, width); val y1 = ceil(sMaxY).toInt().coerceIn(0, height)
        if (x1 <= x0 || y1 <= y0) return
        val before = strokeStart.read(x0, y0, x1 - x0, y1 - y0)
        val after = (if (strokeOnMask) l.mask!! else l.target).read(x0, y0, x1 - x0, y1 - y0)
        push(Edit.Pixels(l.id, strokeOnMask, x0, y0, x1 - x0, y1 - y0, before, after))
        touched(l.id)
    }

    private fun strokeMode(l: Layer) = when {
        strokeOnMask -> 0
        strokeTool == Tool.ERASER && l.props.alphaLock -> 3
        strokeTool == Tool.ERASER -> 1
        l.props.alphaLock -> 2
        else -> 0
    }

    fun cancelStroke() {
        if (builder == null) return
        if (strokeDirect) paintTarget()?.let { it.bind(); glDisable(GL_BLEND); drawTex(pCopy, strokeStart.tex) }
        builder = null; shape = null; prediction = null
        stroke.clear()
    }

    /** Draws the stamps produced since the last frame (plus symmetry copies). */
    fun flushStamps() {
        val b = builder ?: return
        val n0 = b.pending.size / 8
        if (n0 == 0) return
        val brush = strokeBrush!!
        val src = b.pending.data
        stampsOut.clear()
        val sym = if (strokeTool == Tool.CLONE) emptyList() else symmetry
        for (i in 0 until n0) {
            val o = i * 8
            stampsOut.add(src[o], src[o + 1], src[o + 2], src[o + 3], src[o + 4], src[o + 5], src[o + 6], src[o + 7])
            for (m in sym) {
                val x = src[o]; val y = src[o + 1]; val a = src[o + 3]
                val dx = cos(a); val dy = sin(a)
                stampsOut.add(m[0] * x + m[1] * y + m[4], m[2] * x + m[3] * y + m[5], src[o + 2],
                    atan2(m[2] * dx + m[3] * dy, m[0] * dx + m[1] * dy), src[o + 4], src[o + 5], src[o + 6], src[o + 7])
            }
        }
        val d = stampsOut.data
        val n = stampsOut.size / 8
        for (i in 0 until n) {
            val h = d[i * 8 + 2] * 0.75f
            sMinX = min(sMinX, d[i * 8] - h); sMinY = min(sMinY, d[i * 8 + 1] - h)
            sMaxX = max(sMaxX, d[i * 8] + h); sMaxY = max(sMaxY, d[i * 8 + 1] + h)
        }
        if (strokeDirect) directStamps(d, n, brush) else {
            stroke.bind()
            drawStamps(d, n, brush, canvasM, strokeTool == Tool.CLONE)
        }
        b.pending.clear()
    }

    private fun drawStamps(d: FloatArray, n: Int, brush: Brush, m: FloatArray, clone: Boolean) {
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        pStamp.use()
        glUniformMatrix3fv(pStamp.u("uM"), 1, false, m, 0)
        glUniform1f(pStamp.u("uRoundness"), brush.roundness)
        bindBrushTextures(pStamp, brush)
        glUniform1i(pStamp.u("uClone"), if (clone) 1 else 0)
        pStamp.tex("uSrc", 2, strokeStart.tex)
        glUniform2f(pStamp.u("uOffset"), cloneOffX, cloneOffY)
        glUniform2f(pStamp.u("uCanvasSize"), width.toFloat(), height.toFloat())
        if (instBuf.capacity() < n * 8) instBuf = floatBuffer(n * 8 * 2)
        instBuf.clear(); instBuf.put(d, 0, n * 8); instBuf.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, instVbo)
        glBufferData(GL_ARRAY_BUFFER, n * 8 * 4, instBuf, GL_STREAM_DRAW)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, false, 32, 0); glVertexAttribDivisor(1, 1)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 32, 16); glVertexAttribDivisor(2, 1)
        glBindBuffer(GL_ARRAY_BUFFER, cornerVbo)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n)
        glVertexAttribDivisor(1, 0); glVertexAttribDivisor(2, 0)
        glDisableVertexAttribArray(1); glDisableVertexAttribArray(2)
        glDisable(GL_BLEND)
    }

    /** Smudge and wet paint: each stamp mixes what is under it with paint carried from the last one. */
    private fun directStamps(d: FloatArray, n: Int, brush: Brush) {
        val target = paintTarget() ?: return
        val wet = strokeTool == Tool.BRUSH
        pSmudge.use()
        glUniformMatrix3fv(pSmudge.u("uM"), 1, false, canvasM, 0)
        glUniform1f(pSmudge.u("uRoundness"), brush.roundness)
        glUniform1f(pSmudge.u("uRegion"), REGION.toFloat())
        for (i in 0 until n) {
            val o = i * 8
            val x = d[o]; val y = d[o + 1]
            if (!hasPick) { copyRegion(target, regionPick, x, y); pickX = x; pickY = y; hasPick = true; if (!wet) continue }
            val (dx, dy) = copyRegion(target, regionDst, x, y)
            val (px, py) = regionOrigin(pickX, pickY)
            target.bind(); glDisable(GL_BLEND)
            pSmudge.use()
            bindBrushTextures(pSmudge, brush)
            pSmudge.tex("uDstCopy", 2, regionDst.tex)
            pSmudge.tex("uPick", 3, regionPick.tex)
            bindSel(pSmudge, "uSel", "uUseSel", 4)
            glUniform2f(pSmudge.u("uCanvasSize"), width.toFloat(), height.toFloat())
            glUniform2f(pSmudge.u("uDstOrigin"), dx, dy)
            glUniform2f(pSmudge.u("uPickOrigin"), px, py)
            glUniform2f(pSmudge.u("uDelta"), x - pickX, y - pickY)
            val k = if (wet) d[o + 7] * strokeOpacity else brush.smudge * d[o + 7] * strokeOpacity
            glUniform1f(pSmudge.u("uStrength"), k.coerceIn(0f, 1f))
            glUniform1i(pSmudge.u("uUseColor"), if (wet) 1 else 0)
            glUniform3f(pSmudge.u("uColor"), d[o + 4], d[o + 5], d[o + 6])
            glUniform1f(pSmudge.u("uWet"), brush.wet.coerceIn(0f, 1f))
            glUniform4f(pSmudge.u("uStamp"), x, y, d[o + 2], d[o + 3])
            glBindBuffer(GL_ARRAY_BUFFER, cornerVbo)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
            glDisableVertexAttribArray(1)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            copyRegion(target, regionPick, x, y); pickX = x; pickY = y
        }
    }

    private fun regionOrigin(cx: Float, cy: Float) = Pair(round(cx - REGION / 2f), round(cy - REGION / 2f))

    /** Copies the REGION² square of `t` centred on (cx, cy) into `into`; returns its origin. */
    private fun copyRegion(t: RenderTarget, into: RenderTarget, cx: Float, cy: Float): Pair<Float, Float> {
        val (ox, oy) = regionOrigin(cx, cy)
        into.clear()
        val x0 = max(ox.toInt(), 0); val y0 = max(oy.toInt(), 0)
        val x1 = min(ox.toInt() + REGION, width); val y1 = min(oy.toInt() + REGION, height)
        if (x1 > x0 && y1 > y0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, t.fbo)
            glBindTexture(GL_TEXTURE_2D, into.tex)
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, x0 - ox.toInt(), y0 - oy.toInt(), x0, y0, x1 - x0, y1 - y0)
        }
        return ox to oy
    }

    private fun bindBrushTextures(p: Program, brush: Brush) {
        val s = shapeTex.getOrPut(brush.shapeKey) {
            customImages[brush.shapeKey]?.let { (w, h, b) -> makeTexture(w, h, b, single = true, repeat = false) }
                ?: makeTexture(BrushTextures.SHAPE, BrushTextures.SHAPE, BrushTextures.shape(brush.shape), single = true, repeat = false)
        }
        val g = grainTex.getOrPut(brush.grainKey) {
            customImages[brush.grainKey]?.let { (w, h, b) -> makeTexture(w, h, b, single = true, repeat = true) }
                ?: makeTexture(BrushTextures.GRAIN, BrushTextures.GRAIN, BrushTextures.grain(brush.grain), single = true, repeat = true)
        }
        p.tex("uShape", 0, s)
        p.tex("uGrain", 1, g)
        glUniform1f(p.u("uGrainScale"), brush.grainScale)
        glUniform1f(p.u("uGrainDepth"), if (brush.grain == Grain.NONE && brush.grainImage == null) 0f else brush.grainDepth)
    }

    private fun bindSel(p: Program, sampler: String, flag: String, unit: Int) {
        val s = selection
        p.tex(sampler, unit, s?.tex ?: stroke.tex)
        glUniform1i(p.u(flag), if (s != null) 1 else 0)
    }

    private fun merge(base: Int, mode: Int, into: RenderTarget) {
        into.bind(); glDisable(GL_BLEND)
        pMerge.use()
        pMerge.tex("uLayer", 0, base); pMerge.tex("uStroke", 1, stroke.tex)
        bindSel(pMerge, "uSel", "uUseSel", 2)
        glUniform1f(pMerge.u("uOpacity"), strokeOpacity)
        glUniform1i(pMerge.u("uMode"), mode)
        drawQuad()
    }

    // ================================================================ whole-layer operations

    /** Runs `op` on the paint target and records the change as one undo step. */
    private fun editTarget(op: (Layer, RenderTarget) -> Unit) {
        val t = paintTarget() ?: run { listener.onMessage("Elige una capa"); return }
        val l = active
        val mask = editingMask
        val before = t.read(0, 0, width, height)
        op(l, t)
        val now = if (mask) l.mask!! else l.target
        val after = now.read(0, 0, width, height)
        push(Edit.Pixels(l.id, mask, 0, 0, width, height, before, after))
        touched(l.id)
    }

    /** Clears the active layer (inside the selection if there is one). */
    fun clearLayer() = editTarget { l, t ->
        if (selection != null) {
            scratch.bind(); glDisable(GL_BLEND)
            pMasked.use(); pMasked.tex("uTex", 0, t.tex); pMasked.tex("uMask", 1, selection!!.tex); glUniform1i(pMasked.u("uInvert"), 1); drawQuad()
            swapTarget(l, editingMask)
        } else if (editingMask) t.clear(1f, 1f, 1f, 1f) else t.clear()
    }

    fun fillLayer(color: Int) = editTarget { l, t ->
        val a = ((color ushr 24) and 255) / 255f
        val r = ((color shr 16) and 255) / 255f * a; val g = ((color shr 8) and 255) / 255f * a; val b = (color and 255) / 255f * a
        // Paint the colour as a full "stroke" through the usual merge (selection + alpha lock apply).
        stroke.clear(r, g, b, a)
        val keep = strokeOpacity; strokeOpacity = 1f
        merge(t.tex, if (!editingMask && l.props.alphaLock) 2 else 0, scratch)
        strokeOpacity = keep
        stroke.clear()
        swapTarget(l, editingMask)
    }

    fun flipLayer(horizontal: Boolean) = editTarget { l, t ->
        flipInto(t.tex, scratch, horizontal)
        swapTarget(l, editingMask)
    }

    private fun flipInto(tex: Int, into: RenderTarget, horizontal: Boolean) {
        into.bind(); glDisable(GL_BLEND)
        val q = if (horizontal) floatArrayOf(-1f, -1f, 1f, 0f, 1f, -1f, 0f, 0f, -1f, 1f, 1f, 1f, 1f, 1f, 0f, 1f)
        else floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        pCopy.use(); pCopy.tex("uTex", 0, tex); glUniform1f(pCopy.u("uOpacity"), 1f)
        upload(dynVbo, q, GL_STREAM_DRAW); drawBound(dynVbo)
    }

    /** Flips every layer and mask: the whole canvas. */
    fun flipCanvas(horizontal: Boolean) {
        val edits = ArrayList<Edit>()
        for (l in layers) {
            if (l.isGroup) continue
            val b = l.target.read(0, 0, width, height)
            flipInto(l.target.tex, scratch, horizontal); swapTarget(l, false)
            edits.add(Edit.Pixels(l.id, false, 0, 0, width, height, b, l.target.read(0, 0, width, height)))
            l.mask?.let {
                val mb = it.read(0, 0, width, height)
                flipInto(it.tex, scratch, horizontal); swapTarget(l, true)
                edits.add(Edit.Pixels(l.id, true, 0, 0, width, height, mb, l.mask!!.read(0, 0, width, height)))
            }
        }
        push(Edit.Group(edits))
        belowDirty = true; notifyLayers(true)
    }

    /** Flood fill from (x, y) with `color` (ColorDrop). */
    fun floodFill(x: Int, y: Int, color: Int, threshold: Float, fromCanvas: Boolean) {
        if (x !in 0 until width || y !in 0 until height) return
        val t = paintTarget() ?: return
        val l = active
        val refLayer = layers.firstOrNull { it.props.reference && !it.isGroup }
        val ref = when {
            refLayer != null -> refLayer.target.read(0, 0, width, height)
            fromCanvas -> { compositeInto(withBg = false); frame.read(0, 0, width, height) }
            else -> t.read(0, 0, width, height)
        }
        val mask = FloodFill.fill(ref, width, height, x, y, threshold) ?: return
        selection?.let { s -> for (i in mask.bits.indices) if (s.bytes[i].toInt() == 0) mask.bits[i] = false }
        val px = t.read(0, 0, width, height)
        val before = t.read(0, 0, width, height)
        var c = color
        if (editingMask) { val g = ((0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255))).toInt(); c = (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        FloodFill.apply(px, mask.bits, width, height, c, !editingMask && l.props.alphaLock)
        t.write(0, 0, width, height, px)
        push(Edit.Pixels(l.id, editingMask, 0, 0, width, height, before, px))
        touched(l.id)
    }

    // ================================================================ selection

    fun setSelection(bytes: ByteArray?, record: Boolean = true) {
        val before = selection?.bytes?.copyOf()
        val empty = bytes == null || bytes.none { it.toInt() != 0 }
        val after = if (empty) null else bytes!!.copyOf()
        setSelectionRaw(after)
        if (record && !(before == null && after == null)) push(Edit.Selection(before, after))
    }

    fun setSelectionRaw(bytes: ByteArray?) {
        if (bytes == null) { selection?.release(); selection = null }
        else {
            val s = selection ?: Mask8(width, height).also { selection = it }
            bytes.copyInto(s.bytes)
            s.upload()
        }
        listener.onSelection(selection != null)
    }

    /** mode: 0 replace, 1 add, 2 subtract. */
    fun combineSelection(new: ByteArray, mode: Int) {
        val cur = selection?.bytes
        val out = when {
            mode == 0 || cur == null -> if (mode == 2) ByteArray(new.size) else new
            mode == 1 -> ByteArray(new.size) { i -> max(cur[i].toInt() and 255, new[i].toInt() and 255).toByte() }
            else -> ByteArray(new.size) { i -> max(0, (cur[i].toInt() and 255) - (new[i].toInt() and 255)).toByte() }
        }
        setSelection(out)
    }

    fun selectAll() = setSelection(ByteArray(width * height) { -1 })
    fun clearSelection() = setSelection(null)

    fun invertSelection() {
        val cur = selection?.bytes
        setSelection(ByteArray(width * height) { i -> (255 - ((cur?.get(i)?.toInt() ?: 0) and 255)).toByte() })
    }

    fun featherSelection(radius: Int) {
        val s = selection ?: return
        setSelection(SelectionOps.blur(s.bytes, width, height, radius))
    }

    /** Selects the active layer's opaque pixels. */
    fun selectLayerContent(mode: Int) {
        val t = active.takeIf { !it.isGroup }?.target ?: return
        val px = t.read(0, 0, width, height)
        combineSelection(ByteArray(width * height) { i -> px.get(i * 4 + 3) }, mode)
    }

    /** Automatic selection: flood from a point, on the layer or the whole picture. */
    fun autoSelect(x: Int, y: Int, threshold: Float, fromCanvas: Boolean, mode: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val ref = if (fromCanvas) { compositeInto(withBg = false); frame.read(0, 0, width, height) }
        else paintTarget()?.read(0, 0, width, height) ?: return
        val m = FloodFill.fill(ref, width, height, x, y, threshold) ?: return
        combineSelection(ByteArray(width * height) { i -> if (m.bits[i]) -1 else 0 }, mode)
    }

    // ---------------------------------------------------------------- clipboard

    private fun selectedRegion(px: ByteBuffer): Clip? {
        val s = selection
        val b = s?.bounds() ?: alphaBounds(px) ?: return null
        val w = b[2] - b[0]; val h = b[3] - b[1]
        val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (yy in 0 until h) for (xx in 0 until w) {
            val i = (b[1] + yy) * width + (b[0] + xx)
            val k = if (s != null) (s.bytes[i].toInt() and 255) else 255
            for (c in 0..3) out.put(((px.get(i * 4 + c).toInt() and 255) * k / 255).toByte())
        }
        out.position(0)
        return Clip(b[0], b[1], w, h, out)
    }

    private fun alphaBounds(px: ByteBuffer): IntArray? {
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        for (y in 0 until height) for (x in 0 until width) if (px.get((y * width + x) * 4 + 3).toInt() != 0) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    fun copy(): Boolean {
        val t = active.takeIf { !it.isGroup }?.target ?: return false
        clipboard = selectedRegion(t.read(0, 0, width, height)) ?: return false
        return true
    }

    fun copyMerged(): Boolean {
        compositeInto(withBg = false)
        clipboard = selectedRegion(frame.read(0, 0, width, height)) ?: return false
        return true
    }

    fun cut(): Boolean {
        if (!copy()) return false
        if (selection == null) editTarget { l, t -> t.clear() } else clearLayer()
        return true
    }

    /** Pastes the clipboard as a new layer; returns its id or 0. */
    fun paste(): Long {
        val c = clipboard ?: return 0
        if (c.x + c.w > width || c.y + c.h > height) {
            // From a bigger canvas: crop to fit.
            val w = min(c.w, width); val h = min(c.h, height)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for (yy in 0 until h) for (xx in 0 until w) for (k in 0..3) buf.put(c.px.get((yy * c.w + xx) * 4 + k))
            buf.position(0)
            return addLayerWith("Pegado", buf, 0, 0, w, h)
        }
        return addLayerWith("Pegado", c.px, c.x, c.y, c.w, c.h)
    }

    // ================================================================ transform

    private class TransformSession(val layer: Layer, val before: ByteBuffer, val floating: RenderTarget, val box: IntArray,
                                   val selBefore: ByteArray?) {
        var mesh: FloatArray? = null
        var n = 1
        var nearest = false
        var dirty = true
    }
    private var tf: TransformSession? = null
    val transforming get() = tf != null

    /** Lifts the selected (or whole) content of the active layer; returns its bounding box or null. */
    fun beginTransform(): IntArray? {
        if (tf != null) return tf!!.box
        val l = active
        if (l.isGroup || editingMask) { listener.onMessage("Transformar funciona sobre una capa"); return null }
        val before = l.target.read(0, 0, width, height)
        val sel = selection
        var box = (sel?.bounds() ?: alphaBounds(before)) ?: run { listener.onMessage("La capa está vacía"); return null }
        if (sel != null) {
            // Only where there is content inside the selection.
            val sb = box
            var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
            for (y in sb[1] until sb[3]) for (x in sb[0] until sb[2]) {
                val i = y * width + x
                if (sel.bytes[i].toInt() != 0 && before.get(i * 4 + 3).toInt() != 0) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
            if (x1 < 0) { listener.onMessage("No hay nada seleccionado en esta capa"); return null }
            box = intArrayOf(x0, y0, x1 + 1, y1 + 1)
        }
        val fl = RenderTarget(width, height)
        fl.bind(); glDisable(GL_BLEND)
        if (sel != null) {
            pMasked.use(); pMasked.tex("uTex", 0, l.target.tex); pMasked.tex("uMask", 1, sel.tex); glUniform1i(pMasked.u("uInvert"), 0); drawQuad()
            scratch.bind()
            pMasked.use(); pMasked.tex("uTex", 0, l.target.tex); pMasked.tex("uMask", 1, sel.tex); glUniform1i(pMasked.u("uInvert"), 1); drawQuad()
            swapTarget(l, false)
        } else {
            drawTex(pCopy, l.target.tex)
            l.target.clear()
        }
        tf = TransformSession(l, before, fl, box, sel?.bytes?.copyOf())
        belowDirty = true
        return box
    }

    /** Grid of (n+1)² vertices (x, y canvas) where the content box should land. */
    fun setTransformMesh(grid: FloatArray, n: Int, nearest: Boolean) {
        val s = tf ?: return
        s.mesh = grid; s.n = n; s.nearest = nearest; s.dirty = true
    }

    private fun meshTriangles(grid: FloatArray, n: Int, box: IntArray): FloatArray {
        val out = FloatArray(n * n * 6 * 4)
        var k = 0
        val bw = (box[2] - box[0]).toFloat(); val bh = (box[3] - box[1]).toFloat()
        fun put(i: Int, j: Int) {
            val v = (j * (n + 1) + i) * 2
            out[k++] = grid[v]; out[k++] = grid[v + 1]
            out[k++] = (box[0] + bw * i / n) / width; out[k++] = (box[1] + bh * j / n) / height
        }
        for (j in 0 until n) for (i in 0 until n) {
            put(i, j); put(i + 1, j); put(i, j + 1)
            put(i + 1, j); put(i + 1, j + 1); put(i, j + 1)
        }
        return out
    }

    private fun drawMesh(tex: Int, tris: FloatArray, nearest: Boolean) {
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (nearest) GL_NEAREST else GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, if (nearest) GL_NEAREST else GL_LINEAR)
        pMesh.use(); pMesh.tex("uTex", 0, tex); glUniform1f(pMesh.u("uOpacity"), 1f)
        glUniformMatrix3fv(pMesh.u("uM"), 1, false, canvasM, 0)
        upload(meshVbo, tris, GL_STREAM_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glDrawArrays(GL_TRIANGLES, 0, tris.size / 4)
        glDisableVertexAttribArray(1)
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    }

    private fun renderTransform() {
        val s = tf ?: return
        if (!s.dirty) return
        scratch.bind(); glDisable(GL_BLEND); drawTex(pCopy, s.layer.target.tex)
        s.mesh?.let { g ->
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            drawMesh(s.floating.tex, meshTriangles(g, s.n, s.box), s.nearest)
            glDisable(GL_BLEND)
        }
        s.dirty = false
    }

    fun commitTransform() {
        val s = tf ?: return
        s.dirty = true; renderTransform()
        swapTarget(s.layer, false)
        val after = s.layer.target.read(0, 0, width, height)
        val edits = arrayListOf<Edit>(Edit.Pixels(s.layer.id, false, 0, 0, width, height, s.before, after))
        // The selection follows the content.
        val sel = selection
        val mesh = s.mesh
        if (sel != null && mesh != null) {
            val t = RenderTarget(width, height)
            t.clear()
            t.bind(); glDisable(GL_BLEND)
            drawMesh(sel.tex, meshTriangles(mesh, s.n, s.box), false)
            val buf = t.read(0, 0, width, height)
            t.release()
            val nb = ByteArray(width * height) { i -> buf.get(i * 4) }
            edits.add(Edit.Selection(s.selBefore, nb))
            setSelectionRaw(nb)
        }
        push(Edit.Group(edits))
        s.floating.release()
        tf = null
        touched(s.layer.id)
    }

    fun cancelTransform() {
        val s = tf ?: return
        s.layer.target.write(0, 0, width, height, s.before)
        s.floating.release()
        tf = null
        touched(s.layer.id)
    }

    // ================================================================ adjustments

    private class AdjustSession(val kind: AdjustKind, val layer: Layer, val mask: Boolean) {
        var params = FloatArray(12)
        var lut = 0
        var dirty = true
        var field: FloatArray? = null
        var fieldTex = 0
        var gw = 0; var gh = 0
        var fieldDirty = false
    }
    private var adj: AdjustSession? = null
    val adjusting get() = adj != null

    fun beginAdjust(kind: AdjustKind): Boolean {
        if (adj != null) cancelAdjust()
        paintTarget() ?: run { listener.onMessage("Elige una capa"); return false }
        val s = AdjustSession(kind, active, editingMask)
        if (kind == AdjustKind.LIQUIFY) {
            s.gw = (width + 3) / 4; s.gh = (height + 3) / 4
            s.field = FloatArray(s.gw * s.gh * 2)
            val t = IntArray(1); glGenTextures(1, t, 0); s.fieldTex = t[0]
            glBindTexture(GL_TEXTURE_2D, s.fieldTex)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RG16F, s.gw, s.gh)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            s.fieldDirty = true
        }
        if (adjA == null) { adjA = RenderTarget(width, height); adjB = RenderTarget(width, height) }
        adj = s
        belowDirty = true
        return true
    }

    fun setAdjust(params: FloatArray, lut: ByteArray?) {
        val s = adj ?: return
        s.params = params.copyOf(12)
        if (lut != null) {
            val buf = ByteBuffer.allocateDirect(256 * 4).put(lut).also { it.position(0) }
            if (s.lut == 0) s.lut = makeTexture(256, 1, buf, single = false, repeat = false, mipmaps = false)
            else { glBindTexture(GL_TEXTURE_2D, s.lut); glPixelStorei(GL_UNPACK_ALIGNMENT, 1); glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256, 1, GL_RGBA, GL_UNSIGNED_BYTE, buf) }
        }
        s.dirty = true
    }

    private fun adjustPass(kind: Int, src: Int, into: RenderTarget, p: FloatArray, aux: Int = 0, lut: Int = 0) {
        into.bind(); glDisable(GL_BLEND)
        pAdjust.use()
        pAdjust.tex("uTex", 0, src); pAdjust.tex("uAux", 1, if (aux != 0) aux else src); pAdjust.tex("uLut", 2, if (lut != 0) lut else src)
        glUniform1i(pAdjust.u("uKind"), kind)
        glUniform1fv(pAdjust.u("uP"), 12, p, 0)
        glUniform2f(pAdjust.u("uSize"), width.toFloat(), height.toFloat())
        drawQuad()
    }

    private fun blur(src: Int, tmp: RenderTarget, into: RenderTarget, sigma: Float) {
        val p = FloatArray(12); p[0] = sigma
        p[1] = 0f; adjustPass(4, src, tmp, p)
        p[1] = 1f; adjustPass(4, tmp.tex, into, p)
    }

    private fun renderAdjust() {
        val s = adj ?: return
        if (s.kind == AdjustKind.LIQUIFY && s.fieldDirty) {
            val fb: FloatBuffer = floatBuffer(s.field!!.size).apply { put(s.field!!); position(0) }
            glBindTexture(GL_TEXTURE_2D, s.fieldTex)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, s.gw, s.gh, GL_RG, GL_FLOAT, fb)
            s.fieldDirty = false; s.dirty = true
        }
        if (!s.dirty) return
        val orig = (if (s.mask) s.layer.mask!! else s.layer.target).tex
        val a = adjA!!; val b = adjB!!
        val p = s.params
        val result: RenderTarget = when (s.kind) {
            AdjustKind.GAUSSIAN -> { blur(orig, b, a, p[0]); a }
            AdjustKind.SHARPEN -> { blur(orig, b, a, max(0.5f, p[1])); adjustPass(7, orig, b, p, aux = a.tex); b }
            AdjustKind.BLOOM -> { adjustPass(10, orig, a, p); blur(a.tex, b, a, max(0.5f, p[2])); adjustPass(11, orig, b, p, aux = a.tex); b }
            AdjustKind.LIQUIFY -> { adjustPass(15, orig, a, p, aux = s.fieldTex); a }
            else -> { adjustPass(s.kind.code, orig, a, p, lut = s.lut); a }
        }
        scratch.bind(); glDisable(GL_BLEND)
        pSelMix.use()
        pSelMix.tex("uOrig", 0, orig); pSelMix.tex("uAdj", 1, result.tex)
        bindSel(pSelMix, "uSel", "uUseSel", 2)
        drawQuad()
        s.dirty = false
    }

    fun commitAdjust() {
        val s = adj ?: return
        s.dirty = true; renderAdjust()
        val t = if (s.mask) s.layer.mask!! else s.layer.target
        val before = t.read(0, 0, width, height)
        swapTarget(s.layer, s.mask)
        val after = (if (s.mask) s.layer.mask!! else s.layer.target).read(0, 0, width, height)
        push(Edit.Pixels(s.layer.id, s.mask, 0, 0, width, height, before, after))
        releaseAdjust(s)
        touched(s.layer.id)
    }

    fun cancelAdjust() {
        val s = adj ?: return
        releaseAdjust(s)
        belowDirty = true
    }

    private fun releaseAdjust(s: AdjustSession) {
        if (s.lut != 0) glDeleteTextures(1, intArrayOf(s.lut), 0)
        if (s.fieldTex != 0) glDeleteTextures(1, intArrayOf(s.fieldTex), 0)
        adj = null
    }

    /** One liquify dab from (px, py) to (x, y); radius in canvas px. */
    fun liquify(mode: LiquifyMode, x: Float, y: Float, px: Float, py: Float, radius: Float, pressure: Float, strength: Float) {
        val s = adj ?: return
        val f = s.field ?: return
        val gr = radius / 4f
        val cx = x / 4f; val cy = y / 4f
        val i0 = max(0, (cx - gr).toInt()); val i1 = min(s.gw - 1, (cx + gr).toInt() + 1)
        val j0 = max(0, (cy - gr).toInt()); val j1 = min(s.gh - 1, (cy + gr).toInt() + 1)
        val k = strength * pressure
        val mx = x - px; val my = y - py
        for (j in j0..j1) for (i in i0..i1) {
            val dx = (i + 0.5f) - cx; val dy = (j + 0.5f) - cy
            val d = hypot(dx, dy) / gr
            if (d >= 1f) continue
            val w = (1 - d * d).let { it * it } * k
            val o = (j * s.gw + i) * 2
            val rx = dx * 4f; val ry = dy * 4f
            when (mode) {
                LiquifyMode.PUSH -> { f[o] -= mx * w; f[o + 1] -= my * w }
                LiquifyMode.TWIRL_R, LiquifyMode.TWIRL_L -> {
                    val a = (if (mode == LiquifyMode.TWIRL_R) -0.06f else 0.06f) * w
                    f[o] += rx * cos(a) - ry * sin(a) - rx; f[o + 1] += rx * sin(a) + ry * cos(a) - ry
                }
                LiquifyMode.PINCH -> { f[o] += rx * 0.04f * w; f[o + 1] += ry * 0.04f * w }
                LiquifyMode.EXPAND -> { f[o] -= rx * 0.04f * w; f[o + 1] -= ry * 0.04f * w }
                LiquifyMode.CRYSTALS -> {
                    val h = sin((i * 12.9898f + j * 78.233f)) * 43758.547f
                    val ang = (h - floor(h)) * 2 * PI.toFloat()
                    f[o] += cos(ang) * radius * 0.03f * w; f[o + 1] += sin(ang) * radius * 0.03f * w
                }
                LiquifyMode.RECONSTRUCT -> { f[o] *= 1f - 0.2f * w; f[o + 1] *= 1f - 0.2f * w }
            }
        }
        s.fieldDirty = true
    }

    fun resetLiquify() { adj?.field?.fill(0f); adj?.fieldDirty = true }

    // ================================================================ compositing

    private fun drawQuad() = drawBound(quadVbo)

    private fun drawBound(vbo: Int) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(1)
    }

    private fun drawTex(p: Program, tex: Int, opacity: Float = 1f) {
        p.use(); p.tex("uTex", 0, tex); glUniform1f(p.u("uOpacity"), opacity); drawQuad()
    }

    private fun upload(vbo: Int, data: FloatArray, usage: Int) {
        if (data.size > dynBuf.capacity()) dynBuf = floatBuffer(data.size * 2)
        dynBuf.clear(); dynBuf.put(data); dynBuf.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, dynBuf, usage)
    }

    /** Ping-pong pair: `cur` holds the picture so far. */
    private inner class Pair2(var cur: RenderTarget, var other: RenderTarget) {
        fun blend(src: Int, props: LayerProps, clipTex: Int, mul: Float) {
            val op = props.opacity * mul
            if (!props.visible || op <= 0f) return
            if (props.blend == BlendMode.NORMAL && clipTex == 0) {
                cur.bind(); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                drawTex(pCopy, src, op); glDisable(GL_BLEND)
                return
            }
            other.bind(); glDisable(GL_BLEND)
            pBlend.use()
            pBlend.tex("uDst", 0, cur.tex); pBlend.tex("uSrc", 1, src); pBlend.tex("uClip", 2, if (clipTex != 0) clipTex else src)
            glUniform1f(pBlend.u("uOpacity"), op)
            glUniform1i(pBlend.u("uMode"), props.blend.code)
            glUniform1i(pBlend.u("uUseClip"), if (clipTex != 0) 1 else 0)
            drawQuad()
            val t = cur; cur = other; other = t
        }
    }

    private val below = Pair2(belowA, belowB)
    private val frames = Pair2(frameA, frameB)
    private var groupPairObj: Pair2? = null
    private fun groupPair(): Pair2 {
        groupPairObj?.let { return it }
        groupA = RenderTarget(width, height); groupB = RenderTarget(width, height)
        return Pair2(groupA!!, groupB!!).also { groupPairObj = it }
    }

    private val liveSession get() = (builder != null && !strokeDirect) || tf != null || adj != null

    /** Texture of layer i as it looks now (the stroke / transform / adjustment in progress on the active one). */
    private fun liveTex(l: Layer): Int {
        if (l.id == activeId && liveSession) {
            val onMask = if (builder != null) strokeOnMask else adj?.mask ?: false
            if (!onMask) return scratch.tex
        }
        return l.target.tex
    }

    private fun liveMaskTex(l: Layer): Int {
        val m = l.mask ?: return 0
        if (!l.props.maskEnabled) return 0
        if (l.id == activeId && liveSession) {
            val onMask = if (builder != null) strokeOnMask else adj?.mask ?: false
            if (onMask) return scratch.tex
        }
        return m.tex
    }

    /** The layer with its mask applied (in a temp target when it has one). */
    private fun maskedSource(l: Layer): Int {
        val src = liveTex(l)
        val m = liveMaskTex(l)
        if (m == 0) return src
        val t = maskTemp ?: RenderTarget(width, height).also { maskTemp = it }
        t.bind(); glDisable(GL_BLEND)
        pMasked.use(); pMasked.tex("uTex", 0, src); pMasked.tex("uMask", 1, m); glUniform1i(pMasked.u("uInvert"), 0); drawQuad()
        return t.tex
    }

    private fun clipBase(i: Int): Int {
        val l = layers[i]
        if (!l.props.clip) return 0
        var j = i - 1
        while (j >= 0 && layers[j].props.clip && layers[j].parent == l.parent && !layers[j].isGroup) j--
        val b = layers.getOrNull(j) ?: return 0
        if (b.isGroup || b.parent != l.parent) return 0
        return liveTex(b)
    }

    /** Top-level blocks: a lone layer, or a group with its children (index ranges, group last). */
    fun blocks(): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < layers.size) {
            val l = layers[i]
            if (l.parent == 0L) { out.add(i..i); i++; continue }
            val g = l.parent
            var j = i
            while (j < layers.size && layers[j].parent == g) j++
            out.add(i..min(j, layers.size - 1))   // children + the group itself at j
            i = j + 1
        }
        return out
    }

    private fun renderLeaf(i: Int, into: Pair2, mul: Float) {
        val l = layers[i]
        if (!l.props.visible || l.isGroup) return
        into.blend(maskedSource(l), l.props, clipBase(i), mul)
    }

    private fun renderGroupInto(children: IntRange) {
        val gp = groupPair()
        gp.cur.clear()
        for (i in children) renderLeaf(i, gp, 1f)
    }

    private fun renderBlock(r: IntRange, into: Pair2, mul: Float) {
        val last = layers[r.last]
        if (last.isGroup) {
            if (!last.props.visible) return
            renderGroupInto(r.first until r.last)
            into.blend(groupPair().cur.tex, last.props, 0, mul)
        } else for (i in r) renderLeaf(i, into, mul)
    }

    /** Opacity multiplier of a block in animation mode (0 = hidden). */
    private fun animMul(b: Int, count: Int): Float {
        if (!anim.enabled) return 1f
        val f = anim.frame.coerceIn(0, max(0, count - 1))
        if (b == f) return 1f
        if (anim.bgFirst && b == 0) return 1f
        if (anim.fgLast && b == count - 1) return 1f
        val d = abs(b - f)
        if (anim.onion > 0 && d <= anim.onion) return anim.onionOpacity * (1f - (d - 1f) / anim.onion)
        return 0f
    }

    /** Builds the full picture into `frame`. */
    fun compositeInto(withBg: Boolean = true) {
        val bl = blocks()
        val ai = activeIndex
        val activeBlock = bl.indexOfFirst { ai in it }.coerceAtLeast(0)
        val useCache = withBg && !anim.enabled
        var start = 0
        if (useCache) {
            if (belowDirty || cachedBlock != activeBlock) {
                clearWithBg(below.cur, true)
                for (b in 0 until activeBlock) renderBlock(bl[b], below, 1f)
                belowDirty = false; cachedBlock = activeBlock
            }
            frames.cur.bind(); glDisable(GL_BLEND); drawTex(pCopy, below.cur.tex)
            start = activeBlock
        } else {
            clearWithBg(frames.cur, withBg)
            belowDirty = true
        }
        // Pre-pass for the active layer's live preview.
        if (builder != null && !strokeDirect) paintTarget()?.let { merge(it.tex, strokeMode(active), scratch) }
        else if (tf != null) renderTransform()
        else if (adj != null) renderAdjust()
        for (b in start until bl.size) {
            val m = animMul(b, bl.size)
            if (m > 0f) renderBlock(bl[b], frames, m)
        }
        frame = frames.cur
    }

    private fun clearWithBg(t: RenderTarget, withBg: Boolean) {
        if (withBg && bgVisible) {
            val c = bgColor
            t.clear(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, 1f)
        } else t.clear()
    }

    fun composite() = compositeInto(true)

    /**
     * Draws a frame: stamps, composite, then the canvas on screen. `corners` = NDC of the canvas corners
     * (top-left, top-right, bottom-left, bottom-right in canvas space); `scale` = screen px per canvas px.
     */
    fun render(screenW: Int, screenH: Int, corners: FloatArray, scale: Float, uiBg: Int, timeMs: Long) {
        flushStamps()
        if (layers.isNotEmpty()) composite()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenW, screenH)
        glClearColor(((uiBg shr 16) and 255) / 255f, ((uiBg shr 8) and 255) / 255f, (uiBg and 255) / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (layers.isEmpty()) return
        val m3 = model3d
        if (m3 != null && show3d) { m3.draw(frame.tex, screenW, screenH, bgColor); return }
        val q = floatArrayOf(
            corners[0], corners[1], 0f, 0f, corners[2], corners[3], 1f, 0f,
            corners[4], corners[5], 0f, 1f, corners[6], corners[7], 1f, 1f)
        upload(dynVbo, q, GL_STREAM_DRAW)
        if (!bgVisible) { pChecker.use(); drawBound(dynVbo) }
        glBindTexture(GL_TEXTURE_2D, frame.tex)
        if (scale < 1f) {
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        } else glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, if (scale >= 3f) GL_NEAREST else GL_LINEAR)
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        pScreen.use(); pScreen.tex("uTex", 0, frame.tex); drawBound(dynVbo)
        glBindTexture(GL_TEXTURE_2D, frame.tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        // Predicted continuation of the stroke (drawn only on screen, gone next frame).
        val pr = prediction
        val b = builder
        if (pr != null && b != null && !strokeDirect && strokeTool == Tool.BRUSH && !strokeOnMask && shape == null && b.lastSize > 0f) {
            val m = floatArrayOf(
                (corners[2] - corners[0]) / width, (corners[3] - corners[1]) / width, 0f,
                (corners[4] - corners[0]) / height, (corners[5] - corners[1]) / height, 0f,
                corners[0], corners[1], 1f)
            val gap = max(1f, strokeBrush!!.spacing * b.lastSize)
            val dist = hypot(pr[0] - b.lastX, pr[1] - b.lastY)
            val n = min(64, (dist / gap).toInt())
            if (n > 0) {
                val r = ((strokeColor shr 16) and 255) / 255f; val g = ((strokeColor shr 8) and 255) / 255f; val bl = (strokeColor and 255) / 255f
                val d = FloatArray(n * 8)
                for (i in 0 until n) {
                    val t = (i + 1f) / n
                    d[i * 8] = b.lastX + (pr[0] - b.lastX) * t; d[i * 8 + 1] = b.lastY + (pr[1] - b.lastY) * t
                    d[i * 8 + 2] = b.lastSize; d[i * 8 + 3] = b.lastAngle
                    d[i * 8 + 4] = r; d[i * 8 + 5] = g; d[i * 8 + 6] = bl; d[i * 8 + 7] = b.lastAlpha * strokeOpacity
                }
                drawStamps(d, n, strokeBrush!!, m, false)
            }
        }
        // Marching ants.
        selection?.let { s ->
            upload(dynVbo, q, GL_STREAM_DRAW)
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            pSelOut.use(); pSelOut.tex("uSel", 0, s.tex)
            glUniform2f(pSelOut.u("uTexel"), 1.5f / (width * scale), 1.5f / (height * scale))
            glUniform1f(pSelOut.u("uTime"), ((timeMs / 60) % 8).toFloat())
            drawBound(dynVbo)
            glDisable(GL_BLEND)
        }
    }

    /** Colour of the picture at a canvas point (ARGB, not premultiplied), or null outside. */
    fun pick(x: Int, y: Int): Int? {
        if (x !in 0 until width || y !in 0 until height) return null
        val b = frame.read(x, y, 1, 1)
        val a = b.get(3).toInt() and 255
        if (a == 0) return null
        fun un(v: Byte) = ((v.toInt() and 255) * 255 / a).coerceIn(0, 255)
        return (0xFF shl 24) or (un(b.get(0)) shl 16) or (un(b.get(1)) shl 8) or un(b.get(2))
    }

    /** Full composite as a bitmap (premultiplied, what you see). */
    fun flatten(): Bitmap {
        composite()
        val buf = frame.read(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
    }

    /** One animation frame, flattened. */
    fun flattenFrame(f: Int): Bitmap {
        val was = anim.frame; val onion = anim.onion
        anim.frame = f; anim.onion = 0
        val b = flatten()
        anim.frame = was; anim.onion = onion
        belowDirty = true
        return b
    }

    /** The picture scaled to fit `maxSide` (time-lapse, previews). */
    fun capture(maxSide: Int): Bitmap {
        composite()
        val s = min(1f, maxSide.toFloat() / max(width, height))
        val w = max(2, (width * s).toInt() / 2 * 2); val h = max(2, (height * s).toInt() / 2 * 2)
        val t = tl?.takeIf { it.width == w && it.height == h } ?: run { tl?.release(); RenderTarget(w, h).also { tl = it } }
        glBindTexture(GL_TEXTURE_2D, frame.tex)
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        t.bind(); glDisable(GL_BLEND); drawTex(pCopy, frame.tex)
        glBindTexture(GL_TEXTURE_2D, frame.tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        val buf = t.read(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
    }

    fun readLayer(l: Layer): ByteBuffer = l.target.read(0, 0, width, height)
    fun readMask(l: Layer): ByteBuffer? = l.mask?.read(0, 0, width, height)

    fun release() {
        cancelAdjust(); tf?.floating?.release(); tf = null
        history.clear(this)
        layers.forEach { it.target.release(); it.mask?.release() }
        layers.clear()
        selection?.release()
        listOfNotNull(stroke, scratch, strokeStart, belowA, belowB, frameA, frameB, regionDst, regionPick, thumb,
            groupA, groupB, maskTemp, adjA, adjB, tl).forEach { it.release() }
        (shapeTex.values + grainTex.values).forEach { glDeleteTextures(1, intArrayOf(it), 0) }
        model3d?.release()
    }

    companion object {
        const val REGION = 512
        const val THUMB = 160
        /** Shared across documents, like a system clipboard. */
        @Volatile var clipboard: Clip? = null
    }
}

object SelectionOps {
    /** Three box blurs ≈ gaussian, radius px. */
    fun blur(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius <= 0) return src.copyOf()
        var a = IntArray(src.size) { src[it].toInt() and 255 }
        var b = IntArray(src.size)
        val r = max(1, radius / 2)
        repeat(3) {
            for (y in 0 until h) {
                var sum = 0; val row = y * w
                for (x in -r..r) sum += a[row + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    b[row + x] = sum / (2 * r + 1)
                    sum += a[row + (x + r + 1).coerceAtMost(w - 1)] - a[row + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {
                var sum = 0
                for (y in -r..r) sum += b[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    a[y * w + x] = sum / (2 * r + 1)
                    sum += b[(y + r + 1).coerceAtMost(h - 1) * w + x] - b[(y - r).coerceAtLeast(0) * w + x]
                }
            }
        }
        return ByteArray(src.size) { a[it].toByte() }
    }
}

object FloodFill {
    class Mask(val bits: BooleanArray)

    /** Pixels connected to (sx, sy) whose RGBA is within `threshold` (0..1) of the seed's. */
    fun fill(px: ByteBuffer, w: Int, h: Int, sx: Int, sy: Int, threshold: Float): Mask? {
        val n = w * h
        val ints = px.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val seed = ints.get(sy * w + sx)
        val tol = (threshold * 255 * 4).toInt()
        fun close(c: Int): Boolean {
            var d = 0
            for (s in 0..24 step 8) d += abs(((c ushr s) and 255) - ((seed ushr s) and 255))
            return d <= tol
        }
        val bits = BooleanArray(n)
        val stack = IntArray(n)
        var sp = 0
        stack[sp++] = sy * w + sx
        bits[sy * w + sx] = true
        var count = 0
        while (sp > 0) {
            val i = stack[--sp]
            count++
            val x = i % w; val y = i / w
            if (x > 0) { val j = i - 1; if (!bits[j] && close(ints.get(j))) { bits[j] = true; stack[sp++] = j } }
            if (x < w - 1) { val j = i + 1; if (!bits[j] && close(ints.get(j))) { bits[j] = true; stack[sp++] = j } }
            if (y > 0) { val j = i - w; if (!bits[j] && close(ints.get(j))) { bits[j] = true; stack[sp++] = j } }
            if (y < h - 1) { val j = i + w; if (!bits[j] && close(ints.get(j))) { bits[j] = true; stack[sp++] = j } }
        }
        if (count == 0) return null
        // Grow one pixel so the fill tucks under anti-aliased line edges.
        val grown = bits.copyOf()
        for (i in 0 until n) if (bits[i]) {
            val x = i % w; val y = i / w
            if (x > 0) grown[i - 1] = true
            if (x < w - 1) grown[i + 1] = true
            if (y > 0) grown[i - w] = true
            if (y < h - 1) grown[i + w] = true
        }
        return Mask(grown)
    }

    /** Paints `color` under the mask (keeping alpha if `alphaLock`). */
    fun apply(px: ByteBuffer, mask: BooleanArray, w: Int, h: Int, color: Int, alphaLock: Boolean) {
        val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
        for (i in 0 until w * h) if (mask[i]) {
            val o = i * 4
            if (alphaLock) {
                val a = px.get(o + 3).toInt() and 255
                px.put(o, (r * a / 255).toByte()); px.put(o + 1, (g * a / 255).toByte()); px.put(o + 2, (b * a / 255).toByte())
            } else {
                px.put(o, r.toByte()); px.put(o + 1, g.toByte()); px.put(o + 2, b.toByte()); px.put(o + 3, 255.toByte())
            }
        }
        px.position(0)
    }
}
