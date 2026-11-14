package dev.pistation.trazo

import dev.pistation.trazo.data.Lzo
import dev.pistation.trazo.engine.QuickShape
import dev.pistation.trazo.ui.Curves
import dev.pistation.trazo.ui.Offset2
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class EngineLogicTest {
    private fun raw(pts: List<Pair<Float, Float>>): FloatArray = pts.flatMap { listOf(it.first, it.second, 1f) }.toFloatArray()

    @Test fun wobblyLineBecomesLine() {
        val pts = (0..100).map { i -> i * 5f to 200f + sin(i * 0.7f) * 3f }
        val r = raw(pts)
        assertTrue(QuickShape.detect(r, r.size) is QuickShape.Line)
    }

    @Test fun roughCircleBecomesCircle() {
        val pts = (0..120).map { i -> val a = i * 2 * PI.toFloat() / 115; (500f + cos(a) * (200f + sin(i * 1.3f) * 6f)) to (500f + sin(a) * (200f + cos(i * 0.9f) * 6f)) }
        val r = raw(pts)
        val s = QuickShape.detect(r, r.size)
        assertTrue(s is QuickShape.Ellipse)
        assertEquals("Círculo", s!!.label)
    }

    @Test fun squareBecomesQuadrilateral() {
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0..40) pts.add(100f + i * 10 to 100f)
        for (i in 0..40) pts.add(500f to 100f + i * 10)
        for (i in 0..40) pts.add(500f - i * 10 to 500f)
        for (i in 0..38) pts.add(100f to 500f - i * 10)
        val r = raw(pts)
        val s = QuickShape.detect(r, r.size)
        assertTrue("got $s", s is QuickShape.Polygon)
        assertEquals("Cuadrilátero", s!!.label)
    }

    @Test fun lzoLiteralsOnly() {
        val src = byteArrayOf(21, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(), 17, 0, 0)
        assertEquals("abcd", String(Lzo.decompress(src, 4)))
    }

    @Test fun lzoShortMatch() {
        // "abcd" + M2 match (distance 4, length 4) + end of stream.
        val src = byteArrayOf(21, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(), 108, 0, 17, 0, 0)
        assertEquals("abcdabcd", String(Lzo.decompress(src, 8)))
    }

    @Test fun identityCurve() {
        val t = Curves.table(listOf(Offset2(0f, 0f), Offset2(1f, 1f)))
        assertEquals(0, t[0]); assertEquals(255, t[255]); assertTrue(abs(t[128] - 128) <= 2)
    }
}
