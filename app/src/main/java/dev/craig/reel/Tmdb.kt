package dev.craig.reel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

enum class MediaType(val path: String, val label: String) {
    MOVIE("movie", "Movies"),
    TV("tv", "Shows"),
}

data class Genre(val id: Int, val name: String)
data class Keyword(val id: Int, val name: String)
data class ProviderInfo(val id: Int, val name: String)

data class Title(
    val id: Int,
    val type: MediaType,
    val name: String,
    val year: String,
    val posterPath: String?,
    /** A short line under the name on personal rows, e.g. "Next: S2 E5" or "Opened in Netflix". */
    val note: String? = null,
)

data class ResultPage(val page: Int, val totalPages: Int, val items: List<Title>)

/** Everything the detail panel shows about one title. */
data class TitleDetail(
    val title: Title,
    val tagline: String,
    val overview: String,
    val backdropPath: String?,
    /** 0..10, or 0 when unrated. */
    val rating: Double,
    val votes: Int,
    val genres: List<String>,
    /** Minutes; a movie's length or a show's typical episode. 0 when unknown. */
    val runtimeMin: Int,
    val seasons: Int,
    val episodes: Int,
    /** Finished airing (shows only). */
    val ended: Boolean,
    val cast: List<String>,
    /** YouTube video id of the best trailer, if any. */
    val trailerKey: String?,
)

/** Where a title is available in a region: provider ids that stream it (subscription/free/ads) vs. rent/buy only. */
data class Availability(val stream: Set<Int>, val rentBuy: Set<Int>) {
    companion object {
        val NONE = Availability(emptySet(), emptySet())
    }
}

/**
 * Minimal TMDB client. Accepts either a v3 API key (sent as ?api_key=)
 * or a v4 read access token (sent as a Bearer header; these are long JWTs).
 */
class Tmdb(private val key: String, private val cache: DiskCache? = null) {

    private val isBearer = key.length > 40

    /**
     * GET a TMDB endpoint. With [ttlMs] > 0 the response is served from [cache] while younger
     * than that, and a stale copy stands in when the network fails. Keys never include the API key.
     */
    private fun get(path: String, params: Map<String, String> = emptyMap(), ttlMs: Long = 0L): JSONObject {
        val cacheKey = path + "?" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val cached = if (ttlMs > 0) cache?.get(cacheKey) else null
        if (cached != null && cached.ageMs < ttlMs) return JSONObject(cached.body)

        val body = try {
            fetch(path, params)
        } catch (e: IOException) {
            // Offline or TMDB hiccup: an old answer beats an empty screen.
            if (cached != null && !e.message.orEmpty().startsWith("TMDB 4")) return JSONObject(cached.body)
            throw e
        }
        if (ttlMs > 0) cache?.put(cacheKey, body)
        return JSONObject(body)
    }

    private fun fetch(path: String, params: Map<String, String>): String {
        val all = if (isBearer) params else params + ("api_key" to key)
        val query = all.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val url = URL("$BASE$path" + if (query.isEmpty()) "" else "?$query")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (isBearer) conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("status_message") }.getOrNull()
                throw IOException("TMDB $code${if (!msg.isNullOrBlank()) ": $msg" else ""}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    suspend fun genres(type: MediaType): List<Genre> = withContext(Dispatchers.IO) {
        val arr = get("/genre/${type.path}/list", mapOf("language" to "en-US"), TTL_STATIC).optJSONArray("genres")
        (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            Genre(o.getInt("id"), o.getString("name"))
        }
    }

    /**
     * All providers TMDB knows for the region. The full response is bulky (logos, priorities),
     * so only the id/name pairs are kept on disk, as a small array that parses in no time.
     */
    suspend fun providers(type: MediaType, region: String): List<ProviderInfo> = withContext(Dispatchers.IO) {
        val compactKey = "providers/${type.path}/$region"
        cache?.get(compactKey)?.takeIf { it.ageMs < TTL_STATIC }?.let { e ->
            runCatching {
                val arr = JSONArray(e.body)
                (0 until arr.length()).map { i -> arr.getJSONArray(i).let { ProviderInfo(it.getInt(0), it.getString(1)) } }
            }.getOrNull()?.let { return@withContext it }
        }
        val arr = get("/watch/providers/${type.path}", mapOf("watch_region" to region, "language" to "en-US")).optJSONArray("results")
        val list = (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            ProviderInfo(o.getInt("provider_id"), o.getString("provider_name"))
        }
        cache?.put(compactKey, JSONArray().also { out -> list.forEach { out.put(JSONArray().put(it.id).put(it.name)) } }.toString())
        list
    }

    /** Resolve a free-text genre ("folk horror") to a TMDB keyword tag. */
    suspend fun searchKeyword(query: String): Keyword? = withContext(Dispatchers.IO) {
        val arr = get("/search/keyword", mapOf("query" to query, "page" to "1"), TTL_STATIC).optJSONArray("results")
            ?: return@withContext null
        val all = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Keyword(o.getInt("id"), o.getString("name"))
        }
        val wanted = normalize(query)
        all.firstOrNull { normalize(it.name) == wanted } ?: all.firstOrNull()
    }

    suspend fun discover(
        type: MediaType,
        genreId: Int?,
        keywordId: Int?,
        providerIds: Collection<Int>,
        region: String,
        page: Int,
        minVotes: Int,
        sort: Sort = Sort.POPULAR,
    ): ResultPage = withContext(Dispatchers.IO) {
        val dateField = if (type == MediaType.MOVIE) "primary_release_date" else "first_air_date"
        val params = mutableMapOf(
            "language" to "en-US",
            "include_adult" to "false",
            "watch_region" to region,
            "with_watch_providers" to providerIds.joinToString("|"),
            // Only titles included with a subscription or free; skips rent/buy.
            "with_watch_monetization_types" to "flatrate|free|ads",
            "page" to page.toString(),
        )
        when (sort) {
            Sort.POPULAR -> {
                params["sort_by"] = "popularity.desc"
                params["vote_count.gte"] = minVotes.toString()
            }
            Sort.TOP_RATED -> {
                params["sort_by"] = "vote_average.desc"
                // A handful of 10/10 votes shouldn't beat a classic.
                params["vote_count.gte"] = maxOf(minVotes, 300).toString()
            }
            Sort.NEWEST -> {
                params["sort_by"] = "$dateField.desc"
                params["$dateField.lte"] = today()
                params["vote_count.gte"] = minOf(minVotes, 20).toString()
            }
        }
        if (genreId != null) params["with_genres"] = genreId.toString()
        if (keywordId != null) params["with_keywords"] = keywordId.toString()

        page(get("/discover/${type.path}", params, TTL_LISTS), type, page)
    }

    /** This week's trending titles. No provider filter on TMDB's side; caller verifies each one. */
    suspend fun trending(type: MediaType, page: Int): ResultPage = withContext(Dispatchers.IO) {
        page(get("/trending/${type.path}/week", mapOf("language" to "en-US", "page" to page.toString()), TTL_LISTS), type, page)
    }

    /** Free-text search across movies and shows at once; people and junk rows are dropped. */
    suspend fun search(query: String, page: Int): ResultPage = withContext(Dispatchers.IO) {
        val json = get(
            "/search/multi",
            mapOf("query" to query, "include_adult" to "false", "language" to "en-US", "page" to page.toString()),
            TTL_LISTS,
        )
        val arr = json.optJSONArray("results")
        val items = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.getJSONObject(i)
            val type = when (o.optString("media_type")) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.TV
                else -> return@mapNotNull null
            }
            title(o, type)
        }
        ResultPage(json.optInt("page", page), minOf(json.optInt("total_pages", 1), 500), items)
    }

    /** Full details for the panel: credits and trailers come along in the same call. */
    suspend fun detail(type: MediaType, id: Int): TitleDetail = withContext(Dispatchers.IO) {
        val o = get("/${type.path}/$id", mapOf("language" to "en-US", "append_to_response" to "credits,videos"), TTL_DETAILS)
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        } ?: emptyList()
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 6)).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        } ?: emptyList()
        val trailer = o.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
            val vids = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
            // Prefer an official trailer, then any trailer, then a teaser.
            (vids.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
                ?: vids.firstOrNull { it.optString("type") == "Trailer" }
                ?: vids.firstOrNull { it.optString("type") == "Teaser" })?.optString("key")
        }
        val runtime = if (type == MediaType.MOVIE) {
            o.optInt("runtime", 0)
        } else {
            o.optJSONArray("episode_run_time")?.takeIf { it.length() > 0 }?.optInt(0, 0) ?: 0
        }
        TitleDetail(
            title = title(o, type),
            tagline = o.optString("tagline"),
            overview = o.optString("overview"),
            backdropPath = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            rating = o.optDouble("vote_average", 0.0),
            votes = o.optInt("vote_count", 0),
            genres = genres,
            runtimeMin = runtime,
            seasons = o.optInt("number_of_seasons", 0),
            episodes = o.optInt("number_of_episodes", 0),
            ended = o.optString("status") in setOf("Ended", "Canceled"),
            cast = cast,
            trailerKey = trailer,
        )
    }

    /** Look up one title by id (for Trakt lists). Null when TMDB no longer has it. */
    suspend fun details(type: MediaType, id: Int): Title? = withContext(Dispatchers.IO) {
        try {
            title(get("/${type.path}/$id", mapOf("language" to "en-US"), TTL_DETAILS), type)
        } catch (e: IOException) {
            if (e.message?.startsWith("TMDB 404") == true) null else throw e
        }
    }

    /** [details] for many ids at once, a few at a time, dropping any that fail. */
    suspend fun detailsMany(items: List<Pair<MediaType, Int>>): List<Title> = coroutineScope {
        items.map { (type, id) ->
            async {
                availabilityLimit.withPermit {
                    try {
                        details(type, id)
                    } catch (e: IOException) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun page(json: JSONObject, type: MediaType, requested: Int): ResultPage {
        val arr = json.optJSONArray("results")
        val items = (0 until (arr?.length() ?: 0)).map { i -> title(arr!!.getJSONObject(i), type) }
        // TMDB caps paging at 500.
        return ResultPage(json.optInt("page", requested), minOf(json.optInt("total_pages", 1), 500), items)
    }

    private fun title(o: JSONObject, type: MediaType): Title {
        val name = if (type == MediaType.MOVIE) o.optString("title") else o.optString("name")
        val date = if (type == MediaType.MOVIE) o.optString("release_date") else o.optString("first_air_date")
        return Title(
            id = o.getInt("id"),
            type = type,
            name = name,
            year = date.take(4),
            posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // ---------- Availability index ----------
    //
    // One watch/providers response per title is a few KB for every region; checking a page
    // means twenty of them. The parsed verdicts (two small id sets) are kept instead, all in
    // one index file that loads in a single read and is rewritten after each page is checked.

    private class Stamped(val a: Availability, val at: Long)

    private val availabilityCache = ConcurrentHashMap<String, Stamped>()
    private val availabilityLimit = Semaphore(8)
    @Volatile private var indexLoaded = false
    @Volatile private var indexDirty = false

    private fun loadIndex() {
        if (indexLoaded) return
        synchronized(availabilityCache) {
            if (indexLoaded) return
            indexLoaded = true
            val body = cache?.get(INDEX_KEY)?.body ?: return
            val cutoff = System.currentTimeMillis() - TTL_AVAILABILITY
            runCatching {
                val o = JSONObject(body)
                for (key in o.keys()) {
                    val e = o.getJSONObject(key)
                    val at = e.optLong("t", 0L)
                    if (at < cutoff) continue
                    availabilityCache[key] = Stamped(Availability(ints(e.optJSONArray("s")), ints(e.optJSONArray("r"))), at)
                }
            }
        }
    }

    /** Write the index if anything was added since the last write. Cheap to call often. */
    fun flushIndex() {
        if (!indexDirty) return
        indexDirty = false
        val o = JSONObject()
        for ((key, s) in availabilityCache) {
            o.put(
                key,
                JSONObject()
                    .put("s", JSONArray(s.a.stream.toList()))
                    .put("r", JSONArray(s.a.rentBuy.toList()))
                    .put("t", s.at),
            )
        }
        cache?.put(INDEX_KEY, o.toString())
    }

    private fun ints(arr: JSONArray?): Set<Int> =
        if (arr == null) emptySet() else (0 until arr.length()).map { arr.getInt(it) }.toSet()

    /** Where this title is available in the region. Served from the index for [TTL_AVAILABILITY]. */
    suspend fun availability(type: MediaType, id: Int, region: String): Availability {
        val key = "${type.path}/$id/$region"
        if (!indexLoaded) withContext(Dispatchers.IO) { loadIndex() }
        availabilityCache[key]?.let { return it.a }
        return withContext(Dispatchers.IO) {
            val r = get("/${type.path}/$id/watch/providers").optJSONObject("results")?.optJSONObject(region)
            val a = if (r == null) Availability.NONE else Availability(
                stream = providerIds(r, "flatrate", "free", "ads"),
                rentBuy = providerIds(r, "rent", "buy"),
            )
            availabilityCache[key] = Stamped(a, System.currentTimeMillis())
            indexDirty = true
            a
        }
    }

    /**
     * Keep only the titles that really stream on one of [providerIds].
     * Discover's with_watch_providers + with_watch_monetization_types filters are independent:
     * a title that is rent-only on YouTube but streams on Apple TV+ passes both, so every
     * page is checked title by title here. A title whose lookup fails is dropped.
     * [extraCheck] gets each surviving title with the enabled provider ids it streams on,
     * and can veto it (used to confirm YouTube links are still live).
     */
    suspend fun onlyStreamingOn(
        titles: List<Title>,
        providerIds: Set<Int>,
        region: String,
        extraCheck: suspend (Title, Set<Int>) -> Boolean = { _, _ -> true },
    ): List<Title> = coroutineScope {
        titles.map { t ->
            async {
                availabilityLimit.withPermit {
                    val a = try {
                        availability(t.type, t.id, region)
                    } catch (e: IOException) {
                        Availability.NONE
                    }
                    val on = a.stream.filter { it in providerIds }.toSet()
                    t.takeIf { on.isNotEmpty() && extraCheck(t, on) }
                }
            }
        }.awaitAll().filterNotNull().also { withContext(Dispatchers.IO) { flushIndex() } }
    }

    private fun providerIds(r: JSONObject, vararg kinds: String): Set<Int> {
        val ids = mutableSetOf<Int>()
        for (kind in kinds) {
            val arr = r.optJSONArray(kind) ?: continue
            for (i in 0 until arr.length()) ids += arr.getJSONObject(i).getInt("provider_id")
        }
        return ids
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        /** Disk-cache lifetimes. Lists move daily; availability shifts a little slower; the rest barely changes. */
        private const val TTL_LISTS = 6 * DiskCache.HOUR
        private const val TTL_AVAILABILITY = 12 * DiskCache.HOUR
        private const val TTL_DETAILS = 7 * DiskCache.DAY
        private const val TTL_STATIC = 7 * DiskCache.DAY
        private const val INDEX_KEY = "availability-index"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
