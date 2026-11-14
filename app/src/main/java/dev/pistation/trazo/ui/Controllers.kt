package dev.pistation.trazo.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.*
import dev.pistation.trazo.engine.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.*

// ====================================================================== selection

enum class SelectKind(val label: String) { AUTO("Automática"), FREEHAND("A mano alzada"), RECT("Rectángulo"), ELLIPSE("Elipse") }

/** Draws selections on the canvas and hands them to the engine. */
class SelectionController(private val st: EditorState) : InputSink {
    var kind by mutableStateOf(SelectKind.FREEHAND)
    /** 0 replace, 1 add, 2 subtract. */
    var combine by mutableIntStateOf(0)
    var threshold by mutableFloatStateOf(0.15f)
    var fromCanvas by mutableStateOf(false)
    var feather by mutableFloatStateOf(0f)
    /** Shape being drawn, canvas coordinates (for the on-screen preview). */
    val path = mutableStateListOf<Offset2>()
    var dragStart: Offset2? by mutableStateOf(null)
    var dragEnd: Offset2? by mutableStateOf(null)
    private var downAt = Offset2(0f, 0f)
    private var moved = false

    override fun down(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float, stylus: Boolean) {
        downAt = Offset2(sx, sy); moved = false
        when (kind) {
            SelectKind.RECT, SelectKind.ELLIPSE -> { dragStart = Offset2(cx, cy); dragEnd = Offset2(cx, cy) }
            SelectKind.FREEHAND -> { path.add(Offset2(cx, cy)) }
            SelectKind.AUTO -> {}
        }
    }

    override fun move(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float) {
        if (hypot(sx - downAt.x, sy - downAt.y) > 10f) moved = true
        when (kind) {
            SelectKind.RECT, SelectKind.ELLIPSE -> dragEnd = Offset2(cx, cy)
            SelectKind.FREEHAND -> if (moved) path.add(Offset2(cx, cy))
            SelectKind.AUTO -> {}
        }
        st.viewVersion++
    }

    override fun up(sx: Float, sy: Float, cx: Float, cy: Float) {
        when (kind) {
            SelectKind.AUTO -> { val t = threshold; val fc = fromCanvas; val c = combine; st.gl { it.autoSelect(cx.toInt(), cy.toInt(), t, fc, c) } }
            SelectKind.RECT, SelectKind.ELLIPSE -> {
                val a = dragStart; val b = dragEnd
                dragStart = null; dragEnd = null
                if (a != null && b != null && abs(a.x - b.x) > 2 && abs(a.y - b.y) > 2) {
                    val p = Path()
                    val r = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                    if (kind == SelectKind.RECT) p.addRect(r, Path.Direction.CW) else p.addOval(r, Path.Direction.CW)
                    commit(p)
                }
            }
            SelectKind.FREEHAND -> {
                // A tap adds a polygon point; tapping the first point (or dragging) closes the shape.
                val first = path.firstOrNull()
                val closeTap = !moved && path.size > 2 && first != null && hypot(cx - first.x, cy - first.y) * st.zoom() < 30f
                if (moved || closeTap) {
                    if (path.size > 2) {
                        val p = Path(); p.moveTo(path[0].x, path[0].y)
                        for (q in path.drop(1)) p.lineTo(q.x, q.y)
                        p.close(); commit(p)
                    }
                    path.clear()
                }
            }
        }
        st.viewVersion++
    }

    override fun cancel() { path.clear(); dragStart = null; dragEnd = null }

    private fun commit(p: Path) {
        val w = st.meta.width; val h = st.meta.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(bmp).drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL })
        val buf = ByteBuffer.allocate(w * h)
        bmp.copyPixelsToBuffer(buf)
        var bytes = buf.array()
        val f = feather.toInt()
        if (f > 0) bytes = SelectionOps.blur(bytes, w, h, f)
        val c = combine
        st.gl { it.combineSelection(bytes, c) }
    }
}

data class Offset2(val x: Float, val y: Float)

// ====================================================================== transform

enum class TransformMode(val label: String) { FREE("Libre"), UNIFORM("Uniforme"), DISTORT("Distorsionar"), WARP("Deformar") }

/** Moves, scales, rotates, distorts or warps the lifted content; sends a mesh to the engine. */
class TransformController(private val st: EditorState, box: IntArray) : InputSink {
    var mode by mutableStateOf(TransformMode.UNIFORM)
    var nearest by mutableStateOf(false)
    private val bw = (box[2] - box[0]).toFloat()
    private val bh = (box[3] - box[1]).toFloat()
    // Affine form (free / uniform): centre, size (signed for flips), rotation.
    var cx = (box[0] + box[2]) / 2f; var cy = (box[1] + box[3]) / 2f
    var w = bw; var h = bh; var rot = 0f
    /** Corners TL, TR, BR, BL (distort). */
    var corners = FloatArray(8)
    /** 4×4 control points (warp). */
    var ctrl = FloatArray(32)
    var version by mutableIntStateOf(0)

    private var drag = -1          // handle index
    private var lastX = 0f; private var lastY = 0f

    init { cornersFromAffine(); send() }

    private fun cornersFromAffine() {
        val c = cos(rot); val s = sin(rot)
        val pts = listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f)
        pts.forEachIndexed { i, (u, v) ->
            val x = u * w; val y = v * h
            corners[i * 2] = cx + x * c - y * s; corners[i * 2 + 1] = cy + x * s + y * c
        }
    }

    private fun homography(): (Float, Float) -> FloatArray {
        val x0 = corners[0]; val y0 = corners[1]; val x1 = corners[2]; val y1 = corners[3]
        val x2 = corners[4]; val y2 = corners[5]; val x3 = corners[6]; val y3 = corners[7]
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
        val a: Float; val b: Float; val c: Float; val d: Float; val e: Float; val f: Float; val g: Float; val hh: Float
        if (abs(dx3) < 1e-4f && abs(dy3) < 1e-4f) {
            a = x1 - x0; b = x3 - x0; c = x0; d = y1 - y0; e = y3 - y0; f = y0; g = 0f; hh = 0f
        } else {
            val den = dx1 * dy2 - dx2 * dy1
            g = (dx3 * dy2 - dx2 * dy3) / den; hh = (dx1 * dy3 - dx3 * dy1) / den
            a = x1 - x0 + g * x1; b = x3 - x0 + hh * x3; c = x0
            d = y1 - y0 + g * y1; e = y3 - y0 + hh * y3; f = y0
        }
        return { u, v -> val z = g * u + hh * v + 1f; floatArrayOf((a * u + b * v + c) / z, (d * u + e * v + f) / z) }
    }

    private fun bern(i: Int, t: Float) = when (i) { 0 -> (1 - t).pow(3); 1 -> 3 * t * (1 - t).pow(2); 2 -> 3 * t * t * (1 - t); else -> t.pow(3) }

    fun mesh(n: Int): FloatArray {
        val out = FloatArray((n + 1) * (n + 1) * 2)
        val hmap = if (mode != TransformMode.WARP) homography() else null
        for (j in 0..n) for (i in 0..n) {
            val u = i / n.toFloat(); val v = j / n.toFloat()
            val o = (j * (n + 1) + i) * 2
            if (hmap != null) { val p = hmap(u, v); out[o] = p[0]; out[o + 1] = p[1] }
            else {
                var x = 0f; var y = 0f
                for (b in 0..3) for (a in 0..3) { val k = bern(a, u) * bern(b, v); x += ctrl[(b * 4 + a) * 2] * k; y += ctrl[(b * 4 + a) * 2 + 1] * k }
                out[o] = x; out[o + 1] = y
            }
        }
        return out
    }

    fun send() {
        if (mode == TransformMode.FREE || mode == TransformMode.UNIFORM) cornersFromAffine()
        val m = mesh(24); val nn = nearest
        st.gl { it.setTransformMesh(m, 24, nn) }
        version++
    }

    fun changeMode(m: TransformMode) {
        if (m == TransformMode.WARP && mode != TransformMode.WARP) {
            val hm = homography()
            for (j in 0..3) for (i in 0..3) { val p = hm(i / 3f, j / 3f); ctrl[(j * 4 + i) * 2] = p[0]; ctrl[(j * 4 + i) * 2 + 1] = p[1] }
        }
        if ((m == TransformMode.FREE || m == TransformMode.UNIFORM) && (mode == TransformMode.DISTORT || mode == TransformMode.WARP)) {
            // Back to a rectangle: keep the centre, average size and angle of the top edge.
            val q = if (mode == TransformMode.WARP) floatArrayOf(ctrl[0], ctrl[1], ctrl[6], ctrl[7], ctrl[30], ctrl[31], ctrl[24], ctrl[25]) else corners
            cx = (q[0] + q[2] + q[4] + q[6]) / 4; cy = (q[1] + q[3] + q[5] + q[7]) / 4
            w = hypot(q[2] - q[0], q[3] - q[1]); h = hypot(q[6] - q[0], q[7] - q[1]); rot = atan2(q[3] - q[1], q[2] - q[0])
        }
        mode = m; send()
    }

    fun flip(horizontal: Boolean) {
        when (mode) {
            TransformMode.FREE, TransformMode.UNIFORM -> if (horizontal) w = -w else h = -h
            TransformMode.DISTORT -> corners = if (horizontal) floatArrayOf(corners[2], corners[3], corners[0], corners[1], corners[6], corners[7], corners[4], corners[5])
                else floatArrayOf(corners[6], corners[7], corners[4], corners[5], corners[2], corners[3], corners[0], corners[1])
            TransformMode.WARP -> {
                val c = ctrl.copyOf()
                for (j in 0..3) for (i in 0..3) {
                    val si = if (horizontal) 3 - i else i; val sj = if (horizontal) j else 3 - j
                    ctrl[(j * 4 + i) * 2] = c[(sj * 4 + si) * 2]; ctrl[(j * 4 + i) * 2 + 1] = c[(sj * 4 + si) * 2 + 1]
                }
            }
        }
        send()
    }

    fun rotate45() {
        if (mode == TransformMode.FREE || mode == TransformMode.UNIFORM) { rot += (PI / 4).toFloat(); send() }
        else rotatePoints((PI / 4).toFloat())
    }

    private fun rotatePoints(a: Float) {
        val arr = if (mode == TransformMode.WARP) ctrl else corners
        val n = arr.size / 2
        val mx = (0 until n).map { arr[it * 2] }.average().toFloat(); val my = (0 until n).map { arr[it * 2 + 1] }.average().toFloat()
        for (i in 0 until n) { val x = arr[i * 2] - mx; val y = arr[i * 2 + 1] - my; arr[i * 2] = mx + x * cos(a) - y * sin(a); arr[i * 2 + 1] = my + x * sin(a) + y * cos(a) }
        send()
    }

    fun fitToCanvas() {
        changeMode(TransformMode.UNIFORM)
        val s = min(st.meta.width / abs(bw), st.meta.height / abs(bh))
        w = bw * s * sign(w); h = bh * s * sign(h); rot = 0f; cx = st.meta.width / 2f; cy = st.meta.height / 2f
        send()
    }

    fun reset() { mode = TransformMode.UNIFORM; cx = cx0; cy = cy0; w = bw; h = bh; rot = 0f; send() }
    private val cx0 = cx; private val cy0 = cy

    /** Handles on screen: (x, y) for corners, edges, rotation knob (affine) or points. */
    fun handles(): List<FloatArray> {
        val pts = ArrayList<FloatArray>()
        when (mode) {
            TransformMode.WARP -> for (k in 0 until 16) pts.add(st.toScreen(ctrl[k * 2], ctrl[k * 2 + 1]))
            else -> {
                for (k in 0..3) pts.add(st.toScreen(corners[k * 2], corners[k * 2 + 1]))
                if (mode != TransformMode.DISTORT) {
                    for (k in 0..3) { val a = k; val b = (k + 1) % 4
                        pts.add(st.toScreen((corners[a * 2] + corners[b * 2]) / 2, (corners[a * 2 + 1] + corners[b * 2 + 1]) / 2)) }
                    pts.add(rotKnob())
                }
            }
        }
        return pts
    }

    private fun rotKnob(): FloatArray {
        val tm = st.toScreen((corners[0] + corners[2]) / 2, (corners[1] + corners[3]) / 2)
        val c = st.toScreen(cx, cy)
        val dx = tm[0] - c[0]; val dy = tm[1] - c[1]; val l = max(1f, hypot(dx, dy))
        return floatArrayOf(tm[0] + dx / l * 60f, tm[1] + dy / l * 60f)
    }

    override fun down(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float, stylus: Boolean) {
        val hs = handles()
        drag = hs.indexOfFirst { hypot(it[0] - sx, it[1] - sy) < 44f }
        if (drag < 0) drag = 100   // move everything
        lastX = cx; lastY = cy
    }

    override fun move(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float) {
        val dx = cx - lastX; val dy = cy - lastY
        lastX = cx; lastY = cy
        when (mode) {
            TransformMode.WARP -> if (drag == 100) { for (k in 0 until 16) { ctrl[k * 2] += dx; ctrl[k * 2 + 1] += dy } }
                else { ctrl[drag * 2] += dx; ctrl[drag * 2 + 1] += dy }
            TransformMode.DISTORT -> if (drag == 100) { for (k in 0..3) { corners[k * 2] += dx; corners[k * 2 + 1] += dy } }
                else { corners[drag * 2] += dx; corners[drag * 2 + 1] += dy }
            else -> affineDrag(cx, cy, dx, dy)
        }
        send()
    }

    private fun affineDrag(px: Float, py: Float, dx: Float, dy: Float) {
        when (drag) {
            100 -> { cx += dx; cy += dy }
            8 -> rot = atan2(py - cy, px - cx) + (PI / 2).toFloat()
            in 0..3 -> {
                // Opposite corner stays put. Corner k sits at (u·w, v·h) from the centre, u, v = ±½.
                val opp = (drag + 2) % 4
                val ox = corners[opp * 2]; val oy = corners[opp * 2 + 1]
                val c = cos(rot); val s = sin(rot)
                val lx = (px - ox) * c + (py - oy) * s; val ly = -(px - ox) * s + (py - oy) * c
                val su = if (drag == 1 || drag == 2) 1f else -1f
                val sv = if (drag == 2 || drag == 3) 1f else -1f
                var nw = lx / su; var nh = ly / sv
                if (mode == TransformMode.UNIFORM) {
                    val k = max(nw / w, nh / h)
                    if (k < 0.02f) return
                    nw = w * k; nh = h * k
                }
                if (abs(nw) < 4f || abs(nh) < 4f) return
                w = nw; h = nh
                val dx2 = su * w / 2; val dy2 = sv * h / 2
                cx = ox + dx2 * c - dy2 * s; cy = oy + dx2 * s + dy2 * c
            }
            in 4..7 -> {
                // Edge: one dimension (both, keeping aspect, in uniform).
                val c = cos(rot); val s = sin(rot)
                val lx = (px - cx) * c + (py - cy) * s; val ly = -(px - cx) * s + (py - cy) * c
                when (drag) {
                    4, 6 -> { val nh = abs(ly) * 2; if (nh > 4) { if (mode == TransformMode.UNIFORM) w *= nh / abs(h); h = nh * sign(h) } }
                    5, 7 -> { val nw = abs(lx) * 2; if (nw > 4) { if (mode == TransformMode.UNIFORM) h *= nw / abs(w); w = nw * sign(w) } }
                }
            }
        }
    }

    override fun up(sx: Float, sy: Float, cx: Float, cy: Float) { drag = -1 }
}

// ====================================================================== liquify

class LiquifyController(private val st: EditorState) : InputSink {
    var mode by mutableStateOf(LiquifyMode.PUSH)
    var size by mutableFloatStateOf(180f)
    var strength by mutableFloatStateOf(0.6f)
    var pressureOn by mutableStateOf(true)
    private var lx = 0f; private var ly = 0f
    private var stylus = false

    override fun down(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float, stylus: Boolean) {
        lx = cx; ly = cy; this.stylus = stylus
        dab(cx, cy, pressure)
    }
    override fun move(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float) {
        // Several dabs along long moves.
        val d = hypot(cx - lx, cy - ly)
        val steps = max(1, (d / (size * 0.15f)).toInt())
        val x0 = lx; val y0 = ly
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            dab(x0 + (cx - x0) * t, y0 + (cy - y0) * t, pressure)
        }
    }
    private fun dab(x: Float, y: Float, pressure: Float) {
        val m = mode; val s = size; val k = strength; val p = if (pressureOn && stylus) pressure else 0.7f
        val px = lx; val py = ly
        st.gl { it.liquify(m, x, y, px, py, s, p, k) }
        lx = x; ly = y
    }
    override fun up(sx: Float, sy: Float, cx: Float, cy: Float) {}
}

// ====================================================================== drawing guides

enum class GuideType(val label: String) { NONE("Ninguna"), GRID("Cuadrícula 2D"), ISO("Isométrica"), PERSPECTIVE("Perspectiva"), SYMMETRY("Simetría") }
enum class SymKind(val label: String) { VERTICAL("Vertical"), HORIZONTAL("Horizontal"), QUADRANT("Cuadrante"), RADIAL("Radial") }

class GuidesController(private val st: EditorState) : InputSink, StrokeAssist {
    var type by mutableStateOf(GuideType.NONE)
    var assisted by mutableStateOf(false)
    var gridSize by mutableFloatStateOf(100f)
    var opacity by mutableFloatStateOf(0.6f)
    var thickness by mutableFloatStateOf(1.5f)
    var color by mutableIntStateOf(0xFF3A8DFF.toInt())
    var symKind by mutableStateOf(SymKind.VERTICAL)
    var radialN by mutableIntStateOf(8)
    var rotational by mutableStateOf(false)
    var editing by mutableStateOf(false)
    val vps = mutableStateListOf<Offset2>()
    var center by mutableStateOf(Offset2(0f, 0f))
    private var dragIdx = -1

    fun init(w: Int, h: Int) {
        if (center.x == 0f && center.y == 0f) center = Offset2(w / 2f, h / 2f)
        if (vps.isEmpty()) { vps.add(Offset2(w * 0.25f, h * 0.4f)); vps.add(Offset2(w * 0.75f, h * 0.4f)) }
    }

    /** Applies symmetry (when assisted) to the engine. */
    fun apply() {
        val list = if (type == GuideType.SYMMETRY && assisted) symmetryTransforms() else emptyList()
        st.gl { it.symmetry = list }
        st.view?.assist = if (assisted && type in listOf(GuideType.GRID, GuideType.ISO, GuideType.PERSPECTIVE)) this else null
    }

    private fun symmetryTransforms(): List<FloatArray> {
        val cx = center.x; val cy = center.y
        fun rot(a: Float): FloatArray { val c = cos(a); val s = sin(a); return floatArrayOf(c, -s, s, c, cx - c * cx + s * cy, cy - s * cx - c * cy) }
        val mirV = floatArrayOf(-1f, 0f, 0f, 1f, 2 * cx, 0f)
        val mirH = floatArrayOf(1f, 0f, 0f, -1f, 0f, 2 * cy)
        return when (symKind) {
            SymKind.VERTICAL -> listOf(if (rotational) rot(PI.toFloat()) else mirV)
            SymKind.HORIZONTAL -> listOf(if (rotational) rot(PI.toFloat()) else mirH)
            SymKind.QUADRANT -> if (rotational) listOf(rot((PI / 2).toFloat()), rot(PI.toFloat()), rot((3 * PI / 2).toFloat()))
                else listOf(mirV, mirH, rot(PI.toFloat()))
            SymKind.RADIAL -> {
                val out = ArrayList<FloatArray>()
                for (k in 1 until radialN) out.add(rot((2 * PI * k / radialN).toFloat()))
                if (!rotational) for (k in 0 until radialN) {
                    val r = rot((2 * PI * k / radialN).toFloat())
                    // r ∘ mirV
                    out.add(floatArrayOf(-r[0], r[1], -r[2], r[3], r[0] * 2 * cx + r[4], r[2] * 2 * cx + r[5]))
                }
                out
            }
        }
    }

    // ---- assisted drawing: lock the stroke to a guide direction
    private var sx0 = 0f; private var sy0 = 0f
    private var dir: FloatArray? = null
    private var anchor: Offset2? = null

    override fun begin(x: Float, y: Float) { sx0 = x; sy0 = y; dir = null; anchor = null }

    override fun map(x: Float, y: Float): FloatArray {
        val dx = x - sx0; val dy = y - sy0
        if (dir == null) {
            if (hypot(dx, dy) < 12f) return floatArrayOf(sx0, sy0)
            val cands = ArrayList<FloatArray>()
            when (type) {
                GuideType.GRID -> { cands.add(floatArrayOf(1f, 0f)); cands.add(floatArrayOf(0f, 1f)) }
                GuideType.ISO -> for (a in listOf(90.0, 30.0, 150.0)) { val r = Math.toRadians(a); cands.add(floatArrayOf(cos(r).toFloat(), sin(r).toFloat())) }
                GuideType.PERSPECTIVE -> {
                    for (v in vps) { val l = max(1f, hypot(v.x - sx0, v.y - sy0)); cands.add(floatArrayOf((v.x - sx0) / l, (v.y - sy0) / l)) }
                    cands.add(floatArrayOf(0f, 1f)); if (vps.size < 2) cands.add(floatArrayOf(1f, 0f))
                }
                else -> return floatArrayOf(x, y)
            }
            val l = hypot(dx, dy)
            dir = cands.maxByOrNull { abs((it[0] * dx + it[1] * dy) / l) }
        }
        val d = dir!!
        val t = dx * d[0] + dy * d[1]
        return floatArrayOf(sx0 + d[0] * t, sy0 + d[1] * t)
    }

    // ---- editing the vanishing points / symmetry centre
    override fun down(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float, stylus: Boolean) {
        val pts = if (type == GuideType.PERSPECTIVE) vps.toList() else listOf(center)
        dragIdx = pts.indexOfFirst { val s = st.toScreen(it.x, it.y); hypot(s[0] - sx, s[1] - sy) < 50f }
        if (dragIdx < 0 && type == GuideType.PERSPECTIVE && vps.size < 3) { vps.add(Offset2(cx, cy)); dragIdx = vps.lastIndex }
    }
    override fun move(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float) {
        if (dragIdx < 0) return
        if (type == GuideType.PERSPECTIVE) vps[dragIdx] = Offset2(cx, cy) else center = Offset2(cx, cy)
        apply(); st.viewVersion++
    }
    override fun up(sx: Float, sy: Float, cx: Float, cy: Float) { dragIdx = -1 }

    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("assisted", assisted).put("grid", gridSize.toDouble())
        .put("opacity", opacity.toDouble()).put("thickness", thickness.toDouble()).put("color", color).put("sym", symKind.name)
        .put("radialN", radialN).put("rotational", rotational).put("cx", center.x.toDouble()).put("cy", center.y.toDouble())
        .put("vps", JSONArray().apply { vps.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } })

    fun fromJson(o: JSONObject?) {
        o ?: return
        type = runCatching { GuideType.valueOf(o.optString("type")) }.getOrDefault(GuideType.NONE)
        assisted = o.optBoolean("assisted"); gridSize = o.optDouble("grid", 100.0).toFloat()
        opacity = o.optDouble("opacity", 0.6).toFloat(); thickness = o.optDouble("thickness", 1.5).toFloat()
        color = o.optInt("color", color); symKind = runCatching { SymKind.valueOf(o.optString("sym")) }.getOrDefault(SymKind.VERTICAL)
        radialN = o.optInt("radialN", 8); rotational = o.optBoolean("rotational")
        center = Offset2(o.optDouble("cx", 0.0).toFloat(), o.optDouble("cy", 0.0).toFloat())
        o.optJSONArray("vps")?.let { a -> vps.clear(); for (i in 0 until a.length()) { val p = a.getJSONArray(i); vps.add(Offset2(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())) } }
    }
}

// ====================================================================== clone source

class CloneSourceSink(private val st: EditorState) : InputSink {
    override fun down(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float, stylus: Boolean) = place(cx, cy)
    override fun move(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float) = place(cx, cy)
    override fun up(sx: Float, sy: Float, cx: Float, cy: Float) { place(cx, cy); st.placingClone = false; st.updateSink() }
    private fun place(x: Float, y: Float) { st.cloneSource = Offset2(x, y); st.cloneOffset = null; st.viewVersion++ }
}

/** Taps the canvas to place an adjustment's centre (perspective blur, chromatic aberration). */
class CenterSink(private val onPoint: (Float, Float) -> Unit) : InputSink {
    override fun down(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float, stylus: Boolean) = onPoint(cx, cy)
    override fun move(sx: Float, sy: Float, cx: Float, cy: Float, pressure: Float) = onPoint(cx, cy)
    override fun up(sx: Float, sy: Float, cx: Float, cy: Float) = onPoint(cx, cy)
}
