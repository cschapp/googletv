package dev.craig.reel

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Liveness check for YouTube links. JustWatch's "YouTube Free" data goes stale: the upload a
 * link points at is often private or removed by now. YouTube's oEmbed endpoint answers 200 for
 * a public video and 403/404 for a private or deleted one, in about a kilobyte and no API key.
 */
object YouTube {

    private val cache = ConcurrentHashMap<String, Boolean>()

    /** Set once at startup; verdicts are then kept on disk for [TTL] so a relaunch doesn't re-ask YouTube. */
    @Volatile var disk: DiskCache? = null
    private const val TTL = DiskCache.DAY

    /** The v= id of a youtube.com/watch link, or null. */
    fun videoId(url: String?): String? {
        val u = runCatching { Uri.parse(url ?: return null) }.getOrNull() ?: return null
        val host = u.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
        return when {
            host == "youtu.be" -> u.pathSegments.firstOrNull()
            host.endsWith("youtube.com") -> u.getQueryParameter("v")
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /** True when the video is public. A network failure counts as public so a blip doesn't hide titles. */
    suspend fun isPublic(videoId: String): Boolean {
        cache[videoId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val diskKey = "youtube/public/$videoId"
            disk?.get(diskKey)?.takeIf { it.ageMs < TTL }?.let { e ->
                val v = e.body == "1"
                cache[videoId] = v
                return@withContext v
            }
            val url = URL("https://www.youtube.com/oembed?format=json&url=" + Uri.encode("https://www.youtube.com/watch?v=$videoId"))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Reel/0.1 (Android TV)")
            try {
                val code = conn.responseCode
                val result = when (code) {
                    in 200..299 -> true
                    401, 403, 404 -> false
                    else -> true
                }
                cache[videoId] = result
                disk?.put(diskKey, if (result) "1" else "0")
                result
            } catch (e: IOException) {
                true
            } finally {
                conn.disconnect()
            }
        }
    }
}
