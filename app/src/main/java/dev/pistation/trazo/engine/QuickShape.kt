package dev.pistation.trazo.engine

import kotlin.math.*

/**
 * QuickShape: turns a hand-drawn stroke into a clean line, ellipse, polygon or polyline.
 * The shape is kept in a form that can be resized while the pen stays down.
 */
sealed class QuickShape {
    abstract fun path(): FloatArray            // x, y pairs to redraw
    abstract fun dragTo(x: Float, y: Float)    // pen moved after the shape appeared
    abstract val label: String

    class Line(val x0: Float, val y0: Float, var x1: Float, var y1: Float) : QuickShape() {
        override val label = "Línea"
        override fun path(): FloatArray {
            val n = max(2, (hypot(x1 - x0, y1 - y0) / 2f).toInt())
            return FloatArray(n * 2 + 2) { i -> val t = (i / 2) / n.toFloat(); if (i % 2 == 0) x0 + (x1 - x0) * t else y0 + (y1 - y0) * t }
        }
        override fun dragTo(x: Float, y: Float) {
            // Snap to 15° steps when close.
            val ang = atan2(y - y0, x - x0); val len = hypot(x - x0, y - y0)
            val step = (PI / 12).toFloat()
            val snapped = round(ang / step) * step
            val a = if (abs(snapped - ang) < 0.045f) snapped else ang
            x1 = x0 + cos(a) * len; y1 = y0 + sin(a) * len
        }
    }

    class Ellipse(val cx: Float, val cy: Float, var rx: Float, var ry: Float, val angle: Float,
                  private val px: Float, private val py: Float) : QuickShape() {
        private val baseRx = rx; private val baseRy = ry
        private val baseD = max(1f, hypot(px - cx, py - cy))
        override val label get() = if (abs(rx - ry) < 0.5f) "Círculo" else "Elipse"
        override fun path(): FloatArray {
            val n = max(24, (PI * (rx + ry) / 2f).toInt())
            val ca = cos(angle); val sa = sin(angle)
            return FloatArray(n * 2 + 2) { i ->
                val t = (i / 2) * 2 * PI.toFloat() / n
                val ex = cos(t) * rx; val ey = sin(t) * ry
                if (i % 2 == 0) cx + ex * ca - ey * sa else cy + ex * sa + ey * ca
            }
        }
        override fun dragTo(x: Float, y: Float) {
            val k = hypot(x - cx, y - cy) / baseD
            rx = baseRx * k; ry = baseRy * k
        }
    }

    class Polygon(private val base: FloatArray, val closed: Boolean, private val px: Float, private val py: Float) : QuickShape() {
        private var pts = base.copyOf()
        private val cx = (0 until base.size / 2).map { base[it * 2] }.average().toFloat()
        private val cy = (0 until base.size / 2).map { base[it * 2 + 1] }.average().toFloat()
        private val baseD = max(1f, hypot(px - cx, py - cy))
        override val label get() = if (closed) when (base.size / 2) { 3 -> "Triángulo"; 4 -> "Cuadrilátero"; else -> "Polígono" } else "Polilínea"
        override fun path(): FloatArray {
            val out = ArrayList<Float>()
            val n = pts.size / 2
            val segs = if (closed) n else n - 1
            for (s in 0 until segs) {
                val ax = pts[s * 2]; val ay = pts[s * 2 + 1]
                val bx = pts[((s + 1) % n) * 2]; val by = pts[((s + 1) % n) * 2 + 1]
                val m = max(1, (hypot(bx - ax, by - ay) / 2f).toInt())
                for (i in 0 until m) { val t = i / m.toFloat(); out.add(ax + (bx - ax) * t); out.add(ay + (by - ay) * t) }
            }
            val last = if (closed) 0 else n - 1
            out.add(pts[last * 2]); out.add(pts[last * 2 + 1])
            return out.toFloatArray()
        }
        override fun dragTo(x: Float, y: Float) {
            val k = hypot(x - cx, y - cy) / baseD
            pts = FloatArray(base.size) { i -> if (i % 2 == 0) cx + (base[i] - cx) * k else cy + (base[i] - cy) * k }
        }
    }

    companion object {
        /** Recognises the stroke in `raw` (x, y, pressure triples). Null when nothing fits. */
        fun detect(raw: FloatArray, n: Int): QuickShape? {
            val count = n / 3
            if (count < 4) return null
            val xs = FloatArray(count) { raw[it * 3] }; val ys = FloatArray(count) { raw[it * 3 + 1] }
            var len = 0f
            for (i in 1 until count) len += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            if (len < 20f) return null
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            val diag = hypot(maxX - minX, maxY - minY)
            val endD = hypot(xs[count - 1] - xs[0], ys[count - 1] - ys[0])
            val px = xs[count - 1]; val py = ys[count - 1]

            // Line: every point close to the chord.
            if (endD > 0.75f * len) {
                var dev = 0f
                for (i in 0 until count) dev = max(dev, pointSegment(xs[i], ys[i], xs[0], ys[0], px, py))
                if (dev < max(6f, 0.06f * endD)) return Line(xs[0], ys[0], px, py)
            }
            val closed = endD < 0.22f * diag
            val simple = rdp(xs, ys, if (closed) 0.06f * diag else 0.05f * diag)
            if (closed) {
                // Drop the duplicate closing vertex.
                var verts = simple
                if (verts.size >= 4) {
                    val k = verts.size / 2
                    if (hypot(verts[0] - verts[(k - 1) * 2], verts[1] - verts[(k - 1) * 2 + 1]) < 0.22f * diag)
                        verts = verts.copyOf((k - 1) * 2)
                }
                verts = dropStraight(verts)
                val k = verts.size / 2
                if (k in 3..6 && sharpCorners(verts, closed = true)) return Polygon(verts, true, px, py)
                return fitEllipse(xs, ys, px, py)
            }
            val k = simple.size / 2
            if (k in 3..5 && sharpCorners(simple, closed = false)) return Polygon(simple, false, px, py)
            // An open arc that comes most of the way around: ellipse.
            if (endD < 0.5f * diag && len > 2.2f * diag) return fitEllipse(xs, ys, px, py)
            return null
        }

        private fun fitEllipse(xs: FloatArray, ys: FloatArray, px: Float, py: Float): Ellipse {
            val n = xs.size
            val cx = xs.average().toFloat(); val cy = ys.average().toFloat()
            var sxx = 0f; var syy = 0f; var sxy = 0f
            for (i in 0 until n) { val dx = xs[i] - cx; val dy = ys[i] - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
            sxx /= n; syy /= n; sxy /= n
            val ang = 0.5f * atan2(2 * sxy, sxx - syy)
            val ca = cos(ang); val sa = sin(ang)
            var a = 0f; var b = 0f
            for (i in 0 until n) {
                val dx = xs[i] - cx; val dy = ys[i] - cy
                val u = dx * ca + dy * sa; val v = -dx * sa + dy * ca
                a += u * u; b += v * v
            }
            var rx = sqrt(2f * a / n); var ry = sqrt(2f * b / n)
            if (min(rx, ry) / max(rx, ry) > 0.88f) { val r = (rx + ry) / 2; rx = r; ry = r }
            return Ellipse(cx, cy, rx, ry, ang, px, py)
        }

        /** Removes vertices of a closed polygon that sit on a nearly straight run. */
        private fun dropStraight(v0: FloatArray): FloatArray {
            var v = v0
            while (v.size / 2 > 3) {
                val k = v.size / 2
                var removed = false
                for (i in 0 until k) {
                    val a = (i - 1 + k) % k; val c = (i + 1) % k
                    val ux = v[a * 2] - v[i * 2]; val uy = v[a * 2 + 1] - v[i * 2 + 1]
                    val wx = v[c * 2] - v[i * 2]; val wy = v[c * 2 + 1] - v[i * 2 + 1]
                    val cosA = (ux * wx + uy * wy) / max(1e-3f, hypot(ux, uy) * hypot(wx, wy))
                    if (cosA < -0.9f) {
                        v = FloatArray(v.size - 2) { j -> if (j < i * 2) v[j] else v[j + 2] }
                        removed = true; break
                    }
                }
                if (!removed) break
            }
            return v
        }

        private fun sharpCorners(v: FloatArray, closed: Boolean): Boolean {
            val k = v.size / 2
            val range = if (closed) 0 until k else 1 until k - 1
            for (i in range) {
                val a = (i - 1 + k) % k; val c = (i + 1) % k
                val ux = v[a * 2] - v[i * 2]; val uy = v[a * 2 + 1] - v[i * 2 + 1]
                val wx = v[c * 2] - v[i * 2]; val wy = v[c * 2 + 1] - v[i * 2 + 1]
                val cosA = (ux * wx + uy * wy) / max(1e-3f, hypot(ux, uy) * hypot(wx, wy))
                if (cosA < -0.94f) return false   // nearly straight: not a real corner
            }
            return true
        }

        fun pointSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            if (l2 < 1e-6f) return hypot(x - ax, y - ay)
            val t = (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
            return hypot(x - (ax + t * dx), y - (ay + t * dy))
        }

        /** Ramer–Douglas–Peucker simplification; returns x, y pairs. */
        fun rdp(xs: FloatArray, ys: FloatArray, eps: Float): FloatArray {
            val keep = BooleanArray(xs.size)
            keep[0] = true; keep[xs.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to xs.size - 1)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                var best = -1; var dmax = 0f
                for (i in a + 1 until b) {
                    val d = pointSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                    if (d > dmax) { dmax = d; best = i }
                }
                if (best >= 0 && dmax > eps) { keep[best] = true; stack.addLast(a to best); stack.addLast(best to b) }
            }
            val out = ArrayList<Float>()
            for (i in xs.indices) if (keep[i]) { out.add(xs[i]); out.add(ys[i]) }
            return out.toFloatArray()
        }
    }
}
