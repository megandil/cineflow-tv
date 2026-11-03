package com.dani.cineflow.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local CineFlow profiles. Stremio auth and addons stay shared at account level. */
object Profiles {
    private const val PREFS = "cineflow-profiles"
    private const val KEY = "profiles"
    private const val ACTIVE = "active"
    private val COLORS = intArrayOf(
        0xFF255E72.toInt(), 0xFF8B3A3A.toInt(), 0xFF6B4C9A.toInt(),
        0xFF2F6B4F.toInt(), 0xFF9A5A2A.toInt(), 0xFF415A77.toInt(),
    )

    data class Profile(val id: String, val name: String, val color: Int, val main: Boolean)

    @Synchronized
    fun list(context: Context): List<Profile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            val main = Profile("main", "Principal", COLORS[0], true)
            save(context, listOf(main), "main")
            return listOf(main)
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Profile(
                    id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    name = o.optString("name").ifBlank { "Perfil" },
                    color = o.optInt("color", COLORS[0]),
                    main = o.optBoolean("main", false),
                )
            }.ifEmpty { listOf(Profile("main", "Principal", COLORS[0], true)) }
        } catch (_: Exception) {
            listOf(Profile("main", "Principal", COLORS[0], true))
        }
    }

    fun activeId(context: Context): String {
        val ids = list(context).map { it.id }.toSet()
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, "main")
        return if (saved in ids) saved ?: "main" else "main"
    }

    fun active(context: Context): Profile = list(context).firstOrNull { it.id == activeId(context) } ?: list(context).first()

    fun isMain(context: Context): Boolean = active(context).main

    fun setActive(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE, id).apply()
    }

    fun create(context: Context, name: String): Profile {
        val current = list(context)
        val id = "p${System.currentTimeMillis()}"
        val profile = Profile(id, name.trim().ifBlank { "Perfil ${current.size + 1}" }, COLORS[current.size % COLORS.size], false)
        save(context, current + profile, activeId(context))
        setActive(context, id)
        return profile
    }

    fun delete(context: Context, id: String) {
        val current = list(context)
        val target = current.firstOrNull { it.id == id } ?: return
        if (target.main) return
        val next = current.filterNot { it.id == id }
        val nextActive = if (activeId(context) == id) next.firstOrNull { it.main }?.id ?: next.first().id else activeId(context)
        save(context, next, nextActive)
        context.getSharedPreferences("cineflow-native-progress", Context.MODE_PRIVATE).edit()
            .remove("items_$id").remove("watched_$id").apply()
        context.getSharedPreferences("cineflow-profile-library", Context.MODE_PRIVATE).edit()
            .remove("items_$id").apply()
        val settings = context.getSharedPreferences("cineflow-settings", Context.MODE_PRIVATE)
        val editor = settings.edit()
        for (key in settings.all.keys) {
            if (key.startsWith("p_${id}_")) editor.remove(key)
        }
        editor.apply()
    }

    fun settingsKey(context: Context, key: String): String {
        val id = activeId(context)
        return if (id == "main") key else "p_${id}_$key"
    }

    fun progressKey(context: Context): String {
        val id = activeId(context)
        return if (id == "main") "items" else "items_$id"
    }

    private fun save(context: Context, profiles: List<Profile>, active: String) {
        val arr = JSONArray()
        for (p in profiles) arr.put(JSONObject()
            .put("id", p.id).put("name", p.name).put("color", p.color).put("main", p.main))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString())
            .putString(ACTIVE, active)
            .apply()
    }
}
