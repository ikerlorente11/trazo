package dev.pistation.trazo.engine

import kotlin.math.*
import kotlin.random.Random

/**
 * Turns input points (canvas px + pressure) into evenly spaced brush stamps: streamline smoothing,
 * pressure curve, taper, jitter. Pure Kotlin so it can be unit tested.
 */
class StrokeBuilder(
    private val brush: Brush,
    private val baseSize: Float,
    private val r: Float, private val g: Float, private val b: Float,
    private val pressureGamma: Float = 1f,
    seed: Int = 1,
) {
    /** Stamps not yet drawn: x, y, size, angle, r, g, b, alpha. */
    val pending = FloatArrayList()
    var minX = Float.MAX_VALUE; private set
    var minY = Float.MAX_VALUE; private set
    var maxX = -Float.MAX_VALUE; private set
    var maxY = -Float.MAX_VALUE; private set
    val isEmpty get() = minX > maxX

    private val rnd = Random(seed)
    private var started = false
    private var sx = 0f; private var sy = 0f; private var sp = 0f   // smoothed
    private var rawX = 0f; private var rawY = 0f; private var rawP = 0f
    private var carry = 0f
    private var travelled = 0f
    private var dirAngle = 0f

    fun add(x: Float, y: Float, pressure: Float) {
        rawX = x; rawY = y; rawP = pressure
        if (!started) {
            started = true
            sx = x; sy = y; sp = pressure
            emit(x, y, pressure)
            return
        }
        val k = 1f - brush.streamline.coerceIn(0f, 1f) * 0.9f
        step(sx + (x - sx) * k, sy + (y - sy) * k, sp + (pressure - sp) * min(1f, k * 1.5f))
    }

    /** Pulls the smoothed point onto the pen's last position. */
    fun finish() {
        if (!started) return
        repeat(8) {
            val nx = sx + (rawX - sx) * 0.5f
            val ny = sy + (rawY - sy) * 0.5f
            if (hypot(nx - sx, ny - sy) < 0.3f) return
            step(nx, ny, sp)
        }
    }

    private fun step(nx: Float, ny: Float, np: Float) {
        val dx = nx - sx; val dy = ny - sy
        val d = hypot(dx, dy)
        if (d < 1e-3f) { sp = np; return }
        dirAngle = atan2(dy, dx)
        var t = 0f
        var pos = carry
        while (true) {
            val p = sp + (np - sp) * t
            val gap = max(0.5f, brush.spacing * sizeAt(p))
            val need = gap - pos
            if (need > d * (1 - t)) { carry = pos + d * (1 - t); break }
            t += need / d
            pos = 0f
            travelled += need
            emit(sx + dx * t, sy + dy * t, sp + (np - sp) * t)
        }
        sx = nx; sy = ny; sp = np
    }

    private fun curve(p: Float) = p.coerceIn(0f, 1f).pow(brush.pressureGamma * pressureGamma)

    private fun sizeAt(p: Float): Float {
        val c = curve(p)
        val f = 1f + (brush.minSizeFraction + (1 - brush.minSizeFraction) * c - 1f) * brush.pressureSize
        var s = baseSize * f
        if (brush.taperStart > 0f) s *= (0.15f + 0.85f * min(1f, travelled / brush.taperStart))
        return max(0.5f, s)
    }

    private fun emit(x: Float, y: Float, p: Float) {
        val size0 = sizeAt(p)
        val alpha0 = brush.flow * (1f + (curve(p) - 1f) * brush.pressureOpacity)
        repeat(brush.count) {
            var px = x; var py = y; var size = size0; var alpha = alpha0
            if (brush.scatter > 0f) {
                val a = rnd.nextFloat() * 2 * PI.toFloat()
                val rr = rnd.nextFloat() * brush.scatter * size0
                px += cos(a) * rr; py += sin(a) * rr
            }
            if (brush.sizeJitter > 0f) size *= 1f - brush.sizeJitter * rnd.nextFloat()
            if (brush.opacityJitter > 0f) alpha *= 1f - brush.opacityJitter * rnd.nextFloat()
            var ang = brush.angle * PI.toFloat() / 180f
            if (brush.rotateWithStroke) ang += dirAngle
            if (brush.rotationJitter > 0f) ang += (rnd.nextFloat() - 0.5f) * 2 * PI.toFloat() * brush.rotationJitter
            pending.add(px, py, size, ang, r, g, b, alpha)
            val h = size * 0.75f
            minX = min(minX, px - h); minY = min(minY, py - h)
            maxX = max(maxX, px + h); maxY = max(maxY, py + h)
        }
    }
}

class FloatArrayList {
    var data = FloatArray(1024); private set
    var size = 0; private set

    fun add(vararg v: Float) {
        if (size + v.size > data.size) data = data.copyOf(max(data.size * 2, size + v.size))
        v.copyInto(data, size)
        size += v.size
    }

    fun clear() { size = 0 }
}
