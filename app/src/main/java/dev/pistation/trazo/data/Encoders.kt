package dev.pistation.trazo.data

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/** H.264 MP4 from bitmaps (time-lapse, animation). Size is rounded to multiples of 16. */
class Mp4Encoder(file: File, srcW: Int, srcH: Int, private val fps: Int, maxSide: Int = 1920) {
    val w: Int
    val h: Int
    private val codec: MediaCodec
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var frameIndex = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val s = minOf(1f, maxSide.toFloat() / maxOf(srcW, srcH))
        w = maxOf(16, (srcW * s).toInt() / 16 * 16); h = maxOf(16, (srcH * s).toInt() / 16 * 16)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 6).coerceAtLeast(2_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Adds `bmp` (any size: scaled to fit, letterboxed in white) shown for `repeat` frames. */
    fun add(bmp: Bitmap, repeat: Int = 1) {
        val fitted = fit(bmp)
        val argb = IntArray(w * h)
        fitted.getPixels(argb, 0, w, 0, 0, w, h)
        // Convert once (BT.601), then copy into the codec's planes for every repeat.
        val yb0 = ByteArray(w * h); val ub0 = ByteArray(w * h / 4); val vb0 = ByteArray(w * h / 4)
        for (j in 0 until h) for (i in 0 until w) {
            val c = argb[j * w + i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            yb0[j * w + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val k = (j / 2) * (w / 2) + i / 2
                ub0[k] = ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
                vb0[k] = ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
            }
        }
        repeat(repeat) {
            var idx: Int
            while (true) { idx = codec.dequeueInputBuffer(10_000); if (idx >= 0) break; drain(false) }
            val img = codec.getInputImage(idx)!!
            val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
            val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
            if (y.pixelStride == 1) for (j in 0 until h) { yb.position(j * y.rowStride); yb.put(yb0, j * w, w) }
            else for (j in 0 until h) for (i in 0 until w) yb.put(j * y.rowStride + i * y.pixelStride, yb0[j * w + i])
            for (j in 0 until h / 2) for (i in 0 until w / 2) {
                val k = j * (w / 2) + i
                ub.put(j * u.rowStride + i * u.pixelStride, ub0[k])
                vb.put(j * v.rowStride + i * v.pixelStride, vb0[k])
            }
            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, frameIndex * 1_000_000L / fps, 0)
            frameIndex++
            drain(false)
        }
    }

    private fun fit(bmp: Bitmap): Bitmap {
        if (bmp.width == w && bmp.height == h) return bmp
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        val s = minOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = bmp.width * s; val dh = bmp.height * s
        c.drawBitmap(bmp, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun drain(end: Boolean) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish() {
        var idx: Int
        while (true) { idx = codec.dequeueInputBuffer(10_000); if (idx >= 0) break; drain(false) }
        codec.queueInputBuffer(idx, 0, 0, frameIndex * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop(); codec.release()
        if (started) muxer.stop()
        muxer.release()
    }
}

/** Animated GIF (palette per frame from the most used colours, LZW). */
class GifEncoder(private val out: OutputStream, private val w: Int, private val h: Int, private val loop: Boolean = true) {
    private var first = true

    fun add(bmp0: Bitmap, delayMs: Int) {
        val bmp = if (bmp0.width == w && bmp0.height == h) bmp0 else Bitmap.createScaledBitmap(bmp0, w, h, true)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Palette: 255 most frequent 5-bit colours + 1 transparent.
        val counts = IntArray(32768)
        for (c in px) if ((c ushr 24) >= 128) counts[key(c)]++
        val top = counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }.take(255)
        val palette = IntArray(256)
        top.forEachIndexed { i, k -> palette[i] = ((k shr 10) and 31) * 255 / 31 shl 16 or (((k shr 5) and 31) * 255 / 31 shl 8) or ((k and 31) * 255 / 31) }
        val transparent = 255
        val lookup = IntArray(32768) { -1 }
        val indices = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            if ((c ushr 24) < 128 || top.isEmpty()) { indices[i] = transparent.toByte(); continue }
            val k = key(c)
            var best = lookup[k]
            if (best < 0) {
                var bd = Int.MAX_VALUE
                val r = (k shr 10) and 31; val g = (k shr 5) and 31; val b = k and 31
                for (j in top.indices) {
                    val t = top[j]
                    val dr = r - ((t shr 10) and 31); val dg = g - ((t shr 5) and 31); val db = b - (t and 31)
                    val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                    if (d < bd) { bd = d; best = j }
                }
                lookup[k] = best
            }
            indices[i] = best.toByte()
        }
        val d = DataOutputStream(out)
        if (first) {
            d.write("GIF89a".toByteArray())
            le(d, w); le(d, h)
            d.write(0x70); d.write(0); d.write(0)     // no global table
            if (loop) { d.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); d.write("NETSCAPE2.0".toByteArray()); d.write(byteArrayOf(3, 1, 0, 0, 0)) }
            first = false
        }
        // Graphic control: delay, transparency, dispose to background.
        d.write(byteArrayOf(0x21, 0xF9.toByte(), 4, (2 shl 2 or 1).toByte())); le(d, delayMs / 10); d.write(transparent); d.write(0)
        d.write(0x2C); le(d, 0); le(d, 0); le(d, w); le(d, h)
        d.write(0x87)   // local table, 256 entries
        for (c in palette) { d.write((c shr 16) and 255); d.write((c shr 8) and 255); d.write(c and 255) }
        lzw(d, indices)
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun key(c: Int) = (((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)
    private fun le(d: DataOutputStream, v: Int) { d.write(v and 255); d.write((v shr 8) and 255) }

    private fun lzw(d: DataOutputStream, data: ByteArray) {
        val minCode = 8
        d.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val block = ByteArray(255); var bn = 0
        var acc = 0; var bits = 0
        fun emit(code: Int, size: Int) {
            acc = acc or (code shl bits); bits += size
            while (bits >= 8) {
                block[bn++] = (acc and 255).toByte(); acc = acc ushr 8; bits -= 8
                if (bn == 255) { d.write(255); d.write(block, 0, 255); bn = 0 }
            }
        }
        val dict = HashMap<Int, Int>(8192)
        var size = minCode + 1; var next = eoi + 1
        emit(clear, size)
        var prefix = data[0].toInt() and 255
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 255
            val k = (prefix shl 8) or c
            val found = dict[k]
            if (found != null) { prefix = found; continue }
            emit(prefix, size)
            if (next < 4096) {
                dict[k] = next++
                if (next > (1 shl size) && size < 12) size++
            } else {
                emit(clear, size); dict.clear(); size = minCode + 1; next = eoi + 1
            }
            prefix = c
        }
        emit(prefix, size); emit(eoi, size)
        if (bits > 0) { block[bn++] = (acc and 255).toByte(); if (bn == 255) { d.write(255); d.write(block, 0, 255); bn = 0 } }
        if (bn > 0) { d.write(bn); d.write(block, 0, bn) }
        d.write(0)
    }
}

/** Layered Photoshop file. Layers bottom → top; groups as PSD folders; masks as user masks. */
object PsdWriter {
    class PsdLayer(
        val name: String, val pixels: ByteBuffer?, val mask: ByteBuffer?, val opacity: Float, val visible: Boolean,
        val blendKey: String, val clip: Boolean, val alphaLock: Boolean,
        /** 0 normal layer, 1 group folder (open), 3 group end marker. */
        val section: Int = 0,
    )

    fun write(out: OutputStream, w: Int, h: Int, layers: List<PsdLayer>, merged: ByteBuffer) {
        val d = DataOutputStream(out.buffered(1 shl 16))
        d.write("8BPS".toByteArray()); d.writeShort(1); d.write(ByteArray(6))
        d.writeShort(4); d.writeInt(h); d.writeInt(w); d.writeShort(8); d.writeShort(3)
        d.writeInt(0)   // colour mode data
        d.writeInt(0)   // image resources

        // Channel data per layer, bounded to the content.
        class Chan(val id: Int, val data: ByteArray)
        class Rec(val l: PsdLayer, val top: Int, val left: Int, val bottom: Int, val right: Int, val chans: List<Chan>)
        val recs = layers.map { l ->
            val px = l.pixels
            if (px == null || l.section != 0) return@map Rec(l, 0, 0, 0, 0, listOf(Chan(-1, ByteArray(0)), Chan(0, ByteArray(0)), Chan(1, ByteArray(0)), Chan(2, ByteArray(0))))
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if (px.get((y * w + x) * 4 + 3).toInt() != 0) {
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x1 < 0) { x0 = 0; y0 = 0; x1 = -1; y1 = -1 }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val n = maxOf(0, bw) * maxOf(0, bh)
            val r = ByteArray(n); val g = ByteArray(n); val b = ByteArray(n); val a = ByteArray(n)
            var k = 0
            for (y in y0..y1) for (x in x0..x1) {
                val o = (y * w + x) * 4
                val al = px.get(o + 3).toInt() and 255
                fun un(v: Byte) = if (al == 0) 0 else ((v.toInt() and 255) * 255 / al).coerceAtMost(255)
                r[k] = un(px.get(o)).toByte(); g[k] = un(px.get(o + 1)).toByte(); b[k] = un(px.get(o + 2)).toByte(); a[k] = al.toByte(); k++
            }
            val chans = arrayListOf(Chan(-1, a), Chan(0, r), Chan(1, g), Chan(2, b))
            l.mask?.let { m -> chans.add(Chan(-2, ByteArray(w * h) { i -> m.get(i * 4) })) }
            Rec(l, y0, x0, y1 + 1, x1 + 1, chans)
        }

        // Layer info, built in memory to know its length.
        val li = java.io.ByteArrayOutputStream()
        val ld = DataOutputStream(li)
        ld.writeShort(recs.size)
        for (r in recs) {
            ld.writeInt(r.top); ld.writeInt(r.left); ld.writeInt(r.bottom); ld.writeInt(r.right)
            ld.writeShort(r.chans.size)
            for (c in r.chans) { ld.writeShort(c.id); ld.writeInt(2 + c.data.size) }
            ld.write("8BIM".toByteArray())
            ld.write((if (r.l.section == 1) "pass" else r.l.blendKey).toByteArray())
            ld.write((r.l.opacity * 255).toInt().coerceIn(0, 255))
            ld.write(if (r.l.clip) 1 else 0)
            ld.write((if (r.l.alphaLock) 1 else 0) or (if (!r.l.visible) 2 else 0))
            ld.write(0)
            val extra = java.io.ByteArrayOutputStream(); val ed = DataOutputStream(extra)
            if (r.l.mask != null && r.l.section == 0) { ed.writeInt(20); ed.writeInt(0); ed.writeInt(0); ed.writeInt(h); ed.writeInt(w); ed.write(255); ed.write(0); ed.writeShort(0) }
            else ed.writeInt(0)
            ed.writeInt(0)  // blending ranges
            val nameBytes = r.l.name.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
            ed.write(nameBytes.size); ed.write(nameBytes)
            val padded = (1 + nameBytes.size + 3) / 4 * 4
            ed.write(ByteArray(padded - 1 - nameBytes.size))
            // Unicode name
            val uni = java.io.ByteArrayOutputStream(); val ud = DataOutputStream(uni)
            ud.writeInt(r.l.name.length); r.l.name.forEach { ud.writeChar(it.code) }
            if (uni.size() % 2 == 1) ud.write(0)
            ed.write("8BIMluni".toByteArray()); ed.writeInt(uni.size()); ed.write(uni.toByteArray())
            if (r.l.section != 0) {
                ed.write("8BIMlsct".toByteArray()); ed.writeInt(12); ed.writeInt(r.l.section)
                ed.write("8BIM".toByteArray()); ed.write((if (r.l.section == 1) r.l.blendKey else "norm").toByteArray())
            }
            ld.writeInt(extra.size()); ld.write(extra.toByteArray())
        }
        for (r in recs) for (c in r.chans) { ld.writeShort(0); ld.write(c.data) }
        if (li.size() % 2 == 1) ld.write(0)

        d.writeInt(4 + li.size() + 4)   // layer and mask info
        d.writeInt(li.size()); d.write(li.toByteArray())
        d.writeInt(0)                    // global mask info

        // Merged image, planar, raw.
        d.writeShort(0)
        val n = w * h
        for (ch in 0..3) {
            val row = ByteArray(n)
            for (i in 0 until n) {
                val al = merged.get(i * 4 + 3).toInt() and 255
                row[i] = if (ch == 3) al.toByte() else if (al == 0) 0 else ((merged.get(i * 4 + ch).toInt() and 255) * 255 / al).coerceAtMost(255).toByte()
            }
            d.write(row)
        }
        d.flush()
    }
}
