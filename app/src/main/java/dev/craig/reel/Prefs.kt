package dev.craig.reel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    /** What the header greets you as; blank for a plain "Good evening." */
    var userName: String
        get() = sp.getString("user_name", null) ?: ""
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    /**
     * Only ever show what's included with your apps: search and continue-watching rows drop
     * titles that aren't, and the detail panel leaves out "Rent or buy on …".
     */
    var includedOnly: Boolean
        get() = sp.getBoolean("included_only", false)
        set(v) = sp.edit().putBoolean("included_only", v).apply()

    var disabledProviders: Set<String>
        get() = sp.getStringSet("disabled_providers", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("disabled_providers", v).apply()

    var recentGenres: List<String>
        get() = (sp.getString("recent_genres", null) ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("recent_genres", v.take(MAX_RECENT).joinToString("\n")).apply()

    fun addRecentGenre(q: String) {
        recentGenres = listOf(q) + recentGenres.filterNot { it.equals(q, ignoreCase = true) }
    }

    // ---------- Recently launched ----------

    /** Titles opened from Reel, newest first. Stored as JSON so a title survives without TMDB. */
    var recentTitles: List<RecentTitle>
        get() {
            val arr = runCatching { JSONArray(sp.getString("recent_titles", null) ?: "[]") }.getOrDefault(JSONArray())
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = MediaType.entries.firstOrNull { it.path == o.optString("type") } ?: return@mapNotNull null
                RecentTitle(
                    title = Title(
                        id = o.optInt("id"),
                        type = type,
                        name = o.optString("name"),
                        year = o.optString("year"),
                        posterPath = o.optString("poster").takeIf { it.isNotBlank() },
                    ),
                    app = o.optString("app"),
                    at = o.optLong("at"),
                )
            }
        }
        set(v) {
            val arr = JSONArray()
            for (r in v.take(MAX_RECENT_TITLES)) {
                arr.put(
                    JSONObject()
                        .put("type", r.title.type.path)
                        .put("id", r.title.id)
                        .put("name", r.title.name)
                        .put("year", r.title.year)
                        .put("poster", r.title.posterPath ?: "")
                        .put("app", r.app)
                        .put("at", r.at),
                )
            }
            sp.edit().putString("recent_titles", arr.toString()).apply()
        }

    fun addRecentTitle(t: Title, app: String) {
        recentTitles = listOf(RecentTitle(t.copy(note = null), app, System.currentTimeMillis())) +
            recentTitles.filterNot { it.title.type == t.type && it.title.id == t.id }
    }

    fun removeRecentTitle(t: Title) {
        recentTitles = recentTitles.filterNot { it.title.type == t.type && it.title.id == t.id }
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

    var traktToken: OAuthToken?
        get() {
            val access = sp.getString("trakt_access", null)?.takeIf { it.isNotBlank() } ?: return null
            return OAuthToken(
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

    // ---------- YouTube subscriptions ----------

    var googleClientId: String
        get() = sp.getString("google_client_id", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GOOGLE_CLIENT_ID
        set(v) = sp.edit().putString("google_client_id", v.trim()).apply()

    var googleClientSecret: String
        get() = sp.getString("google_client_secret", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GOOGLE_CLIENT_SECRET
        set(v) = sp.edit().putString("google_client_secret", v.trim()).apply()

    var googleToken: OAuthToken?
        get() {
            val access = sp.getString("google_access", null)?.takeIf { it.isNotBlank() } ?: return null
            return OAuthToken(
                access = access,
                refresh = sp.getString("google_refresh", "") ?: "",
                expiresAt = sp.getLong("google_expires", 0L),
            )
        }
        set(v) = sp.edit()
            .putString("google_access", v?.access)
            .putString("google_refresh", v?.refresh)
            .putLong("google_expires", v?.expiresAt ?: 0L)
            .apply()

    var youtubeRowHidden: Boolean
        get() = sp.getBoolean("youtube_row_hidden", false)
        set(v) = sp.edit().putBoolean("youtube_row_hidden", v).apply()

    var youtubeRowSort: RowSort
        get() = RowSort.entries.firstOrNull { it.name == sp.getString("youtube_row_sort", null) } ?: RowSort.MOST_WATCHED
        set(v) = sp.edit().putString("youtube_row_sort", v.name).apply()

    var youtubeThumbSize: ThumbSize
        get() = ThumbSize.entries.firstOrNull { it.name == sp.getString("youtube_thumb_size", null) } ?: ThumbSize.SMALL
        set(v) = sp.edit().putString("youtube_thumb_size", v.name).apply()

    /** Seconds of YouTube you've played per channel, keyed by [WatchLog.channelKey]. */
    val channelWatchSecs: Map<String, Long>
        get() {
            val o = runCatching { JSONObject(sp.getString("youtube_channel_secs", null) ?: "{}") }.getOrDefault(JSONObject())
            return o.keys().asSequence().associateWith { o.optLong(it) }
        }

    fun addChannelWatchSecs(channel: String, secs: Long) {
        if (channel.isBlank() || secs <= 0) return
        val m = channelWatchSecs.toMutableMap()
        m[channel] = (m[channel] ?: 0L) + secs
        sp.edit().putString("youtube_channel_secs", JSONObject(m as Map<*, *>).toString()).apply()
    }

    /** Videos you've watched, as [WatchLog.videoKey]s, oldest first. */
    val watchedVideos: Set<String>
        get() = (sp.getString("youtube_watched", null) ?: "").split('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet())

    fun markVideosWatched(keys: Collection<String>) {
        val now = watchedVideos
        if (now.containsAll(keys)) return
        sp.edit().putString("youtube_watched", (now + keys).toList().takeLast(MAX_WATCHED_VIDEOS).joinToString("\n")).apply()
    }

    fun markVideoUnwatched(key: String) {
        val now = watchedVideos
        if (key !in now) return
        sp.edit().putString("youtube_watched", (now - key).joinToString("\n")).apply()
    }

    /**
     * Channels you watch in order: channel id -> publish time of the video you chose to start from.
     * Anything older counts as watched; the row shows the oldest unwatched one from there on.
     */
    val inOrderChannels: Map<String, Long>
        get() {
            val o = runCatching { JSONObject(sp.getString("youtube_in_order", null) ?: "{}") }.getOrDefault(JSONObject())
            return o.keys().asSequence().associateWith { o.optLong(it) }
        }

    fun setInOrder(channelId: String, start: Long?) {
        val m = inOrderChannels.toMutableMap()
        if (start == null) m.remove(channelId) else m[channelId] = start
        sp.edit().putString("youtube_in_order", JSONObject(m as Map<*, *>).toString()).apply()
    }

    companion object {
        private const val MAX_RECENT = 8
        private const val MAX_RECENT_TITLES = 40
        private const val MAX_WATCHED_VIDEOS = 2000
    }
}

/** How the YouTube row orders its channels. */
enum class RowSort(val label: String) {
    /** Channels you spend the most time on first (your TV watch time, then YouTube's own ranking), then newest upload. */
    MOST_WATCHED("Most watched channels first"),
    /** Newest upload first, whoever posted it. */
    NEWEST("Newest uploads first"),
    /** Channels you watch in order first, then the rest; each group newest upload first. */
    IN_ORDER("Watch in order channels first"),
}

/** Thumbnail width in the YouTube row; height follows at 16:9. */
enum class ThumbSize(val label: String, val widthDp: Int) {
    SMALL("Small", 176),
    MEDIUM("Medium", 224),
    LARGE("Large", 288),
}

/** A title you opened from Reel, and which app it went to. */
data class RecentTitle(val title: Title, val app: String, val at: Long)

/** How discover-based rows are ordered. */
enum class Sort(val label: String) {
    POPULAR("Popular"),
    TOP_RATED("Top rated"),
    NEWEST("Newest"),
}
