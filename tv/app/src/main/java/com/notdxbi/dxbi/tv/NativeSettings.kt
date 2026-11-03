package com.notdxbi.dxbi.tv

import android.content.Context
import org.json.JSONObject

/** CineFlow's native settings, kept in the same shape as the old TV plugin. */
object NativeSettings {
    private const val PREFS = "cineflow-settings"
    private val DEFAULTS = mapOf(
        "performance" to "true", "previews" to "false", "ratings" to "true", "reviews" to "true",
        "wideContinue" to "true", "billboard" to "true", "autoPick" to "true", "maxQuality" to "2160",
        "tvTorrents" to "false", "playerClock" to "true", "remainingTime" to "true",
        "skipButtons" to "true", "autoSkip" to "off", "subtitlesStart" to "off", "subtitleStyle" to "stremio",
        "preloadNext" to "true", "upNext" to "10", "uiSounds" to "false", "soundVolume" to "subtle",
        "introSound" to "true", "splash" to "true", "addonTools" to "true", "streamTools" to "true",
        "audioLanguage" to "spa", "subtitlesLanguage" to "off", "interfaceLanguage" to "es-ES",
    )

    fun value(context: Context, key: String, fallback: String = DEFAULTS[key] ?: ""): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(key)) return prefs.getString(key, fallback) ?: fallback
        // migrate the values the old WebView plugin already saved, when present
        try {
            val old = context.getSharedPreferences("plugin-settings", Context.MODE_PRIVATE).getString("dxbi", null)
            val json = JSONObject(old ?: "")
            if (json.has(key)) return json.optString(key, fallback)
        } catch (_: Exception) { /* no old settings */ }
        return fallback
    }

    fun bool(context: Context, key: String, fallback: Boolean = DEFAULTS[key] == "true"): Boolean =
        value(context, key, if (fallback) "true" else "false") == "true"

    fun set(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }
}
