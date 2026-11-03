package com.notdxbi.dxbi.tv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.URL

/**
 * The Stremio account behind the native UI: the same link-code login the TV app uses, the
 * addonCollectionGet list of installed addons, and stream requests to those addons. No WebView.
 */
object StremioSession {
    private const val PREFS = "cineflow-account"
    private const val KEY_AUTH = "authKey"
    private const val KEY_EMAIL = "email"
    private const val KEY_ADDONS = "addons"

    @Volatile private var cachedAddons: List<Addon> = emptyList()
    @Volatile private var cachedFor: String? = null
    private val streamCache = ConcurrentHashMap<String, Pair<Long, List<Stream>>>()

    data class Addon(
        val name: String,
        val transportUrl: String,
        val types: List<String>,
        val resources: List<String>,
    )

    data class LibraryItem(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val videoId: String?,
        val timeOffset: Long,
        val duration: Long,
        val removed: Boolean = false,
    )

    data class Stream(
        val name: String,
        val title: String,
        val infoHash: String?,
        val url: String?,
        val fileIdx: Int,
        val filename: String?,
        val sources: List<String>,
    )

    fun authKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUTH, null)

    fun email(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EMAIL, null)

    fun isLoggedIn(context: Context): Boolean = !authKey(context).isNullOrBlank()

    fun loginWithToken(context: Context, token: String): Pair<Boolean, String> {
        return try {
            val payload = JSONObject().put("type", "LoginWithToken").put("token", token)
            val result = post("https://api.strem.io/api/loginWithToken", payload)
            val body = JSONObject(result ?: return false to "Sin respuesta de Stremio")
            val value = body.optJSONObject("result")
                ?: return false to (body.optJSONObject("error")?.optString("message") ?: "No se pudo iniciar sesión")
            val auth = value.optString("authKey")
            if (auth.isBlank()) return false to "Stremio no devolvió la clave de sesión"
            val email = value.optJSONObject("user")?.optString("email").orEmpty()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_AUTH, auth)
                .putString(KEY_EMAIL, email)
                .remove(KEY_ADDONS)
                .apply()
            cachedAddons = emptyList()
            cachedFor = null
            true to email
        } catch (e: Exception) {
            false to (e.message ?: "Error de conexión")
        }
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        cachedAddons = emptyList()
        cachedFor = null
    }

    fun addons(context: Context, force: Boolean = false): List<Addon> {
        val key = authKey(context) ?: return emptyList()
        if (!force && cachedFor == key && cachedAddons.isNotEmpty()) return cachedAddons
        if (!force) {
            readCachedAddons(context)?.let {
                if (it.isNotEmpty()) {
                    cachedAddons = it
                    cachedFor = key
                    return it
                }
            }
        }
        val fresh = fetchAddons(key)
        if (fresh.isNotEmpty()) {
            streamCache.clear()
            cachedAddons = fresh
            cachedFor = key
            saveCachedAddons(context, fresh)
        }
        return fresh.ifEmpty { cachedAddons }
    }

    data class CatalogRow(val addon: String, val title: String, val type: String, val url: String)

    fun addonDescriptors(context: Context): List<JSONObject> {
        val auth = authKey(context) ?: return emptyList()
        return try {
            val payload = JSONObject().put("type", "AddonCollectionGet").put("authKey", auth).put("update", true)
            val raw = post("https://api.strem.io/api/addonCollectionGet", payload) ?: return emptyList()
            val result = JSONObject(raw).optJSONObject("result") ?: return emptyList()
            val arr = result.optJSONArray("addons") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    /** Meta from the addon that actually owns the id (PeLis uses tmdb: ids, Cinemeta uses tt). */
    fun metaJson(context: Context, type: String, id: String): JSONObject? {
        val descriptors = addonDescriptors(context)
        data class Candidate(val base: String, val priority: Int)
        val candidates = mutableListOf<Candidate>()
        for (descriptor in descriptors) {
            val manifest = descriptor.optJSONObject("manifest") ?: continue
            val transport = descriptor.optString("transportUrl").takeIf { it.isNotBlank() } ?: continue
            var supportsMeta = false
            var prefixes: List<String> = emptyList()
            val resources = manifest.optJSONArray("resources") ?: continue
            for (i in 0 until resources.length()) {
                when (val resource = resources.opt(i)) {
                    is String -> if (resource == "meta") {
                        supportsMeta = true
                        prefixes = manifest.optJSONArray("idPrefixes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    }
                    is JSONObject -> if (resource.optString("name") == "meta") {
                        supportsMeta = true
                        prefixes = resource.optJSONArray("idPrefixes")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                            ?: manifest.optJSONArray("idPrefixes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    }
                }
            }
            if (!supportsMeta) continue
            val priority = if (prefixes.any { id.startsWith(it) }) 0 else 1
            candidates += Candidate(transport.removeSuffix("/manifest.json").trimEnd('/'), priority)
        }
        for (candidate in candidates.sortedBy { it.priority }) {
            val url = "${candidate.base}/meta/$type/${Uri.encode(id, ":")}.json"
            val raw = get(url) ?: continue
            val meta = JSONObject(raw).optJSONObject("meta") ?: continue
            if (meta.optString("name").isNotBlank()) return meta
        }
        return null
    }

    fun catalogs(context: Context): List<CatalogRow> {
        val out = mutableListOf<CatalogRow>()
        val seen = mutableSetOf<String>()
        for (descriptor in addonDescriptors(context)) {
            val manifest = descriptor.optJSONObject("manifest") ?: continue
            val transport = descriptor.optString("transportUrl").takeIf { it.isNotBlank() } ?: continue
            val base = transport.removeSuffix("/manifest.json").trimEnd('/')
            val addonName = manifest.optString("name").ifBlank { "Addon" }
            val catalogs = manifest.optJSONArray("catalogs") ?: continue
            for (i in 0 until catalogs.length()) {
                val catalog = catalogs.optJSONObject(i) ?: continue
                val type = catalog.optString("type")
                val id = catalog.optString("id")
                if ((type != "movie" && type != "series") || id.isBlank()) continue
                val rawTitle = catalog.optString("name").takeIf { it.isNotBlank() && !it.equals("null", true) }
                val title = rawTitle ?: addonName
                val url = "$base/catalog/$type/${Uri.encode(id)}.json"
                if (!seen.add(url)) continue
                out += CatalogRow(addonName, title, type, url)
            }
        }
        return out
    }

    fun installAddon(context: Context, rawUrl: String): Pair<Boolean, String> {
        val auth = authKey(context) ?: return false to "Vincula tu cuenta primero."
        val url = normaliseAddonUrl(rawUrl)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false to "La dirección del addon no es válida."
        }
        val manifestRaw = get(url) ?: return false to "No se pudo descargar el manifiesto del addon."
        val manifest = try { JSONObject(manifestRaw) } catch (_: Exception) { return false to "El addon no devolvió un manifiesto válido." }
        if (manifest.optString("id").isBlank() || manifest.optString("name").isBlank()) {
            return false to "El manifiesto no tiene id o nombre."
        }
        val existing = addonDescriptors(context)
        val updated = JSONArray()
        for (descriptor in existing) {
            if (descriptor.optString("transportUrl") == url) continue
            updated.put(descriptor)
        }
        updated.put(JSONObject()
            .put("transportUrl", url)
            .put("transportName", "")
            .put("manifest", manifest)
            .put("flags", JSONObject()))
        return setAddonCollection(auth, updated, "Addon instalado")
    }

    fun removeAddon(context: Context, transportUrl: String): Pair<Boolean, String> {
        val auth = authKey(context) ?: return false to "Vincula tu cuenta primero."
        val updated = JSONArray()
        for (descriptor in addonDescriptors(context)) {
            if (descriptor.optString("transportUrl") == transportUrl) continue
            updated.put(descriptor)
        }
        return setAddonCollection(auth, updated, "Addon eliminado")
    }

    /**
     * Torrentio/AD publish a real error clip as a stream when the debrid account is down:
     * "Torrentio / AD error", "AllDebrid is currently unavailable!" and
     * /videos/failed_unavailable_v1.mp4. Playing that clip is worse than saying there is no
     * provider, so these placeholders are discarded before the picker or autoplay sees them.
     */
    fun isUnavailableStream(o: JSONObject): Boolean {
        val name = o.optString("name").lowercase()
        val title = o.optString("title").lowercase()
        val url = o.optString("url").lowercase()
        return url.contains("failed_unavailable") ||
            url.contains("/videos/failed_") ||
            name.contains("ad error") ||
            title.contains("ad error") ||
            (title.contains("unavailable") && (title.contains("debrid") || title.contains("alldebrid") || title.contains("try again later"))) ||
            title.contains("alldebrid is currently unavailable")
    }

    private fun setAddonCollection(auth: String, addons: JSONArray, success: String): Pair<Boolean, String> {
        return try {
            val raw = post("https://api.strem.io/api/addonCollectionSet",
                JSONObject().put("authKey", auth).put("addons", addons)) ?: return false to "Sin respuesta de Stremio."
            val body = JSONObject(raw)
            if (body.has("error")) return false to (body.optJSONObject("error")?.optString("message") ?: "Stremio rechazó el addon.")
            val result = body.optJSONObject("result")
            if (result != null && result.has("success") && !result.optBoolean("success")) {
                return false to "Stremio rechazó el addon."
            }
            cachedFor = null
            streamCache.clear()
            true to success
        } catch (e: Exception) { false to (e.message ?: "Error de conexión.") }
    }

    private fun normaliseAddonUrl(raw: String): String {
        var value = raw.trim()
        value = value.removePrefix("stremio://")
        value = value.removePrefix("stremio:")
        value = value.removePrefix("addon/")
        value = Uri.decode(value)
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://" + value.trimStart('/')
        if (!value.endsWith("/manifest.json")) value = value.trimEnd('/') + "/manifest.json"
        return value
    }

    fun hasStreamAddons(context: Context): Boolean =
        addons(context).any { it.resources.isEmpty() || "stream" in it.resources }

    fun isInLibrary(context: Context, id: String): Boolean =
        libraryRaw(context).any { !it.optBoolean("removed", false) && it.optString("_id") == id }

    fun addToLibrary(context: Context, meta: MetaInput): Pair<Boolean, String> {
        val auth = authKey(context) ?: return false to "Vincula tu cuenta primero."
        val existing = libraryRaw(context).firstOrNull { it.optString("_id") == meta.id }
        val item = existing ?: JSONObject()
        val now = nowIso()
        if (existing == null) {
            item.put("_id", meta.id)
            item.put("_ctime", now)
            item.put("name", meta.name)
            item.put("type", meta.type)
            item.put("poster", meta.poster.orEmpty())
            item.put("posterShape", "poster")
            item.put("background", meta.background.orEmpty())
            item.put("logo", "")
            item.put("year", meta.year.orEmpty())
            item.put("state", JSONObject()
                .put("timeWatched", 0)
                .put("timeOffset", 0)
                .put("overallTimeWatched", 0)
                .put("timesWatched", 0)
                .put("flaggedWatched", 0)
                .put("duration", 0)
                .put("video_id", meta.id)
                .put("watched", "")
                .put("noNotif", false)
                .put("season", 0)
                .put("episode", 0))
        }
        item.put("removed", false)
        item.put("temp", false)
        item.put("_mtime", now)
        val payload = JSONObject()
            .put("authKey", auth)
            .put("collection", "libraryItem")
            .put("changes", JSONArray().put(item))
        return putDatastore(payload, "Añadido a tu biblioteca")
    }

    fun removeFromLibrary(context: Context, id: String): Pair<Boolean, String> {
        val auth = authKey(context) ?: return false to "Vincula tu cuenta primero."
        val existing = libraryRaw(context).firstOrNull { it.optString("_id") == id }
            ?: return true to "No estaba en la biblioteca."
        existing.put("removed", true)
        existing.put("temp", false)
        existing.put("_mtime", nowIso())
        return putDatastore(JSONObject()
            .put("authKey", auth)
            .put("collection", "libraryItem")
            .put("changes", JSONArray().put(existing)), "Quitado de tu biblioteca")
    }

    fun syncProgress(context: Context, streamJson: String?, position: Long, duration: Long, ended: Boolean) {
        if (streamJson.isNullOrBlank() || position <= 5_000L) return
        val stream = try { JSONObject(streamJson) } catch (_: Exception) { return }
        val id = stream.optString("metaId").ifBlank { stream.optString("videoId") }
        if (id.isBlank()) return
        val auth = authKey(context) ?: return
        try {
            val existing = libraryRaw(context).firstOrNull { it.optString("_id") == id }
            val item = existing ?: JSONObject()
            val now = nowIso()
            if (existing == null) {
                item.put("_id", id)
                item.put("_ctime", now)
                item.put("name", stream.optString("name", stream.optString("title", "CineFlow")))
                item.put("type", stream.optString("metaType", "movie"))
                item.put("poster", stream.optString("poster"))
                item.put("posterShape", "poster")
                item.put("background", "")
                item.put("logo", "")
                item.put("year", "")
            }
            val state = item.optJSONObject("state") ?: JSONObject()
            state.put("timeOffset", position)
            if (duration > 0) state.put("duration", duration)
            if (!ended) state.put("lastWatched", now)
            state.put("video_id", stream.optString("videoId").ifBlank { id })
            state.put("season", stream.optInt("season", 0))
            state.put("episode", stream.optInt("episode", 0))
            item.put("state", state)
            item.put("removed", false)
            item.put("temp", false)
            item.put("_mtime", now)
            putDatastore(JSONObject()
                .put("authKey", auth)
                .put("collection", "libraryItem")
                .put("changes", JSONArray().put(item)), null)
        } catch (_: Exception) { /* sync is best effort */ }
    }

    data class MetaInput(val id: String, val type: String, val name: String, val poster: String?, val background: String?, val year: String?)

    fun libraryRaw(context: Context): List<JSONObject> {
        val auth = authKey(context) ?: return emptyList()
        return try {
            val payload = JSONObject()
                .put("authKey", auth)
                .put("collection", "libraryItem")
                .put("ids", JSONArray())
                .put("all", true)
            val raw = post("https://api.strem.io/api/datastoreGet", payload) ?: return emptyList()
            val result = JSONObject(raw).opt("result")
            val arr = when (result) {
                is JSONArray -> result
                is JSONObject -> result.optJSONArray("items") ?: result.optJSONArray("library")
                else -> null
            } ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun putDatastore(payload: JSONObject, successMessage: String?): Pair<Boolean, String> {
        return try {
            val raw = post("https://api.strem.io/api/datastorePut", payload) ?: return false to "Sin respuesta de Stremio."
            val body = JSONObject(raw)
            if (body.has("error")) false to (body.optJSONObject("error")?.optString("message") ?: "Error de Stremio.")
            else true to (successMessage ?: "")
        } catch (e: Exception) { false to (e.message ?: "Error de conexión.") }
    }

    private fun nowIso(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date())
    }

    private fun fetchAddons(auth: String): List<Addon> {
        return try {
            val payload = JSONObject()
                .put("type", "AddonCollectionGet")
                .put("authKey", auth)
                .put("update", true)
            val raw = post("https://api.strem.io/api/addonCollectionGet", payload) ?: return emptyList()
            val root = JSONObject(raw)
            val result = root.optJSONObject("result") ?: return emptyList()
            val arr = result.optJSONArray("addons") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val manifest = d.optJSONObject("manifest") ?: return@mapNotNull null
                val transport = d.optString("transportUrl")
                if (transport.isBlank()) return@mapNotNull null
                val types = manifest.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                val resources = parseResources(manifest.opt("resources"))
                Addon(
                    name = manifest.optString("name").ifBlank { "Addon" },
                    transportUrl = transport,
                    types = types,
                    resources = resources,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseResources(value: Any?): List<String> {
        val out = mutableListOf<String>()
        if (value is JSONArray) {
            for (i in 0 until value.length()) {
                when (val item = value.opt(i)) {
                    is String -> out += item
                    is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
        }
        return out.distinct()
    }

    /** Continue-watching/library entries from the account's datastore. */
    fun library(context: Context): List<LibraryItem> {
        val auth = authKey(context) ?: return emptyList()
        return try {
            val payload = JSONObject()
                .put("authKey", auth)
                .put("collection", "libraryItem")
                .put("ids", JSONArray())
                .put("all", true)
            val raw = post("https://api.strem.io/api/datastoreGet", payload) ?: return emptyList()
            val root = JSONObject(raw)
            val result = root.opt("result")
            val arr = when (result) {
                is JSONArray -> result
                is JSONObject -> result.optJSONArray("items") ?: result.optJSONArray("library")
                else -> null
            } ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optBoolean("removed", false)) return@mapNotNull null
                val state = o.optJSONObject("state") ?: JSONObject()
                val offset = state.optLong("timeOffset", state.optLong("time_offset", 0L))
                val duration = state.optLong("duration", 0L)
                LibraryItem(
                    id = o.optString("_id").ifBlank { o.optString("id") },
                    type = o.optString("type", "movie"),
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    videoId = state.optString("videoId").ifBlank { state.optString("video_id") }.takeIf { it.isNotBlank() },
                    timeOffset = offset,
                    duration = duration,
                    removed = o.optBoolean("removed", false),
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Streams from the viewer's own addons, in their installed order. */
    fun streams(context: Context, type: String, id: String): List<Stream> {
        val cacheKey = "$type:$id"
        streamCache[cacheKey]?.let { (at, cached) ->
            if (System.currentTimeMillis() - at < 600_000L && cached.isNotEmpty()) return cached
        }
        val list = addons(context).filter {
            (type in it.types || it.types.isEmpty()) && (it.resources.isEmpty() || "stream" in it.resources)
        }
        if (list.isEmpty()) return emptyList()
        // One addon at a time: Torrentio AD, MediaFusion and Peerflix all use the same
        // AllDebrid account, and firing them together can trigger a temporary AD block.
        val pool = Executors.newSingleThreadExecutor()
        val completion = ExecutorCompletionService<Pair<Int, List<Stream>>>(pool)
        list.forEachIndexed { index, addon ->
            completion.submit(java.util.concurrent.Callable { index to fetchAddonStreams(addon, type, id) })
        }
        val byAddon = HashMap<Int, List<Stream>>()
        val deadline = System.currentTimeMillis() + 10_000L
        try {
            for (i in list.indices) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val future = completion.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                val (index, streams) = future.get()
                byAddon[index] = streams
                // Stop as soon as one addon has a usable answer. Querying the other AllDebrid
                // addons (MediaFusion, Peerflix) multiplies AD link resolutions and can trigger
                // its temporary block; they remain available when this one has no links.
                if (streams.isNotEmpty()) break
            }
        } catch (_: Exception) {
            // use whatever arrived
        } finally {
            pool.shutdownNow()
        }
        val out = mutableListOf<Stream>()
        val seen = mutableSetOf<String>()
        for (i in list.indices) {
            for (stream in byAddon[i].orEmpty()) {
                val key = stream.infoHash ?: stream.url ?: continue
                if (!seen.add(key)) continue
                out += stream
                if (out.size >= 80) break
            }
            if (out.size >= 80) break
        }
        if (out.isNotEmpty()) streamCache[cacheKey] = System.currentTimeMillis() to out
        return out
    }

    private fun fetchAddonStreams(addon: Addon, type: String, id: String): List<Stream> {
        val base = addon.transportUrl.removeSuffix("/manifest.json").trimEnd('/')
        val url = "$base/stream/$type/${Uri.encode(id)}.json"
        return try {
            val raw = get(url) ?: return emptyList()
            val arr = JSONObject(raw).optJSONArray("streams") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (isUnavailableStream(o)) return@mapNotNull null
                val hints = o.optJSONObject("behaviorHints")
                val stream = Stream(
                    name = o.optString("name").lineSequence().firstOrNull().orEmpty().ifBlank { addon.name },
                    title = o.optString("title"),
                    infoHash = o.optString("infoHash").takeIf { it.isNotBlank() },
                    url = o.optString("url").takeIf { it.isNotBlank() },
                    fileIdx = if (o.has("fileIdx")) o.optInt("fileIdx") else -1,
                    filename = hints?.optString("filename")?.takeIf { it.isNotBlank() },
                    sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                )
                if (stream.infoHash.isNullOrBlank() && stream.url.isNullOrBlank()) null
                else stream.copy(name = stream.name.ifBlank { addon.name })
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveCachedAddons(context: Context, addons: List<Addon>) {
        val arr = JSONArray()
        for (a in addons) arr.put(JSONObject()
            .put("name", a.name)
            .put("transportUrl", a.transportUrl)
            .put("types", JSONArray(a.types))
            .put("resources", JSONArray(a.resources)))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ADDONS, arr.toString()).apply()
    }

    private fun readCachedAddons(context: Context): List<Addon>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDONS, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Addon(
                    name = o.optString("name"),
                    transportUrl = o.optString("transportUrl"),
                    types = o.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    resources = o.optJSONArray("resources")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                )
            }
        } catch (_: Exception) { null }
    }

    private fun post(url: String, payload: JSONObject): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "CineFlow-TV")
        }
        connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 18_000
            setRequestProperty("User-Agent", "CineFlow-TV")
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
