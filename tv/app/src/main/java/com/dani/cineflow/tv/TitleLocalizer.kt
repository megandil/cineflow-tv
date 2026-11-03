package com.dani.cineflow.tv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Spanish titles without requiring a TMDB API key.
 *
 * Batch 1: Wikidata labels in Spanish, keyed by IMDb id (one SPARQL request per chunk of 50).
 * Batch 2: TMDB's public Spanish page for films/series Wikidata does not label in Spanish
 * (for example Avengers: Infinity War). Only a handful per screen fall into this second batch.
 */
object TitleLocalizer {
    data class Lookup(val key: String, val imdb: String?, val tmdb: Long?, val type: String)

    private val cache = ConcurrentHashMap<String, String>()
    private var cacheFile: File? = null
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, "spanish-titles.json")
        cacheFile = file
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) cache[key] = value
                }
            }
        } catch (_: Exception) { /* cache is optional */ }
    }

    fun spanish(context: Context, entries: List<Lookup>): Map<String, String> {
        init(context)
        if (entries.isEmpty()) return emptyMap()
        val byKey = entries.associateBy { it.key }
        val out = HashMap<String, String>()
        for (entry in entries) cache[entry.key]?.let { out[entry.key] = it }
        val wanted = entries.filter { it.imdb != null && !cache.containsKey(it.key) }
        val imdbIds = wanted.mapNotNull { it.imdb }.distinct()
        for (chunk in imdbIds.chunked(50)) {
            try {
                val labels = wikidata(chunk)
                for ((imdb, title) in labels) {
                    val entry = wanted.firstOrNull { it.imdb == imdb } ?: continue
                    if (title.isNotBlank()) {
                        cache[entry.key] = title
                        out[entry.key] = title
                    }
                }
            } catch (_: Exception) { /* keep original titles */ }
        }
        // TMDB fallback only for titles Wikidata did not translate.
        val missing = wanted.filter { !out.containsKey(it.key) && it.tmdb != null }.take(8)
        for (entry in missing) {
            val title = tmdb(entry.tmdb!!.toLong(), entry.type)
            if (!title.isNullOrBlank()) {
                cache[entry.key] = title
                out[entry.key] = title
            }
        }
        save()
        return out
    }

    @Synchronized
    private fun save() {
        val file = cacheFile ?: return
        try {
            val json = JSONObject()
            for ((key, value) in cache) json.put(key, value)
            file.writeText(json.toString())
        } catch (_: Exception) { /* ignore cache write errors */ }
    }

    private fun wikidata(imdbIds: List<String>): Map<String, String> {
        if (imdbIds.isEmpty()) return emptyMap()
        val values = imdbIds.joinToString(" ") { "\"$it\"" }
        val query = """SELECT ?imdb ?title WHERE { VALUES ?imdb { $values } ?film wdt:P345 ?imdb . ?film rdfs:label ?title FILTER(lang(?title)="es") }"""
        val url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, "UTF-8")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "CineFlow-TV/2.23 (Spanish titles)")
            setRequestProperty("Accept", "application/sparql-results+json")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val bindings = JSONObject(body).optJSONObject("results")?.optJSONArray("bindings") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (i in 0 until bindings.length()) {
            val row = bindings.optJSONObject(i) ?: continue
            val imdb = row.optJSONObject("imdb")?.optString("value").orEmpty()
            val title = row.optJSONObject("title")?.optString("value").orEmpty()
            if (imdb.isNotBlank() && title.isNotBlank()) out[imdb] = clean(title)
        }
        return out
    }

    private fun tmdb(id: Long, type: String): String? {
        val kind = if (type == "series") "tv" else "movie"
        val connection = (URL("https://www.themoviedb.org/$kind/$id?language=es-ES").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val match = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
            var title = htmlUnescape(match.groupValues[1])
            title = title.substringBefore(" — The Movie Database").substringBefore(" - The Movie Database")
            title = title.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()
            title.takeIf { it.isNotBlank() && !it.equals("The Movie Database", true) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun clean(value: String): String =
        value.replace(Regex("""\s*\((película|serie|programa de televisión)[^)]*\)\s*$"""), "").trim()

    private fun htmlUnescape(value: String): String = value
        .replace("&#8212;", "—").replace("&#8211;", "–")
        .replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
}
