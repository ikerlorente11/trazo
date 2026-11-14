package dev.pistation.trazo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.pistation.trazo.engine.Brush
import dev.pistation.trazo.engine.BrushLibrary
import dev.pistation.trazo.engine.Grain
import dev.pistation.trazo.engine.Shape
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/**
 * The user's brushes (duplicated, edited or imported) and edits to built-in ones, in files/brushes/:
 * brushes.json + one PNG per imported shape or grain.
 */
class BrushStore(context: Context) {
    private val dir = File(context.filesDir, "brushes").apply { mkdirs() }
    private val file = File(dir, "brushes.json")

    /** Custom brushes and edited copies of built-ins (same id as the built-in). */
    fun load(): Pair<List<Brush>, Map<String, Brush>> {
        if (!file.exists()) return emptyList<Brush>() to emptyMap()
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList<Brush>() to emptyMap()
        val custom = o.optJSONArray("custom")?.let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } ?: emptyList()
        val edits = o.optJSONArray("edits")?.let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } ?: emptyList()
        return custom to edits.associateBy { it.id }
    }

    fun save(custom: List<Brush>, edits: Map<String, Brush>) {
        val o = JSONObject()
            .put("custom", JSONArray().apply { custom.forEach { put(toJson(it)) } })
            .put("edits", JSONArray().apply { edits.values.forEach { put(toJson(it)) } })
        val tmp = File(dir, "brushes.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(file)
    }

    /** Saves an image as a single-channel brush texture; returns its key (file path). */
    fun saveImage(name: String, bmp: Bitmap, invert: Boolean): String {
        val s = minOf(1f, 512f / maxOf(bmp.width, bmp.height))
        val sc = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val out = Bitmap.createBitmap(sc.width, sc.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(sc.width * sc.height); sc.getPixels(px, 0, sc.width, 0, 0, sc.width, sc.height)
        for (i in px.indices) {
            val c = px[i]; val a = (c ushr 24) and 255
            var l = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
            l = l * a / 255
            if (invert) l = 255 - l
            px[i] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
        out.setPixels(px, 0, sc.width, 0, 0, sc.width, sc.height)
        val f = File(dir, "$name.png")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.path
    }

    /** Single-channel bytes of a saved brush image, for the engine. */
    fun imageBytes(key: String): Triple<Int, Int, ByteBuffer>? {
        val bmp = BitmapFactory.decodeFile(key) ?: return null
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val buf = ByteBuffer.allocateDirect(px.size)
        for (c in px) buf.put(((c shr 16) and 255).toByte())
        buf.position(0)
        return Triple(bmp.width, bmp.height, buf)
    }

    fun deleteImages(b: Brush) { listOfNotNull(b.shapeImage, b.grainImage).forEach { File(it).delete() } }

    companion object {
        fun toJson(b: Brush): JSONObject = JSONObject()
            .put("id", b.id).put("name", b.name).put("category", b.category).put("shape", b.shape.name).put("grain", b.grain.name)
            .put("grainScale", b.grainScale.toDouble()).put("grainDepth", b.grainDepth.toDouble()).put("spacing", b.spacing.toDouble())
            .put("sizeMin", b.sizeMin.toDouble()).put("sizeMax", b.sizeMax.toDouble()).put("minSizeFraction", b.minSizeFraction.toDouble())
            .put("pressureSize", b.pressureSize.toDouble()).put("pressureOpacity", b.pressureOpacity.toDouble())
            .put("pressureGamma", b.pressureGamma.toDouble()).put("flow", b.flow.toDouble()).put("streamline", b.streamline.toDouble())
            .put("scatter", b.scatter.toDouble()).put("sizeJitter", b.sizeJitter.toDouble()).put("rotationJitter", b.rotationJitter.toDouble())
            .put("opacityJitter", b.opacityJitter.toDouble()).put("hueJitter", b.hueJitter.toDouble()).put("rotateWithStroke", b.rotateWithStroke)
            .put("angle", b.angle.toDouble()).put("roundness", b.roundness.toDouble()).put("taperStart", b.taperStart.toDouble())
            .put("smudge", b.smudge.toDouble()).put("count", b.count).put("wet", b.wet.toDouble())
            .put("shapeImage", b.shapeImage ?: "").put("grainImage", b.grainImage ?: "").put("custom", b.custom)

        fun fromJson(o: JSONObject): Brush {
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            val base = BrushLibrary.all.firstOrNull { it.id == o.optString("id") } ?: Brush("x", "x", "x")
            return Brush(
                id = o.getString("id"), name = o.optString("name", base.name), category = o.optString("category", base.category),
                shape = runCatching { Shape.valueOf(o.getString("shape")) }.getOrDefault(base.shape),
                grain = runCatching { Grain.valueOf(o.getString("grain")) }.getOrDefault(base.grain),
                grainScale = f("grainScale", base.grainScale), grainDepth = f("grainDepth", base.grainDepth), spacing = f("spacing", base.spacing),
                sizeMin = f("sizeMin", base.sizeMin), sizeMax = f("sizeMax", base.sizeMax), minSizeFraction = f("minSizeFraction", base.minSizeFraction),
                pressureSize = f("pressureSize", base.pressureSize), pressureOpacity = f("pressureOpacity", base.pressureOpacity),
                pressureGamma = f("pressureGamma", base.pressureGamma), flow = f("flow", base.flow), streamline = f("streamline", base.streamline),
                scatter = f("scatter", base.scatter), sizeJitter = f("sizeJitter", base.sizeJitter), rotationJitter = f("rotationJitter", base.rotationJitter),
                opacityJitter = f("opacityJitter", base.opacityJitter), hueJitter = f("hueJitter", base.hueJitter),
                rotateWithStroke = o.optBoolean("rotateWithStroke", base.rotateWithStroke), angle = f("angle", base.angle),
                roundness = f("roundness", base.roundness), taperStart = f("taperStart", base.taperStart), smudge = f("smudge", base.smudge),
                count = o.optInt("count", base.count), wet = f("wet", base.wet),
                shapeImage = o.optString("shapeImage").takeIf { it.isNotEmpty() }, grainImage = o.optString("grainImage").takeIf { it.isNotEmpty() },
                custom = o.optBoolean("custom"),
            )
        }
    }
}
