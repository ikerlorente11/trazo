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

/** One stored layer, for rebuilding a document on a fresh GL context. */
class DocLayer(val id: Long, val props: LayerProps, val isGroup: Boolean, val parent: Long,
               val pixels: () -> ByteBuffer?, val mask: () -> ByteBuffer?)

/** What a document needs to be rebuilt on a fresh GL context. */
class DocSource(
    val width: Int, val height: Int,
    val bgColor: Int, val bgVisible: Boolean,
    val layers: List<DocLayer>,
    val activeId: Long,
    val model: Mesh? = null,
)

interface CanvasCallbacks : PaintEngine.Listener {
    fun onPick(color: Int?, screenX: Float, screenY: Float, done: Boolean)
    fun onGesture(kind: String)
    fun onReady()
    fun onView() {}
    fun onHover(x: Float, y: Float, visible: Boolean) {}
    fun onStrokeEnd() {}
}

/** Takes over one-pointer input (selection, transform, liquify, guides…). Coordinates: screen and canvas. */
interface InputSink {
    fun down(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float, stylus: Boolean)
    fun move(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float)
    fun up(sx: Float, sy: Float, cx: Float, cy: Float)
    fun cancel() {}
}

/** Bends input points while drawing (drawing guides' assisted drawing). Canvas coordinates. */
interface StrokeAssist {
    fun begin(x: Float, y: Float)
    fun map(x: Float, y: Float): FloatArray
}

/** Current tool settings, read when a stroke starts. */
data class ToolState(val tool: Tool, val brush: Brush, val size: Float, val opacity: Float, val color: Int,
                     val cloneOffset: FloatArray? = null)

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CanvasView(context: Context, private val cb: CanvasCallbacks) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile var engine: PaintEngine? = null; private set
    var source: (() -> DocSource)? = null
    var toolState: () -> ToolState = { ToolState(Tool.BRUSH, BrushLibrary.all[0], 10f, 1f, 0xFF000000.toInt()) }
    /** Tool state for a stroke starting at a canvas point (the clone tool needs it). */
    var toolStateAt: (Float, Float) -> ToolState = { _, _ -> toolState() }
    @Volatile var pressureMap: (Float) -> Float = { it }
    var fingerPaints = true
    var pickNext = false
    var quickShape = true
    var predict = true
    var sink: InputSink? = null
    var assist: StrokeAssist? = null
    @Volatile var uiBg = 0xFF2A2A2E.toInt()

    private val main = Handler(Looper.getMainLooper())
    private val view = Matrix()       // canvas -> screen
    private val inverse = Matrix()
    private var fitted = false
    @Volatile private var sw = 1
    @Volatile private var sh = 1

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
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
        for (l in src.layers) e.appendLoaded(l.id, l.props, l.isGroup, l.parent, l.pixels(), l.mask())
        if (src.layers.isNotEmpty() && e.layer(src.activeId) != null) e.setActive(src.activeId)
        e.finishLoading()
        src.model?.let { e.model3d = Model3D(it); e.show3d = true }
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
            cb.onView()
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
        e.render(sw, sh, pts, s, uiBg, System.currentTimeMillis())
        if (e.selection != null) main.postDelayed(antsTick, 90)
    }

    private val antsTick = Runnable { requestRender() }

    /** Runs on the GL thread with the engine, then redraws. */
    fun gl(block: (PaintEngine) -> Unit) {
        queueEvent { engine?.let(block) }
        requestRender()
    }

    private val in3d get() = engine?.let { it.model3d != null && it.show3d } == true

    // ------------------------------------------------------------------ view transform

    fun fit() {
        val e = engine ?: return
        if (sw <= 1) return
        val margin = 0.9f
        val s = min(sw * margin / e.width, sh * margin / e.height)
        synchronized(view) {
            view.reset()
            view.postScale(s, s)
            view.postTranslate((sw - e.width * s) / 2f, (sh - e.height * s) / 2f)
            updateInverse()
        }
        fitted = true
        cb.onView()
        requestRender()
    }

    /** Mirrors the view (not the picture). */
    fun flipView() {
        synchronized(view) { view.postScale(-1f, 1f, sw / 2f, sh / 2f); updateInverse() }
        cb.onView(); requestRender()
    }

    private fun updateInverse() { view.invert(inverse) }

    fun toCanvas(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        synchronized(view) { inverse.mapPoints(p) }
        return p
    }

    fun toScreen(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        synchronized(view) { view.mapPoints(p) }
        return p
    }

    /** Screen pixels per canvas pixel. */
    val zoom: Float get() = synchronized(view) { view.mapRadius(1f) }

    // ------------------------------------------------------------------ input

    private enum class Mode { NONE, STROKE, GESTURE, PICK, SINK }
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
    private var threeStartY = 0f
    private var scrubDir = 0; private var scrubFlips = 0; private var scrubTravel = 0f

    // QuickShape: hold still at the end of a stroke
    private var holdX = 0f; private var holdY = 0f
    private var shaped = false
    private val shapeRunnable = Runnable {
        if (mode != Mode.STROKE || shaped) return@Runnable
        gl { e -> e.quickShape()?.let { name -> shaped = true; main.post { cb.onMessage(name) } } }
    }

    // Prediction
    private val hist = FloatArray(9)  // x, y, t for the last 3 points (canvas)
    private var histN = 0
    private var last3d: FloatArray? = null

    private val holdRunnable = Runnable { startPick(downX, downY) }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        val stylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        if (!stylus) return super.onHoverEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> cb.onHover(ev.x, ev.y, true)
            MotionEvent.ACTION_HOVER_EXIT -> cb.onHover(ev.x, ev.y, false)
        }
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val idx = ev.actionIndex
        val stylus = ev.getToolType(idx).let { it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(ev)
                cb.onHover(0f, 0f, false)
                if (stylus) stylusSeen = true
                downX = ev.x; downY = ev.y
                if (pickNext) { pickNext = false; startPick(ev.x, ev.y); return true }
                val s = sink
                if (s != null) {
                    mode = Mode.SINK
                    strokeIsStylus = stylus
                    strokeStartTime = ev.eventTime
                    val c = toCanvas(ev.x, ev.y)
                    s.down(ev.x, ev.y, c[0], c[1], if (stylus) ev.pressure else 1f, stylus)
                    return true
                }
                if (stylus || (fingerPaints && !stylusSeen)) {
                    startStroke(ev, idx, stylus)
                    if (!stylus) main.postDelayed(holdRunnable, 450L)
                } else {
                    beginGesture(ev)
                    main.postDelayed(holdRunnable, 450L)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                when {
                    mode == Mode.SINK && !strokeIsStylus && ev.eventTime - strokeStartTime < 250 -> { sink?.cancel(); beginGesture(ev) }
                    mode == Mode.SINK -> {}
                    stylus && mode != Mode.STROKE -> {
                        // The pen wins over fingers.
                        main.removeCallbacks(holdRunnable)
                        mode = Mode.NONE
                        stylusSeen = true
                        startStroke(ev, idx, true)
                    }
                    mode == Mode.STROKE && !strokeIsStylus && ev.eventTime - strokeStartTime < 220 -> {
                        main.removeCallbacks(holdRunnable)
                        gl { it.cancelStroke() }
                        beginGesture(ev)
                    }
                    mode == Mode.GESTURE -> { main.removeCallbacks(holdRunnable); beginGesture(ev) }
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.STROKE -> strokeMove(ev)
                Mode.GESTURE -> gestureMove(ev)
                Mode.PICK -> pickAt(ev.getX(0), ev.getY(0), false)
                Mode.SINK -> { val c = toCanvas(ev.x, ev.y); sink?.move(ev.x, ev.y, c[0], c[1], if (strokeIsStylus) ev.pressure else 1f) }
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
                    Mode.SINK -> { val c = toCanvas(ev.x, ev.y); sink?.up(ev.x, ev.y, c[0], c[1]) }
                    Mode.NONE -> {}
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdRunnable); main.removeCallbacks(shapeRunnable)
                if (mode == Mode.STROKE) gl { it.cancelStroke() }
                if (mode == Mode.SINK) sink?.cancel()
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
        shaped = false
        histN = 0
        val pr = if (stylus) ev.getPressure(idx) else 1f
        val map = pressureMap
        val sx = ev.getX(idx); val sy = ev.getY(idx)
        holdX = sx; holdY = sy
        if (in3d) {
            val t = toolState()
            val w = sw; val h = sh
            gl { e ->
                val m = e.model3d ?: return@gl
                val uv = m.pick(sx, sy, w, h, e.frame.tex)
                if (e.beginStroke(t.tool, t.brush, t.size, t.opacity, t.color, map, t.cloneOffset) && uv != null) {
                    e.strokePoint(uv[0] * e.width, uv[1] * e.height, pr)
                    last3d = floatArrayOf(uv[0] * e.width, uv[1] * e.height)
                } else last3d = null
            }
            return
        }
        var p = toCanvas(sx, sy)
        assist?.let { it.begin(p[0], p[1]); p = it.map(p[0], p[1]) }
        val t = toolStateAt(p[0], p[1])
        pushHist(p[0], p[1], ev.eventTime.toFloat())
        gl {
            if (it.beginStroke(t.tool, t.brush, t.size, t.opacity, t.color, map, t.cloneOffset)) it.strokePoint(p[0], p[1], pr)
            else main.post { mode = Mode.NONE }
        }
        if (quickShape) main.postDelayed(shapeRunnable, 550)
    }

    private fun pushHist(x: Float, y: Float, t: Float) {
        if (histN == 3) { System.arraycopy(hist, 3, hist, 0, 6); histN = 2 }
        hist[histN * 3] = x; hist[histN * 3 + 1] = y; hist[histN * 3 + 2] = t; histN++
    }

    private fun strokeMove(ev: MotionEvent) {
        val i = ev.findPointerIndex(strokePointer)
        if (i < 0) return
        if (hypot(ev.getX(i) - downX, ev.getY(i) - downY) > 12f) main.removeCallbacks(holdRunnable)
        if (hypot(ev.getX(i) - holdX, ev.getY(i) - holdY) > 5f) {
            holdX = ev.getX(i); holdY = ev.getY(i)
            main.removeCallbacks(shapeRunnable)
            if (quickShape && !shaped) main.postDelayed(shapeRunnable, 550)
        }
        val n = ev.historySize
        if (in3d) {
            val xs = FloatArray(n + 1) { h -> if (h < n) ev.getHistoricalX(i, h) else ev.getX(i) }
            val ys = FloatArray(n + 1) { h -> if (h < n) ev.getHistoricalY(i, h) else ev.getY(i) }
            val ps = FloatArray(n + 1) { h -> if (!strokeIsStylus) 1f else if (h < n) ev.getHistoricalPressure(i, h) else ev.getPressure(i) }
            val w = sw; val hh = sh
            gl { e ->
                val m = e.model3d ?: return@gl
                for (k in 0..n) {
                    val uv = m.pick(xs[k], ys[k], w, hh, e.frame.tex) ?: continue
                    val cx = uv[0] * e.width; val cy = uv[1] * e.height
                    val l = last3d
                    if (l == null || hypot(cx - l[0], cy - l[1]) > 0.12f * max(e.width, e.height)) e.strokeJump(cx, cy, ps[k])
                    else e.strokePoint(cx, cy, ps[k])
                    last3d = floatArrayOf(cx, cy)
                }
            }
            return
        }
        val pts = FloatArray((n + 1) * 3)
        val a = assist
        for (h in 0..n) {
            val x = if (h < n) ev.getHistoricalX(i, h) else ev.getX(i)
            val y = if (h < n) ev.getHistoricalY(i, h) else ev.getY(i)
            val pr = if (!strokeIsStylus) 1f else if (h < n) ev.getHistoricalPressure(i, h) else ev.getPressure(i)
            var c = toCanvas(x, y)
            if (a != null) c = a.map(c[0], c[1])
            pts[h * 3] = c[0]; pts[h * 3 + 1] = c[1]; pts[h * 3 + 2] = pr
            pushHist(c[0], c[1], (if (h < n) ev.getHistoricalEventTime(h) else ev.eventTime).toFloat())
        }
        val pred = if (predict && histN >= 2 && !shaped) {
            val k = (histN - 1) * 3; val j = (histN - 2) * 3
            val dt = max(1f, hist[k + 2] - hist[j + 2])
            val vx = (hist[k] - hist[j]) / dt; val vy = (hist[k + 1] - hist[j + 1]) / dt
            val ahead = 18f  // ms
            floatArrayOf(hist[k] + vx * ahead, hist[k + 1] + vy * ahead)
        } else null
        gl { e ->
            for (k in 0..n) e.strokePoint(pts[k * 3], pts[k * 3 + 1], pts[k * 3 + 2])
            e.prediction = pred
        }
    }

    private fun endStroke() {
        main.removeCallbacks(holdRunnable); main.removeCallbacks(shapeRunnable)
        mode = Mode.NONE
        strokePointer = -1
        gl { it.endStroke(); main.post { cb.onStrokeEnd() } }
    }

    private fun beginGesture(ev: MotionEvent) {
        if (mode != Mode.GESTURE) {
            gestureStart = ev.eventTime
            gestureFingers = 0
            gestureMoved = 0f
            scrubDir = 0; scrubFlips = 0; scrubTravel = 0f
            gestureScaleStart = zoom
        }
        mode = Mode.GESTURE
        if (ev.pointerCount >= 3 && gestureFingers < 3) threeStartY = (0 until ev.pointerCount).map { ev.getY(it) }.average().toFloat()
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
        if (ev.pointerCount >= 3) {
            // Three-finger scrub = clear layer; swipe down = copy & paste menu.
            val dx = mx - lastMidX
            if (abs(dx) > 4f) {
                val d = if (dx > 0) 1 else -1
                if (scrubDir != 0 && d != scrubDir) scrubFlips++
                scrubDir = d; scrubTravel += abs(dx)
            }
        } else if (ev.pointerCount == 2) {
            if (in3d) {
                val m = engine?.model3d
                if (m != null) { m.orbit(mx - lastMidX, my - lastMidY); m.zoom(dist / lastDist) }
            } else synchronized(view) {
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
            cb.onView()
            requestRender()
        }
        lastMidX = mx; lastMidY = my; lastDist = dist; lastAngle = ang
    }

    private fun endGesture(ev: MotionEvent) {
        val dt = ev.eventTime - gestureStart
        if (gestureFingers == 3 && scrubFlips >= 3 && scrubTravel > 250f) { cb.onGesture("scrub"); return }
        if (gestureFingers == 3 && ev.y - threeStartY > 140f && dt < 900) { cb.onGesture("copyPaste"); return }
        if (dt < 300 && gestureMoved < 40f) {
            when (gestureFingers) {
                2 -> { gl { it.undo() }; cb.onGesture("undo") }
                3 -> { gl { it.redo() }; cb.onGesture("redo") }
                4 -> cb.onGesture("toggleUi")
            }
        } else if (!in3d && dt < 350 && gestureFingers == 2 && zoom < gestureScaleStart * 0.75f) {
            fit(); cb.onGesture("fit")
        }
    }

    private fun startPick(x: Float, y: Float) {
        if (mode == Mode.STROKE) gl { it.cancelStroke() }
        main.removeCallbacks(shapeRunnable)
        mode = Mode.PICK
        pickAt(x, y, false)
    }

    private fun pickAt(x: Float, y: Float, done: Boolean) {
        if (in3d) {
            val w = sw; val h = sh
            gl { e ->
                val uv = e.model3d?.pick(x, y, w, h, e.frame.tex)
                val col = uv?.let { e.pick((it[0] * e.width).toInt(), (it[1] * e.height).toInt()) }
                main.post { cb.onPick(col, x, y, done) }
            }
        } else {
            val c = toCanvas(x, y)
            gl { e ->
                val col = e.pick(c[0].toInt(), c[1].toInt())
                main.post { cb.onPick(col, x, y, done) }
            }
        }
        if (done) mode = Mode.NONE
    }

    /** ColorDrop: fill at a screen point. */
    fun fillAt(x: Float, y: Float, color: Int, threshold: Float, fromCanvas: Boolean) {
        val c = toCanvas(x, y)
        gl { it.floodFill(c[0].toInt(), c[1].toInt(), color, threshold, fromCanvas) }
    }
}
