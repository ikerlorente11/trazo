package dev.pistation.trazo.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** What a document needs to be rebuilt on a fresh GL context. */
class DocSource(
    val width: Int, val height: Int,
    val bgColor: Int, val bgVisible: Boolean,
    val layers: List<Triple<Long, LayerProps, () -> ByteBuffer?>>,
    val activeId: Long,
)

interface CanvasCallbacks : PaintEngine.Listener {
    fun onPick(color: Int?, screenX: Float, screenY: Float, done: Boolean)
    fun onGesture(kind: String)
    fun onReady()
}

/** Current tool settings, read when a stroke starts. */
data class ToolState(val tool: Tool, val brush: Brush, val size: Float, val opacity: Float, val color: Int)

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CanvasView(context: Context, private val cb: CanvasCallbacks) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile var engine: PaintEngine? = null; private set
    var source: (() -> DocSource)? = null
    var toolState: () -> ToolState = { ToolState(Tool.BRUSH, BrushLibrary.all[0], 10f, 1f, 0xFF000000.toInt()) }
    var pressureGamma = 1f
    var fingerPaints = true
    var pickNext = false
    var uiBg = 0xFF2A2A2E.toInt()

    private val main = Handler(Looper.getMainLooper())
    private val view = Matrix()       // canvas -> screen
    private val inverse = Matrix()
    private var fitted = false
    private var sw = 1; private var sh = 1

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ------------------------------------------------------------------ GL thread

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: anything from an old one is gone, rebuild the document from its source.
        engine = null
        val src = source?.invoke() ?: return
        val e = PaintEngine(src.width, src.height, cb)
        e.setBackgroundRaw(src.bgColor, src.bgVisible)
        if (src.layers.isEmpty()) e.insertLayerRaw(e.newLayer("Capa 1"), 0)
        else for ((id, props, pixels) in src.layers) e.appendLoaded(id, props, pixels())
        if (src.layers.isNotEmpty()) e.setActive(if (e.layer(src.activeId) != null) src.activeId else e.layers.last().id)
        e.notifyLayers(true)
        cb.onHistory(false, false)
        engine = e
        main.post { cb.onReady() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val ow = sw; val oh = sh
        sw = width; sh = height
        main.post {
            if (!fitted) fit() else synchronized(view) { view.postTranslate((width - ow) / 2f, (height - oh) / 2f); updateInverse() }
            requestRender()
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val e = engine ?: return
        val pts = FloatArray(8)
        val s: Float
        synchronized(view) {
            view.mapPoints(pts, floatArrayOf(0f, 0f, e.width.toFloat(), 0f, 0f, e.height.toFloat(), e.width.toFloat(), e.height.toFloat()))
            s = view.mapRadius(1f)
        }
        for (i in 0 until 4) {
            pts[i * 2] = pts[i * 2] / sw * 2f - 1f
            pts[i * 2 + 1] = 1f - pts[i * 2 + 1] / sh * 2f
        }
        e.render(sw, sh, pts, s, uiBg)
    }

    /** Runs on the GL thread with the engine, then redraws. */
    fun gl(block: (PaintEngine) -> Unit) {
        queueEvent { engine?.let(block) }
        requestRender()
    }

    // ------------------------------------------------------------------ view transform

    fun fit() {
        val e = engine ?: return
        if (sw <= 1) return
        val margin = 0.92f
        val s = min(sw * margin / e.width, sh * margin / e.height)
        synchronized(view) {
            view.reset()
            view.postScale(s, s)
            view.postTranslate((sw - e.width * s) / 2f, (sh - e.height * s) / 2f)
            updateInverse()
        }
        fitted = true
        requestRender()
    }

    private fun updateInverse() { view.invert(inverse) }

    fun toCanvas(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        synchronized(view) { inverse.mapPoints(p) }
        return p
    }

    // ------------------------------------------------------------------ input

    private enum class Mode { NONE, STROKE, GESTURE, PICK }
    private var mode = Mode.NONE
    private var strokePointer = -1
    private var strokeIsStylus = false
    private var strokeStartTime = 0L
    private var stylusSeen = false

    private var gestureStart = 0L
    private var gestureFingers = 0
    private var gestureMoved = 0f
    private var gestureScaleStart = 1f
    private var lastMidX = 0f; private var lastMidY = 0f
    private var lastDist = 0f; private var lastAngle = 0f
    private var downX = 0f; private var downY = 0f

    private val holdRunnable = Runnable { startPick(downX, downY) }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val idx = ev.actionIndex
        val stylus = ev.getToolType(idx).let { it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(ev)
                if (stylus) stylusSeen = true
                downX = ev.x; downY = ev.y
                if (pickNext) { pickNext = false; startPick(ev.x, ev.y); return true }
                if (stylus || (fingerPaints && !stylusSeen)) {
                    startStroke(ev, idx, stylus)
                    if (!stylus) main.postDelayed(holdRunnable, 450L)
                } else {
                    beginGesture(ev)
                    main.postDelayed(holdRunnable, 450L)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (stylus && mode != Mode.STROKE) {
                    // The pen wins over fingers.
                    main.removeCallbacks(holdRunnable)
                    mode = Mode.NONE
                    stylusSeen = true
                    startStroke(ev, idx, true)
                } else if (mode == Mode.STROKE && !strokeIsStylus && ev.eventTime - strokeStartTime < 220) {
                    main.removeCallbacks(holdRunnable)
                    gl { it.cancelStroke() }
                    beginGesture(ev)
                } else if (mode == Mode.GESTURE) {
                    main.removeCallbacks(holdRunnable)
                    beginGesture(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.STROKE -> strokeMove(ev)
                Mode.GESTURE -> gestureMove(ev)
                Mode.PICK -> pickAt(ev.getX(0), ev.getY(0), false)
                Mode.NONE -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.STROKE && ev.getPointerId(idx) == strokePointer) endStroke()
                else if (mode == Mode.GESTURE) resetGestureAnchor(ev, idx)
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(holdRunnable)
                when (mode) {
                    Mode.STROKE -> { strokeMove(ev); endStroke() }
                    Mode.GESTURE -> endGesture(ev)
                    Mode.PICK -> pickAt(ev.x, ev.y, true)
                    Mode.NONE -> {}
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdRunnable)
                if (mode == Mode.STROKE) gl { it.cancelStroke() }
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun startStroke(ev: MotionEvent, idx: Int, stylus: Boolean) {
        mode = Mode.STROKE
        strokePointer = ev.getPointerId(idx)
        strokeIsStylus = stylus
        strokeStartTime = ev.eventTime
        val t = toolState()
        val p = toCanvas(ev.getX(idx), ev.getY(idx))
        val pr = if (stylus) ev.getPressure(idx) else 1f
        val gamma = pressureGamma
        gl {
            it.beginStroke(t.tool, t.brush, t.size, t.opacity, t.color, gamma)
            it.strokePoint(p[0], p[1], pr)
        }
    }

    private fun strokeMove(ev: MotionEvent) {
        val i = ev.findPointerIndex(strokePointer)
        if (i < 0) return
        if (hypot(ev.getX(i) - downX, ev.getY(i) - downY) > 12f) main.removeCallbacks(holdRunnable)
        val n = ev.historySize
        val pts = FloatArray((n + 1) * 3)
        for (h in 0..n) {
            val x = if (h < n) ev.getHistoricalX(i, h) else ev.getX(i)
            val y = if (h < n) ev.getHistoricalY(i, h) else ev.getY(i)
            val pr = if (!strokeIsStylus) 1f else if (h < n) ev.getHistoricalPressure(i, h) else ev.getPressure(i)
            val c = toCanvas(x, y)
            pts[h * 3] = c[0]; pts[h * 3 + 1] = c[1]; pts[h * 3 + 2] = pr
        }
        gl { e -> for (k in 0..n) e.strokePoint(pts[k * 3], pts[k * 3 + 1], pts[k * 3 + 2]) }
    }

    private fun endStroke() {
        main.removeCallbacks(holdRunnable)
        mode = Mode.NONE
        strokePointer = -1
        gl { it.endStroke() }
    }

    private fun beginGesture(ev: MotionEvent) {
        if (mode != Mode.GESTURE) {
            gestureStart = ev.eventTime
            gestureFingers = 0
            gestureMoved = 0f
            gestureScaleStart = synchronized(view) { view.mapRadius(1f) }
        }
        mode = Mode.GESTURE
        gestureFingers = max(gestureFingers, ev.pointerCount)
        resetGestureAnchor(ev, -1)
    }

    private fun resetGestureAnchor(ev: MotionEvent, skip: Int) {
        val ids = (0 until ev.pointerCount).filter { it != skip }.take(2)
        if (ids.size < 2) { lastDist = 0f; if (ids.size == 1) { lastMidX = ev.getX(ids[0]); lastMidY = ev.getY(ids[0]) }; return }
        val (a, b) = ids
        lastMidX = (ev.getX(a) + ev.getX(b)) / 2; lastMidY = (ev.getY(a) + ev.getY(b)) / 2
        lastDist = hypot(ev.getX(b) - ev.getX(a), ev.getY(b) - ev.getY(a))
        lastAngle = atan2(ev.getY(b) - ev.getY(a), ev.getX(b) - ev.getX(a))
    }

    private fun gestureMove(ev: MotionEvent) {
        gestureFingers = max(gestureFingers, ev.pointerCount)
        if (ev.pointerCount < 2) {
            if (hypot(ev.x - downX, ev.y - downY) > 12f) main.removeCallbacks(holdRunnable)
            return
        }
        main.removeCallbacks(holdRunnable)
        val mx = (ev.getX(0) + ev.getX(1)) / 2; val my = (ev.getY(0) + ev.getY(1)) / 2
        val dist = hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))
        val ang = atan2(ev.getY(1) - ev.getY(0), ev.getX(1) - ev.getX(0))
        if (lastDist <= 0f) { resetGestureAnchor(ev, -1); return }
        gestureMoved += hypot(mx - lastMidX, my - lastMidY) + abs(dist - lastDist)
        if (ev.pointerCount == 2) {
            synchronized(view) {
                val cur = view.mapRadius(1f)
                var k = dist / lastDist
                k = k.coerceIn(0.02f / cur, 64f / cur)
                view.postTranslate(mx - lastMidX, my - lastMidY)
                view.postScale(k, k, mx, my)
                var da = ang - lastAngle
                if (da > PI) da -= (2 * PI).toFloat() else if (da < -PI) da += (2 * PI).toFloat()
                view.postRotate(Math.toDegrees(da.toDouble()).toFloat(), mx, my)
                updateInverse()
            }
            requestRender()
        }
        lastMidX = mx; lastMidY = my; lastDist = dist; lastAngle = ang
    }

    private fun endGesture(ev: MotionEvent) {
        val dt = ev.eventTime - gestureStart
        val scaleNow = synchronized(view) { view.mapRadius(1f) }
        if (dt < 300 && gestureMoved < 40f) {
            when (gestureFingers) {
                2 -> { gl { it.undo() }; cb.onGesture("undo") }
                3 -> { gl { it.redo() }; cb.onGesture("redo") }
                4 -> cb.onGesture("toggleUi")
            }
        } else if (dt < 350 && gestureFingers == 2 && scaleNow < gestureScaleStart * 0.75f) {
            fit(); cb.onGesture("fit")
        }
    }

    private fun startPick(x: Float, y: Float) {
        if (mode == Mode.STROKE) gl { it.cancelStroke() }
        mode = Mode.PICK
        pickAt(x, y, false)
    }

    private fun pickAt(x: Float, y: Float, done: Boolean) {
        val c = toCanvas(x, y)
        gl { e ->
            val col = e.pick(c[0].toInt(), c[1].toInt())
            main.post { cb.onPick(col, x, y, done) }
        }
        if (done) mode = Mode.NONE
    }

    /** ColorDrop: fill at a screen point. */
    fun fillAt(x: Float, y: Float, color: Int, threshold: Float, fromCanvas: Boolean) {
        val c = toCanvas(x, y)
        gl { it.floodFill(c[0].toInt(), c[1].toInt(), color, threshold, fromCanvas) }
    }
}
