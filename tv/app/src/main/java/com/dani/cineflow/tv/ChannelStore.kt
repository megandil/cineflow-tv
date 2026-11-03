package com.dani.cineflow.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last channels opened from CineFlow Canales. Stored natively (the WebView and the native
 * ChannelsActivity do not share a DOM), so the Continue Watching live row on Home can show them
 * after the activity is gone.
 */
object ChannelStore {
    private const val PREFS = "recent-channels"
    private const val KEY = "items"
    private const val MAX = 8
    @Volatile private var guide: Map<String, String> = emptyMap()

    fun remember(context: Context, name: String, url: String, group: String, epgId: String?, program: String?) {
        if (url.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until items.length()) {
            val old = items.optJSONObject(i) ?: continue
            if (old.optString("url") == url || old.optString("name").equals(name, ignoreCase = true)) continue
            kept.put(old)
        }
        val entry = JSONObject()
            .put("name", name)
            .put("url", url)
            .put("group", group)
            .put("epgId", epgId ?: "")
            .put("program", program ?: "")
            .put("at", System.currentTimeMillis())
        val out = JSONArray().put(entry)
        for (i in 0 until minOf(kept.length(), MAX - 1)) out.put(kept.opt(i))
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun recent(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        if (guide.isEmpty()) return raw
        return try {
            val items = JSONArray(raw)
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val live = guide[item.optString("epgId")]
                if (!live.isNullOrBlank()) item.put("program", live)
            }
            items.toString()
        } catch (_: Exception) {
            raw
        }
    }

    fun setGuide(programs: Map<String, String>) {
        guide = programs
    }

    /** Keep the live guide of an already-watched channel fresh while Canales is open. */
    fun refreshPrograms(context: Context, programs: Map<String, String>) {
        if (programs.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { return }
        var changed = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("epgId")
            val current = programs[id] ?: continue
            if (current.isNotBlank() && item.optString("program") != current) {
                item.put("program", current)
                changed = true
            }
        }
        if (changed) prefs.edit().putString(KEY, items.toString()).apply()
    }

    /** Opens an AceStream content id in AceStream, or a plain HTTP channel in the native player. */
    fun play(context: Context, json: String) {
        val data = try { JSONObject(json) } catch (_: Exception) { return }
        val url = data.optString("url")
        val name = data.optString("name", "Canal")
        if (url.isBlank()) return
        val id = aceId(url)
        if (id != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("acestream://$id")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "AceStream no está instalado", Toast.LENGTH_LONG).show()
            }
            return
        }
        val stream = JSONObject()
            .put("url", url)
            .put("title", name)
            .put("subtitle", "CineFlow · Canales")
            .put("clock", false)
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_STREAM, stream.toString())
        )
    }

    fun aceId(url: String): String? {
        val m = Regex("""(?:^acestream://|ace/getstream\?id=)([A-Za-z0-9]+)""").find(url.trim())
        return m?.groupValues?.get(1)?.takeIf { it.length >= 16 }
    }
}
