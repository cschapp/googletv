package dev.craig.reel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
)

data class ResultPage(val page: Int, val totalPages: Int, val items: List<Title>)

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
class Tmdb(private val key: String) {

    private val isBearer = key.length > 40

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
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
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun genres(type: MediaType): List<Genre> = withContext(Dispatchers.IO) {
        val arr = get("/genre/${type.path}/list", mapOf("language" to "en-US")).optJSONArray("genres")
        (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            Genre(o.getInt("id"), o.getString("name"))
        }
    }

    suspend fun providers(type: MediaType, region: String): List<ProviderInfo> = withContext(Dispatchers.IO) {
        val arr = get(
            "/watch/providers/${type.path}",
            mapOf("watch_region" to region, "language" to "en-US"),
        ).optJSONArray("results")
        (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            ProviderInfo(o.getInt("provider_id"), o.getString("provider_name"))
        }
    }

    /** Resolve a free-text genre ("folk horror") to a TMDB keyword tag. */
    suspend fun searchKeyword(query: String): Keyword? = withContext(Dispatchers.IO) {
        val arr = get("/search/keyword", mapOf("query" to query, "page" to "1")).optJSONArray("results")
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

        page(get("/discover/${type.path}", params), type, page)
    }

    /** This week's trending titles. No provider filter on TMDB's side; caller verifies each one. */
    suspend fun trending(type: MediaType, page: Int): ResultPage = withContext(Dispatchers.IO) {
        page(get("/trending/${type.path}/week", mapOf("language" to "en-US", "page" to page.toString())), type, page)
    }

    /** Look up one title by id (for Trakt lists). Null when TMDB no longer has it. */
    suspend fun details(type: MediaType, id: Int): Title? = withContext(Dispatchers.IO) {
        try {
            title(get("/${type.path}/$id", mapOf("language" to "en-US")), type)
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

    private val availabilityCache = ConcurrentHashMap<String, Availability>()
    private val availabilityLimit = Semaphore(8)

    /** Where this title is available in the region. Cached for the life of the client. */
    suspend fun availability(type: MediaType, id: Int, region: String): Availability {
        val key = "${type.path}/$id/$region"
        availabilityCache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val r = get("/${type.path}/$id/watch/providers").optJSONObject("results")?.optJSONObject(region)
            val a = if (r == null) Availability.NONE else Availability(
                stream = providerIds(r, "flatrate", "free", "ads"),
                rentBuy = providerIds(r, "rent", "buy"),
            )
            availabilityCache[key] = a
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
        }.awaitAll().filterNotNull()
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
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
