package dev.pistation.trazo.engine

import android.opengl.GLES30.*
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Triangle mesh: positions, normals, uvs (per vertex) and indices. */
class Mesh(val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray, val idx: IntArray)

object Meshes {
    val builtIn = listOf("sphere" to "Esfera", "cube" to "Cubo", "cylinder" to "Cilindro", "torus" to "Toro", "plane" to "Plano")

    fun byName(name: String): Mesh = when (name) {
        "cube" -> cube(); "cylinder" -> cylinder(); "torus" -> torus(); "plane" -> plane(); else -> sphere()
    }

    private class B { val p = ArrayList<Float>(); val n = ArrayList<Float>(); val t = ArrayList<Float>(); val i = ArrayList<Int>()
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float): Int {
            p += x; p += y; p += z; n += nx; n += ny; n += nz; t += u; t += w; return p.size / 3 - 1
        }
        fun tri(a: Int, b: Int, c: Int) { i += a; i += b; i += c }
        fun mesh() = Mesh(p.toFloatArray(), n.toFloatArray(), t.toFloatArray(), i.toIntArray())
    }

    fun sphere(seg: Int = 96, rings: Int = 48): Mesh {
        val b = B()
        for (r in 0..rings) for (s in 0..seg) {
            val v = r / rings.toFloat(); val u = s / seg.toFloat()
            val th = v * PI.toFloat(); val ph = u * 2 * PI.toFloat()
            val x = sin(th) * sin(ph); val y = cos(th); val z = sin(th) * cos(ph)
            b.v(x, y, z, x, y, z, u, v)
        }
        for (r in 0 until rings) for (s in 0 until seg) {
            val a = r * (seg + 1) + s; val c = a + seg + 1
            b.tri(a, c, a + 1); b.tri(a + 1, c, c + 1)
        }
        return b.mesh()
    }

    /** Cube unwrapped as a cross in a 4×3 grid. */
    fun cube(): Mesh {
        val b = B()
        // face: normal, u axis, v axis, cell (col,row)
        val faces = listOf(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, -1f, 0f, 2f, 1f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, -1f, 0f, 3f, 1f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f, 1f, 2f),
        )
        for (f in faces) {
            val base = b.p.size / 3
            for ((cu, cv) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                val su = cu * 2 - 1; val sv = cv * 2 - 1
                val x = f[0] + f[3] * su + f[6] * sv; val y = f[1] + f[4] * su + f[7] * sv; val z = f[2] + f[5] * su + f[8] * sv
                b.v(x * 0.75f, y * 0.75f, z * 0.75f, f[0], f[1], f[2], (f[9] + cu) / 4f, (f[10] + cv) / 3f)
            }
            b.tri(base, base + 2, base + 1); b.tri(base + 1, base + 2, base + 3)
        }
        return b.mesh()
    }

    fun cylinder(seg: Int = 96): Mesh {
        val b = B()
        for (s in 0..seg) for (k in 0..1) {
            val u = s / seg.toFloat(); val a = u * 2 * PI.toFloat()
            val x = sin(a) * 0.7f; val z = cos(a) * 0.7f
            b.v(x, if (k == 0) 0.9f else -0.9f, z, sin(a), 0f, cos(a), u, 0.25f + k * 0.5f)
        }
        for (s in 0 until seg) { val a = s * 2; b.tri(a, a + 1, a + 2); b.tri(a + 2, a + 1, a + 3) }
        for ((y, ny, v0) in listOf(Triple(0.9f, 1f, 0f), Triple(-0.9f, -1f, 0.75f))) {
            val c = b.v(0f, y, 0f, 0f, ny, 0f, 0.5f, v0 + 0.125f)
            val start = b.p.size / 3
            for (s in 0..seg) { val a = s / seg.toFloat() * 2 * PI.toFloat()
                b.v(sin(a) * 0.7f, y, cos(a) * 0.7f, 0f, ny, 0f, 0.5f + sin(a) * 0.12f, v0 + 0.125f + cos(a) * 0.12f) }
            for (s in 0 until seg) if (ny > 0) b.tri(c, start + s, start + s + 1) else b.tri(c, start + s + 1, start + s)
        }
        return b.mesh()
    }

    fun torus(seg: Int = 96, ring: Int = 48, r1: Float = 0.62f, r2: Float = 0.28f): Mesh {
        val b = B()
        for (i in 0..seg) for (j in 0..ring) {
            val u = i / seg.toFloat(); val v = j / ring.toFloat()
            val a = u * 2 * PI.toFloat(); val c = v * 2 * PI.toFloat()
            val nx = cos(c) * sin(a); val ny = sin(c); val nz = cos(c) * cos(a)
            b.v((r1 + r2 * cos(c)) * sin(a), r2 * sin(c), (r1 + r2 * cos(c)) * cos(a), nx, ny, nz, u, v)
        }
        for (i in 0 until seg) for (j in 0 until ring) {
            val a = i * (ring + 1) + j; val c = a + ring + 1
            b.tri(a, a + 1, c); b.tri(a + 1, c + 1, c)
        }
        return b.mesh()
    }

    fun plane(): Mesh {
        val b = B()
        b.v(-1f, 1f, 0f, 0f, 0f, 1f, 0f, 0f); b.v(1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f)
        b.v(-1f, -1f, 0f, 0f, 0f, 1f, 0f, 1f); b.v(1f, -1f, 0f, 0f, 0f, 1f, 1f, 1f)
        b.tri(0, 2, 1); b.tri(1, 2, 3)
        return b.mesh()
    }

    /** Wavefront OBJ with texture coordinates; normals are computed if missing. Centered, fit to radius 1. */
    fun parseObj(text: String): Mesh {
        val vs = ArrayList<FloatArray>(); val vts = ArrayList<FloatArray>(); val vns = ArrayList<FloatArray>()
        val b = B()
        val cache = HashMap<String, Int>()
        var hasNormals = true
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "v" -> vs += floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                "vt" -> vts += floatArrayOf(parts[1].toFloat(), parts.getOrNull(2)?.toFloat() ?: 0f)
                "vn" -> vns += floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                "f" -> {
                    val ids = parts.drop(1).map { key ->
                        cache.getOrPut(key) {
                            val s = key.split('/')
                            fun ix(str: String?, size: Int) = str?.takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 0) size + it else it - 1 }
                            val p = vs[ix(s[0], vs.size)!!]
                            val t = ix(s.getOrNull(1), vts.size)?.let { vts[it] } ?: floatArrayOf(0f, 0f)
                            val n = ix(s.getOrNull(2), vns.size)?.let { vns[it] }
                            if (n == null) hasNormals = false
                            b.v(p[0], p[1], p[2], n?.get(0) ?: 0f, n?.get(1) ?: 0f, n?.get(2) ?: 1f, t[0], 1f - t[1])
                        }
                    }
                    for (k in 1 until ids.size - 1) b.tri(ids[0], ids[k], ids[k + 1])
                }
            }
        }
        require(vts.isNotEmpty()) { "El modelo no tiene coordenadas de textura (UV)" }
        val m = b.mesh()
        if (!hasNormals) computeNormals(m)
        // Center and scale to radius 1.
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        val n = m.pos.size / 3
        for (i in 0 until n) { cx += m.pos[i * 3]; cy += m.pos[i * 3 + 1]; cz += m.pos[i * 3 + 2] }
        cx /= n; cy /= n; cz /= n
        var r = 1e-6
        for (i in 0 until n) r = max(r, sqrt((m.pos[i * 3] - cx).pow(2) + (m.pos[i * 3 + 1] - cy).pow(2) + (m.pos[i * 3 + 2] - cz).pow(2)))
        for (i in 0 until n) {
            m.pos[i * 3] = ((m.pos[i * 3] - cx) / r).toFloat(); m.pos[i * 3 + 1] = ((m.pos[i * 3 + 1] - cy) / r).toFloat(); m.pos[i * 3 + 2] = ((m.pos[i * 3 + 2] - cz) / r).toFloat()
        }
        return m
    }

    private fun computeNormals(m: Mesh) {
        m.nrm.fill(0f)
        for (t in 0 until m.idx.size / 3) {
            val a = m.idx[t * 3]; val b = m.idx[t * 3 + 1]; val c = m.idx[t * 3 + 2]
            val ux = m.pos[b * 3] - m.pos[a * 3]; val uy = m.pos[b * 3 + 1] - m.pos[a * 3 + 1]; val uz = m.pos[b * 3 + 2] - m.pos[a * 3 + 2]
            val vx = m.pos[c * 3] - m.pos[a * 3]; val vy = m.pos[c * 3 + 1] - m.pos[a * 3 + 1]; val vz = m.pos[c * 3 + 2] - m.pos[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { m.nrm[v * 3] += nx; m.nrm[v * 3 + 1] += ny; m.nrm[v * 3 + 2] += nz }
        }
    }
}

/** A mesh painted with the canvas as its texture; orbit camera; screen -> UV picking. GL thread only. */
class Model3D(mesh: Mesh) {
    private val vbo: Int
    private val ibo: Int
    private val count = mesh.idx.size
    private val program = Program(Shaders.VS_MODEL, Shaders.FS_MODEL)
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.35f
    @Volatile var dist = 4.4f
    private val mvp = FloatArray(16)
    private val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var pickFbo = 0; private var pickTex = 0; private var pickDepth = 0
    private var pickW = 0; private var pickH = 0
    private var pickValid = false
    private var lastCam = FloatArray(3)

    init {
        val n = mesh.pos.size / 3
        val inter = ByteBuffer.allocateDirect(n * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until n) {
            inter.put(mesh.pos, i * 3, 3)
            val nx = mesh.nrm[i * 3]; val ny = mesh.nrm[i * 3 + 1]; val nz = mesh.nrm[i * 3 + 2]
            val l = max(1e-6f, sqrt(nx * nx + ny * ny + nz * nz))
            inter.put(nx / l); inter.put(ny / l); inter.put(nz / l)
            inter.put(mesh.uv, i * 2, 2)
        }
        inter.position(0)
        val ids = IntArray(2); glGenBuffers(2, ids, 0); vbo = ids[0]; ibo = ids[1]
        glBindBuffer(GL_ARRAY_BUFFER, vbo); glBufferData(GL_ARRAY_BUFFER, n * 32, inter, GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(mesh.idx).also { it.position(0) }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo); glBufferData(GL_ELEMENT_ARRAY_BUFFER, count * 4, ib, GL_STATIC_DRAW)
    }

    private fun matrices(w: Int, h: Int) {
        val proj = FloatArray(16); val view = FloatArray(16)
        Matrix.perspectiveM(proj, 0, 40f, w.toFloat() / h, 0.1f, 50f)
        val ex = dist * cos(pitch) * sin(yaw); val ey = dist * sin(pitch); val ez = dist * cos(pitch) * cos(yaw)
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    }

    private fun drawMesh(tex: Int, pick: Boolean, bg: Int) {
        program.use()
        glUniformMatrix4fv(program.u("uMvp"), 1, false, mvp, 0)
        glUniformMatrix4fv(program.u("uModel"), 1, false, model, 0)
        program.tex("uTex", 0, tex)
        glUniform3f(program.u("uBase"), ((bg shr 16) and 255) / 255f, ((bg shr 8) and 255) / 255f, (bg and 255) / 255f)
        glUniform1i(program.u("uPick"), if (pick) 1 else 0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 32, 12)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, false, 32, 24)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL)
        glClear(GL_DEPTH_BUFFER_BIT)
        glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0)
        glDisable(GL_DEPTH_TEST)
        glDisableVertexAttribArray(1); glDisableVertexAttribArray(2)
    }

    fun draw(tex: Int, w: Int, h: Int, bg: Int) {
        matrices(w, h)
        glBindTexture(GL_TEXTURE_2D, tex)
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        drawMesh(tex, false, bg)
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        val cam = floatArrayOf(yaw, pitch, dist)
        if (!cam.contentEquals(lastCam) || w != pickW || h != pickH) { pickValid = false; lastCam = cam }
    }

    /** UV (0..1) under a screen point, or null if the model isn't there. */
    fun pick(x: Float, y: Float, w: Int, h: Int, tex: Int): FloatArray? {
        if (!pickValid || w != pickW || h != pickH) {
            if (w != pickW || h != pickH) allocPick(w, h)
            glBindFramebuffer(GL_FRAMEBUFFER, pickFbo)
            glViewport(0, 0, w, h)
            glClearColor(0f, 0f, 0f, 0f); glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            matrices(w, h)
            drawMesh(tex, true, 0)
            pickValid = true
        }
        glBindFramebuffer(GL_FRAMEBUFFER, pickFbo)
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        val px = x.toInt().coerceIn(0, w - 1); val py = (h - 1 - y.toInt()).coerceIn(0, h - 1)
        glReadPixels(px, py, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val r = buf.get(0).toInt() and 255; val g = buf.get(1).toInt() and 255
        val b = buf.get(2).toInt() and 255; val a = buf.get(3).toInt() and 255
        if (r == 0 && g == 0 && b == 0 && a == 0) return null
        return floatArrayOf((r * 256 + g) / 65535f, (b * 256 + a) / 65535f)
    }

    private fun allocPick(w: Int, h: Int) {
        if (pickFbo != 0) { glDeleteFramebuffers(1, intArrayOf(pickFbo), 0); glDeleteTextures(1, intArrayOf(pickTex), 0); glDeleteRenderbuffers(1, intArrayOf(pickDepth), 0) }
        val t = IntArray(1)
        glGenTextures(1, t, 0); pickTex = t[0]
        glBindTexture(GL_TEXTURE_2D, pickTex); glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glGenRenderbuffers(1, t, 0); pickDepth = t[0]
        glBindRenderbuffer(GL_RENDERBUFFER, pickDepth); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h)
        glGenFramebuffers(1, t, 0); pickFbo = t[0]
        glBindFramebuffer(GL_FRAMEBUFFER, pickFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, pickTex, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, pickDepth)
        pickW = w; pickH = h; pickValid = false
    }

    fun orbit(dx: Float, dy: Float) {
        yaw -= dx * 0.008f
        pitch = (pitch + dy * 0.008f).coerceIn(-1.45f, 1.45f)
    }

    fun zoom(k: Float) { dist = (dist / k).coerceIn(1.4f, 12f) }

    fun release() {
        glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        if (pickFbo != 0) { glDeleteFramebuffers(1, intArrayOf(pickFbo), 0); glDeleteTextures(1, intArrayOf(pickTex), 0); glDeleteRenderbuffers(1, intArrayOf(pickDepth), 0) }
    }
}
