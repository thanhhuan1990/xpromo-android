package com.huanhuynh.xpromo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tải icon: stream thẳng từ mạng xuống file cache (không giữ cả ảnh trong RAM, giới hạn dung lượng).
 * Tách riêng khỏi phần decode – không bao giờ decode trực tiếp từ luồng mạng.
 */
internal object IconDownloader {
    private const val MAX_BYTES = 1L shl 20 // icon > 1 MB coi như lỗi

    fun download(url: String, dest: File): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) return false
            if (c.contentLengthLong > MAX_BYTES) return false
            val tmp = File(dest.parentFile, dest.name + ".part")
            val ok = c.inputStream.use { input -> tmp.outputStream().use { out -> copyLimited(input, out) } }
            (ok && tmp.renameTo(dest)).also { if (!it) tmp.delete() }
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    private fun copyLimited(input: InputStream, out: OutputStream): Boolean {
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return true
            total += n
            if (total > MAX_BYTES) return false
            out.write(buf, 0, n)
        }
    }
}

/** Decode icon có downsample về kích thước hiển thị + cache LRU theo dung lượng. */
internal class IconDecoder(private val context: Context, private val maxPx: Int = 192) {
    private val cache = object : LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    operator fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, value: ImageBitmap) { cache.put(key, value) }

    fun fromFile(file: File): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        BitmapFactory.decodeFile(file.absolutePath, options(bounds))?.let(::scaled)
    }.getOrNull()

    fun fromAsset(path: String): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options(bounds)) }?.let(::scaled)
    }.getOrNull()

    private fun options(bounds: BitmapFactory.Options) = BitmapFactory.Options().apply {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        inSampleSize = sample
    }

    private fun scaled(b: Bitmap): ImageBitmap {
        val big = maxOf(b.width, b.height)
        if (big <= maxPx) return b.asImageBitmap()
        val k = maxPx.toFloat() / big
        val out = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        if (out !== b) b.recycle()
        return out.asImageBitmap()
    }
}
