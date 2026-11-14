package dev.pistation.trazo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.pistation.trazo.engine.BlendMode
import dev.pistation.trazo.engine.Brush
import dev.pistation.trazo.engine.LayerProps
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Apple binary property lists (bplist00), as used by NSKeyedArchiver. */
object BPlist {
    class Uid(val v: Int)

    fun parse(b: ByteArray): Any? {
        require(String(b, 0, 8, Charsets.US_ASCII) == "bplist00") { "No es un bplist" }
        val t = b.size - 32
        val offSize = b[t + 6].toInt() and 255
        val refSize = b[t + 7].toInt() and 255
        val num = beLong(b, t + 8, 8).toInt()
        val top = beLong(b, t + 16, 8).toInt()
        val tableOff = beLong(b, t + 24, 8).toInt()
        val offsets = IntArray(num) { beLong(b, tableOff + it * offSize, offSize).toInt() }
        val cache = arrayOfNulls<Any>(num)
        val done = BooleanArray(num)
        fun obj(i: Int): Any? {
            if (done[i]) return cache[i]
            var p = offsets[i]
            val m = b[p].toInt() and 255
            val hi = m shr 4; val lo = m and 15
            fun count(): Int {
                if (lo != 15) return lo
                val im = b[p + 1].toInt() and 255
                val n = 1 shl (im and 15)
                val v = beLong(b, p + 2, n).toInt(); p += 1 + n
                return v
            }
            val r: Any? = when (hi) {
                0 -> when (lo) { 8 -> false; 9 -> true; else -> null }
                1 -> beLong(b, p + 1, 1 shl lo)
                2 -> if (lo == 2) java.lang.Float.intBitsToFloat(beLong(b, p + 1, 4).toInt()).toDouble() else java.lang.Double.longBitsToDouble(beLong(b, p + 1, 8))
                3 -> java.lang.Double.longBitsToDouble(beLong(b, p + 1, 8))
                4 -> { val n = count(); b.copyOfRange(p + 1, p + 1 + n) }
                5 -> { val n = count(); String(b, p + 1, n, Charsets.US_ASCII) }
                6 -> { val n = count(); String(b, p + 1, n * 2, Charsets.UTF_16BE) }
                8 -> Uid(beLong(b, p + 1, lo + 1).toInt())
                10, 12 -> { val n = count(); val start = p + 1; done[i] = true; val list = ArrayList<Any?>(n); cache[i] = list
                    for (k in 0 until n) list.add(obj(beLong(b, start + k * refSize, refSize).toInt())); list }
                13 -> { val n = count(); val start = p + 1; val map = LinkedHashMap<String, Any?>(); done[i] = true; cache[i] = map
                    for (k in 0 until n) {
                        val key = obj(beLong(b, start + k * refSize, refSize).toInt()).toString()
                        map[key] = obj(beLong(b, start + (n + k) * refSize, refSize).toInt())
                    }
                    map }
                else -> null
            }
            cache[i] = r; done[i] = true
            return r
        }
        return obj(top)
    }

    private fun beLong(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 255)
        return v
    }
}

/** NSKeyedArchiver: follows UIDs into the $objects table. */
class Keyed(bytes: ByteArray) {
    @Suppress("UNCHECKED_CAST")
    private val plist = BPlist.parse(bytes) as Map<String, Any?>
    private val objects = plist["\$objects"] as List<Any?>
    @Suppress("UNCHECKED_CAST")
    val root: Map<String, Any?> = resolve((plist["\$top"] as Map<String, Any?>).let { it["root"] ?: it.values.first() }) as Map<String, Any?>

    fun resolve(v: Any?): Any? = if (v is BPlist.Uid) objects.getOrNull(v.v).let { if (it == "\$null") null else it } else v

    fun get(m: Map<String, Any?>?, key: String): Any? = m?.let { resolve(it[key]) }
    fun str(m: Map<String, Any?>?, key: String) = get(m, key) as? String
    fun num(m: Map<String, Any?>?, key: String) = (get(m, key) as? Number)?.toDouble()
    fun bool(m: Map<String, Any?>?, key: String) = get(m, key) as? Boolean

    @Suppress("UNCHECKED_CAST")
    fun array(m: Map<String, Any?>?, key: String): List<Map<String, Any?>> {
        val a = get(m, key) as? Map<String, Any?> ?: return emptyList()
        val items = a["NS.objects"] as? List<Any?> ?: return emptyList()
        return items.mapNotNull { resolve(it) as? Map<String, Any?> }
    }
}

/** LZO1X decompression (the format of Procreate's tile chunks). */
object Lzo {
    fun decompress(src: ByteArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        var ip = 0; var op = 0
        fun u(i: Int) = src[i].toInt() and 255
        fun copyLit(n: Int) { System.arraycopy(src, ip, out, op, n); ip += n; op += n }
        fun copyMatch(pos0: Int, n: Int) { var p = pos0; repeat(n) { out[op++] = out[p++] } }
        var t: Int
        var state: Int   // 0 = read instruction, 1 = after first literal run, 2 = match with t, 3 = literals after match
        if (u(ip) > 17) {
            t = u(ip++) - 17
            if (t < 4) { copyLit(t); t = u(ip++); state = 2 } else { copyLit(t); state = 1 }
        } else state = 0
        t = 0
        while (true) {
            when (state) {
                0 -> {
                    t = u(ip++)
                    if (t >= 16) { state = 2; continue }
                    if (t == 0) { while (u(ip) == 0) { t += 255; ip++ }; t += 15 + u(ip++) }
                    copyLit(t + 3)
                    state = 1
                }
                1 -> {
                    t = u(ip++)
                    if (t >= 16) { state = 2; continue }
                    var mp = op - (1 + 0x0800)
                    mp -= t shr 2
                    mp -= u(ip++) shl 2
                    copyMatch(mp, 3)
                    t = u(ip - 2) and 3
                    if (t == 0) { state = 0; continue }
                    copyLit(t); t = u(ip++); state = 2
                }
                2 -> {
                    var mp: Int
                    if (t >= 64) {
                        mp = op - 1 - ((t shr 2) and 7) - (u(ip++) shl 3)
                        t = (t shr 5) - 1
                        copyMatch(mp, t + 2)
                    } else if (t >= 32) {
                        t = t and 31
                        if (t == 0) { while (u(ip) == 0) { t += 255; ip++ }; t += 31 + u(ip++) }
                        mp = op - 1 - ((u(ip) shr 2) + (u(ip + 1) shl 6)); ip += 2
                        copyMatch(mp, t + 2)
                    } else if (t >= 16) {
                        mp = op - ((t and 8) shl 11)
                        t = t and 7
                        if (t == 0) { while (u(ip) == 0) { t += 255; ip++ }; t += 7 + u(ip++) }
                        mp -= (u(ip) shr 2) + (u(ip + 1) shl 6); ip += 2
                        if (mp == op) return out          // end of stream
                        mp -= 0x4000
                        copyMatch(mp, t + 2)
                    } else {
                        mp = op - 1 - (t shr 2) - (u(ip++) shl 2)
                        copyMatch(mp, 2)
                    }
                    t = u(ip - 2) and 3
                    if (t == 0) { state = 0; continue }
                    copyLit(t); t = u(ip++)
                }
            }
            if (op >= outLen && state == 0) return out
        }
    }
}

class ImportedLayer(val props: LayerProps, val pixels: ByteBuffer)
class ImportedDoc(val name: String, val width: Int, val height: Int, val bgColor: Int, val bgVisible: Boolean, val layers: List<ImportedLayer>)

/** Procreate documents (.procreate). Experimental: built from the community's notes on the format. */
object ProcreateImporter {
    // Procreate's blend numbers, as far as known.
    private val blends = mapOf(0 to BlendMode.NORMAL, 1 to BlendMode.MULTIPLY, 2 to BlendMode.SCREEN, 3 to BlendMode.ADD,
        4 to BlendMode.LIGHTEN, 5 to BlendMode.EXCLUSION, 6 to BlendMode.DIFFERENCE, 7 to BlendMode.SUBTRACT,
        8 to BlendMode.LINEAR_BURN, 9 to BlendMode.COLOR_DODGE, 10 to BlendMode.COLOR_BURN, 11 to BlendMode.OVERLAY,
        12 to BlendMode.HARD_LIGHT, 13 to BlendMode.COLOR, 14 to BlendMode.LUMINOSITY, 15 to BlendMode.HUE,
        16 to BlendMode.SATURATION, 17 to BlendMode.SOFT_LIGHT, 19 to BlendMode.DARKEN, 24 to BlendMode.LIGHTER_COLOR,
        25 to BlendMode.DARKER_COLOR, 26 to BlendMode.DIVIDE)

    fun read(input: InputStream, fallbackName: String): ImportedDoc {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) entries[e.name] = z.readBytes() }
        }
        val archive = entries["Document.archive"] ?: error("No es un archivo de Procreate")
        val k = Keyed(archive)
        val root = k.root
        val size = (k.str(root, "size") ?: "{1024, 1024}").trim('{', '}').split(',').map { it.trim().toDouble().toInt() }
        val w = size[0]; val h = size[1]
        val tile = (k.num(root, "tileSize") ?: 256.0).toInt()
        val name = k.str(root, "name") ?: fallbackName
        val bgHidden = k.bool(root, "backgroundHidden") ?: false
        val bgColor = (k.get(root, "backgroundColor") as? ByteArray)?.takeIf { it.size >= 12 }?.let { d ->
            val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val r = (bb.getFloat(0) * 255).toInt().coerceIn(0, 255); val g = (bb.getFloat(4) * 255).toInt().coerceIn(0, 255); val b = (bb.getFloat(8) * 255).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } ?: 0xFFFFFFFF.toInt()
        val cols = (w + tile - 1) / tile; val rows = (h + tile - 1) / tile
        val layers = ArrayList<ImportedLayer>()
        // Procreate lists layers top first.
        for (lm in k.array(root, "layers").asReversed()) {
            val uuid = k.str(lm, "UUID") ?: continue
            val px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for ((path, data) in entries) {
                if (!path.startsWith("$uuid/")) continue
                val file = path.substringAfter('/').substringBefore('.')
                val parts = file.split('~')
                if (parts.size != 2) continue
                val col = parts[0].toIntOrNull() ?: continue; val row = parts[1].toIntOrNull() ?: continue
                val tw = if (col == cols - 1) w - col * tile else tile
                val th = if (row == rows - 1) h - row * tile else tile
                val raw = runCatching { Lzo.decompress(data, tw * th * 4) }.getOrNull()
                    ?: data.takeIf { it.size == tw * th * 4 } ?: continue
                // Tiles are stored bottom-up (GL); the canvas here is top-down.
                for (y in 0 until th) {
                    val dy = h - 1 - (row * tile + y)
                    if (dy !in 0 until h) continue
                    px.position((dy * w + col * tile) * 4)
                    px.put(raw, y * tw * 4, tw * 4)
                }
            }
            px.position(0)
            val props = LayerProps(
                name = k.str(lm, "name") ?: "Capa",
                visible = !(k.bool(lm, "hidden") ?: false),
                opacity = (k.num(lm, "opacity") ?: 1.0).toFloat(),
                blend = blends[(k.num(lm, "blend") ?: 0.0).toInt()] ?: BlendMode.NORMAL,
                alphaLock = k.bool(lm, "preserve") ?: false,
                clip = k.bool(lm, "clipped") ?: false,
            )
            layers.add(ImportedLayer(props, px))
        }
        require(layers.isNotEmpty()) { "No se encontraron capas" }
        return ImportedDoc(name, w, h, bgColor, !bgHidden, layers)
    }
}

/** Procreate brushes (.brush / .brushset): name, shape and grain images and the main dynamics. Experimental. */
object BrushImporter {
    class Imported(val brush: Brush, val shape: Bitmap?, val grain: Bitmap?)

    fun read(input: InputStream, idPrefix: String): List<Imported> {
        val folders = HashMap<String, HashMap<String, ByteArray>>()
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val dir = e.name.substringBeforeLast('/', "")
                folders.getOrPut(dir) { HashMap() }[e.name.substringAfterLast('/')] = z.readBytes()
            }
        }
        val out = ArrayList<Imported>()
        for ((dir, files) in folders) {
            val archive = files["Brush.archive"] ?: continue
            val k = runCatching { Keyed(archive) }.getOrNull() ?: continue
            val r = k.root
            fun n(key: String, def: Float) = (k.num(r, key) ?: def.toDouble()).toFloat()
            val shape = files["Shape.png"]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val grain = files["Grain.png"]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val id = "$idPrefix-${out.size}-${dir.hashCode().toUInt()}"
            val maxSize = n("maxSize", 0.5f)
            val b = Brush(
                id = id, name = k.str(r, "name") ?: "Pincel importado", category = "Importados",
                spacing = n("plotSpacing", 0.08f).coerceIn(0.01f, 1f),
                scatter = n("plotJitter", 0f).coerceIn(0f, 2f),
                streamline = n("plotSmoothing", 0.2f).coerceIn(0f, 1f),
                sizeMin = 1f, sizeMax = (10f + maxSize.coerceIn(0f, 1f) * 490f),
                minSizeFraction = n("minSize", 0.1f).coerceIn(0f, 1f),
                pressureSize = n("dynamicsPressureSize", 0.6f).coerceIn(0f, 1f),
                pressureOpacity = n("dynamicsPressureOpacity", 0f).coerceIn(0f, 1f),
                grainScale = 64f + n("textureScale", 0.5f).coerceIn(0f, 1f) * 500f,
                grainDepth = if (grain != null) 0.7f else 0f,
                roundness = n("shapeRoundness", 1f).coerceIn(0.05f, 1f),
                rotateWithStroke = (k.num(r, "shapeRotation") ?: 0.0) > 0.0,
                custom = true,
            )
            out.add(Imported(b, shape, grain))
        }
        return out
    }
}
