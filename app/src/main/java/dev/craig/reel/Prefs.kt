package dev.craig.reel

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reel", Context.MODE_PRIVATE)

    /** Key set on the TV wins; otherwise fall back to the one baked in at build time. */
    var tmdbKey: String
        get() = sp.getString("tmdb_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_KEY
        set(v) = sp.edit().putString("tmdb_key", v.trim()).apply()

    var region: String
        get() = sp.getString("region", null)?.takeIf { it.isNotBlank() } ?: "US"
        set(v) = sp.edit().putString("region", v.trim().uppercase()).apply()

    var mediaType: MediaType
        get() = if (sp.getString("media_type", "movie") == "tv") MediaType.TV else MediaType.MOVIE
        set(v) = sp.edit().putString("media_type", v.path).apply()

    var sort: Sort
        get() = Sort.entries.firstOrNull { it.name == sp.getString("sort", null) } ?: Sort.POPULAR
        set(v) = sp.edit().putString("sort", v.name).apply()

    var hiddenApps: Set<String>
        get() = sp.getStringSet("hidden_apps", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_apps", v).apply()

    /** Package names in the order you arranged them; anything not listed follows A–Z. */
    var appOrder: List<String>
        get() = (sp.getString("app_order", null) ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("app_order", v.joinToString("\n")).apply()

    var disabledProviders: Set<String>
        get() = sp.getStringSet("disabled_providers", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("disabled_providers", v).apply()

    var recentGenres: List<String>
        get() = (sp.getString("recent_genres", null) ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("recent_genres", v.take(MAX_RECENT).joinToString("\n")).apply()

    fun addRecentGenre(q: String) {
        recentGenres = listOf(q) + recentGenres.filterNot { it.equals(q, ignoreCase = true) }
    }

    // ---------- Weather ----------

    /** What you typed (city or postcode); blank means "where my IP says I am". */
    var weatherQuery: String
        get() = sp.getString("weather_query", null) ?: ""
        set(v) = sp.edit().putString("weather_query", v.trim()).apply()

    /** Resolved place for [weatherQuery] (or the IP lookup), so it's not geocoded every launch. */
    var weatherPlace: Place?
        get() {
            val lat = sp.getString("weather_lat", null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString("weather_lon", null)?.toDoubleOrNull() ?: return null
            return Place(lat, lon, sp.getString("weather_name", "") ?: "", sp.getString("weather_for", "") ?: "")
        }
        set(v) = sp.edit()
            .putString("weather_lat", v?.lat?.toString())
            .putString("weather_lon", v?.lon?.toString())
            .putString("weather_name", v?.name)
            .putString("weather_for", v?.query)
            .apply()

    // ---------- Trakt ----------

    var traktClientId: String
        get() = sp.getString("trakt_client_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.TRAKT_CLIENT_ID
        set(v) = sp.edit().putString("trakt_client_id", v.trim()).apply()

    var traktClientSecret: String
        get() = sp.getString("trakt_client_secret", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.TRAKT_CLIENT_SECRET
        set(v) = sp.edit().putString("trakt_client_secret", v.trim()).apply()

    var traktToken: TraktToken?
        get() {
            val access = sp.getString("trakt_access", null)?.takeIf { it.isNotBlank() } ?: return null
            return TraktToken(
                access = access,
                refresh = sp.getString("trakt_refresh", "") ?: "",
                expiresAt = sp.getLong("trakt_expires", 0L),
            )
        }
        set(v) = sp.edit()
            .putString("trakt_access", v?.access)
            .putString("trakt_refresh", v?.refresh)
            .putLong("trakt_expires", v?.expiresAt ?: 0L)
            .apply()

    var traktUser: String
        get() = sp.getString("trakt_user", null) ?: ""
        set(v) = sp.edit().putString("trakt_user", v).apply()

    companion object {
        private const val MAX_RECENT = 8
    }
}

/** How discover-based rows are ordered. */
enum class Sort(val label: String) {
    POPULAR("Popular"),
    TOP_RATED("Top rated"),
    NEWEST("Newest"),
}
