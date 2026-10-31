package dev.craig.reel

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Notices what the YouTube app is playing, so the subscriptions row knows what you've watched
 * and which channels you watch most. YouTube's API has no watch history, but the TV app
 * publishes a media session with the video title, the channel, and whether it's playing.
 *
 * Reading other apps' media sessions needs notification-listener access. Google TV hides the
 * settings screen for it, so grant it once from a PC:
 *
 *     adb shell cmd notification allow_listener dev.craig.reel/dev.craig.reel.PlaybackWatcher
 *
 * Play time is counted on the wall clock while the session says PLAYING (YouTube doesn't report
 * a position), credited to the channel, and a video counts as watched once you've played
 * [WATCHED_FRACTION] of it, or [WATCHED_NO_DURATION_MS] when the length isn't known.
 */
class PlaybackWatcher : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private val watching = HashMap<Session, MediaController.Callback>()

    /** The video on screen now. */
    private var current: Playing? = null

    /** Play time of recent videos, so an ad break in the middle doesn't reset the count. */
    private val played = LinkedHashMap<String, Long>()

    private class Playing(val key: String, val channel: String, val durationMs: Long) {
        /** elapsedRealtime when it last started playing; null while paused. */
        var since: Long? = null
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { track(it) }

    private val tick = object : Runnable {
        override fun run() {
            credit()
            if (current?.since != null) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onListenerConnected() {
        prefs = Prefs(this)
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = msm
        val me = ComponentName(this, PlaybackWatcher::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
        track(msm.getActiveSessions(me))
    }

    override fun onListenerDisconnected() {
        credit()
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        for ((s, cb) in watching) s.controller.unregisterCallback(cb)
        watching.clear()
        main.removeCallbacks(tick)
    }

    /** A session is the same one across lists when its token matches; controllers are new objects each time. */
    private class Session(val controller: MediaController) {
        override fun equals(other: Any?) = other is Session && other.controller.sessionToken == controller.sessionToken
        override fun hashCode() = controller.sessionToken.hashCode()
    }

    private fun track(controllers: List<MediaController>?) {
        val now = controllers.orEmpty().filter { it.packageName in YOUTUBE_PACKAGES }.map { Session(it) }.toSet()
        for (gone in watching.keys - now) {
            gone.controller.unregisterCallback(watching.remove(gone)!!)
        }
        for (s in now - watching.keys) {
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = onVideo(metadata, s.controller.playbackState)
                override fun onPlaybackStateChanged(state: PlaybackState?) = onState(state)
                override fun onSessionDestroyed() = onState(null)
            }
            s.controller.registerCallback(cb, main)
            watching[s] = cb
            onVideo(s.controller.metadata, s.controller.playbackState)
        }
        if (now.isEmpty()) onState(null)
    }

    private fun onVideo(md: MediaMetadata?, state: PlaybackState?) {
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString()
        val channel = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.description?.subtitle?.toString()
        val key = if (title.isNullOrBlank() || channel.isNullOrBlank()) null else WatchLog.videoKey(channel, title)
        if (key == current?.key) return onState(state)
        credit()
        main.removeCallbacks(tick)
        current = key?.let {
            val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            Log.i(TAG, "playing “$title” by $channel, ${duration / 1000}s")
            Playing(it, WatchLog.channelKey(channel!!), duration)
        }
        onState(state)
    }

    private fun onState(state: PlaybackState?) {
        val c = current ?: return
        val playing = state?.state == PlaybackState.STATE_PLAYING
        if (playing && c.since == null) {
            c.since = SystemClock.elapsedRealtime()
            main.removeCallbacks(tick)
            main.postDelayed(tick, TICK_MS)
        } else if (!playing && c.since != null) {
            credit()
            c.since = null
            main.removeCallbacks(tick)
        }
    }

    /** Book the play time since the last credit to the channel and the video. */
    private fun credit() {
        val c = current ?: return
        val start = c.since ?: return
        val now = SystemClock.elapsedRealtime()
        val ms = now - start
        c.since = now
        if (ms <= 0) return
        prefs.addChannelWatchSecs(c.channel, ms / 1000)
        val total = (played.remove(c.key) ?: 0L) + ms
        played[c.key] = total
        while (played.size > MAX_TRACKED) played.remove(played.keys.first())
        val needed = if (c.durationMs > 0) (c.durationMs * WATCHED_FRACTION).toLong() else WATCHED_NO_DURATION_MS
        if (total >= needed && c.key !in prefs.watchedVideos) {
            Log.i(TAG, "watched ${c.key}")
            prefs.markVideosWatched(listOf(c.key))
        }
    }

    companion object {
        private const val TAG = "Reel"
        val YOUTUBE_PACKAGES = setOf("com.google.android.youtube.tv", "com.google.android.youtube")
        private const val TICK_MS = 30_000L
        private const val WATCHED_FRACTION = 0.7
        private const val WATCHED_NO_DURATION_MS = 5 * 60_000L
        private const val MAX_TRACKED = 30

        fun isEnabled(ctx: Context) = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

        const val GRANT_COMMAND = "adb shell cmd notification allow_listener dev.craig.reel/dev.craig.reel.PlaybackWatcher"
    }
}
