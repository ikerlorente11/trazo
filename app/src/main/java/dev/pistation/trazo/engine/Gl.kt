package dev.pistation.trazo.engine

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A texture with its framebuffer: every layer, buffer and cache is one of these. All premultiplied RGBA8. */
class RenderTarget(val width: Int, val height: Int, mipmaps: Boolean = false) {
    val tex: Int
    val fbo: Int
    val levels = if (mipmaps) 1 + floor(log2(maxOf(width, height).toDouble())).toInt() else 1

    init {
        val t = IntArray(1)
        glGenTextures(1, t, 0); tex = t[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, width, height)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (mipmaps) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenFramebuffers(1, t, 0); fbo = t[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "FBO incompleto" }
        clear()
    }

    val bytes: Long get() = width.toLong() * height * 4 * (if (levels > 1) 4 else 3) / 3

    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, width, height)
    }

    fun clear(r: Float = 0f, g: Float = 0f, b: Float = 0f, a: Float = 0f) {
        bind()
        glDisable(GL_SCISSOR_TEST)
        glClearColor(r, g, b, a)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    /** Reads a rect (canvas coordinates, y down = texture rows) as RGBA premultiplied. */
    fun read(x: Int, y: Int, w: Int, h: Int, into: ByteBuffer? = null): ByteBuffer {
        val buf = into ?: ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        buf.position(0)
        bind()
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        return buf
    }

    fun write(x: Int, y: Int, w: Int, h: Int, data: ByteBuffer) {
        data.position(0)
        glBindTexture(GL_TEXTURE_2D, tex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, data)
        data.position(0)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(tex), 0)
    }

    private fun log2(v: Double) = Math.log(v) / Math.log(2.0)
    private fun floor(v: Double) = Math.floor(v)
}

class Program(vs: String, fs: String) {
    val id: Int = glCreateProgram()
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GL_VERTEX_SHADER, vs)
        val f = compile(GL_FRAGMENT_SHADER, fs)
        glAttachShader(id, v); glAttachShader(id, f)
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + glGetProgramInfoLog(id) }
        glDeleteShader(v); glDeleteShader(f)
    }

    fun use() = glUseProgram(id)
    fun u(name: String) = locs.getOrPut(name) { glGetUniformLocation(id, name) }
    fun tex(name: String, unit: Int, tex: Int) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(u(name), unit)
    }

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + glGetShaderInfoLog(s) + "\n" + src }
        return s
    }
}

fun floatBuffer(n: Int): FloatBuffer =
    ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/** Creates an R8 or RGBA8 texture from bytes, repeating or clamped. */
fun makeTexture(w: Int, h: Int, data: ByteBuffer, single: Boolean, repeat: Boolean, mipmaps: Boolean = true): Int {
    val t = IntArray(1)
    glGenTextures(1, t, 0)
    glBindTexture(GL_TEXTURE_2D, t[0])
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
    data.position(0)
    if (single) glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, w, h, 0, GL_RED, GL_UNSIGNED_BYTE, data)
    else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data)
    val wrap = if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    if (mipmaps) {
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
    } else glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    return t[0]
}
