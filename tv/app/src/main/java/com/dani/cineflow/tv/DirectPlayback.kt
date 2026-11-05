package com.dani.cineflow.tv

import android.app.Activity
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Resolves a continue-watching item and opens the native player directly, with no detail screen. */
object DirectPlayback {
    data class Target(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val videoId: String?,
        val startMs: Long,
        val season: Int = 0,
        val episode: Int = 0,
        val episodeTitle: String? = null,
        val imdbId: String? = null,
    )

    fun play(
        activity: Activity,
        target: Target,
        onStatus: (String) -> Unit = {},
        onError: (String) -> Unit,
        onStarted: () -> Unit = {},
    ) {
        thread(name = "cineflow-direct-play", isDaemon = true) {
            val videoId = target.videoId ?: target.id
            activity.runOnUiThread { onStatus("Buscando proveedores…") }
            var streams = StremioSession.streams(activity, target.type, videoId)
            if (streams.isEmpty()) {
                activity.runOnUiThread { onStatus("Probando Torrentio…") }
                streams = torrentio(target.type, videoId)
            }
            streams = PlaybackQuality.apply(activity, streams)
            val stream = streams.firstOrNull()
            if (stream == null) {
                activity.runOnUiThread { onError("No hay proveedores de AllDebrid disponibles.") }
                return@thread
            }
            activity.runOnUiThread { onStatus(if (stream.infoHash != null) "Conectando al torrent…" else "Abriendo ${stream.name.ifBlank { "proveedor" }}…") }
            val trackers = stream.sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
            val url: String
            val torrent: Boolean
            if (!stream.infoHash.isNullOrBlank()) {
                url = TorrentEngine.open(activity.applicationContext, stream.infoHash, stream.fileIdx, trackers, stream.filename.orEmpty())
                torrent = true
            } else {
                url = stream.url.orEmpty()
                torrent = false
            }
            if (url.isBlank()) {
                activity.runOnUiThread { onError("No se pudo abrir el proveedor.") }
                return@thread
            }
            val json = JSONObject()
                .put("url", url)
                .put("torrent", torrent)
                .put("title", target.name)
                .put("subtitle", stream.name)
                .put("audioLanguage", if (NativeSettings.value(activity, "audioLanguage", "spa") == "original") "" else NativeSettings.value(activity, "audioLanguage", "spa"))
                .put("clock", true)
                .put("metaId", target.id)
                .put("metaType", target.type)
                .put("name", target.name)
                .put("poster", target.poster.orEmpty())
                .put("videoId", videoId)
                .put("imdbId", target.imdbId.orEmpty())
                .put("season", target.season)
                .put("episode", target.episode)
                .put("episodeTitle", target.episodeTitle.orEmpty())
                .put("startMs", target.startMs.coerceAtLeast(0))
                .put("hash", "native:${target.id}")
                .put("openDetailOnBack", true)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                activity.startActivity(Intent(activity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM, json.toString()))
                onStarted()
            }
        }
    }

    private fun torrentio(type: String, id: String): List<StremioSession.Stream> {
        return try {
            val connection = (URL("https://torrentio.strem.fun/stream/$type/$id.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", "CineFlow-TV")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val arr = JSONObject(body).optJSONArray("streams") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (StremioSession.isUnavailableStream(o)) return@mapNotNull null
                val hints = o.optJSONObject("behaviorHints")
                StremioSession.Stream(
                    name = o.optString("name").lineSequence().firstOrNull().orEmpty(),
                    title = o.optString("title"),
                    infoHash = o.optString("infoHash").takeIf { it.isNotBlank() },
                    url = o.optString("url").takeIf { it.isNotBlank() },
                    fileIdx = if (o.has("fileIdx")) o.optInt("fileIdx") else -1,
                    filename = hints?.optString("filename")?.takeIf { it.isNotBlank() },
                    sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
