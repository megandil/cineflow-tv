package com.dani.cineflow.tv

import android.content.Context
import android.view.WindowManager

/** Caps stream choices to the user's quality setting and to the TV's own panel. */
object PlaybackQuality {
    fun tvHeight(context: Context): Int {
        val dm = context.resources.displayMetrics
        var height = maxOf(dm.heightPixels, dm.widthPixels)
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val mode = wm.defaultDisplay.mode
            height = maxOf(height, mode.physicalHeight, mode.physicalWidth)
        } catch (_: Exception) { }
        return height
    }

    fun effectiveMax(context: Context): Int {
        val configured = NativeSettings.value(context, "maxQuality", "2160").toIntOrNull() ?: 2160
        val tv = when {
            tvHeight(context) >= 2000 -> 2160
            tvHeight(context) >= 1000 -> 1080
            tvHeight(context) >= 700 -> 720
            else -> 480
        }
        return minOf(configured, tv)
    }

    fun qualityOf(stream: StremioSession.Stream): Int {
        val text = (stream.name + " " + (stream.title ?: "")).lowercase()
        return when {
            text.contains("2160") || text.contains("4k") -> 2160
            text.contains("1080") -> 1080
            text.contains("720") -> 720
            text.contains("480") -> 480
            else -> 0
        }
    }

    fun apply(context: Context, streams: List<StremioSession.Stream>): List<StremioSession.Stream> {
        if (streams.isEmpty()) return streams
        val max = effectiveMax(context)
        val within = streams.filter { val q = qualityOf(it); q == 0 || q <= max }
        val chosen = within.ifEmpty { streams }
        return chosen.sortedByDescending { qualityOf(it) }
    }
}
