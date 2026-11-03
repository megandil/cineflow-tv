package com.dani.cineflow.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local library for non-main profiles. The Principal keeps the Stremio account library. */
object ProfileLibrary {
    private const val PREFS = "cineflow-profile-library"
    data class Item(val id: String, val type: String, val name: String, val poster: String?, val background: String?, val releaseInfo: String?)

    private fun key(context: Context): String = "items_${Profiles.activeId(context)}"

    fun list(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(context), "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    id = o.optString("id"),
                    type = o.optString("type", "movie"),
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    background = o.optString("background").takeIf { it.isNotBlank() },
                    releaseInfo = o.optString("releaseInfo").takeIf { it.isNotBlank() },
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    fun contains(context: Context, id: String): Boolean = list(context).any { it.id == id }

    fun add(context: Context, item: Item) {
        val next = list(context).filterNot { it.id == item.id } + item
        save(context, next)
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    private fun save(context: Context, items: List<Item>) {
        val arr = JSONArray()
        for (item in items) arr.put(JSONObject()
            .put("id", item.id).put("type", item.type).put("name", item.name)
            .put("poster", item.poster.orEmpty()).put("background", item.background.orEmpty())
            .put("releaseInfo", item.releaseInfo.orEmpty()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(context), arr.toString()).apply()
    }
}
