package dev.pistation.trazo.engine

import java.nio.ByteBuffer
import kotlin.math.*
import kotlin.random.Random

enum class Shape(val label: String) {
    ROUND_HARD("Redonda dura"), ROUND_SOFT("Redonda suave"), PENCIL("Grafito"), CHARCOAL("Carboncillo"),
    SPRAY("Spray"), FLAT("Plana"), BRISTLE("Cerdas"), SQUARE("Cuadrada")
}

enum class Grain(val label: String) { NONE("Sin grano"), PAPER("Papel"), CANVAS("Lienzo"), ROUGH("Rugoso"), NOISE("Ruido") }

/** A brush definition. Sizes are in canvas pixels; fractions in 0..1. */
data class Brush(
    val id: String,
    val name: String,
    val category: String,
    val shape: Shape = Shape.ROUND_HARD,
    val grain: Grain = Grain.NONE,
    val grainScale: Float = 256f,
    val grainDepth: Float = 0f,
    val spacing: Float = 0.08f,
    val sizeMin: Float = 2f,
    val sizeMax: Float = 120f,
    val minSizeFraction: Float = 0.15f,
    val pressureSize: Float = 1f,
    val pressureOpacity: Float = 0f,
    val pressureGamma: Float = 1f,
    val flow: Float = 1f,
    val streamline: Float = 0.25f,
    val scatter: Float = 0f,
    val sizeJitter: Float = 0f,
    val rotationJitter: Float = 0f,
    val opacityJitter: Float = 0f,
    val hueJitter: Float = 0f,
    val rotateWithStroke: Boolean = false,
    val angle: Float = 0f,
    val roundness: Float = 1f,
    val taperStart: Float = 0f,
    val smudge: Float = 0.6f,
    val count: Int = 1,
) {
    /** Size in canvas pixels for the sidebar's 0..1 slider. */
    fun sizeFor(slider: Float) = sizeMin + (sizeMax - sizeMin) * slider * slider
}

object BrushLibrary {
    val all: List<Brush> = listOf(
        // Bocetos
        Brush("pencil6b", "Lápiz 6B", "Bocetos", Shape.PENCIL, Grain.PAPER, 180f, 0.75f, 0.06f, 2f, 40f, 0.45f, 0.5f, 0.85f, 1.4f, 1f, 0.1f),
        Brush("pencilhb", "Lápiz HB", "Bocetos", Shape.PENCIL, Grain.PAPER, 120f, 0.6f, 0.06f, 1f, 16f, 0.6f, 0.3f, 0.9f, 1.6f, 0.9f, 0.1f),
        Brush("mechanical", "Portaminas", "Bocetos", Shape.ROUND_HARD, Grain.PAPER, 90f, 0.35f, 0.05f, 1f, 10f, 0.8f, 0.2f, 0.7f, 1.2f, 1f, 0.2f),
        // Entintado
        Brush("studiopen", "Pluma de estudio", "Entintado", Shape.ROUND_HARD, spacing = 0.04f, sizeMin = 1f, sizeMax = 60f, minSizeFraction = 0.05f, pressureSize = 1f, streamline = 0.45f, taperStart = 30f),
        Brush("techpen", "Rotulador técnico", "Entintado", Shape.ROUND_HARD, spacing = 0.04f, sizeMin = 1f, sizeMax = 40f, pressureSize = 0f, streamline = 0.55f),
        Brush("drypen", "Tinta seca", "Entintado", Shape.ROUND_HARD, Grain.ROUGH, 140f, 0.55f, 0.05f, 2f, 60f, 0.2f, 0.8f, 0.3f, 1f, 1f, 0.35f),
        Brush("gelpen", "Tinta gel", "Entintado", Shape.ROUND_HARD, spacing = 0.04f, sizeMin = 2f, sizeMax = 50f, minSizeFraction = 0.35f, pressureSize = 0.7f, streamline = 0.35f),
        // Pintura
        Brush("roundbrush", "Pincel redondo", "Pintura", Shape.ROUND_SOFT, spacing = 0.05f, sizeMin = 4f, sizeMax = 200f, minSizeFraction = 0.25f, pressureSize = 0.8f, pressureOpacity = 0.5f, flow = 0.8f, streamline = 0.2f),
        Brush("gouache", "Gouache", "Pintura", Shape.BRISTLE, Grain.CANVAS, 200f, 0.35f, 0.04f, 6f, 220f, 0.4f, 0.6f, 0.4f, 1f, 0.9f, 0.15f, rotateWithStroke = true),
        Brush("oil", "Óleo", "Pintura", Shape.BRISTLE, Grain.CANVAS, 260f, 0.5f, 0.03f, 8f, 260f, 0.5f, 0.5f, 0.3f, 1f, 1f, 0.1f, rotateWithStroke = true, roundness = 0.7f),
        Brush("flatbrush", "Pincel plano", "Pintura", Shape.FLAT, spacing = 0.03f, sizeMin = 6f, sizeMax = 220f, minSizeFraction = 0.6f, pressureSize = 0.4f, pressureOpacity = 0.6f, rotateWithStroke = true, roundness = 0.35f),
        Brush("watercolor", "Acuarela", "Pintura", Shape.ROUND_SOFT, Grain.PAPER, 300f, 0.5f, 0.05f, 10f, 300f, 0.6f, 0.4f, 0.6f, 1f, 0.25f, 0.2f),
        // Aerógrafo
        Brush("softair", "Aerógrafo suave", "Aerógrafo", Shape.ROUND_SOFT, spacing = 0.04f, sizeMin = 10f, sizeMax = 800f, minSizeFraction = 1f, pressureSize = 0f, pressureOpacity = 1f, flow = 0.12f, streamline = 0.1f),
        Brush("mediumair", "Aerógrafo medio", "Aerógrafo", Shape.ROUND_SOFT, spacing = 0.05f, sizeMin = 4f, sizeMax = 400f, minSizeFraction = 0.6f, pressureSize = 0.4f, pressureOpacity = 0.8f, flow = 0.3f, streamline = 0.1f),
        Brush("hardair", "Aerógrafo duro", "Aerógrafo", Shape.ROUND_HARD, spacing = 0.05f, sizeMin = 2f, sizeMax = 400f, minSizeFraction = 1f, pressureSize = 0f, pressureOpacity = 1f, flow = 0.5f, streamline = 0.15f),
        // Artístico
        Brush("charcoal", "Carboncillo", "Artístico", Shape.CHARCOAL, Grain.ROUGH, 220f, 0.7f, 0.06f, 6f, 150f, 0.5f, 0.4f, 0.7f, 1.2f, 0.9f, 0.1f, rotationJitter = 1f),
        Brush("chalk", "Tiza", "Artístico", Shape.CHARCOAL, Grain.PAPER, 160f, 0.85f, 0.07f, 6f, 150f, 0.6f, 0.3f, 0.6f, 1f, 1f, 0.1f, rotationJitter = 1f),
        Brush("pastel", "Pastel", "Artístico", Shape.SQUARE, Grain.CANVAS, 160f, 0.8f, 0.05f, 6f, 160f, 0.5f, 0.4f, 0.6f, 1f, 1f, 0.15f, rotationJitter = 0.3f),
        // Caligrafía
        Brush("monoline", "Monolínea", "Caligrafía", Shape.ROUND_HARD, spacing = 0.03f, sizeMin = 2f, sizeMax = 60f, pressureSize = 0f, streamline = 0.7f),
        Brush("script", "Caligrafía", "Caligrafía", Shape.ROUND_HARD, spacing = 0.03f, sizeMin = 2f, sizeMax = 80f, minSizeFraction = 0.08f, pressureSize = 1f, pressureGamma = 0.8f, streamline = 0.6f, taperStart = 20f),
        Brush("chisel", "Punta biselada", "Caligrafía", Shape.FLAT, spacing = 0.02f, sizeMin = 4f, sizeMax = 100f, minSizeFraction = 0.9f, pressureSize = 0.1f, streamline = 0.5f, angle = 45f, roundness = 0.18f),
        // Texturas y efectos
        Brush("splatter", "Salpicadura", "Texturas", Shape.SPRAY, spacing = 0.5f, sizeMin = 10f, sizeMax = 400f, minSizeFraction = 0.5f, pressureSize = 0.5f, scatter = 0.8f, sizeJitter = 0.6f, rotationJitter = 1f, opacityJitter = 0.3f, streamline = 0f),
        Brush("stipple", "Punteado", "Texturas", Shape.ROUND_HARD, spacing = 0.9f, sizeMin = 2f, sizeMax = 40f, minSizeFraction = 0.4f, scatter = 1.5f, sizeJitter = 0.7f, count = 3, streamline = 0f),
        Brush("noise", "Ruido", "Texturas", Shape.ROUND_SOFT, Grain.NOISE, 60f, 1f, 0.06f, 20f, 600f, 1f, 0f, 1f, 1f, 0.3f, 0.1f),
        Brush("blender", "Difuminador", "Texturas", Shape.ROUND_SOFT, spacing = 0.06f, sizeMin = 10f, sizeMax = 400f, minSizeFraction = 0.6f, pressureSize = 0.4f, pressureOpacity = 0f, smudge = 0.8f),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]
    val categories: List<String> get() = all.map { it.category }.distinct()
}

/** Procedural brush shapes (256²) and tileable grains (512²), single channel. */
object BrushTextures {
    const val SHAPE = 256
    const val GRAIN = 512

    fun shape(kind: Shape): ByteBuffer {
        val n = SHAPE
        val out = ByteBuffer.allocateDirect(n * n)
        val rnd = Random(kind.ordinal * 7919 + 1)
        val noise = valueNoise(n, 24, kind.ordinal + 3)
        val spray = if (kind == Shape.SPRAY) sprayDots(n, rnd) else null
        val bristles = if (kind == Shape.BRISTLE) FloatArray(n) { 0.55f + 0.45f * rnd.nextFloat() }.smooth(3) else null
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + 0.5f) / n * 2 - 1
            val dy = (y + 0.5f) / n * 2 - 1
            val r = sqrt(dx * dx + dy * dy)
            val nz = noise[y * n + x]
            val v = when (kind) {
                Shape.ROUND_HARD -> clamp01((1f - r) * n * 0.5f / 1.5f)
                Shape.ROUND_SOFT -> { val t = clamp01(1f - r); t * t * (3 - 2 * t) * t }
                Shape.PENCIL -> clamp01((1f - r) * 6f) * (0.35f + 0.65f * nz)
                Shape.CHARCOAL -> clamp01((1f - r * (0.8f + 0.4f * nz)) * 4f) * (0.5f + 0.5f * nz)
                Shape.SPRAY -> spray!![y * n + x]
                Shape.FLAT -> clamp01((1f - max(abs(dx), abs(dy) * 0.98f)) * 30f)
                Shape.BRISTLE -> {
                    val col = bristles!![x]
                    clamp01((1f - r) * 5f) * col * (0.7f + 0.3f * nz)
                }
                Shape.SQUARE -> clamp01((1f - max(abs(dx), abs(dy))) * 8f) * (0.6f + 0.4f * nz)
            }
            out.put((v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
        }
        out.position(0)
        return out
    }

    fun grain(kind: Grain): ByteBuffer {
        val n = GRAIN
        val out = ByteBuffer.allocateDirect(n * n)
        val a = valueNoise(n, 4, 11 + kind.ordinal)
        val b = valueNoise(n, 32, 29 + kind.ordinal)
        val c = valueNoise(n, 128, 47 + kind.ordinal)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            val v = when (kind) {
                Grain.NONE -> 1f
                Grain.PAPER -> clamp01((0.25f * a[i] + 0.35f * b[i] + 0.4f * c[i] - 0.25f) * 2.2f)
                Grain.CANVAS -> {
                    val wx = 0.5f + 0.5f * sin(x * 2f * PI.toFloat() / 8f)
                    val wy = 0.5f + 0.5f * sin(y * 2f * PI.toFloat() / 8f)
                    clamp01(0.35f + 0.45f * max(wx * (1 - wy * 0.5f), wy * (1 - wx * 0.5f)) + 0.2f * c[i])
                }
                Grain.ROUGH -> clamp01((0.5f * b[i] + 0.5f * c[i] - 0.3f) * 2.6f)
                Grain.NOISE -> if (c[i] > 0.55f) 1f else clamp01((c[i] - 0.35f) * 5f)
            }
            out.put((v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
        }
        out.position(0)
        return out
    }

    private fun sprayDots(n: Int, rnd: Random): FloatArray {
        val f = FloatArray(n * n)
        repeat(90) {
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            val rad = sqrt(rnd.nextFloat()) * 0.85f
            val cx = (0.5f + 0.5f * rad * cos(ang)) * n
            val cy = (0.5f + 0.5f * rad * sin(ang)) * n
            val rr = (1.5f + rnd.nextFloat() * 6f) * (1.2f - rad)
            for (y in max(0, (cy - rr - 2).toInt()) until min(n, (cy + rr + 2).toInt()))
                for (x in max(0, (cx - rr - 2).toInt()) until min(n, (cx + rr + 2).toInt())) {
                    val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
                    f[y * n + x] = max(f[y * n + x], clamp01(rr - d))
                }
        }
        return f
    }

    /** Tileable value noise with `cells` cells per side, values 0..1. */
    private fun valueNoise(n: Int, cells: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val g = FloatArray(cells * cells) { rnd.nextFloat() }
        val out = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val fx = x.toFloat() / n * cells
            val fy = y.toFloat() / n * cells
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val tx = smooth(fx - x0); val ty = smooth(fy - y0)
            fun at(i: Int, j: Int) = g[(j % cells) * cells + (i % cells)]
            val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
            val bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
            out[y * n + x] = top + (bot - top) * ty
        }
        return out
    }

    private fun FloatArray.smooth(r: Int) = FloatArray(size) { i ->
        var s = 0f; var c = 0
        for (k in -r..r) { val j = i + k; if (j in indices) { s += this[j]; c++ } }
        s / c
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
}
