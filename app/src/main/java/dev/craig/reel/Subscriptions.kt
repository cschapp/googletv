package dev.craig.reel

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

data class Channel(val id: String, val name: String)

/** One upload from a channel's feed. */
data class Video(
    val id: String,
    val title: String,
    val channelId: String,
    val channel: String,
    val published: Long,
) {
    val thumbUrl get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val watchKey get() = WatchLog.videoKey(channel, title)
}

/**
 * Your YouTube subscriptions and what they've uploaded lately.
 *
 * Google sign-in is the TV device-code flow with a "TVs and Limited Input devices" OAuth client
 * of your own, scoped to youtube.readonly. The API is only asked for the subscription list
 * (one quota unit per 50 channels); uploads come from each channel's public RSS feed, which
 * costs nothing and needs no login. YouTube's API has no watch history, so what you've watched
 * is tracked on the TV instead (see [PlaybackWatcher]).
 */
class Subscriptions(
    private val clientId: String,
    private val clientSecret: String,
    private val prefs: Prefs,
    private val disk: DiskCache?,
) {

    val isLinked get() = prefs.googleToken != null

    // ---------- Login ----------

    suspend fun requestCode(): DeviceCode = withContext(Dispatchers.IO) {
        val r = post(DEVICE_CODE_URL, mapOf("client_id" to clientId, "scope" to SCOPE))
        if (r.code !in 200..299) throw IOException(r.body.optString("error_description").ifBlank { r.body.optString("error", "Google ${r.code}") })
        DeviceCode(
            deviceCode = r.body.getString("device_code"),
            userCode = r.body.getString("user_code"),
            url = r.body.optString("verification_url", "https://www.google.com/device"),
            interval = r.body.optInt("interval", 5),
            expiresIn = r.body.optInt("expires_in", 1800),
            verifier = "",
        )
    }

    /** Polls until the code is approved, denied, or expires. True once linked. */
    suspend fun waitForApproval(code: DeviceCode): Boolean {
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        var interval = code.interval.coerceAtLeast(1)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val r = withContext(Dispatchers.IO) {
                post(
                    TOKEN_URL,
                    mapOf(
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
            }
            if (r.code == 200) {
                saveToken(r.body)
                return true
            }
            when (r.body.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied", "expired_token" -> return false
                else -> throw IOException(r.body.optString("error_description").ifBlank { "Google ${r.code}" })
            }
        }
        return false
    }

    fun unlink() {
        prefs.googleToken = null
        disk?.remove(SUBS_KEY)
    }

    private fun saveToken(j: JSONObject) {
        prefs.googleToken = OAuthToken(
            access = j.getString("access_token"),
            // A refresh answer has no new refresh token; keep the one from login.
            refresh = j.optString("refresh_token").ifBlank { prefs.googleToken?.refresh.orEmpty() },
            expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000,
        )
    }

    private fun refreshIfNeeded() {
        val t = prefs.googleToken ?: return
        if (System.currentTimeMillis() < t.expiresAt - 5 * 60_000L) return
        if (t.refresh.isBlank()) return unlink()
        val r = post(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "refresh_token" to t.refresh,
                "grant_type" to "refresh_token",
            ),
        )
        // invalid_grant: revoked, or a "Testing" OAuth app's weekly expiry. Link again from settings.
        if (r.code == 200) saveToken(r.body) else if (r.code in 400..499) unlink() else throw IOException("Google ${r.code}")
    }

    // ---------- Subscriptions ----------

    /**
     * Every channel you subscribe to, in YouTube's "most relevant" order (it puts the channels
     * you watch most first). Cached for [TTL_SUBS]; a stale list stands in when offline.
     */
    suspend fun channels(): List<Channel> = withContext(Dispatchers.IO) {
        val cached = disk?.get(SUBS_KEY)
        if (cached != null && cached.ageMs < TTL_SUBS) parseChannels(cached.body)?.let { return@withContext it }
        val fresh = try {
            fetchChannels()
        } catch (e: IOException) {
            cached?.let { parseChannels(it.body) } ?: throw e
        }
        disk?.put(SUBS_KEY, JSONArray().also { a -> fresh.forEach { a.put(JSONArray().put(it.id).put(it.name)) } }.toString())
        fresh
    }

    private fun parseChannels(body: String): List<Channel>? = runCatching {
        val a = JSONArray(body)
        (0 until a.length()).map { i -> a.getJSONArray(i).let { Channel(it.getString(0), it.getString(1)) } }
    }.getOrNull()

    private fun fetchChannels(): List<Channel> {
        val out = mutableListOf<Channel>()
        var pageToken = ""
        do {
            val j = apiGet(
                "/subscriptions?part=snippet&mine=true&maxResults=50&order=relevance" +
                    if (pageToken.isNotEmpty()) "&pageToken=$pageToken" else "",
            )
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val s = items.getJSONObject(i).optJSONObject("snippet") ?: continue
                val id = s.optJSONObject("resourceId")?.optString("channelId").orEmpty()
                if (id.isNotBlank()) out += Channel(id, s.optString("title"))
            }
            pageToken = j.optString("nextPageToken")
        } while (pageToken.isNotEmpty() && out.size < MAX_CHANNELS)
        return out
    }

    // ---------- Uploads ----------

    /**
     * What the row is picked from, newest first, Shorts left out. Most channels give their uploads
     * from the last [WINDOW_MS] (from the RSS feed). Channels in [inOrder] (id -> start time) give
     * everything since their start, however old, from the API, falling back to the feed if it fails.
     */
    suspend fun recentUploads(inOrder: Map<String, Long>): List<Video> = coroutineScope {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        val limit = Semaphore(6)
        channels().map { ch ->
            async {
                limit.withPermit {
                    val start = inOrder[ch.id]
                    if (start != null) {
                        runCatching { channelUploads(ch, since = start) }
                            .recoverCatching { uploads(ch) }
                            .getOrDefault(emptyList())
                    } else {
                        runCatching { uploads(ch) }.getOrDefault(emptyList()).filter { it.published >= cutoff }
                    }
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.published }
    }

    /**
     * A channel's uploads from the API, newest first, Shorts left out: back to [since] when given
     * (up to [MAX_UPLOAD_PAGES] pages of 50), otherwise just the latest 50. Cached for [TTL_FEED].
     */
    suspend fun channelUploads(ch: Channel, since: Long? = null): List<Video> = withContext(Dispatchers.IO) {
        val key = "youtube/uploads/${ch.id}/${since ?: "latest"}"
        val cached = disk?.get(key)
        if (cached != null && cached.ageMs < TTL_FEED) parseVideos(cached.body, ch)?.let { return@withContext it }
        val fresh = try {
            fetchUploads(ch, since)
        } catch (e: IOException) {
            cached?.let { parseVideos(it.body, ch) } ?: throw e
        }
        disk?.put(key, JSONArray().also { a -> fresh.forEach { a.put(JSONArray().put(it.id).put(it.title).put(it.published)) } }.toString())
        fresh
    }

    private fun parseVideos(body: String, ch: Channel): List<Video>? = runCatching {
        val a = JSONArray(body)
        (0 until a.length()).map { i -> a.getJSONArray(i).let { Video(it.getString(0), it.getString(1), ch.id, ch.name, it.getLong(2)) } }
    }.getOrNull()

    private fun fetchUploads(ch: Channel, since: Long?): List<Video> {
        // UULF… is the uploads playlist without Shorts (UU… has everything). It isn't documented,
        // so fall back to UU… if a channel doesn't have one.
        val suffix = ch.id.removePrefix("UC")
        val playlist = try {
            apiGet("/playlistItems?part=id&maxResults=1&playlistId=UULF$suffix")
            "UULF$suffix"
        } catch (e: IOException) {
            if (e.message?.startsWith("YouTube 404") != true) throw e
            "UU$suffix"
        }
        val out = mutableListOf<Video>()
        var pageToken = ""
        var pages = 0
        do {
            val j = apiGet(
                "/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlist" +
                    if (pageToken.isNotEmpty()) "&pageToken=$pageToken" else "",
            )
            val items = j.optJSONArray("items") ?: JSONArray()
            var reachedStart = false
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val sn = o.optJSONObject("snippet") ?: continue
                val id = sn.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                val title = sn.optString("title")
                // Private and deleted uploads stay in the playlist with a placeholder title and no publish date.
                val publishedAt = o.optJSONObject("contentDetails")?.optString("videoPublishedAt").orEmpty()
                if (id.isBlank() || publishedAt.isBlank()) continue
                val published = parseDate(publishedAt)
                if (since != null && published < since) {
                    reachedStart = true
                    continue
                }
                out += Video(id, title, ch.id, ch.name, published)
            }
            pageToken = j.optString("nextPageToken")
            pages++
        } while (since != null && !reachedStart && pageToken.isNotEmpty() && pages < MAX_UPLOAD_PAGES)
        return out.sortedByDescending { it.published }
    }

    private suspend fun uploads(ch: Channel): List<Video> = withContext(Dispatchers.IO) {
        val key = "youtube/feed/${ch.id}"
        val cached = disk?.get(key)
        val body = if (cached != null && cached.ageMs < TTL_FEED) {
            cached.body
        } else {
            try {
                fetchText(FEED_URL + ch.id).also { disk?.put(key, it) }
            } catch (e: IOException) {
                cached?.body ?: throw e
            }
        }
        parseFeed(body, ch)
    }

    /** The Atom feed's entries. Shorts are the ones whose link goes to /shorts/. */
    private fun parseFeed(xml: String, ch: Channel): List<Video> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        val out = mutableListOf<Video>()
        var inEntry = false
        var id = ""
        var title = ""
        var published = 0L
        var short = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> {
                        inEntry = true
                        id = ""; title = ""; published = 0L; short = false
                    }
                    "yt:videoId" -> if (inEntry) id = p.nextText()
                    "title" -> if (inEntry) title = p.nextText()
                    "published" -> if (inEntry) published = parseDate(p.nextText())
                    "link" -> if (inEntry && p.getAttributeValue(null, "href")?.contains("/shorts/") == true) short = true
                }
                XmlPullParser.END_TAG -> if (p.name == "entry") {
                    inEntry = false
                    if (id.isNotBlank() && !short) out += Video(id, title, ch.id, ch.name, published)
                }
            }
        }
        return out
    }

    /** GET a YouTube Data API path with your sign-in; a 401 unlinks so the row asks you to link again. */
    private fun apiGet(path: String): JSONObject {
        refreshIfNeeded()
        val token = prefs.googleToken?.access ?: throw IOException("YouTube isn't linked")
        val conn = URL(API + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            val code = conn.responseCode
            if (code == 401) {
                unlink()
                throw IOException("YouTube sign-in expired; link it again in ⚙")
            }
            if (code !in 200..299) throw IOException("YouTube $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDate(s: String): Long =
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s)?.time }.getOrNull() ?: 0L

    // ---------- HTTP ----------

    private class Response(val code: Int, val body: JSONObject)

    private fun post(url: String, form: Map<String, String>): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            val body = form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Reel/${BuildConfig.VERSION_NAME} (Android TV)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("YouTube feed $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        private const val API = "https://www.googleapis.com/youtube/v3"
        private const val FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id="
        // Was alphabetical under "youtube/subscriptions"; the new key keeps an old cache from being used.
        private const val SUBS_KEY = "youtube/subscriptions/relevance"
        private const val TTL_SUBS = 12 * DiskCache.HOUR
        private const val TTL_FEED = 30 * 60_000L
        private const val MAX_CHANNELS = 500
        /** How far back an in-order channel is followed: 200 uploads. */
        private const val MAX_UPLOAD_PAGES = 4
        /** How far back an upload still counts as new. */
        private const val WINDOW_MS = 14 * DiskCache.DAY

        /**
         * The row: one card per channel with something you haven't watched. Normally that's its
         * newest upload; for a channel you watch in order ([inOrder]: id -> start time) it's the
         * oldest one since the start, with a count of the rest. [sort] decides the order:
         *  - MOST_WATCHED: your watch time on the TV, then YouTube's own ranking of your channels
         *    ([rank]: channel id -> position, most relevant first), then newest upload;
         *  - NEWEST: newest upload;
         *  - IN_ORDER: channels in [inOrder] first, then the rest, each group by newest upload.
         * An in-order channel counts its newest unwatched upload, so a fresh episode brings its card forward.
         */
        fun pickRow(
            uploads: List<Video>,
            watched: Set<String>,
            secs: Map<String, Long>,
            inOrder: Map<String, Long>,
            rank: Map<String, Int>,
            sort: RowSort,
        ): List<RowCard> =
            uploads.groupBy { it.channelId }
                .mapNotNull { (id, vs) ->
                    val start = inOrder[id]
                    val left = vs.filter { it.watchKey !in watched && (start == null || it.published >= start) }
                    when {
                        left.isEmpty() -> null
                        start != null -> RowCard(left.minBy { it.published }, more = left.size - 1, latest = left.maxOf { it.published })
                        else -> left.maxBy { it.published }.let { RowCard(it, more = 0, latest = it.published) }
                    }
                }
                .sortedWith(
                    when (sort) {
                        RowSort.MOST_WATCHED ->
                            compareByDescending<RowCard> { secs[WatchLog.channelKey(it.video.channel)] ?: 0L }
                                .thenBy { rank[it.video.channelId] ?: Int.MAX_VALUE }
                                .thenByDescending { it.latest }
                        RowSort.NEWEST -> compareByDescending { it.latest }
                        RowSort.IN_ORDER ->
                            compareBy<RowCard> { if (it.video.channelId in inOrder) 0 else 1 }
                                .thenByDescending { it.latest }
                    },
                )
    }
}

/** A channel's card in the row: the video to watch next, how many more are waiting, and its newest upload. */
data class RowCard(val video: Video, val more: Int, val latest: Long)

/** How a playing video and its channel are recognised, from either the feed or YouTube's media session. */
object WatchLog {
    fun channelKey(channel: String) = Tmdb.normalize(channel)
    fun videoKey(channel: String, title: String) = channelKey(channel) + "|" + Tmdb.normalize(title)
}
