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
import dev.pistation.trazo.engine.Mesh
import dev.pistation.trazo.engine.Meshes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class StoredLayer(val id: Long, val props: LayerProps, val isGroup: Boolean = false, val parent: Long = 0L, val hasMask: Boolean = false)

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
    /** null = flat canvas; a built-in mesh name or "obj" (model.obj in the folder) for 3D painting. */
    val model: String? = null,
    /** Free-form settings kept with the artwork: guides, animation, time-lapse… (JSON). */
    val extra: JSONObject = JSONObject(),
)

/**
 * Artworks on disk: files/artworks/<id>/ with doc.json, one deflated raw RGBA (premultiplied) file per layer
 * (L<id>.z) and mask (M<id>.z), thumb.jpg for the gallery, timelapse/ frames and model.obj for 3D.
 */
class ArtworkStore(private val context: Context) {
    private val root = File(context.filesDir, "artworks").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun thumbFile(id: String) = File(dir(id), "thumb.jpg")
    fun timelapseDir(id: String) = File(dir(id), "timelapse")

    fun list(): List<ArtworkMeta> = root.listFiles().orEmpty()
        .mapNotNull { runCatching { readMeta(it.name) }.getOrNull() }
        .sortedByDescending { it.modified }

    fun create(name: String, w: Int, h: Int, model: String? = null, objText: String? = null): ArtworkMeta {
        val m = ArtworkMeta(UUID.randomUUID().toString(), name, w, h, System.currentTimeMillis(), model = model)
        dir(m.id).mkdirs()
        if (objText != null) File(dir(m.id), "model.obj").writeText(objText)
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
                    l.optBoolean("alphaLock"), l.optBoolean("clip"), l.optBoolean("reference"), l.optBoolean("collapsed"),
                    l.optBoolean("maskEnabled", true), l.optInt("hold", 1)),
                    l.optBoolean("group"), l.optLong("parent"), l.optBoolean("mask"))
            },
            o.optLong("activeId"),
            o.optString("model").takeIf { it.isNotEmpty() },
            o.optJSONObject("extra") ?: JSONObject(),
        )
    }

    fun writeMeta(m: ArtworkMeta) {
        val o = JSONObject()
            .put("name", m.name).put("width", m.width).put("height", m.height).put("modified", m.modified)
            .put("bgColor", m.bgColor).put("bgVisible", m.bgVisible).put("activeId", m.activeId)
            .put("model", m.model ?: "").put("extra", m.extra)
            .put("layers", JSONArray().apply {
                m.layers.forEach { l ->
                    put(JSONObject().put("id", l.id).put("name", l.props.name).put("visible", l.props.visible)
                        .put("opacity", l.props.opacity.toDouble()).put("blend", l.props.blend.name)
                        .put("alphaLock", l.props.alphaLock).put("clip", l.props.clip).put("reference", l.props.reference)
                        .put("collapsed", l.props.collapsed).put("maskEnabled", l.props.maskEnabled).put("hold", l.props.hold)
                        .put("group", l.isGroup).put("parent", l.parent).put("mask", l.hasMask))
                }
            })
        val tmp = File(dir(m.id), "doc.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir(m.id), "doc.json"))
    }

    fun rename(id: String, name: String) = writeMeta(readMeta(id).copy(name = name))

    /** Writes layers and masks (raw RGBA buffers) and the gallery thumbnail; removes stale files. */
    @Synchronized
    fun save(meta: ArtworkMeta, pixels: Map<String, ByteBuffer>, flat: Bitmap?) {
        val d = dir(meta.id).apply { mkdirs() }
        val arr = ByteArray(meta.width * meta.height * 4)
        for ((name, buf) in pixels) {
            buf.position(0); buf.get(arr); buf.position(0)
            val tmp = File(d, "$name.z.tmp")
            DeflaterOutputStream(tmp.outputStream().buffered(1 shl 16), Deflater(1), 1 shl 16).use { it.write(arr) }
            tmp.renameTo(File(d, "$name.z"))
        }
        val keep = meta.layers.filter { !it.isGroup }.map { "L${it.id}.z" }.toSet() + meta.layers.filter { it.hasMask }.map { "M${it.id}.z" }
        d.listFiles { f -> (f.name.startsWith("L") || f.name.startsWith("M")) && f.name.endsWith(".z") && f.name !in keep }?.forEach { it.delete() }
        if (flat != null) {
            val s = 600f / maxOf(flat.width, flat.height)
            val small = if (s < 1f) Bitmap.createScaledBitmap(flat, (flat.width * s).toInt().coerceAtLeast(1), (flat.height * s).toInt().coerceAtLeast(1), true) else flat
            thumbFile(meta.id).outputStream().use { onWhite(small).compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
        writeMeta(meta.copy(modified = System.currentTimeMillis()))
    }

    /** Pixels of a stored layer ("L<id>") or mask ("M<id>"), or null when missing. */
    fun loadPixels(meta: ArtworkMeta, name: String): ByteBuffer? {
        val f = File(dir(meta.id), "$name.z")
        if (!f.exists()) return null
        val arr = ByteArray(meta.width * meta.height * 4)
        InflaterInputStream(f.inputStream().buffered(1 shl 16)).use { inp ->
            var off = 0
            while (off < arr.size) { val r = inp.read(arr, off, arr.size - off); if (r < 0) break; off += r }
        }
        return ByteBuffer.allocateDirect(arr.size).order(ByteOrder.nativeOrder()).put(arr).also { it.position(0) }
    }

    fun loadModel(meta: ArtworkMeta): Mesh? = when (meta.model) {
        null -> null
        "obj" -> runCatching { Meshes.parseObj(File(dir(meta.id), "model.obj").readText()) }.getOrNull()
        else -> Meshes.byName(meta.model)
    }

    fun delete(id: String) = dir(id).deleteRecursively()

    fun duplicate(id: String): ArtworkMeta {
        val m = readMeta(id)
        val n = m.copy(id = UUID.randomUUID().toString(), name = m.name + " copia", modified = System.currentTimeMillis())
        dir(id).copyRecursively(dir(n.id))
        writeMeta(n)
        return n
    }

    /**
     * New canvas size. `scale` true resizes the picture; false crops/extends it, with the old picture's top-left at
     * (offX, offY) in the new canvas.
     */
    fun resize(id: String, w: Int, h: Int, scale: Boolean, offX: Int, offY: Int) {
        val m = readMeta(id)
        val d = dir(id)
        for (l in m.layers) {
            for (name in listOf("L${l.id}", "M${l.id}")) {
                val px = loadPixels(m, name) ?: continue
                val src = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(px) }
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                if (name.startsWith("M")) c.drawColor(android.graphics.Color.WHITE)
                if (scale) c.drawBitmap(src, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                else c.drawBitmap(src, offX.toFloat(), offY.toFloat(), null)
                val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                out.copyPixelsToBuffer(buf); buf.position(0)
                val arr = ByteArray(w * h * 4); buf.get(arr)
                DeflaterOutputStream(File(d, "$name.z").outputStream().buffered(1 shl 16), Deflater(1), 1 shl 16).use { it.write(arr) }
            }
        }
        File(d, "timelapse").deleteRecursively()
        writeMeta(m.copy(width = w, height = h, modified = System.currentTimeMillis()))
    }

    fun thumbnail(id: String): Bitmap? = thumbFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Saves a file into the shared collection (Pictures/Trazo, Movies/Trazo or Download/Trazo). */
    fun exportStream(fileName: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        val (collection, dirName) = when {
            mime.startsWith("image/") && mime != "image/vnd.adobe.photoshop" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safe(fileName))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirName/Trazo")
        }
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use(write)
        return uri
    }

    fun exportImage(name: String, bmp: Bitmap, png: Boolean): Uri? =
        exportStream("$name.${if (png) "png" else "jpg"}", if (png) "image/png" else "image/jpeg") { out ->
            (if (png) bmp else onWhite(bmp)).compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)
        }

    fun shareFile(fileName: String, write: (OutputStream) -> Unit): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, safe(fileName))
        f.outputStream().use(write)
        return f
    }

    private fun safe(n: String) = n.replace(Regex("[^\\w\\-. ]"), "_")

    fun onWhite(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        return out
    }
}
