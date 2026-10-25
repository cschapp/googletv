package dev.craig.reel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A resolved location. [query] is what it was resolved from ("" for the IP lookup). */
data class Place(val lat: Double, val lon: Double, val name: String, val query: String)

data class Conditions(val icon: String, val temp: Int, val isDay: Boolean)

/** Current conditions from Open-Meteo (no key). Location from a typed place or, failing that, the TV's IP. */
object Weather {

    /** City / postcode -> place, via Open-Meteo's geocoder. */
    suspend fun geocode(query: String): Place? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val arr = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json")
            .optJSONArray("results") ?: return@withContext null
        if (arr.length() == 0) return@withContext null
        val o = arr.getJSONObject(0)
        val label = listOfNotNull(o.optString("name").takeIf { it.isNotBlank() }, o.optString("admin1").takeIf { it.isNotBlank() })
            .joinToString(", ")
        Place(o.getDouble("latitude"), o.getDouble("longitude"), label, query)
    }

    /** Rough location from the TV's public IP. */
    suspend fun locateByIp(): Place? = withContext(Dispatchers.IO) {
        val o = get("https://ipapi.co/json/")
        val lat = o.optDouble("latitude", Double.NaN)
        val lon = o.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) null else Place(lat, lon, o.optString("city"), "")
    }

    suspend fun current(p: Place, fahrenheit: Boolean): Conditions = withContext(Dispatchers.IO) {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val o = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit&timezone=auto",
        ).getJSONObject("current")
        val day = o.optInt("is_day", 1) == 1
        Conditions(icon(o.optInt("weather_code", 0), day), Math.round(o.getDouble("temperature_2m")).toInt(), day)
    }

    /** WMO weather code -> emoji. */
    fun icon(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1 -> if (day) "🌤️" else "🌙"
        2 -> if (day) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Reel/0.1 (Android TV)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
