package dev.craig.reel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** One way into one streaming app for one title. */
data class Offer(
    /** JustWatch's provider name, e.g. "Netflix Standard with Ads"; matched with StreamingApp.matches. */
    val providerName: String,
    /** FLATRATE, FREE, ADS, RENT or BUY. */
    val monetization: String,
    /** The title's own page, e.g. https://www.netflix.com/title/81757047. */
    val webUrl: String?,
    /** An Android intent: URI aimed at the TV app, usually straight to playback. */
    val androidTvIntent: String?,
) {
    val isStreaming get() = monetization == "FLATRATE" || monetization == "FREE" || monetization == "ADS"
}

/**
 * Per-app title links from JustWatch, the service behind TMDB's availability data.
 * This is JustWatch's unofficial GraphQL endpoint, so it's best effort: any failure
 * or miss just means Launch.openTitle falls back to the app's search.
 * Results are matched to the exact TMDB id, never by name alone.
 */
object JustWatch {

    private const val ENDPOINT = "https://apis.justwatch.com/graphql"
    private const val TTL = 12 * DiskCache.HOUR
    private val cache = ConcurrentHashMap<String, List<Offer>>()

    /** Set once at startup; offers are then kept on disk for [TTL] too. */
    @Volatile var disk: DiskCache? = null

    private const val QUERY = """
query(${'$'}c: Country!, ${'$'}l: Language!, ${'$'}q: String!, ${'$'}t: [ObjectType!]) {
  popularTitles(country: ${'$'}c, first: 8, filter: {searchQuery: ${'$'}q, objectTypes: ${'$'}t}) {
    edges { node {
      content(country: ${'$'}c, language: ${'$'}l) { externalIds { tmdbId } }
      ... on MovieOrShow { offers(country: ${'$'}c, platform: WEB) {
        monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV) package { clearName }
      } }
    } }
  }
}"""

    suspend fun offers(title: Title, region: String): List<Offer> {
        val key = "${title.type.path}/${title.id}/$region"
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val diskKey = "justwatch/$key"
            val stale = disk?.get(diskKey)
            val json = if (stale != null && stale.ageMs < TTL) {
                JSONObject(stale.body)
            } else {
                val variables = JSONObject()
                    .put("c", region)
                    .put("l", "en")
                    .put("q", title.name)
                    .put("t", JSONArray().put(if (title.type == MediaType.MOVIE) "MOVIE" else "SHOW"))
                try {
                    post(JSONObject().put("query", QUERY).put("variables", variables)).also { disk?.put(diskKey, it.toString()) }
                } catch (e: IOException) {
                    if (stale != null) JSONObject(stale.body) else throw e
                }
            }
            val edges = json.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges")
                ?: JSONArray()
            var found: List<Offer> = emptyList()
            for (i in 0 until edges.length()) {
                val node = edges.getJSONObject(i).optJSONObject("node") ?: continue
                val tmdbId = node.optJSONObject("content")?.optJSONObject("externalIds")?.optString("tmdbId")
                if (tmdbId != title.id.toString()) continue
                val arr = node.optJSONArray("offers") ?: JSONArray()
                found = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                    Offer(
                        providerName = o.optJSONObject("package")?.optString("clearName").orEmpty(),
                        monetization = o.optString("monetizationType"),
                        webUrl = o.optString("standardWebURL").takeIf { it.startsWith("http") },
                        androidTvIntent = o.optString("deeplinkURL").takeIf { it.startsWith("intent:") },
                    )
                }.distinct()
                break
            }
            cache[key] = found
            found
        }
    }

    private fun post(body: JSONObject): JSONObject {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Reel/0.1 (Android TV)")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("JustWatch $code")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
