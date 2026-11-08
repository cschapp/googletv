package dev.craig.reel

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns an app's own content id into a name, for apps whose media session gives an id and no
 * title. Prime Video only reports its catalog id ("amzn1.dv.gti.…"), but primevideo.com's public
 * page for that id names the title.
 */
object AppTitles {

    /** A title an app's id stands for. [lengthMs] is 0 when the page doesn't say. */
    data class Found(
        val title: String,
        val episode: Pair<Int, Int>?,
        val year: Int?,
        val lengthMs: Long,
        /** The episode's own title, which matches Trakt better than numbers do (pilots filed as specials). */
        val episodeTitle: String? = null,
    )

    fun lookup(mediaId: String): Found? = when {
        mediaId.startsWith(PRIME_PREFIX) -> prime(mediaId)
        mediaId.startsWith(TUBI_PREFIX) -> tubi(mediaId.removePrefix(TUBI_PREFIX))
        else -> null
    }

    /** An id [lookup] understands for what [pkg] reports as its playing item, if any. */
    fun idFor(pkg: String, appItemId: Long, episode: Boolean): String? =
        if (pkg == "com.tubitv" && appItemId > 0) "$TUBI_PREFIX${if (episode) "tv-shows" else "movies"}/$appItemId" else null

    /**
     * Tubi's player gives its content id as the playback state's active item, and for episodes
     * no show name. tubitv.com/tv-shows/<id> (or /movies/<id>) names it in og:title.
     */
    private fun tubi(path: String): Found? {
        val html = fetch("https://tubitv.com/$path") ?: return null
        val raw = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return null
        val og = unescape(raw)
        Log.i(TAG, "tubi $path og:title “$og”")
        val year = Regex("\\(((?:19|20)\\d\\d)\\)").find(og)?.groupValues?.get(1)?.toInt()
        // "Hell's Kitchen S01:E01 - Day One" -> "Hell's Kitchen"; "The Crow (1994)" -> "The Crow".
        val name = og.replace(Regex("^Watch\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(-|\\|)\\s*(Free|Tubi).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\bS(?:eason)?\\s*\\d{1,2}\\s*[:.]?\\s*E(?:p(?:isode)?)?\\.?\\s*\\d{1,3}\\b.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\((?:19|20)\\d\\d\\)\\s*$"), "")
            .trim(' ', '-', ':', '|')
        if (name.isEmpty()) return null
        return Found(name, null, year, 0L)
    }

    /**
     * primevideo.com/detail/<gti>. A movie's page is its own; an episode's id lands on its season's
     * page ("Prime Video: Columbo Season 1"), whose data has an entry keyed by that id with the
     * episode's title and number.
     */
    private fun prime(gti: String): Found? {
        val html = fetch("https://www.primevideo.com/detail/$gti") ?: return null
        val raw = Regex("<title>([^<]+)</title>").find(html)?.groupValues?.get(1) ?: return null
        var title = unescape(raw).removePrefix("Prime Video:").trim()
        if (title.isEmpty() || title.equals("Prime Video", ignoreCase = true)) return null
        // This id's own entry, if the page has one; else the page's first values are the title's.
        // Several maps are keyed by the id; the detail one starts with its title.
        val block = (html.indexOf("\"$gti\":{\"title\":").takeIf { it >= 0 } ?: html.indexOf("\"$gti\":{").takeIf { it >= 0 })
            ?.let { html.substring(it, (it + 4000).coerceAtMost(html.length)) }
        fun field(name: String, src: String = block ?: html) =
            Regex("\"$name\":\"?([^\",}]+)").find(src)?.groupValues?.get(1)
        val titleType = block?.let { field("titleType", it) }.orEmpty()
        val entity = field("entityType").orEmpty()
        val year = field("releaseYear")?.toIntOrNull()
        val lengthMs = (field("duration")?.toLongOrNull() ?: 0L) * 1000
        if (titleType.equals("movie", true) || (titleType.isEmpty() && entity.equals("Movie", true))) {
            return Found(title, null, year, lengthMs)
        }
        // "Columbo Season 1" or "Severance: Season 2".
        val seasonRe = Regex("[:\\s]+Season\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        val season = seasonRe.find(title)?.groupValues?.get(1)?.toInt() ?: field("seasonNumber", html)?.toIntOrNull()
        title = title.replace(seasonRe, "").trim()
        val ep = block?.let { field("episodeNumber", it) }?.toIntOrNull()
        val epTitle = block?.let { Regex("^\"[^\"]+\":\\{\"title\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }?.let(::unescape)
        Log.i(TAG, "prime $gti is a $titleType of “$title”: season $season episode $ep “$epTitle”")
        // A show with no episode we can name can't be scrobbled.
        if (season == null || ep == null) return null
        return Found(title, season to ep, null, lengthMs, epTitle)
    }

    private fun fetch(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        // The plain page, as a desktop browser gets it; the default Android agent gets an app redirect.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
        try {
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "fetch $url failed", it) }.getOrNull()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")

    const val PRIME_PREFIX = "amzn1.dv.gti."
    private const val TUBI_PREFIX = "tubi:"
    private const val TAG = "Reel"
}
