package com.dani.cineflow.tv

/** Prefers clean metahub posters (no baked-in rating chips) when an IMDb id is available. */
object MediaArt {
    fun poster(id: String?, fallback: String?): String? {
        if (!id.isNullOrBlank() && id.startsWith("tt")) {
            return "https://images.metahub.space/poster/large/$id/img"
        }
        return fallback
    }
}
