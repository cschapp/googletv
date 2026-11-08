package dev.craig.reel

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Tells Trakt what the streaming apps play, so it lands in your Trakt history.
 * None of these apps scrobble on Google TV, but they publish a media session that
 * [PlaybackWatcher] hands us. What's in it varies:
 *  - most apps give a title, and for shows the season and episode;
 *  - Prime Video gives only its own catalog id, which [AppTitles] looks up on primevideo.com;
 *  - Netflix gives nothing but a play position. If Reel opened a title in that app a little
 *    earlier ([Prefs.launchHint]), we take it to be that title, and for a show the next episode
 *    you haven't watched on Trakt.
 *
 * What happens to a title:
 *  - once it has played for [MIN_PLAY_MS] (so trailers and home-screen previews don't count),
 *    it's matched on Trakt and a scrobble start is sent with the current progress;
 *  - pausing sends a pause, resuming another start;
 *  - when it stops, the app closes or the next one starts, a stop is sent. Trakt adds it to
 *    your history at 80% or more, and keeps a paused position (Continue watching) below that.
 *
 * Progress is the app's play position over the length it (or Trakt, or the Prime page) gives.
 * Apps that report no position (Prime) get wall-clock play time added to wherever Trakt last
 * had it paused, so finishing a movie on a second night still counts.
 */
class Scrobbler(private val prefs: Prefs, private val main: Handler) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Trakt calls run one at a time, in order, so a stop never overtakes its start. */
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val watching = HashMap<SessionKey, MediaController.Callback>()
    private val items = HashMap<SessionKey, Item>()

    /**
     * Titles whose session just went away, by app. Pluto ends the session that named the title
     * and plays it in a new one that names nothing, so the next session in that app picks it up.
     */
    private val parked = HashMap<String, Item>()

    /** Title lookups already done, so a pause/resume doesn't search again. Only touched on the queue. */
    private val resolved = object : LinkedHashMap<String, ScrobbleTarget?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrobbleTarget?>) = size > 50
    }

    /** Last time something played on the launch hint, keyed by when Reel launched it. Keeps a binge going. */
    private var hintActive = 0L to 0L

    private val tick = object : Runnable {
        override fun run() {
            for ((s, item) in items.toList()) {
                update(item, s.controller.playbackState)
                checkRollover(s, item)
            }
            if (items.values.any { it.since != null }) main.postDelayed(this, TICK_MS)
        }
    }

    init {
        current = this
        scope.launch { for (job in queue) runCatching { job() }.onFailure { Log.w(TAG, "trakt scrobble failed", it) } }
    }

    fun close() {
        if (current === this) current = null
        for (s in watching.keys.toList()) gone(s)
        for (item in parked.values.toList()) finish(item)
        parked.clear()
        main.removeCallbacks(tick)
        queue.close()
        // Let the queued stops go out before the scope dies with the service.
        main.postDelayed({ scope.cancel() }, 10_000)
    }

    /**
     * You changed which episode of a Reel-opened show is on. Drop what we were following without
     * telling Trakt it stopped (that would save the wrong episode); the next play starts afresh,
     * and its start replaces the wrong one on Trakt.
     */
    fun episodeChanged() = main.post {
        for ((s, item) in items.toList()) if (item.what.launched != null) {
            item.ending?.let { main.removeCallbacks(it) }
            items.remove(s)
            Log.i(TAG, "dropped ${item.target?.label ?: item.what.label}; you picked another episode")
        }
        parked.entries.removeAll { (_, item) -> item.what.launched != null && run { item.ending?.let { main.removeCallbacks(it) }; true } }
    }

    /** The full list of active sessions; we keep the streaming apps' ones. */
    fun track(controllers: List<MediaController>) {
        val now = controllers.filter { it.packageName in PACKAGES }.map { SessionKey(it) }.toSet()
        for (s in watching.keys - now) gone(s)
        for (s in now - watching.keys) {
            Log.i(TAG, "${s.controller.packageName} session ${s.tag} appeared")
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = onMedia(s, metadata, s.controller.playbackState)
                override fun onPlaybackStateChanged(state: PlaybackState?) = onState(s, state)
                override fun onSessionDestroyed() = gone(s)
            }
            s.controller.registerCallback(cb, main)
            watching[s] = cb
            onMedia(s, s.controller.metadata, s.controller.playbackState)
        }
    }

    private fun gone(s: SessionKey) {
        watching.remove(s)?.let { s.controller.unregisterCallback(it) }
        val item = items.remove(s) ?: return
        Log.i(TAG, "${s.controller.packageName} session ${s.tag} ended during ${item.what.label}")
        park(s.controller.packageName, item)
    }

    /** Holds [item] for a minute in case [pkg] carries on with it in a new session; then it's over. */
    private fun park(pkg: String, item: Item) {
        update(item, null)
        item.since = null
        item.ending?.let { main.removeCallbacks(it) }
        parked.remove(pkg)?.let { finish(it) }
        parked[pkg] = item
        val end = Runnable {
            if (parked[pkg] === item) {
                parked.remove(pkg)
                finish(item)
            }
        }
        item.ending = end
        main.postDelayed(end, END_GRACE_MS)
    }

    /** The title [s]'s app was just playing in another session, if [s] isn't naming a different one. */
    private fun adopt(s: SessionKey, what: Parsed?): Item? {
        val pkg = s.controller.packageName
        val item = parked[pkg] ?: return null
        if (what != null && what.key != item.what.key) return null
        parked.remove(pkg)
        item.ending?.let { main.removeCallbacks(it) }
        item.ending = null
        item.lastState = -1
        Log.i(TAG, "$pkg session ${s.tag} carries on with ${item.what.label}")
        return item
    }

    // ---------- Following playback ----------

    /** One title playing in one app. [playedMs] counts wall-clock play time; [since] is set while playing. */
    private class Item(val pkg: String, val what: Parsed, val durationMs: Long, var armed: Boolean = false) {
        val createdAt = SystemClock.elapsedRealtime()
        var since: Long? = null
        var playedMs = 0L
        /** Last play position the app reported, extrapolated to now. -1 when it doesn't report one. */
        var positionMs = -1L
        var peakMs = -1L
        @Volatile var target: ScrobbleTarget? = null
        /** Where Trakt had it paused when we started, for apps with no position. Set on the queue. */
        @Volatile var basePercent: Double? = null
        /** What Trakt was last told: null (nothing yet, or stopped), START or PAUSE. */
        var sent: ScrobbleAction? = null
        var started = false
        /** Pending end of this title after the app said it stopped; cancelled if it plays again. */
        var ending: Runnable? = null
        var lastState = -1
        /** The app's id for what's playing, from its playback state (Tubi's content id). */
        @Volatile var appItemId = -1L
    }

    private fun onMedia(s: SessionKey, md: MediaMetadata?, state: PlaybackState?) {
        // Ad breaks come with their own metadata; they're part of the title around them.
        if (md != null && runCatching { md.getLong(KEY_ADVERTISEMENT) }.getOrDefault(0L) == 1L) return onState(s, state)
        val what = md?.let { parse(it) }?.let { withHint(s.controller.packageName, it) }
        val old = items[s]
        // No metadata at all mid-play (Pluto after buffering, Netflix always) is the same title.
        // Metadata with the fields emptied (Prime when you leave the player) means it ended.
        if (old != null && (md == null || what?.key == old.what.key)) return onState(s, state)
        if (md != null) Log.i(TAG, "${s.controller.packageName} metadata: ${dump(md)} -> ${what ?: "not a title"}")
        old?.let { finish(it) }
        items.remove(s)
        if (what != null) items[s] = adopt(s, what) ?: Item(s.controller.packageName, what, md!!.getLong(MediaMetadata.METADATA_KEY_DURATION))
        onState(s, state)
    }

    private fun onState(s: SessionKey, state: PlaybackState?) {
        val item = items[s]
            ?: adopt(s, s.controller.metadata?.let { parse(it) })?.also { items[s] = it }
            ?: hintItem(s, state)?.also { items[s] = it }
            ?: return
        val code = state?.state ?: PlaybackState.STATE_NONE
        if (!item.armed && isLive(item, state, code)) {
            item.armed = true
            Log.i(TAG, "${s.controller.packageName} ${item.what.label} is really playing")
        }
        update(item, state)
        if (code != item.lastState) {
            Log.i(TAG, "${s.controller.packageName} ${item.what.label}: state $code at ${(state?.position ?: 0) / 1000}s, played ${item.playedMs / 1000}s")
            item.lastState = code
        }
        if (code == PlaybackState.STATE_PLAYING) item.ending?.let {
            main.removeCallbacks(it)
            item.ending = null
        }
        when (state?.state) {
            PlaybackState.STATE_PLAYING -> {
                if (!item.armed) return
                if (item.since == null) {
                    item.since = SystemClock.elapsedRealtime()
                    main.removeCallbacks(tick)
                    main.postDelayed(tick, TICK_MS)
                }
                if (checkRollover(s, item)) return
                maybeStart(item)
            }
            PlaybackState.STATE_PAUSED -> {
                item.since = null
                if (item.sent == ScrobbleAction.START) send(item, ScrobbleAction.PAUSE)
            }
            // Buffering, seeking and skipping are part of watching; leave Trakt as it is.
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> Unit
            else -> {
                // Stopped or errored. Apps report that between ads and the show (Pluto) or episodes
                // (Netflix), so it's only over if nothing plays again soon.
                item.since = null
                if (item.ending == null) {
                    val end = Runnable {
                        if (items[s] === item) {
                            items.remove(s)
                            finish(item)
                        }
                    }
                    item.ending = end
                    main.postDelayed(end, END_GRACE_MS)
                }
            }
        }
    }

    /**
     * For an app that says nothing about what it plays: the title Reel opened in it, if that was
     * recent, or if something has kept playing in it since (one episode autoplaying the next).
     */
    private fun hintItem(s: SessionKey, state: PlaybackState?): Item? {
        if (state?.state != PlaybackState.STATE_PLAYING) return null
        if (s.controller.metadata?.let { parse(it) } != null) return null
        val hint = liveHint(s.controller.packageName) ?: return null
        Log.i(TAG, "${hint.pkg} says nothing about what's on; taking it to be “${hint.name}”, opened from Reel")
        return Item(hint.pkg, Parsed(null, null, null, launched = hint), 0L, armed = true)
    }

    /** What Reel opened in [pkg], if recently, or if something has kept playing in it since. */
    private fun liveHint(pkg: String): LaunchHint? {
        val hint = prefs.launchHint?.takeIf { it.pkg == pkg } ?: return null
        val lastActive = maxOf(hint.at, hintActive.takeIf { it.first == hint.at }?.second ?: 0L)
        return hint.takeIf { System.currentTimeMillis() - lastActive <= HINT_TTL_MS }
    }

    /**
     * Some apps name only the episode of a show Reel just opened: Pluto says "Pilot" for The 100,
     * Tubi says "S01:E01 - Day One". Read those as that show's episode. A title naming some
     * other show is left alone.
     */
    private fun withHint(pkg: String, p: Parsed): Parsed {
        if (p.launched != null || (p.title == null && p.episode == null)) return p
        val hint = liveHint(pkg)?.takeIf { it.type == MediaType.TV } ?: return p
        val show = Tmdb.normalize(hint.name)
        val named = p.fields.any { Tmdb.normalize(it) == show } || p.title?.let(Tmdb::normalize) == show
        if (p.title != null && p.episode != null && !named) return p
        val episodeTitle = if (p.episode != null) null else {
            p.fields.firstOrNull { Tmdb.normalize(it) != show && EPISODE.none { r -> r.containsMatchIn(it) } }
        }
        return Parsed(hint.name, p.episode, null, launched = hint, episodeTitle = episodeTitle).also {
            it.fields = p.fields
            Log.i(TAG, "$pkg: reading ${p.label} as ${it.label}, opened from Reel")
        }
    }

    /**
     * An app that reuses one session for the next episode (Netflix) just jumps back to the start.
     * Treat a big jump back as a new title: close out the old one where it got to, and look again.
     */
    private fun checkRollover(s: SessionKey, item: Item): Boolean {
        if (item.what.launched == null || item.positionMs < 0) return false
        if (item.peakMs - item.positionMs < ROLLOVER_MS) return false
        Log.i(TAG, "playback jumped from ${item.peakMs / 1000}s back to ${item.positionMs / 1000}s; a new title")
        items.remove(s)
        item.positionMs = item.peakMs
        finish(item)
        onState(s, s.controller.playbackState)
        return true
    }

    /**
     * Whether [state] shows [item] actually playing, rather than a state left over from the title
     * before. Tubi's home screen puts "up next" metadata on a session still saying PLAYING at the
     * last movie's position; that mustn't count. Playing counts once the state moves into
     * PLAYING after we have the title, or the app reports a position since then.
     */
    private fun isLive(item: Item, state: PlaybackState?, code: Int): Boolean {
        if (code != PlaybackState.STATE_PLAYING || state == null) return false
        if (item.lastState != -1 && item.lastState != PlaybackState.STATE_PLAYING) return true
        return state.lastPositionUpdateTime >= item.createdAt - FRESH_MS
    }

    /** Books play time up to now and refreshes the position from [state]. Nothing counts until [Item.armed]. */
    private fun update(item: Item, state: PlaybackState?) {
        state?.activeQueueItemId?.takeIf { it > 0 }?.let { item.appItemId = it }
        if (!item.armed) return
        val now = SystemClock.elapsedRealtime()
        item.since?.let {
            item.playedMs += now - it
            item.since = if (state?.state == PlaybackState.STATE_PLAYING) now else null
        }
        if (state != null && state.position > 0) {
            val drift = if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
                ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong().coerceAtLeast(0)
            } else 0L
            item.positionMs = state.position + drift
            item.peakMs = maxOf(item.peakMs, item.positionMs)
        }
        if (state?.state == PlaybackState.STATE_PLAYING) {
            item.what.launched?.let { hintActive = it.at to System.currentTimeMillis() }
            maybeStart(item)
        }
    }

    private fun maybeStart(item: Item) {
        if (item.sent == ScrobbleAction.START) return
        if (!item.started) {
            if (item.playedMs + (item.since?.let { SystemClock.elapsedRealtime() - it } ?: 0) < MIN_PLAY_MS) return
            if (item.durationMs in 1 until MIN_DURATION_MS) return
            item.started = true
        }
        send(item, ScrobbleAction.START)
    }

    private fun finish(item: Item) {
        item.ending?.let { main.removeCallbacks(it) }
        item.ending = null
        update(item, null)
        item.since = null
        if (item.started) send(item, ScrobbleAction.STOP)
    }

    // ---------- Talking to Trakt ----------

    private fun send(item: Item, action: ScrobbleAction) {
        item.sent = if (action == ScrobbleAction.STOP) null else action
        // Snapshot now: by the time the queue gets to it, playback has moved on.
        val positionMs = item.positionMs
        val playedMs = item.playedMs
        queue.trySend {
            val trakt = trakt() ?: return@trySend
            val target = item.target ?: resolve(trakt, item.what, item.durationMs, item.pkg, item.appItemId)?.also { item.target = it }
            if (target == null) {
                if (action == ScrobbleAction.START) note("Couldn't find “${item.what.label}” on Trakt")
                return@trySend
            }
            val length = item.durationMs.takeIf { it > 0 } ?: target.runtimeMs
            val progress = when {
                length <= 0 -> 0.0
                positionMs >= 0 -> positionMs * 100.0 / length
                else -> {
                    val base = item.basePercent ?: trakt.pausedProgress(target).also { item.basePercent = it }
                    base + playedMs * 100.0 / length
                }
            }.coerceIn(0.0, 100.0)
            val code = trakt.scrobble(action, target, progress)
            val pct = "%.0f%%".format(progress)
            Log.i(TAG, "trakt ${action.path} ${target.label} at $pct: $code")
            if (action == ScrobbleAction.STOP && progress >= WATCHED_PERCENT && code in 200..299) item.what.launched?.let { moveOn(trakt, it, target) }
            when {
                code == 409 -> Unit // Trakt already has it from a moment ago
                code !in 200..299 -> note("Trakt said $code to “${target.label}”")
                action == ScrobbleAction.STOP && progress >= WATCHED_PERCENT -> note("Added ${target.label} to your Trakt history")
                action == ScrobbleAction.STOP -> note("${target.label}: saved at $pct on Trakt")
                action == ScrobbleAction.START -> note("Watching ${target.label}")
            }
        }
    }

    private fun resolve(trakt: Trakt, what: Parsed, lengthHint: Long, pkg: String, appItemId: Long): ScrobbleTarget? {
        // Which episode a Reel-opened show is on changes as you watch, so that one isn't cached.
        what.launched?.let { launched ->
            // The app said which episode, by number or name: that, and nothing else if the name isn't found.
            if (what.episode != null || what.episodeTitle != null) {
                return trakt.findByTmdb(launched.type, launched.tmdbId, what.episode, what.episodeTitle)
            }
            // The episode may have moved on (or been picked in settings) since this item began.
            val hint = prefs.launchHint?.takeIf { it.at == launched.at && it.tmdbId == launched.tmdbId } ?: launched
            val t = trakt.findByTmdb(hint.type, hint.tmdbId, hint.episode)
            // Remember the guess, so everything after it follows on from this episode.
            if (t?.episode != null && hint.episode == null && prefs.launchHint == hint) prefs.launchHint = hint.copy(episode = t.episode)
            return t
        }
        if (resolved.containsKey(what.key)) return resolved[what.key]
        val id = what.mediaId ?: AppTitles.idFor(pkg, appItemId, what.episode != null)
        val t = if (what.title != null) {
            trakt.findForScrobble(what.title, what.episode, what.year, lengthHint)
        } else {
            id?.let { AppTitles.lookup(it) }?.let { found ->
                Log.i(TAG, "$id is $found")
                trakt.findForScrobble(found.title, found.episode ?: what.episode, found.year, lengthHint.takeIf { it > 0 } ?: found.lengthMs, found.episodeTitle)
                    ?.let { if (found.lengthMs > 0) it.copy(runtimeMs = found.lengthMs) else it }
            }
        }
        resolved[what.key] = t
        return t
    }

    /** A Reel-opened show's episode is done: the next one is on next, whatever Trakt's history says. */
    private fun moveOn(trakt: Trakt, launched: LaunchHint, target: ScrobbleTarget) {
        val ep = target.episode ?: return
        val hint = prefs.launchHint?.takeIf { it.at == launched.at && it.tmdbId == launched.tmdbId } ?: return
        val after = trakt.episodeAfter(target.traktId, ep) ?: return
        prefs.launchHint = hint.copy(episode = after)
        Log.i(TAG, "${hint.name}: next up S${after.first} E${after.second}")
    }

    private fun trakt(): Trakt? {
        if (!prefs.traktScrobble) return null
        val id = prefs.traktClientId.takeIf { it.isNotBlank() } ?: return null
        return Trakt(id, prefs.traktClientSecret, prefs).takeIf { it.isLinked }
    }

    private fun note(s: String) {
        Log.i(TAG, s)
        prefs.lastScrobble = "$s · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date())}"
    }

    // ---------- Reading the app's metadata ----------

    /**
     * What an app says is playing: a [title] (with [episode] as (season, number) for a show), or
     * only the app's own [mediaId] to look up, or nothing at all but the title Reel [launched].
     */
    data class Parsed(
        val title: String?,
        val episode: Pair<Int, Int>?,
        val year: Int?,
        val mediaId: String? = null,
        val launched: LaunchHint? = null,
        /** The episode's own name, when the app gave that instead of numbers (Pluto's "Pilot"). */
        val episodeTitle: String? = null,
    ) {
        /** Every text field the app gave, brand names left out; not part of equality. */
        var fields: List<String> = emptyList()

        val key get() = when {
            launched != null -> "reel|${launched.type.path}|${launched.tmdbId}|${episode?.first}|${episode?.second}|${episodeTitle?.let(Tmdb::normalize)}"
            title != null -> "${Tmdb.normalize(title)}|${episode?.first}|${episode?.second}"
            else -> "id|$mediaId|${episode?.first}|${episode?.second}"
        }
        val label get() = (title ?: launched?.name ?: mediaId ?: "?") +
            (if (title == null && episode != null) " S${episode.first} E${episode.second}" else "") +
            (episodeTitle?.let { " “$it”" } ?: "")
    }

    companion object {
        /** The running one, if notification access is on, for settings to poke. */
        @Volatile var current: Scrobbler? = null

        /** Apps whose sessions name nothing, so the episode comes from what Reel opened. */
        val NO_TITLE_APPS = setOf("com.netflix.ninja")

        private const val TAG = "Reel"
        private const val TICK_MS = 30_000L
        /** Play this long before it counts: skips trailers, previews and channel-surfing. */
        private const val MIN_PLAY_MS = 3 * 60_000L
        /** Anything the app says is shorter than this is a clip or a trailer. */
        private const val MIN_DURATION_MS = 5 * 60_000L
        /** How long after Reel opens a title (or after it last played) we still take it to be what's on. */
        private const val HINT_TTL_MS = 20 * 60_000L
        /** A jump back this big in a Reel-opened title means the next one started. */
        private const val ROLLOVER_MS = 5 * 60_000L
        /** A position update this close to when we got the title belongs to it. */
        private const val FRESH_MS = 2_000L
        /** How long after an app says it stopped before we believe it. */
        private const val END_GRACE_MS = 60_000L
        const val WATCHED_PERCENT = 80.0
        private const val KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"

        /** Every streaming app Reel knows, except YouTube (videos there aren't on Trakt). */
        val PACKAGES: Set<String> =
            ProviderCatalog.apps.flatMap { it.packages }.toSet() - PlaybackWatcher.YOUTUBE_PACKAGES

        /** Apps put their own name in the title or author fields; it's never what's playing. */
        private val BRANDS: Set<String> = ProviderCatalog.apps.map { Tmdb.normalize(it.label) }.toSet() +
            setOf("amazonprimevideo", "amazon", "disneyplus", "hbomax", "paramountplus", "appletvplus", "peacocktv")

        private val EPISODE = listOf(
            Regex("""\bS(?:eason)?\s*(\d{1,2})\s*[:.,·|\-]?\s*E(?:p(?:isode)?)?\.?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bSeason\s+(\d{1,2})\W{1,4}Episode\s+(\d{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,2})x(\d{2,3})\b"""),
        )
        private val YEAR = Regex("""\s*\(((?:19|20)\d\d)\)\s*$""")
        private val NOT_A_TITLE = Regex("""^(ad|ads|advertisement|commercial|sponsored|trailer|preview)\b""", RegexOption.IGNORE_CASE)
        private val SEPARATORS = " \t-–—:|·•,\"“”'"

        private val TEXT_KEYS = listOf(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_WRITER,
        )

        private fun texts(md: MediaMetadata): List<String> =
            // Not the description fields: those hold the plot, never a name.
            (TEXT_KEYS.map { md.getString(it) } + listOf(md.description?.title, md.description?.subtitle).map { it?.toString() })
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()

        fun parse(md: MediaMetadata): Parsed? = parse(texts(md), md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))

        /**
         * Picks the show or movie out of an app's text fields. Shows: a season/episode marker in
         * any field ("S2:E5", "Season 2 Episode 5", "2x05"); the show name is the text before it
         * in the same field, or else the first other field without one. Otherwise it's a movie,
         * named by the first field. With no usable text, an id we know how to look up ([AppTitles]).
         */
        fun parse(all: List<String>, mediaId: String? = null): Parsed? {
            val fields = all.filter { Tmdb.normalize(it) !in BRANDS }
            if (fields.isEmpty()) {
                return mediaId?.takeIf { it.startsWith(AppTitles.PRIME_PREFIX) }?.let { Parsed(null, null, null, mediaId = it) }
            }
            if (NOT_A_TITLE.containsMatchIn(fields.first())) return null
            for (f in fields) {
                val m = EPISODE.firstNotNullOfOrNull { it.find(f) } ?: continue
                val episode = m.groupValues[1].toInt() to m.groupValues[2].toInt()
                val before = f.substring(0, m.range.first).trim { it in SEPARATORS }
                val show = before.takeIf { it.isNotEmpty() }
                    ?: fields.firstOrNull { other -> other != f && EPISODE.none { it.containsMatchIn(other) } }
                // Tubi names the episode ("S01:E01 - Day One") but not the show; that comes from its id.
                return Parsed(show, episode, null, mediaId = mediaId).also { it.fields = fields }
            }
            val title = fields.first()
            val year = YEAR.find(title)?.groupValues?.get(1)?.toInt()
            return Parsed(title.replace(YEAR, "").trim(), null, year).also { it.fields = fields }
        }

        private fun dump(md: MediaMetadata): String =
            md.keySet().sorted().joinToString(", ") { k ->
                val v = md.getString(k) ?: runCatching { md.getLong(k).toString() }.getOrNull() ?: "?"
                "${k.removePrefix("android.media.metadata.")}=$v"
            }
    }
}

/** A session is the same one across lists when its token matches; controllers are new objects each time. */
class SessionKey(val controller: MediaController) {
    /** Short id for logs. */
    val tag get() = Integer.toHexString(controller.sessionToken.hashCode())
    override fun equals(other: Any?) = other is SessionKey && other.controller.sessionToken == controller.sessionToken
    override fun hashCode() = controller.sessionToken.hashCode()
}
