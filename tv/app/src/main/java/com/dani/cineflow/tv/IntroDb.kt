package com.dani.cineflow.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** TheIntroDB: public intro/recap/credits timestamps for an IMDb title and episode. */
object IntroDb {
    private const val BASE = "https://api.theintrodb.org/v4/media"

    /** Returns null when the API has no timestamps, so the player just keeps playing. */
    fun segments(imdbId: String, season: Int, episode: Int): List<PlayerActivity.SkipSegment> {
        if (!imdbId.startsWith("tt")) return emptyList()
        return try {
            var url = "$BASE?imdb_id=" + URLEncoder.encode(imdbId, "UTF-8")
            if (season > 0 && episode > 0) url += "&season=$season&episode=$episode"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return emptyList()
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val root = JSONObject(body)
            val out = mutableListOf<PlayerActivity.SkipSegment>()
            for (type in listOf("intro", "recap", "credits")) {
                val arr = root.optJSONArray(type) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val start = o.optLong("start_ms", -1L)
                    val end = if (o.isNull("end_ms")) -1L else o.optLong("end_ms", -1L)
                    if (start < 0) continue
                    out += PlayerActivity.SkipSegment(type, start / 1000.0, if (end < 0) -1.0 else end / 1000.0)
                }
            }
            out
        } catch (_: Exception) { emptyList() }
    }
}
