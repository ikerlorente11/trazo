package dev.pistation.trazo.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.pistation.trazo.engine.BlendMode
import dev.pistation.trazo.engine.LayerProps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.DeflaterOutputStream
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream

data class StoredLayer(val id: Long, val props: LayerProps)

data class ArtworkMeta(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val bgColor: Int = 0xFFFFFFFF.toInt(),
    val bgVisible: Boolean = true,
    val layers: List<StoredLayer> = emptyList(),
    val activeId: Long = 0,
)

/**
 * Artworks on disk: files/artworks/<id>/ with doc.json, one deflated raw RGBA (premultiplied) file per
 * layer and thumb.jpg for the gallery.
 */
class ArtworkStore(private val context: Context) {
    private val root = File(context.filesDir, "artworks").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun thumbFile(id: String) = File(dir(id), "thumb.jpg")

    fun list(): List<ArtworkMeta> = root.listFiles().orEmpty()
        .mapNotNull { runCatching { readMeta(it.name) }.getOrNull() }
        .sortedByDescending { it.modified }

    fun create(name: String, w: Int, h: Int): ArtworkMeta {
        val m = ArtworkMeta(UUID.randomUUID().toString(), name, w, h, System.currentTimeMillis())
        dir(m.id).mkdirs()
        writeMeta(m)
        return m
    }

    fun readMeta(id: String): ArtworkMeta {
        val o = JSONObject(File(dir(id), "doc.json").readText())
        val ls = o.optJSONArray("layers") ?: JSONArray()
        return ArtworkMeta(
            id, o.getString("name"), o.getInt("width"), o.getInt("height"), o.getLong("modified"),
            o.optInt("bgColor", 0xFFFFFFFF.toInt()), o.optBoolean("bgVisible", true),
            (0 until ls.length()).map { i ->
                val l = ls.getJSONObject(i)
                StoredLayer(l.getLong("id"), LayerProps(
                    l.getString("name"), l.optBoolean("visible", true), l.optDouble("opacity", 1.0).toFloat(),
                    runCatching { BlendMode.valueOf(l.optString("blend", "NORMAL")) }.getOrDefault(BlendMode.NORMAL),
                    l.optBoolean("alphaLock"), l.optBoolean("clip")))
            },
            o.optLong("activeId"),
        )
    }

    private fun writeMeta(m: ArtworkMeta) {
        val o = JSONObject()
            .put("name", m.name).put("width", m.width).put("height", m.height).put("modified", m.modified)
            .put("bgColor", m.bgColor).put("bgVisible", m.bgVisible).put("activeId", m.activeId)
            .put("layers", JSONArray().apply {
                m.layers.forEach { l ->
                    put(JSONObject().put("id", l.id).put("name", l.props.name).put("visible", l.props.visible)
                        .put("opacity", l.props.opacity.toDouble()).put("blend", l.props.blend.name)
                        .put("alphaLock", l.props.alphaLock).put("clip", l.props.clip))
                }
            })
        val tmp = File(dir(m.id), "doc.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir(m.id), "doc.json"))
    }

    fun rename(id: String, name: String) = writeMeta(readMeta(id).copy(name = name))

    /** Writes layers (raw RGBA buffers) and the gallery thumbnail; removes files of deleted layers. */
    @Synchronized
    fun save(meta: ArtworkMeta, pixels: Map<Long, ByteBuffer>, flat: Bitmap?) {
        val d = dir(meta.id).apply { mkdirs() }
        val arr = ByteArray(meta.width * meta.height * 4)
        for ((id, buf) in pixels) {
            buf.position(0); buf.get(arr); buf.position(0)
            val tmp = File(d, "L$id.z.tmp")
            DeflaterOutputStream(tmp.outputStream().buffered(1 shl 16), Deflater(1), 1 shl 16).use { it.write(arr) }
            tmp.renameTo(File(d, "L$id.z"))
        }
        val keep = meta.layers.map { "L${it.id}.z" }.toSet()
        d.listFiles { f -> f.name.startsWith("L") && f.name.endsWith(".z") && f.name !in keep }?.forEach { it.delete() }
        if (flat != null) {
            val s = 600f / maxOf(flat.width, flat.height)
            val small = if (s < 1f) Bitmap.createScaledBitmap(flat, (flat.width * s).toInt().coerceAtLeast(1), (flat.height * s).toInt().coerceAtLeast(1), true) else flat
            thumbFile(meta.id).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
        writeMeta(meta.copy(modified = System.currentTimeMillis()))
    }

    /** Layer pixels for loading, or null when the file is missing (new layer). */
    fun loadLayer(meta: ArtworkMeta, id: Long): ByteBuffer? {
        val f = File(dir(meta.id), "L$id.z")
        if (!f.exists()) return null
        val arr = ByteArray(meta.width * meta.height * 4)
        InflaterInputStream(f.inputStream().buffered(1 shl 16)).use { inp ->
            var off = 0
            while (off < arr.size) { val r = inp.read(arr, off, arr.size - off); if (r < 0) break; off += r }
        }
        return ByteBuffer.allocateDirect(arr.size).order(ByteOrder.nativeOrder()).put(arr).also { it.position(0) }
    }

    fun delete(id: String) = dir(id).deleteRecursively()

    fun duplicate(id: String): ArtworkMeta {
        val m = readMeta(id)
        val n = m.copy(id = UUID.randomUUID().toString(), name = m.name + " copia", modified = System.currentTimeMillis())
        dir(id).copyRecursively(dir(n.id))
        writeMeta(n)
        return n
    }

    fun thumbnail(id: String): Bitmap? = thumbFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Saves an image into Pictures/Trazo; returns its uri. */
    fun exportImage(name: String, bmp: Bitmap, png: Boolean): Uri? {
        val ext = if (png) "png" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${name.replace(Regex("[^\\w\\- ]"), "_")}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Trazo")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            val src = if (png) bmp else flattenOnWhite(bmp)
            src.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)
        }
        return uri
    }

    fun shareFile(name: String, bmp: Bitmap): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "${name.replace(Regex("[^\\w\\- ]"), "_")}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun flattenOnWhite(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        return out
    }
}
