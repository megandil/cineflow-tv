package com.notdxbi.dxbi.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small, memory-capped image loader for the native Home. Coil/OkHttp would be overkill here. */
object NativeImageLoader {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(view: ImageView, url: String?, sample: Int = 2) {
        if (url.isNullOrBlank()) return
        view.tag = url
        cache.get(url)?.let { view.setImageBitmap(it); return }
        pool.execute {
            val bitmap = try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CineFlow-TV")
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
            } catch (error: Exception) {
                android.util.Log.e("CineFlow", "image failed: $url", error)
                null
            }
            if (bitmap == null) return@execute
            cache.put(url, bitmap)
            main.post {
                if (view.tag == url) view.setImageBitmap(bitmap)
            }
        }
    }
}
