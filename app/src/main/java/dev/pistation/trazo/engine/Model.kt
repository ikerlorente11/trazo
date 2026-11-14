package dev.pistation.trazo.engine

import java.nio.ByteBuffer

enum class BlendMode(val label: String, val code: Int, val group: Int) {
    NORMAL("Normal", 0, 0),
    DARKEN("Oscurecer", 4, 1), MULTIPLY("Multiplicar", 1, 1), COLOR_BURN("Subexponer color", 7, 1),
    LINEAR_BURN("Subexponer lineal", 18, 1), DARKER_COLOR("Color más oscuro", 20, 1),
    LIGHTEN("Aclarar", 5, 2), SCREEN("Trama", 2, 2), COLOR_DODGE("Sobreexponer color", 6, 2),
    ADD("Añadir", 12, 2), LIGHTER_COLOR("Color más claro", 21, 2),
    OVERLAY("Superponer", 3, 3), SOFT_LIGHT("Luz suave", 9, 3), HARD_LIGHT("Luz fuerte", 8, 3),
    DIFFERENCE("Diferencia", 10, 4), EXCLUSION("Exclusión", 11, 4), SUBTRACT("Restar", 13, 4), DIVIDE("Dividir", 19, 4),
    HUE("Tono", 14, 5), SATURATION("Saturación", 15, 5), COLOR("Color", 16, 5), LUMINOSITY("Luminosidad", 17, 5);

    val short: String get() = when (this) {
        NORMAL -> "N"; MULTIPLY -> "M"; SCREEN -> "T"; OVERLAY -> "S"; ADD -> "A"; COLOR -> "C"
        else -> label.take(2)
    }
}

enum class Tool { BRUSH, SMUDGE, ERASER }

data class LayerProps(
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.NORMAL,
    val alphaLock: Boolean = false,
    val clip: Boolean = false,
)

class Layer(val id: Long, var props: LayerProps, var target: RenderTarget)

/** What the UI shows of a layer. */
data class LayerInfo(val id: Long, val props: LayerProps, val thumb: android.graphics.Bitmap?)

/** One undoable step. Everything runs on the GL thread. */
sealed class Edit {
    abstract val bytes: Long
    abstract fun undo(e: PaintEngine)
    abstract fun redo(e: PaintEngine)
    /** Frees what only this edit still holds (a removed layer's texture). */
    open fun dispose(e: PaintEngine, applied: Boolean) {}

    class Pixels(val layerId: Long, val x: Int, val y: Int, val w: Int, val h: Int,
                 val before: ByteBuffer, val after: ByteBuffer) : Edit() {
        override val bytes get() = before.capacity().toLong() * 2
        override fun undo(e: PaintEngine) { e.layer(layerId)?.target?.write(x, y, w, h, before); e.touched(layerId) }
        override fun redo(e: PaintEngine) { e.layer(layerId)?.target?.write(x, y, w, h, after); e.touched(layerId) }
    }

    class AddLayer(val layer: Layer, val index: Int) : Edit() {
        override val bytes get() = layer.target.bytes
        override fun undo(e: PaintEngine) = e.removeLayerRaw(layer)
        override fun redo(e: PaintEngine) = e.insertLayerRaw(layer, index)
        override fun dispose(e: PaintEngine, applied: Boolean) { if (!applied && !e.contains(layer)) layer.target.release() }
    }

    class RemoveLayer(val layer: Layer, val index: Int) : Edit() {
        override val bytes get() = layer.target.bytes
        override fun undo(e: PaintEngine) = e.insertLayerRaw(layer, index)
        override fun redo(e: PaintEngine) = e.removeLayerRaw(layer)
        override fun dispose(e: PaintEngine, applied: Boolean) { if (applied && !e.contains(layer)) layer.target.release() }
    }

    class Props(val layerId: Long, val before: LayerProps, val after: LayerProps) : Edit() {
        override val bytes get() = 64L
        override fun undo(e: PaintEngine) = e.setPropsRaw(layerId, before)
        override fun redo(e: PaintEngine) = e.setPropsRaw(layerId, after)
    }

    class Move(val from: Int, val to: Int) : Edit() {
        override val bytes get() = 16L
        override fun undo(e: PaintEngine) = e.moveRaw(to, from)
        override fun redo(e: PaintEngine) = e.moveRaw(from, to)
    }

    class Background(val before: Pair<Int, Boolean>, val after: Pair<Int, Boolean>) : Edit() {
        override val bytes get() = 16L
        override fun undo(e: PaintEngine) = e.setBackgroundRaw(before.first, before.second)
        override fun redo(e: PaintEngine) = e.setBackgroundRaw(after.first, after.second)
    }

    class Group(val edits: List<Edit>) : Edit() {
        override val bytes get() = edits.sumOf { it.bytes }
        override fun undo(e: PaintEngine) = edits.asReversed().forEach { it.undo(e) }
        override fun redo(e: PaintEngine) = edits.forEach { it.redo(e) }
        override fun dispose(e: PaintEngine, applied: Boolean) = edits.forEach { it.dispose(e, applied) }
    }
}

class UndoStack(private val budget: Long = 600L shl 20, private val maxSteps: Int = 250) {
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
