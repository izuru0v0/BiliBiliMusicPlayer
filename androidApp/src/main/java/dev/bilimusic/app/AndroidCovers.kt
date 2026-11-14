package dev.bilimusic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal object AndroidCovers {
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(context: Context, raw: String): Bitmap? = runCatching {
        val uri = URI(if (raw.startsWith("//")) "https:$raw" else raw)
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme !in listOf("https", "http") || uri.userInfo != null ||
            (host != "hdslb.com" && !host.endsWith(".hdslb.com") &&
                host != "bilibili.com" && !host.endsWith(".bilibili.com"))) return null
        val secure = URI("https", null, host, -1, uri.path, uri.query, null)
        val key = MessageDigest.getInstance("SHA-256").digest(secure.toASCIIString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        memory.get(key)?.let { return it }
        val dir = File(context.cacheDir, "covers")
        val file = File(dir, "$key.jpg")
        BitmapFactory.decodeFile(file.absolutePath)?.let { memory.put(key, it); return it }
        val connection = secure.toURL().openConnection() as HttpURLConnection
        val image = try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (connection.responseCode != 200) return null
            val data = connection.inputStream.use { input ->
                val bytes = ByteArray(1_048_577)
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    length += count
                }
                if (length == bytes.size) return null
                bytes.copyOf(length)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 350) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            connection.disconnect()
        } ?: return null
        memory.put(key, image)
        runCatching {
            dir.mkdirs()
            val temp = File.createTempFile("cover-", ".tmp", dir)
            try {
                if (temp.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 88, it) }) temp.renameTo(file)
            } finally { temp.delete() }
        }
        image
    }.getOrNull()
}
