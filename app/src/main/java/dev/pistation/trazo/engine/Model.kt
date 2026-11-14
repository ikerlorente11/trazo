package dev.pistation.trazo.engine

import java.nio.ByteBuffer

enum class BlendMode(val label: String, val code: Int, val group: Int, val psd: String) {
    NORMAL("Normal", 0, 0, "norm"),
    DARKEN("Oscurecer", 4, 1, "dark"), MULTIPLY("Multiplicar", 1, 1, "mul "), COLOR_BURN("Subexponer color", 7, 1, "idiv"),
    LINEAR_BURN("Subexponer lineal", 18, 1, "lbrn"), DARKER_COLOR("Color más oscuro", 20, 1, "dkCl"),
    LIGHTEN("Aclarar", 5, 2, "lite"), SCREEN("Trama", 2, 2, "scrn"), COLOR_DODGE("Sobreexponer color", 6, 2, "div "),
    ADD("Añadir", 12, 2, "lddg"), LIGHTER_COLOR("Color más claro", 21, 2, "lgCl"),
    OVERLAY("Superponer", 3, 3, "over"), SOFT_LIGHT("Luz suave", 9, 3, "sLit"), HARD_LIGHT("Luz fuerte", 8, 3, "hLit"),
    DIFFERENCE("Diferencia", 10, 4, "diff"), EXCLUSION("Exclusión", 11, 4, "smud"), SUBTRACT("Restar", 13, 4, "fsub"), DIVIDE("Dividir", 19, 4, "fdiv"),
    HUE("Tono", 14, 5, "hue "), SATURATION("Saturación", 15, 5, "sat "), COLOR("Color", 16, 5, "colr"), LUMINOSITY("Luminosidad", 17, 5, "lum ");

    val short: String get() = when (this) {
        NORMAL -> "N"; MULTIPLY -> "M"; SCREEN -> "T"; OVERLAY -> "S"; ADD -> "A"; COLOR -> "C"
        else -> label.take(2)
    }

    companion object {
        fun fromPsd(key: String) = entries.firstOrNull { it.psd == key } ?: NORMAL
    }
}

enum class Tool { BRUSH, SMUDGE, ERASER, CLONE }

data class LayerProps(
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.NORMAL,
    val alphaLock: Boolean = false,
    val clip: Boolean = false,
    /** ColorDrop uses this layer as the reference for fills. */
    val reference: Boolean = false,
    /** Groups: folded in the layers panel. */
    val collapsed: Boolean = false,
    val maskEnabled: Boolean = true,
)

/**
 * A layer or a group. Groups own no pixels (a 1×1 placeholder target); their children sit directly below
 * them in the list, contiguous, with `parent` = the group's id. One level of grouping.
 */
class Layer(val id: Long, var props: LayerProps, var target: RenderTarget, val isGroup: Boolean = false) {
    var parent: Long = 0L
    /** Layer mask: white shows, black hides (red channel). */
    var mask: RenderTarget? = null
}

/** What the UI shows of a layer. */
data class LayerInfo(
    val id: Long, val props: LayerProps, val thumb: android.graphics.Bitmap?,
    val isGroup: Boolean = false, val parent: Long = 0L, val hasMask: Boolean = false, val maskThumb: android.graphics.Bitmap? = null,
)

/** Single-channel canvas-sized mask (the selection), kept on the CPU and mirrored in an R8 texture. */
class Mask8(val width: Int, val height: Int, val bytes: ByteArray = ByteArray(width * height)) {
    var tex = 0; private set

    fun upload() {
        val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.position(0) }
        if (tex == 0) {
            tex = makeTexture(width, height, buf, single = true, repeat = false, mipmaps = false)
        } else {
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, tex)
            android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT, 1)
            android.opengl.GLES30.glTexSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
                android.opengl.GLES30.GL_RED, android.opengl.GLES30.GL_UNSIGNED_BYTE, buf)
        }
    }

    fun release() { if (tex != 0) android.opengl.GLES30.glDeleteTextures(1, intArrayOf(tex), 0); tex = 0 }

    /** Bounding box of selected pixels (x0, y0, x1, y1), or null if empty. */
    fun bounds(): IntArray? {
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) if (bytes[row + x].toInt() != 0) {
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }
}

/** Order + grouping of the document, for structural undo. */
data class Node(val layer: Layer, val parent: Long)

/** One undoable step. Everything runs on the GL thread. */
sealed class Edit {
    abstract val bytes: Long
    abstract fun undo(e: PaintEngine)
    abstract fun redo(e: PaintEngine)
    /** Frees what only this edit still holds; `applied` = the edit is in effect (evicted from the undo side). */
    open fun dispose(e: PaintEngine, applied: Boolean) {}

    class Pixels(val layerId: Long, val mask: Boolean, val x: Int, val y: Int, val w: Int, val h: Int,
                 val before: ByteBuffer, val after: ByteBuffer) : Edit() {
        override val bytes get() = before.capacity().toLong() * 2
        private fun target(e: PaintEngine) = e.layer(layerId)?.let { if (mask) it.mask else it.target }
        override fun undo(e: PaintEngine) { target(e)?.write(x, y, w, h, before); e.touched(layerId) }
        override fun redo(e: PaintEngine) { target(e)?.write(x, y, w, h, after); e.touched(layerId) }
    }

    /** Any change of which layers exist, their order or grouping. */
    class Structure(val before: List<Node>, val after: List<Node>, val activeBefore: Long, val activeAfter: Long) : Edit() {
        override val bytes get() = (after.map { it.layer } - before.map { it.layer }.toSet()).sumOf { it.target.bytes } +
            (before.map { it.layer } - after.map { it.layer }.toSet()).sumOf { it.target.bytes }
        override fun undo(e: PaintEngine) = e.restoreStructure(before, activeBefore)
        override fun redo(e: PaintEngine) = e.restoreStructure(after, activeAfter)
        override fun dispose(e: PaintEngine, applied: Boolean) {
            val gone = if (applied) before.map { it.layer } - after.map { it.layer }.toSet()
            else after.map { it.layer } - before.map { it.layer }.toSet()
            gone.filter { !e.contains(it) }.forEach { it.target.release(); it.mask?.release() }
        }
    }

    class Props(val layerId: Long, val before: LayerProps, val after: LayerProps) : Edit() {
        override val bytes get() = 64L
        override fun undo(e: PaintEngine) = e.setPropsRaw(layerId, before)
        override fun redo(e: PaintEngine) = e.setPropsRaw(layerId, after)
    }

    class MaskSet(val layerId: Long, val before: RenderTarget?, val after: RenderTarget?) : Edit() {
        override val bytes get() = (before?.bytes ?: 0) + (after?.bytes ?: 0)
        override fun undo(e: PaintEngine) = e.setMaskRaw(layerId, before)
        override fun redo(e: PaintEngine) = e.setMaskRaw(layerId, after)
        override fun dispose(e: PaintEngine, applied: Boolean) {
            val gone = if (applied) before else after
            if (gone != null && e.layers.none { it.mask === gone }) gone.release()
        }
    }

    class Background(val before: Pair<Int, Boolean>, val after: Pair<Int, Boolean>) : Edit() {
        override val bytes get() = 16L
        override fun undo(e: PaintEngine) = e.setBackgroundRaw(before.first, before.second)
        override fun redo(e: PaintEngine) = e.setBackgroundRaw(after.first, after.second)
    }

    class Selection(val before: ByteArray?, val after: ByteArray?) : Edit() {
        override val bytes get() = (before?.size ?: 0).toLong() + (after?.size ?: 0)
        override fun undo(e: PaintEngine) = e.setSelectionRaw(before)
        override fun redo(e: PaintEngine) = e.setSelectionRaw(after)
    }

    class Group(val edits: List<Edit>) : Edit() {
        override val bytes get() = edits.sumOf { it.bytes }
        override fun undo(e: PaintEngine) = edits.asReversed().forEach { it.undo(e) }
        override fun redo(e: PaintEngine) = edits.forEach { it.redo(e) }
        override fun dispose(e: PaintEngine, applied: Boolean) = edits.forEach { it.dispose(e, applied) }
    }
}

class UndoStack(private val budget: Long = 700L shl 20, private val maxSteps: Int = 250) {
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(edit: Edit, e: PaintEngine) {
        redo.forEach { it.dispose(e, false) }
        redo.clear()
        undo.addLast(edit)
        var total = undo.sumOf { it.bytes }
        while (undo.size > 1 && (undo.size > maxSteps || total > budget)) {
            val old = undo.removeFirst()
            total -= old.bytes
            old.dispose(e, true)
        }
    }

    fun undo(e: PaintEngine): Boolean {
        val x = undo.removeLastOrNull() ?: return false
        x.undo(e); redo.addLast(x); return true
    }

    fun redo(e: PaintEngine): Boolean {
        val x = redo.removeLastOrNull() ?: return false
        x.redo(e); undo.addLast(x); return true
    }

    fun clear(e: PaintEngine) {
        undo.forEach { it.dispose(e, true) }; redo.forEach { it.dispose(e, false) }
        undo.clear(); redo.clear()
    }
}
