package dev.pistation.trazo

import dev.pistation.trazo.engine.Brush
import dev.pistation.trazo.engine.StrokeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StrokeBuilderTest {
    private val round = Brush("t", "t", "t", spacing = 0.1f, pressureSize = 0f, streamline = 0f)

    @Test fun stampsAreEvenlySpaced() {
        val b = StrokeBuilder(round, 20f, 0f, 0f, 0f)
        b.add(0f, 0f, 1f)
        for (x in 1..100) b.add(x.toFloat(), 0f, 1f)
        b.finish()
        val d = b.pending.data
        val n = b.pending.size / 8
        assertEquals(51, n)   // one at the start + one every 2 px over 100 px
        for (i in 1 until n) assertEquals(2f, hypot(d[i * 8] - d[(i - 1) * 8], d[i * 8 + 1] - d[(i - 1) * 8 + 1]), 0.01f)
    }

    @Test fun pressureScalesSize() {
        val b = StrokeBuilder(round.copy(pressureSize = 1f, minSizeFraction = 0f), 40f, 0f, 0f, 0f)
        b.add(0f, 0f, 0.25f)
        assertEquals(10f, b.pending.data[2], 0.01f)
    }

    @Test fun streamlineEndsOnThePen() {
        val b = StrokeBuilder(round.copy(streamline = 0.9f), 10f, 0f, 0f, 0f)
        b.add(0f, 0f, 1f); b.add(50f, 0f, 1f); b.finish()
        val last = (b.pending.size / 8 - 1) * 8
        assertTrue(b.pending.data[last] > 48f)
    }
}
