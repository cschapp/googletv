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
    data class Found(val title: String, val episode: Pair<Int, Int>?, val year: Int?, val lengthMs: Long)

    fun lookup(mediaId: String): Found? =
        if (mediaId.startsWith(PRIME_PREFIX)) prime(mediaId) else null

    private fun prime(gti: String): Found? {
        val html = fetch("https://www.primevideo.com/detail/$gti") ?: return null
        val raw = Regex("<title>([^<]+)</title>").find(html)?.groupValues?.get(1) ?: return null
        var title = unescape(raw).removePrefix("Prime Video:").trim()
        if (title.isEmpty() || title.equals("Prime Video", ignoreCase = true)) return null
        // The page's first entity, release year and duration are the title's own; related titles follow.
        val entity = Regex("\"entityType\":\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val year = Regex("\"releaseYear\":(\\d{4})").find(html)?.groupValues?.get(1)?.toInt()
        val lengthMs = (Regex("\"duration\":(\\d+)").find(html)?.groupValues?.get(1)?.toLong() ?: 0L) * 1000
        var episode: Pair<Int, Int>? = null
        if (!entity.equals("Movie", ignoreCase = true)) {
            // Series pages are titled "Show: Season 2"; the episode is the one listed with this id.
            val season = Regex(":\\s*Season\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE).find(title)
            title = title.replace(Regex(":\\s*Season\\s+\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
            val at = html.indexOf("\"gti\":\"$gti\"").takeIf { it >= 0 }
            val near = at?.let { html.substring((it - 1500).coerceAtLeast(0), (it + 1500).coerceAtMost(html.length)) }
            val ep = near?.let { Regex("\"episodeNumber\":(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
            val sn = near?.let { Regex("\"seasonNumber\":(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
                ?: season?.groupValues?.get(1)?.toInt()
            if (ep != null && sn != null) episode = sn to ep
            Log.i(TAG, "prime $gti is a $entity: “$title” season $sn episode $ep")
            // A show with no episode we can name can't be scrobbled.
            if (episode == null) return null
        }
        return Found(title, episode, year, if (episode == null) lengthMs else 0L)
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
    private const val TAG = "Reel"
}
