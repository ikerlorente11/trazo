package dev.pistation.trazo.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.pistation.trazo.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

/** OTA updates from the GitHub releases (the same ones Obtainium follows). */
class Updater(private val context: Context) {
    private val prefs = context.getSharedPreferences("trazo", Context.MODE_PRIVATE)

    /** The latest release if it is newer than this build; null if not or on any error. Blocking. */
    fun check(force: Boolean = false): Release? {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("updateCheck", 0) < 15 * 60_000L) {
            return prefs.getString("updateCache", null)?.let { parse(it) }?.takeIf { newer(it.version) }
        }
        return runCatching {
            val c = URL(API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Trazo")
            c.connectTimeout = 8000; c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            prefs.edit().putLong("updateCheck", now).putString("updateCache", body).apply()
            parse(body)?.takeIf { newer(it.version) }
        }.getOrNull()
    }

    private fun parse(json: String): Release? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(o.getString("tag_name").removePrefix("v"), o.optString("body"),
            apk.getString("browser_download_url"), apk.optLong("size"))
    }

    /** Downloads the APK into the cache, reporting 0..1. Blocking. */
    fun download(r: Release, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "trazo-${r.version}.apk")
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        // GitHub redirects to its storage host.
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Trazo")
            c.connectTimeout = 10000; c.readTimeout = 20000
            if (c.responseCode in 300..399) { url = URL(url, c.getHeaderField("Location")); continue }
            break
        }
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
        c.inputStream.use { inp ->
            f.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        return f
    }

    val canInstall get() = context.packageManager.canRequestPackageInstalls()

    /** Settings page where the user allows Trazo to install updates. */
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "dev.pistation.trazo.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val REPO = "ikerlorente11/trazo"
        const val API = "https://api.github.com/repos/$REPO/releases/latest"
        val current: String get() = BuildConfig.VERSION_NAME

        fun newer(v: String): Boolean {
            fun parts(s: String) = s.split('.', '-').map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }
            val a = parts(v); val b = parts(current)
            for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }
    }
}
