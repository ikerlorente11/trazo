package dev.pistation.trazo.engine

import android.graphics.Bitmap
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * The document and everything drawn on it. Lives on the GL thread: every method must be called there.
 * Layers are premultiplied RGBA textures the size of the canvas; index 0 is the bottom one.
 */
class PaintEngine(val width: Int, val height: Int, private val listener: Listener) {

    interface Listener {
        fun onLayers(layers: List<LayerInfo>, activeId: Long)
        fun onHistory(canUndo: Boolean, canRedo: Boolean)
        fun onBackground(color: Int, visible: Boolean)
    }

    val layers = ArrayList<Layer>()
    var activeId = 0L; private set
    var bgColor = 0xFFFFFFFF.toInt(); private set
    var bgVisible = true; private set
    private var nextId = 1L
    val history = UndoStack()

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
    private var belowDirty = true
    /** The last composited image (what is on screen). */
    var frame: RenderTarget = frameA; private set

    private val pCopy = Program(Shaders.VS_QUAD, Shaders.FS_COPY)
    private val pColor = Program(Shaders.VS_QUAD, Shaders.FS_COLOR)
    private val pChecker = Program(Shaders.VS_QUAD, Shaders.FS_CHECKER)
    private val pBlend = Program(Shaders.VS_QUAD, Shaders.FS_BLEND)
    private val pMerge = Program(Shaders.VS_QUAD, Shaders.FS_MERGE)
    private val pStamp = Program(Shaders.VS_STAMP, Shaders.FS_STAMP)
    private val pSmudge = Program(Shaders.VS_SMUDGE, Shaders.FS_SMUDGE)
    private val pScreen = Program(Shaders.VS_QUAD, Shaders.FS_SCREEN)

    private val quadVbo: Int
    private val dynVbo: Int
    private val cornerVbo: Int
    private val instVbo: Int
    private val dynBuf = floatBuffer(16)
    private var instBuf = floatBuffer(8 * 512)

    private val shapeTex = HashMap<Shape, Int>()
    private val grainTex = HashMap<Grain, Int>()

    init {
        val ids = IntArray(4)
        glGenBuffers(4, ids, 0)
        quadVbo = ids[0]; dynVbo = ids[1]; cornerVbo = ids[2]; instVbo = ids[3]
        upload(quadVbo, floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f), GL_STATIC_DRAW)
        upload(cornerVbo, floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f), GL_STATIC_DRAW)
        val tw = if (width >= height) THUMB else max(1, THUMB * width / height)
        val th = if (height >= width) THUMB else max(1, THUMB * height / width)
        thumb = RenderTarget(tw, th)
        glDisable(GL_DITHER)
    }

    // ---------------------------------------------------------------- document structure

    fun layer(id: Long) = layers.firstOrNull { it.id == id }
    fun contains(l: Layer) = layers.any { it === l }
    val activeIndex get() = layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val active get() = layers[activeIndex]
    val memoryPerLayer get() = width.toLong() * height * 4

    fun newLayer(name: String = "Capa ${nextId}"): Layer = Layer(nextId++, LayerProps(name), RenderTarget(width, height))

    /** Adds a layer above the active one (undoable). */
    fun addLayer(): Layer {
        val l = newLayer()
        val idx = if (layers.isEmpty()) 0 else activeIndex + 1
        insertLayerRaw(l, idx)
        push(Edit.AddLayer(l, idx))
        return l
    }

    /** For loading documents: appends without history. */
    fun appendLoaded(id: Long, props: LayerProps, pixels: ByteBuffer?): Layer {
        val l = Layer(id, props, RenderTarget(width, height))
        if (pixels != null) l.target.write(0, 0, width, height, pixels)
        layers.add(l)
        nextId = max(nextId, id + 1)
        activeId = id
        belowDirty = true
        return l
    }

    fun setActive(id: Long) {
        if (layer(id) == null || id == activeId) return
        activeId = id; belowDirty = true; notifyLayers(false)
    }

    fun deleteLayer(id: Long) {
        if (layers.size <= 1) return
        val l = layer(id) ?: return
        val idx = layers.indexOf(l)
        removeLayerRaw(l)
        push(Edit.RemoveLayer(l, idx))
    }

    fun duplicateLayer(id: Long) {
        val src = layer(id) ?: return
        val l = Layer(nextId++, src.props.copy(name = src.props.name + " copia"), RenderTarget(width, height))
        l.target.bind(); glDisable(GL_BLEND); drawTex(pCopy, src.target.tex)
        val idx = layers.indexOf(src) + 1
        insertLayerRaw(l, idx)
        push(Edit.AddLayer(l, idx))
    }

    fun setProps(id: Long, p: LayerProps, record: Boolean = true, before: LayerProps? = null) {
        val l = layer(id) ?: return
        val b = before ?: l.props
        if (b == p && l.props == p) return
        if (l.props != p) setPropsRaw(id, p)
        if (record && b != p) push(Edit.Props(id, b, p))
    }

    fun moveLayer(from: Int, to: Int) {
        if (from == to || from !in layers.indices || to !in layers.indices) return
        moveRaw(from, to)
        push(Edit.Move(from, to))
    }

    fun mergeDown(id: Long) {
        val upper = layer(id) ?: return
        val ui = layers.indexOf(upper)
        if (ui <= 0) return
        val lower = layers[ui - 1]
        val before = lower.target.read(0, 0, width, height)
        // lower := lower blended with upper (upper's opacity, mode and clip all apply)
        scratch.bind(); glDisable(GL_BLEND); drawTex(pCopy, lower.target.tex)
        val tmp = RenderTarget(width, height)
        tmp.bind(); glDisable(GL_BLEND)
        pBlend.use()
        pBlend.tex("uDst", 0, scratch.tex); pBlend.tex("uSrc", 1, upper.target.tex); pBlend.tex("uClip", 2, lower.target.tex)
        glUniform1f(pBlend.u("uOpacity"), if (upper.props.visible) upper.props.opacity else 0f)
        glUniform1i(pBlend.u("uMode"), upper.props.blend.code)
        glUniform1i(pBlend.u("uUseClip"), if (upper.props.clip) 1 else 0)
        drawQuad()
        lower.target.bind(); drawTex(pCopy, tmp.tex)
        tmp.release()
        val after = lower.target.read(0, 0, width, height)
        removeLayerRaw(upper)
        activeId = lower.id
        push(Edit.Group(listOf(Edit.RemoveLayer(upper, ui), Edit.Pixels(lower.id, 0, 0, width, height, before, after))))
        touched(lower.id)
    }

    fun setBackground(color: Int, visible: Boolean) {
        val before = bgColor to bgVisible
        if (before == (color to visible)) return
        setBackgroundRaw(color, visible)
        push(Edit.Background(before, color to visible))
    }

    // Raw mutations used by history
    fun insertLayerRaw(l: Layer, index: Int) {
        layers.add(index.coerceIn(0, layers.size), l); activeId = l.id; belowDirty = true; notifyLayers(true)
    }

    fun removeLayerRaw(l: Layer) {
        val idx = layers.indexOf(l)
        if (idx < 0) return
        layers.removeAt(idx)
        if (activeId == l.id && layers.isNotEmpty()) activeId = layers[max(0, idx - 1)].id
        belowDirty = true; notifyLayers(false)
    }

    fun setPropsRaw(id: Long, p: LayerProps) { layer(id)?.props = p; belowDirty = true; notifyLayers(false) }

    fun moveRaw(from: Int, to: Int) {
        val l = layers.removeAt(from); layers.add(to, l); belowDirty = true; notifyLayers(false)
    }

    fun setBackgroundRaw(color: Int, visible: Boolean) {
        bgColor = color; bgVisible = visible; belowDirty = true
        listener.onBackground(color, visible)
    }

    /** Pixels of a layer changed: caches and its thumbnail. */
    fun touched(id: Long) {
        if (id != activeId) belowDirty = true
        thumbs.remove(id)
        notifyLayers(false)
    }

    fun undo() { if (history.undo(this)) { belowDirty = true; notifyHistory() } }
    fun redo() { if (history.redo(this)) { belowDirty = true; notifyHistory() } }

    private fun push(e: Edit) { history.push(e, this); notifyHistory() }
    private fun notifyHistory() = listener.onHistory(history.canUndo, history.canRedo)

    // ---------------------------------------------------------------- thumbnails

    private val thumbs = HashMap<Long, Bitmap>()

    fun notifyLayers(all: Boolean) {
        if (all) thumbs.clear()
        val infos = layers.map { l -> LayerInfo(l.id, l.props, thumbs.getOrPut(l.id) { thumbnailOf(l.target.tex, false) }) }
        listener.onLayers(infos, activeId)
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

    // ---------------------------------------------------------------- strokes

    private var builder: StrokeBuilder? = null
    private var strokeTool = Tool.BRUSH
    private var strokeBrush: Brush? = null
    private var strokeOpacity = 1f
    private var hasPick = false
    private var pickX = 0f; private var pickY = 0f
    val stroking get() = builder != null

    fun beginStroke(tool: Tool, brush: Brush, size: Float, opacity: Float, color: Int, pressureGamma: Float) {
        if (layers.isEmpty()) return
        val l = active
        val sz = if (tool == Tool.SMUDGE) min(size, REGION / 1.6f) else size
        builder = StrokeBuilder(brush, sz, ((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f,
            (color and 255) / 255f, pressureGamma, (System.nanoTime() and 0xffff).toInt())
        strokeTool = tool; strokeBrush = brush; strokeOpacity = opacity; hasPick = false
        strokeStart.bind(); glDisable(GL_BLEND); drawTex(pCopy, l.target.tex)
        stroke.clear()
    }

    fun strokePoint(x: Float, y: Float, pressure: Float) { builder?.add(x, y, pressure) }

    fun endStroke() {
        val b = builder ?: return
        b.finish()
        flushStamps()
        val l = active
        if (strokeTool != Tool.SMUDGE) {
            val mode = when {
                strokeTool == Tool.ERASER && l.props.alphaLock -> 3
                strokeTool == Tool.ERASER -> 1
                l.props.alphaLock -> 2
                else -> 0
            }
            merge(l, mode, scratch)
            val old = l.target; l.target = scratch; scratch = old
        }
        builder = null
        if (b.isEmpty) return
        val x0 = floor(b.minX).toInt().coerceIn(0, width); val y0 = floor(b.minY).toInt().coerceIn(0, height)
        val x1 = ceil(b.maxX).toInt().coerceIn(0, width); val y1 = ceil(b.maxY).toInt().coerceIn(0, height)
        if (x1 <= x0 || y1 <= y0) return
        val before = strokeStart.read(x0, y0, x1 - x0, y1 - y0)
        val after = l.target.read(x0, y0, x1 - x0, y1 - y0)
        push(Edit.Pixels(l.id, x0, y0, x1 - x0, y1 - y0, before, after))
        touched(l.id)
    }

    fun cancelStroke() {
        if (builder == null) return
        if (strokeTool == Tool.SMUDGE) { active.target.bind(); glDisable(GL_BLEND); drawTex(pCopy, strokeStart.tex) }
        builder = null
        stroke.clear()
    }

    /** Draws the stamps produced since the last frame. */
    fun flushStamps() {
        val b = builder ?: return
        val n = b.pending.size / 8
        if (n == 0) return
        val brush = strokeBrush!!
        if (strokeTool == Tool.SMUDGE) smudgeStamps(b.pending.data, n, brush) else {
            stroke.bind()
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            pStamp.use()
            glUniform2f(pStamp.u("uCanvas"), width.toFloat(), height.toFloat())
            glUniform1f(pStamp.u("uRoundness"), brush.roundness)
            bindBrushTextures(pStamp, brush)
            if (instBuf.capacity() < n * 8) instBuf = floatBuffer(n * 8 * 2)
            instBuf.clear(); instBuf.put(b.pending.data, 0, n * 8); instBuf.position(0)
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
        b.pending.clear()
    }

    private fun smudgeStamps(d: FloatArray, n: Int, brush: Brush) {
        val l = active
        pSmudge.use()
        glUniform2f(pSmudge.u("uCanvas"), width.toFloat(), height.toFloat())
        glUniform1f(pSmudge.u("uRoundness"), brush.roundness)
        glUniform1f(pSmudge.u("uRegion"), REGION.toFloat())
        for (i in 0 until n) {
            val o = i * 8
            val x = d[o]; val y = d[o + 1]
            if (!hasPick) { copyRegion(l, regionPick, x, y); pickX = x; pickY = y; hasPick = true; continue }
            val (dx, dy) = copyRegion(l, regionDst, x, y)
            val (px, py) = regionOrigin(pickX, pickY)
            l.target.bind(); glDisable(GL_BLEND)
            pSmudge.use()
            bindBrushTextures(pSmudge, brush)
            pSmudge.tex("uDstCopy", 2, regionDst.tex)
            pSmudge.tex("uPick", 3, regionPick.tex)
            glUniform2f(pSmudge.u("uDstOrigin"), dx, dy)
            glUniform2f(pSmudge.u("uPickOrigin"), px, py)
            glUniform2f(pSmudge.u("uDelta"), x - pickX, y - pickY)
            glUniform1f(pSmudge.u("uStrength"), (brush.smudge * d[o + 7] * strokeOpacity).coerceIn(0f, 1f))
            glUniform4f(pSmudge.u("uStamp"), x, y, d[o + 2], d[o + 3])
            glBindBuffer(GL_ARRAY_BUFFER, cornerVbo)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
            glDisableVertexAttribArray(1)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            copyRegion(l, regionPick, x, y); pickX = x; pickY = y
        }
    }

    private fun regionOrigin(cx: Float, cy: Float) = Pair(round(cx - REGION / 2f), round(cy - REGION / 2f))

    /** Copies the REGION² square of the layer centred on (cx, cy) into `into`; returns its origin. */
    private fun copyRegion(l: Layer, into: RenderTarget, cx: Float, cy: Float): Pair<Float, Float> {
        val (ox, oy) = regionOrigin(cx, cy)
        into.clear()
        val x0 = max(ox.toInt(), 0); val y0 = max(oy.toInt(), 0)
        val x1 = min(ox.toInt() + REGION, width); val y1 = min(oy.toInt() + REGION, height)
        if (x1 > x0 && y1 > y0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, l.target.fbo)
            glBindTexture(GL_TEXTURE_2D, into.tex)
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, x0 - ox.toInt(), y0 - oy.toInt(), x0, y0, x1 - x0, y1 - y0)
        }
        return ox to oy
    }

    private fun bindBrushTextures(p: Program, brush: Brush) {
        val s = shapeTex.getOrPut(brush.shape) {
            makeTexture(BrushTextures.SHAPE, BrushTextures.SHAPE, BrushTextures.shape(brush.shape), single = true, repeat = false)
        }
        val g = grainTex.getOrPut(brush.grain) {
            makeTexture(BrushTextures.GRAIN, BrushTextures.GRAIN, BrushTextures.grain(brush.grain), single = true, repeat = true)
        }
        p.tex("uShape", 0, s)
        p.tex("uGrain", 1, g)
        glUniform1f(p.u("uGrainScale"), brush.grainScale)
        glUniform1f(p.u("uGrainDepth"), if (brush.grain == Grain.NONE) 0f else brush.grainDepth)
    }

    private fun merge(l: Layer, mode: Int, into: RenderTarget) {
        into.bind(); glDisable(GL_BLEND)
        pMerge.use()
        pMerge.tex("uLayer", 0, l.target.tex); pMerge.tex("uStroke", 1, stroke.tex)
        glUniform1f(pMerge.u("uOpacity"), strokeOpacity)
        glUniform1i(pMerge.u("uMode"), mode)
        drawQuad()
    }

    // ---------------------------------------------------------------- whole-layer operations

    /** Runs `op` on the active layer and records the change as one undo step. */
    private fun editActive(op: (Layer) -> Unit) {
        val l = active
        val before = l.target.read(0, 0, width, height)
        op(l)
        val after = l.target.read(0, 0, width, height)
        push(Edit.Pixels(l.id, 0, 0, width, height, before, after))
        touched(l.id)
    }

    fun clearLayer() = editActive { it.target.clear() }

    fun fillLayer(color: Int) = editActive { l ->
        val a = ((color ushr 24) and 255) / 255f
        val r = ((color shr 16) and 255) / 255f * a; val g = ((color shr 8) and 255) / 255f * a; val b = (color and 255) / 255f * a
        if (l.props.alphaLock) {
            l.target.bind(); glEnable(GL_BLEND)
            glBlendFuncSeparate(GL_DST_ALPHA, GL_ZERO, GL_ZERO, GL_ONE)
            pColor.use(); glUniform4f(pColor.u("uColor"), r, g, b, a); drawQuad()
            glDisable(GL_BLEND)
        } else l.target.clear(r, g, b, a)
    }

    fun flipLayer(horizontal: Boolean) = editActive { l ->
        scratch.bind(); glDisable(GL_BLEND)
        val q = if (horizontal) floatArrayOf(-1f, -1f, 1f, 0f, 1f, -1f, 0f, 0f, -1f, 1f, 1f, 1f, 1f, 1f, 0f, 1f)
        else floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        pCopy.use(); pCopy.tex("uTex", 0, l.target.tex); glUniform1f(pCopy.u("uOpacity"), 1f)
        upload(dynVbo, q, GL_STREAM_DRAW); drawBound(dynVbo)
        val old = l.target; l.target = scratch; scratch = old
    }

    /** Flood fill from (x, y) with `color`; `fromCanvas` uses the whole picture as reference. */
    fun floodFill(x: Int, y: Int, color: Int, threshold: Float, fromCanvas: Boolean) {
        if (x !in 0 until width || y !in 0 until height) return
        val l = active
        val ref = if (fromCanvas) { composite(); frame.read(0, 0, width, height) } else l.target.read(0, 0, width, height)
        val mask = FloodFill.fill(ref, width, height, x, y, threshold) ?: return
        val px = l.target.read(0, 0, width, height)
        val before = l.target.read(0, 0, width, height)
        FloodFill.apply(px, mask.bits, width, height, color, l.props.alphaLock)
        l.target.write(0, 0, width, height, px)
        push(Edit.Pixels(l.id, 0, 0, width, height, before, px))
        touched(l.id)
    }

    // ---------------------------------------------------------------- compositing

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
        val fb = if (data.size <= dynBuf.capacity()) dynBuf else floatBuffer(data.size)
        fb.clear(); fb.put(data); fb.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb, usage)
    }

    /** Ping-pong pair: `cur` holds the picture so far. */
    private inner class Pair2(var cur: RenderTarget, var other: RenderTarget) {
        fun blend(src: Int, props: LayerProps, clipTex: Int) {
            if (!props.visible || props.opacity <= 0f) return
            if (props.blend == BlendMode.NORMAL && clipTex == 0) {
                cur.bind(); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                drawTex(pCopy, src, props.opacity); glDisable(GL_BLEND)
                return
            }
            other.bind(); glDisable(GL_BLEND)
            pBlend.use()
            pBlend.tex("uDst", 0, cur.tex); pBlend.tex("uSrc", 1, src); pBlend.tex("uClip", 2, if (clipTex != 0) clipTex else src)
            glUniform1f(pBlend.u("uOpacity"), props.opacity)
            glUniform1i(pBlend.u("uMode"), props.blend.code)
            glUniform1i(pBlend.u("uUseClip"), if (clipTex != 0) 1 else 0)
            drawQuad()
            val t = cur; cur = other; other = t
        }
    }

    private val below = Pair2(belowA, belowB)
    private val frames = Pair2(frameA, frameB)

    /** Texture of layer i as it looks now (with the stroke in progress on the active layer). */
    private fun liveTex(i: Int): Int {
        val l = layers[i]
        return if (l.id == activeId && builder != null && strokeTool != Tool.SMUDGE) scratch.tex else l.target.tex
    }

    private fun clipBase(i: Int): Int {
        if (!layers[i].props.clip) return 0
        var j = i - 1
        while (j >= 0 && layers[j].props.clip) j--
        return if (j >= 0) liveTex(j) else 0
    }

    /** Builds the full picture into `frame`. */
    fun composite() {
        val ai = activeIndex
        if (belowDirty) {
            if (bgVisible) {
                val c = bgColor
                below.cur.clear(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, 1f)
            } else below.cur.clear()
            for (i in 0 until ai) below.blend(layers[i].target.tex, layers[i].props, clipBase(i))
            belowDirty = false
        }
        if (builder != null && strokeTool != Tool.SMUDGE) {
            val l = active
            val mode = when {
                strokeTool == Tool.ERASER && l.props.alphaLock -> 3
                strokeTool == Tool.ERASER -> 1
                l.props.alphaLock -> 2
                else -> 0
            }
            merge(l, mode, scratch)
        }
        frames.cur.bind(); glDisable(GL_BLEND); drawTex(pCopy, below.cur.tex)
        for (i in ai until layers.size) frames.blend(liveTex(i), layers[i].props, clipBase(i))
        // A clipped layer above whose base is below the active one still clips correctly: base textures are layers.
        frame = frames.cur
    }

    /**
     * Draws a frame: stamps, composite, then the canvas on screen. `corners` = NDC of the canvas corners
     * (top-left, top-right, bottom-left, bottom-right in canvas space); `scale` = screen px per canvas px.
     */
    fun render(screenW: Int, screenH: Int, corners: FloatArray, scale: Float, uiBg: Int) {
        flushStamps()
        if (layers.isNotEmpty()) composite()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenW, screenH)
        glClearColor(((uiBg shr 16) and 255) / 255f, ((uiBg shr 8) and 255) / 255f, (uiBg and 255) / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (layers.isEmpty()) return
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
        glDisable(GL_BLEND)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
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

    fun readLayer(l: Layer): ByteBuffer = l.target.read(0, 0, width, height)

    fun release() {
        history.clear(this)
        layers.forEach { it.target.release() }
        layers.clear()
        listOf(stroke, scratch, strokeStart, belowA, belowB, frameA, frameB, regionDst, regionPick, thumb).forEach { it.release() }
        (shapeTex.values + grainTex.values).forEach { glDeleteTextures(1, intArrayOf(it), 0) }
    }

    companion object {
        const val REGION = 512
        const val THUMB = 160
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

    /** Paints `color` under the mask: over the old pixel (keeping alpha if `alphaLock`). */
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
