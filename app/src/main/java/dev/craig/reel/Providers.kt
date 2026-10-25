package dev.craig.reel

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * A streaming app Reel knows how to match against TMDB's provider list.
 * [matches] receives a TMDB provider name normalized to lowercase letters/digits
 * ("Paramount+ with Showtime" -> "paramountwithshowtime").
 */
data class StreamingApp(
    val label: String,
    val packages: List<String>,
    val matches: (String) -> Boolean,
    val searchUrl: ((String) -> String)? = null,
    /** String extras the app needs on a title link before it honours it. */
    val linkExtras: Map<String, String> = emptyMap(),
)

/** A known streaming app that is actually installed, with the package that was found. */
data class InstalledService(val app: StreamingApp, val pkg: String) {
    val label get() = app.label
}

object ProviderCatalog {

    val apps: List<StreamingApp> = listOf(
        StreamingApp(
            "Netflix", listOf("com.netflix.ninja"),
            { it.startsWith("netflix") },
            { q -> "https://www.netflix.com/search?q=${Uri.encode(q)}" },
            // The TV app opens to its home screen on a bare link; it only follows one tagged with a source.
            linkExtras = mapOf("source" to "30"),
        ),
        StreamingApp(
            "Prime Video", listOf("com.amazon.amazonvideo.livingroom"),
            // "Amazon Video" is TMDB's name for the same app's rent/buy store.
            { it.startsWith("amazonprimevideo") || it == "amazonvideo" },
        ),
        StreamingApp(
            "Tubi", listOf("com.tubitv"),
            { it.startsWith("tubi") },
            { q -> "https://tubitv.com/search/${Uri.encode(q)}" },
        ),
        StreamingApp(
            "Pluto TV", listOf("tv.pluto.android"),
            { it.startsWith("plutotv") },
        ),
        StreamingApp(
            "YouTube", listOf("com.google.android.youtube.tv"),
            // Only the free, ad-supported catalog. TMDB's plain "YouTube" is rent/buy and
            // "YouTube Premium" needs a paid plan, so neither counts as something you can watch.
            { it == "youtubefree" },
            { q -> "https://www.youtube.com/results?search_query=${Uri.encode(q)}" },
        ),
        StreamingApp("Disney+", listOf("com.disney.disneyplus"), { it.startsWith("disneyplus") }),
        StreamingApp("Hulu", listOf("com.hulu.livingroomplus"), { it.startsWith("hulu") }),
        StreamingApp("Max", listOf("com.wbd.stream", "com.hbo.hbonow"), { it == "max" || it.startsWith("hbomax") }),
        StreamingApp("Peacock", listOf("com.peacocktv.peacockandroid"), { it.startsWith("peacock") }),
        StreamingApp("Paramount+", listOf("com.cbs.ott"), { it.startsWith("paramount") }),
        StreamingApp("Apple TV", listOf("com.apple.atve.androidtv.appletv"), { it.startsWith("appletv") }),
        StreamingApp("Plex", listOf("com.plexapp.android"), { it.startsWith("plex") }),
        StreamingApp("Crunchyroll", listOf("com.crunchyroll.crunchyroid"), { it.startsWith("crunchyroll") }),
    )

    fun installed(pm: PackageManager): List<InstalledService> = apps.mapNotNull { app ->
        app.packages.firstOrNull { Launch.launchIntent(pm, it) != null }?.let { InstalledService(app, it) }
    }

    /** label -> every TMDB provider id that belongs to that app (e.g. Netflix + "Netflix Standard with Ads"). */
    fun mapIds(installed: List<InstalledService>, providers: List<ProviderInfo>): Map<String, Set<Int>> =
        installed.associate { svc ->
            svc.label to providers.filter { svc.app.matches(Tmdb.normalize(it.name)) }.map { it.id }.toSet()
        }
}

object Launch {

    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun openApp(ctx: Context, pkg: String): Boolean {
        val intent = launchIntent(ctx.packageManager, pkg) ?: return false
        return start(ctx, intent)
    }

    /**
     * Best effort at landing on a title, most direct first:
     * 1) the title's own page in the app (JustWatch link), 2) JustWatch's Android TV intent,
     * 3) the app's own search URL if we know one, 4) a targeted ACTION_SEARCH,
     * 5) just open the app and tell the user what to search for.
     */
    fun openTitle(ctx: Context, svc: InstalledService, title: String, offers: List<Offer> = emptyList()): Boolean {
        val mine = offers
            .filter { svc.app.matches(Tmdb.normalize(it.providerName)) }
            .sortedByDescending { it.isStreaming }
        for (o in mine) {
            val tv = o.androidTvIntent
                ?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
                ?.takeIf { i ->
                    i.`package`.let { it == null || it == svc.pkg } &&
                        i.component.let { it == null || it.packageName == svc.pkg }
                }
            for (url in candidateUrls(o.webUrl)) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(svc.pkg)
                // Carry the extras the app's own TV intent uses (Netflix's "source"), or our known ones.
                tv?.extras?.let { i.putExtras(it) }
                for ((k, v) in svc.app.linkExtras) if (!i.hasExtra(k)) i.putExtra(k, v)
                if (start(ctx, i)) return true
            }
            if (tv != null) {
                for ((k, v) in svc.app.linkExtras) if (!tv.hasExtra(k)) tv.putExtra(k, v)
                if (start(ctx, tv.setPackage(svc.pkg))) return true
            }
        }
        svc.app.searchUrl?.let { url ->
            if (start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url(title))).setPackage(svc.pkg))) return true
        }
        val search = Intent(Intent.ACTION_SEARCH)
            .setPackage(svc.pkg)
            .putExtra(SearchManager.QUERY, title)
        if (start(ctx, search)) return true
        if (openApp(ctx, svc.pkg)) {
            Toast.makeText(ctx, "Search for “$title” in ${svc.label}", Toast.LENGTH_LONG).show()
            return true
        }
        return false
    }

    /** The web link as given, plus app-specific spellings their TV intent filters want. */
    private fun candidateUrls(url: String?): List<String> {
        if (url == null) return emptyList()
        val out = mutableListOf(url)
        // Pluto's TV app only accepts paths with a locale segment: /en/on-demand/...
        val pluto = Regex("^(https?://(?:www\\.)?pluto\\.tv)(/on-demand/.*)$").find(url)
        if (pluto != null) out += pluto.groupValues[1] + "/en" + pluto.groupValues[2]
        return out
    }
}
