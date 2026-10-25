package dev.craig.reel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class TraktToken(val access: String, val refresh: String, val expiresAt: Long)

/** What the TV shows while you enter the code on trakt.tv/activate. [verifier] is the PKCE secret for this attempt. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val url: String,
    val interval: Int,
    val expiresIn: Int,
    val verifier: String,
)

/** A title from a Trakt list, as TMDB knows it. */
data class TraktItem(val type: MediaType, val tmdbId: Int)

/**
 * Minimal Trakt client: device-code login, watchlist and recommendations.
 * Needs the client id of a Trakt app (trakt.tv/oauth/applications). Apps created since
 * October 2026 get no client secret (PKCE-only); the device flow works with the id alone,
 * so [clientSecret] is optional and only sent when present.
 */
class Trakt(private val clientId: String, private val clientSecret: String, private val prefs: Prefs) {

    val isLinked get() = prefs.traktToken != null

    // ---------- Login ----------

    suspend fun requestCode(): DeviceCode = withContext(Dispatchers.IO) {
        // PKCE: Trakt accepts (and for new apps may require) a code challenge on the device flow.
        val verifier = pkceVerifier()
        val j = call(
            "POST", "/oauth/device/code",
            JSONObject()
                .put("client_id", clientId)
                .put("code_challenge", pkceChallenge(verifier))
                .put("code_challenge_method", "S256"),
            auth = false,
        ).body
        DeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            url = j.getString("verification_url"),
            interval = j.optInt("interval", 5),
            expiresIn = j.optInt("expires_in", 600),
            verifier = verifier,
        )
    }

    /** Polls until the code is approved, denied, or expires. Returns the username, or null if not approved. */
    suspend fun waitForApproval(code: DeviceCode): String? {
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        var interval = code.interval.coerceAtLeast(1)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val r = withContext(Dispatchers.IO) {
                call(
                    "POST", "/oauth/device/token",
                    withSecret(
                        JSONObject()
                            .put("code", code.deviceCode)
                            .put("client_id", clientId)
                            .put("code_verifier", code.verifier),
                    ),
                    auth = false, allowError = true,
                )
            }
            when (r.code) {
                200 -> {
                    saveToken(r.body)
                    return fetchUsername()
                }
                400 -> Unit // still waiting on the user
                429 -> interval += 1
                404, 409, 410, 418 -> return null
                else -> throw IOException("Trakt ${r.code}")
            }
        }
        return null
    }

    fun unlink() {
        prefs.traktToken = null
        prefs.traktUser = ""
    }

    private fun saveToken(j: JSONObject) {
        prefs.traktToken = TraktToken(
            access = j.getString("access_token"),
            refresh = j.optString("refresh_token"),
            expiresAt = (j.optLong("created_at", System.currentTimeMillis() / 1000) + j.optLong("expires_in", 7776000)) * 1000,
        )
    }

    private suspend fun fetchUsername(): String = withContext(Dispatchers.IO) {
        val j = call("GET", "/users/settings").body
        val name = j.optJSONObject("user")?.optString("username").orEmpty()
        prefs.traktUser = name
        name
    }

    // ---------- Lists ----------

    suspend fun watchlist(type: MediaType): List<TraktItem> = withContext(Dispatchers.IO) {
        val kind = if (type == MediaType.MOVIE) "movies" else "shows"
        items(callArray("GET", "/sync/watchlist/$kind/added?extended=min"), type)
    }

    suspend fun recommendations(type: MediaType): List<TraktItem> = withContext(Dispatchers.IO) {
        val kind = if (type == MediaType.MOVIE) "movies" else "shows"
        items(callArray("GET", "/recommendations/$kind?limit=60&ignore_collected=true&ignore_watchlisted=true"), type)
    }

    private fun items(arr: JSONArray, type: MediaType): List<TraktItem> {
        val key = if (type == MediaType.MOVIE) "movie" else "show"
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            // Watchlist entries wrap the title; recommendations are the title itself.
            val media = o.optJSONObject(key) ?: o
            val tmdb = media.optJSONObject("ids")?.optInt("tmdb", 0) ?: 0
            if (tmdb > 0) TraktItem(type, tmdb) else null
        }
    }

    // ---------- HTTP ----------

    private class Response(val code: Int, val body: JSONObject, val raw: String)

    private fun callArray(method: String, path: String): JSONArray {
        val r = call(method, path)
        return runCatching { JSONArray(r.raw) }.getOrDefault(JSONArray())
    }

    private fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        allowError: Boolean = false,
    ): Response {
        if (auth) refreshIfNeeded()
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("trakt-api-version", "2")
        conn.setRequestProperty("trakt-api-key", clientId)
        if (auth) prefs.traktToken?.let { conn.setRequestProperty("Authorization", "Bearer ${it.access}") }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299 && !allowError) {
                if (code == 401 && auth) unlink()
                throw IOException("Trakt $code")
            }
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            return Response(code, json, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun refreshIfNeeded() {
        val t = prefs.traktToken ?: return
        if (t.refresh.isBlank() || System.currentTimeMillis() < t.expiresAt - 24 * 3600_000L) return
        val r = call(
            "POST", "/oauth/token",
            withSecret(
                JSONObject()
                    .put("refresh_token", t.refresh)
                    .put("client_id", clientId)
                    .put("redirect_uri", REDIRECT_URI)
                    .put("grant_type", "refresh_token"),
            ),
            auth = false, allowError = true,
        )
        if (r.code == 200) saveToken(r.body) else if (r.code in 400..499) unlink()
    }

    private fun withSecret(body: JSONObject): JSONObject =
        if (clientSecret.isBlank()) body else body.put("client_secret", clientSecret)

    private fun pkceVerifier(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    companion object {
        private const val BASE = "https://api.trakt.tv"
        /** Register the Trakt app with exactly this redirect URI. Device-code login never redirects; token refresh just has to quote it. */
        const val REDIRECT_URI = "https://trakt.tv"
    }
}
