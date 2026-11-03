package com.dani.cineflow.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local "Continue watching" state for the native player, independent from the old WebView. */
object NativeProgressStore {
    private const val PREFS = "cineflow-native-progress"
    private fun key(context: Context): String = Profiles.progressKey(context)
    private const val MAX = 16

    data class Entry(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val videoId: String?,
        val season: Int,
        val episode: Int,
        val episodeTitle: String?,
        val position: Long,
        val duration: Long,
        val updated: Long,
    )

    fun record(context: Context, streamJson: String?, position: Long, duration: Long, ended: Boolean) {
        if (streamJson.isNullOrBlank()) return
        val stream = try { JSONObject(streamJson) } catch (_: Exception) { return }
        val id = stream.optString("metaId").ifBlank { stream.optString("videoId") }
        if (id.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = try { JSONArray(prefs.getString(key(context), "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        if (!ended && position > 5_000L) {
            kept.put(JSONObject()
                .put("id", id)
                .put("type", stream.optString("metaType", "movie"))
                .put("name", stream.optString("name", stream.optString("title", "CineFlow")))
                .put("poster", stream.optString("poster"))
                .put("videoId", stream.optString("videoId"))
                .put("season", stream.optInt("season", 0).takeIf { it > 0 } ?: seasonFrom(stream.optString("videoId")))
                .put("episode", stream.optInt("episode", 0).takeIf { it > 0 } ?: episodeFrom(stream.optString("videoId")))
                .put("episodeTitle", stream.optString("episodeTitle"))
                .put("position", position)
                .put("duration", duration)
                .put("updated", System.currentTimeMillis()))
        }
        val out = JSONArray()
        val upto = minOf(kept.length(), MAX)
        // newest first: the new entry is the last one we wrote
        for (i in 0 until upto) {
            val o = kept.optJSONObject(kept.length() - 1 - i) ?: continue
            out.put(o)
        }
        prefs.edit().putString(key(context), out.toString()).apply()
    }

    fun list(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(context), "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val videoId = o.optString("videoId").takeIf { it.isNotBlank() }
                Entry(
                    id = o.optString("id"),
                    type = o.optString("type", "movie"),
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    videoId = videoId,
                    season = o.optInt("season", 0).takeIf { it > 0 } ?: seasonFrom(videoId.orEmpty()),
                    episode = o.optInt("episode", 0).takeIf { it > 0 } ?: episodeFrom(videoId.orEmpty()),
                    episodeTitle = o.optString("episodeTitle").takeIf { it.isNotBlank() },
                    position = o.optLong("position"),
                    duration = o.optLong("duration"),
                    updated = o.optLong("updated"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun get(context: Context, id: String): Entry? = list(context).firstOrNull { it.id == id }

    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = try { JSONArray(prefs.getString(key(context), "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        prefs.edit().putString(key(context), kept.toString()).apply()
    }

    private fun seasonFrom(videoId: String): Int = videoId.split(':').let { p -> if (p.size >= 3) p[p.size - 2].toIntOrNull() ?: 0 else 0 }
    private fun episodeFrom(videoId: String): Int = videoId.split(':').let { p -> if (p.size >= 3) p[p.size - 1].toIntOrNull() ?: 0 else 0 }

    private fun watchedKey(context: Context): String =
        if (Profiles.isMain(context)) "watched" else "watched_${Profiles.activeId(context)}"

    fun isWatched(context: Context, videoId: String): Boolean {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(watchedKey(context), "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).any { arr.optString(it) == videoId }
        } catch (_: Exception) { false }
    }

    fun markWatched(context: Context, videoId: String) {
        if (videoId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(watchedKey(context), "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if ((0 until arr.length()).any { arr.optString(it) == videoId }) return
        arr.put(videoId)
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - 200) until arr.length()) out.put(arr.optString(i))
        prefs.edit().putString(watchedKey(context), out.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key(context)).remove(watchedKey(context)).apply()
    }
}
