package dev.craig.reel

import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {

    private sealed class GenreChoice {
        data class Std(val genre: Genre) : GenreChoice()
        data class Kw(val query: String) : GenreChoice()
        /** Free-text results for [query]; movies and shows together, whatever the type toggle says. */
        data class Search(val query: String) : GenreChoice()
        object All : GenreChoice()
        object Trending : GenreChoice()
        object Watchlist : GenreChoice()
        object ForYou : GenreChoice()
        object Continue : GenreChoice()
        object Recent : GenreChoice()
        object More : GenreChoice()

        /** Discover-based rows honour the sort button; the others come pre-ordered. */
        val sortable get() = this is Std || this is Kw || this === All

        /**
         * Rows you asked for by name: shown as-is, without the "is it on my apps" filter,
         * and with movies and shows together whatever the type toggle says.
         */
        val personal get() = this === Continue || this === Recent || this is Search

        /** Rows that come from Trakt and need a linked account. */
        val trakt get() = this === Watchlist || this === ForYou || this === Continue
    }

    private lateinit var prefs: Prefs
    private lateinit var cache: DiskCache
    private var tmdb: Tmdb? = null

    private lateinit var appsSection: View
    private lateinit var appsRow: RecyclerView
    private lateinit var youtubeLabel: View
    private lateinit var youtubeRow: RecyclerView
    private lateinit var genreRow: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnMediaType: TextView
    private lateinit var btnSort: TextView
    private lateinit var weather: TextView
    private lateinit var greeting: TextView
    private lateinit var quoteView: TextView

    // Catalog state
    private var installed: List<InstalledService> = emptyList()
    private var providerIds: Map<String, Set<Int>> = emptyMap()
    private var providerNames: Map<Int, String> = emptyMap()
    private var standardGenres: List<Genre> = emptyList()
    private var genreChoices: List<GenreChoice> = emptyList()
    private var currentGenre: GenreChoice? = null
    private var currentKeyword: Keyword? = null

    // Paging state; `generation` lets stale responses be dropped when the selection changes.
    private var page = 0
    private var totalPages = 1
    private var loading = false
    private var generation = 0

    // App row "move mode"
    private var moving: AppEntry? = null

    private var weatherJob: Job? = null

    private val appAdapter = AppAdapter(
        onClick = { if (!Launch.openApp(this, it.pkg)) toast("Couldn't open ${it.label}") },
        onLongClick = { showAppMenu(it) },
    )

    private val videoAdapter = VideoRowAdapter(
        note = { listOf(it.channel, ago(it.published)).joinToString(" · ") },
        onClick = { onVideoClicked(it) },
        onLongClick = { onVideoLongClicked(it) },
        onSettings = { showYouTubeRowMenu() },
        onMessage = { if (subscriptions()?.isLinked == true) refreshYouTube(force = true) else linkYouTube() },
    )

    private val genreAdapter = ChipAdapter<GenreChoice>(
        label = {
            when (it) {
                is GenreChoice.Std -> it.genre.name
                is GenreChoice.Kw -> "✦ ${it.query}"
                is GenreChoice.Search -> "🔍 ${it.query}"
                GenreChoice.All -> "All"
                GenreChoice.Trending -> "🔥 Trending"
                GenreChoice.Watchlist -> "Watchlist"
                GenreChoice.ForYou -> "For you"
                GenreChoice.Continue -> "▶ Continue watching"
                GenreChoice.Recent -> "Recent"
                GenreChoice.More -> "＋ More genres…"
            }
        },
        isSelected = { it == currentGenre },
        onClick = { onGenreClicked(it) },
    )

    private val posterAdapter = PosterAdapter(
        onClick = { onTitleClicked(it) },
        onLongClick = { onTitleLongClicked(it) },
    )

    private val pickGenre = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val q = r.data?.getStringExtra(GenrePickerActivity.EXTRA_QUERY)?.trim()
        if (r.resultCode == RESULT_OK && !q.isNullOrEmpty()) {
            prefs.addRecentGenre(q)
            rebuildGenreChoices()
            selectGenre(GenreChoice.Kw(q))
        }
    }

    private val startedAt = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        cache = DiskCache(cacheDir)
        JustWatch.disk = cache
        YouTube.disk = cache
        lifecycleScope.launch(Dispatchers.IO) { cache.prune(CACHE_MAX_AGE_MS) }
        applyIntentExtras(intent)

        appsSection = findViewById(R.id.appsSection)
        appsRow = findViewById(R.id.appsRow)
        youtubeLabel = findViewById(R.id.youtubeLabel)
        youtubeRow = findViewById(R.id.youtubeRow)
        genreRow = findViewById(R.id.genreRow)
        grid = findViewById(R.id.grid)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnMediaType = findViewById(R.id.btnMediaType)
        btnSort = findViewById(R.id.btnSort)
        weather = findViewById(R.id.weather)
        greeting = findViewById(R.id.greeting)
        quoteView = findViewById(R.id.quote)

        appsRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        appsRow.adapter = appAdapter
        // No item animations: rebinding a slot must not swap the focused view out from under the D-pad.
        appsRow.itemAnimator = null
        youtubeRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        youtubeRow.adapter = videoAdapter
        youtubeRow.itemAnimator = null
        videoAdapter.size = prefs.youtubeThumbSize
        genreRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        genreRow.adapter = genreAdapter
        grid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        grid.adapter = posterAdapter

        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = maybeLoadMore()
        })

        btnMediaType.text = prefs.mediaType.label
        btnMediaType.setOnClickListener {
            prefs.mediaType = if (prefs.mediaType == MediaType.MOVIE) MediaType.TV else MediaType.MOVIE
            btnMediaType.text = prefs.mediaType.label
            lifecycleScope.launch { safely { loadGenres() } }
        }
        updateSortButton()
        btnSort.setOnClickListener { showSortPicker() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { showSearch() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }

        // Give the poster grid the whole screen while you're browsing it.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val v = if (newFocus != null && isInside(newFocus, grid)) View.GONE else View.VISIBLE
            if (appsSection.visibility != v) appsSection.visibility = v
            // The YouTube row's ⚙ is only there while it has focus; it folds away again when you move off it.
            // The row may be mid-layout (it re-focuses a card after Mark watched), so don't rebind it then.
            // Decide when the post runs, not now: unfolding it re-lays the row, which passes focus
            // through the first card on the way to the ⚙.
            if (youtubeRow.isComputingLayout) {
                youtubeRow.post { videoAdapter.settingsShown = rowSettingsFocused() }
            } else {
                videoAdapter.settingsShown = rowSettingsFocused()
            }
        }

        // A launcher never exits on Back; jump back up toward the top instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    grid.hasFocus() -> focusSelectedGenre()
                    !appsRow.hasFocus() -> appsRow.getChildAt(0)?.requestFocus()
                }
            }
        })

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // System broadcasts still arrive at a non-exported receiver; the flag is required from API 33.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, packageFilter)
        }
        loadApps()
        initCatalog()
        mark("onCreate done")
    }

    /** Startup timing in logcat: `adb logcat -s Reel:I`. */
    private fun mark(what: String) = Log.i(TAG, "$what after ${SystemClock.elapsedRealtime() - startedAt} ms")

    override fun onResume() {
        super.onResume()
        // The app row is rebuilt only when a package was installed, removed or updated while away.
        if (appsDirty) loadApps()
        // Back from YouTube: drop what you just watched, and pick up new uploads now and then.
        refreshYouTube()
        channelPage?.notifyDataSetChanged()
        startWeather()
        startGreeting()
    }

    // ---------- Greeting ----------

    private var greetingJob: Job? = null
    private var currentQuote: Quote? = null

    /** "Good evening, Craig" by the clock, and a movie line underneath that fades to a new one every so often. */
    private fun startGreeting() {
        greetingJob?.cancel()
        greetingJob = lifecycleScope.launch {
            while (true) {
                updateGreeting()
                showNextQuote()
                delay(QUOTE_INTERVAL_MS)
            }
        }
    }

    private fun updateGreeting() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val name = prefs.userName
        val base = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> if (name.isBlank()) "Up late?" else "Up late"
        }
        greeting.text = when {
            name.isBlank() -> if (base.endsWith("?")) base else "$base."
            hour in 5..21 -> "$base, $name"
            else -> "$base, $name?"
        }
    }

    private fun showNextQuote() {
        val q = Quotes.next(currentQuote)
        currentQuote = q
        if (quoteView.text.isNullOrEmpty()) {
            quoteView.text = q.display
            quoteView.alpha = 0f
            quoteView.animate().alpha(1f).setDuration(600).start()
        } else {
            quoteView.animate().alpha(0f).setDuration(400).withEndAction {
                quoteView.text = q.display
                quoteView.animate().alpha(1f).setDuration(600).start()
            }.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
    }

    private var appsDirty = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsDirty = true
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) loadApps()
        }
    }

    override fun onPause() {
        super.onPause()
        weatherJob?.cancel()
        greetingJob?.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntentExtras(intent)) initCatalog()
    }

    /**
     * Lets you set things from your PC, e.g.
     * adb shell am start -n dev.craig.reel/.MainActivity --es tmdb_key KEY
     * adb shell am start -n dev.craig.reel/.MainActivity --es trakt_client_id ID --es trakt_client_secret SECRET
     */
    private fun applyIntentExtras(i: Intent?): Boolean {
        var changed = false
        i?.getStringExtra("tmdb_key")?.takeIf { it.isNotBlank() }?.let {
            prefs.tmdbKey = it
            changed = true
            toast("TMDB key saved")
        }
        i?.getStringExtra("region")?.takeIf { it.isNotBlank() }?.let {
            prefs.region = it
            changed = true
        }
        i?.getStringExtra("trakt_client_id")?.takeIf { it.isNotBlank() }?.let {
            prefs.traktClientId = it
            toast("Trakt client id saved")
        }
        i?.getStringExtra("trakt_client_secret")?.takeIf { it.isNotBlank() }?.let { prefs.traktClientSecret = it }
        i?.getStringExtra("google_client_id")?.takeIf { it.isNotBlank() }?.let {
            prefs.googleClientId = it
            toast("Google client id saved")
            if (::youtubeRow.isInitialized) refreshYouTube()
        }
        i?.getStringExtra("google_client_secret")?.takeIf { it.isNotBlank() }?.let { prefs.googleClientSecret = it }
        i?.getStringExtra("weather")?.let {
            prefs.weatherQuery = it
            if (::weather.isInitialized) startWeather()
        }
        // --es name Craig : what the greeting calls you.
        i?.getStringExtra("name")?.let {
            prefs.userName = it
            if (::greeting.isInitialized) updateGreeting()
        }
        // --es row continue|recent|trending|all|watchlist|foryou : jump to a row (handy from adb).
        i?.getStringExtra("row")?.let { name ->
            pendingRow = name.lowercase()
            if (!changed && standardGenres.isNotEmpty()) applyPendingRow()
        }
        // --es search "the bear" : run a search as if typed.
        i?.getStringExtra("search")?.takeIf { it.isNotBlank() }?.let { q ->
            pendingRow = "search:$q"
            if (!changed && standardGenres.isNotEmpty()) applyPendingRow()
        }
        // --es open tv:95480 : open the detail panel for a TMDB id.
        i?.getStringExtra("open")?.takeIf { it.isNotBlank() }?.let { ref ->
            pendingRow = "open:$ref"
            if (!changed && standardGenres.isNotEmpty()) applyPendingRow()
        }
        return changed
    }

    private var pendingRow: String? = null

    private fun applyPendingRow() {
        val name = pendingRow ?: return
        pendingRow = null
        if (name.startsWith("search:")) {
            search(name.removePrefix("search:"))
            return
        }
        if (name.startsWith("open:")) {
            val (kind, id) = name.removePrefix("open:").split(':').let { it.getOrNull(0) to it.getOrNull(1)?.toIntOrNull() }
            val type = MediaType.entries.firstOrNull { it.path == kind }
            val client = tmdb
            if (type == null || id == null || client == null) return toast("Bad open ref “$name”")
            lifecycleScope.launch { attempt { client.details(type, id) }?.let { onTitleClicked(it) } ?: toast("TMDB has no $kind $id") }
            return
        }
        val choice = when (name) {
            "continue" -> GenreChoice.Continue
            "recent" -> GenreChoice.Recent
            "trending" -> GenreChoice.Trending
            "all" -> GenreChoice.All
            "watchlist" -> GenreChoice.Watchlist
            "foryou" -> GenreChoice.ForYou
            else -> null
        }
        if (choice != null && choice in genreChoices) selectGenre(choice) else toast("No row “$name”")
    }

    // ---------- Apps ----------

    private var appsJob: Job? = null

    /** Query installed apps and decode their banners off the main thread, then show them. */
    private fun loadApps() {
        appsDirty = false
        appsJob?.cancel()
        appsJob = lifecycleScope.launch {
            val pm = packageManager
            val hidden = prefs.hiddenApps
            val own = packageName
            val entries = withContext(Dispatchers.IO) {
                val seen = HashSet<String>()
                val out = mutableListOf<AppEntry>()
                for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                    @Suppress("DEPRECATION")
                    val infos = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                    for (ri in infos) {
                        val ai = ri.activityInfo
                        val pkg = ai.packageName
                        if (pkg == own || pkg in hidden || !seen.add(pkg)) continue
                        out += AppEntry(
                            pkg = pkg,
                            label = ri.loadLabel(pm).toString(),
                            banner = ai.loadBanner(pm) ?: ai.applicationInfo?.loadBanner(pm),
                            icon = ri.loadIcon(pm),
                        )
                    }
                }
                out
            }
            // Your arranged order first, then everything else A–Z.
            val rank = prefs.appOrder.withIndex().associate { it.value to it.index }
            appAdapter.items = entries.sortedWith(compareBy({ rank[it.pkg] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
            mark("apps shown")
        }
    }

    private fun showAppMenu(e: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle(e.label)
            .setItems(arrayOf("Move", "Hide")) { _, which ->
                when (which) {
                    0 -> startMove(e)
                    1 -> confirmHide(e)
                }
            }
            .show()
    }

    private fun startMove(e: AppEntry) {
        val idx = appAdapter.items.indexOf(e)
        if (idx < 0) return
        moving = e
        appAdapter.movingPkg = e.pkg
        appAdapter.notifyItemChanged(idx)
        focusApp(idx)
        toast("◀ ▶ to move ${e.label}, OK when done")
    }

    private fun endMove() {
        val e = moving ?: return
        moving = null
        appAdapter.movingPkg = null
        val idx = appAdapter.items.indexOf(e)
        if (idx >= 0) appAdapter.notifyItemChanged(idx)
        prefs.appOrder = appAdapter.items.map { it.pkg }
        focusApp(idx)
    }

    private fun moveApp(e: AppEntry, delta: Int) {
        val from = appAdapter.items.indexOf(e)
        val to = from + delta
        if (from < 0 || to < 0 || to >= appAdapter.items.size) return
        appAdapter.swap(from, to)
        focusApp(to)
    }

    /** Focus the card in [idx] once it's laid out, scrolling it into view only if it's off-screen. */
    private fun focusApp(idx: Int) {
        if (idx < 0) return
        appsRow.post {
            val v = appsRow.layoutManager?.findViewByPosition(idx)
            if (v != null) {
                v.requestFocus()
            } else {
                appsRow.scrollToPosition(idx)
                appsRow.post { appsRow.layoutManager?.findViewByPosition(idx)?.requestFocus() }
            }
        }
    }

    /** While an app is being moved, the D-pad rearranges instead of navigating. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (moving == null && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && genreRow.hasFocus() &&
            appsSection.visibility == View.VISIBLE && posterAdapter.itemCount > 0
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) dropIntoGrid()
            return true
        }
        if (moving == null && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !videoAdapter.settingsShown &&
            youtubeRow.focusedChild?.let { youtubeRow.getChildAdapterPosition(it) } == 1
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) revealRowSettings()
            return true
        }
        val e = moving ?: return super.dispatchKeyEvent(event)
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveApp(e, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveApp(e, +1)
                else -> endMove()
            }
        }
        return true
    }

    /**
     * Down from the genres. The rows above can leave the grid too short to hold a focusable
     * poster, so collapse them first (as browsing the grid would anyway), then step down.
     */
    private fun dropIntoGrid() {
        val from = currentFocus ?: return
        appsSection.visibility = View.GONE
        grid.doOnNextLayout {
            val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, from, View.FOCUS_DOWN)
                ?: grid.getChildAt(0)
            if (next?.requestFocus() != true) appsSection.visibility = View.VISIBLE
        }
    }

    private fun confirmHide(e: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle("Hide ${e.label}?")
            .setMessage("You can bring hidden apps back from Reel settings.")
            .setPositiveButton("Hide") { _, _ ->
                prefs.hiddenApps = prefs.hiddenApps + e.pkg
                loadApps()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Catalog ----------

    private fun initCatalog() {
        installed = ProviderCatalog.installed(packageManager)
        val key = prefs.tmdbKey
        if (key.isBlank()) {
            tmdb = null
            showStatus(getString(R.string.no_key))
            return
        }
        val client = Tmdb(key, cache)
        tmdb = client
        lifecycleScope.launch {
            showStatus("Loading…", busy = true)
            safely {
                val region = prefs.region
                val providers = client.providers(MediaType.MOVIE, region) + client.providers(MediaType.TV, region)
                providerIds = ProviderCatalog.mapIds(installed, providers)
                providerNames = providers.associate { it.id to it.name }
                mark("providers loaded")
                loadGenres()
            }
        }
    }

    private suspend fun loadGenres() {
        val client = tmdb ?: return
        standardGenres = client.genres(prefs.mediaType)
        rebuildGenreChoices()
        // Keep the current pick across a Movies/Shows switch when it still exists.
        val keep = currentGenre
        val next = when (keep) {
            is GenreChoice.Std -> genreChoices.firstOrNull { it is GenreChoice.Std && it.genre.name == keep.genre.name }
            null, GenreChoice.More -> null
            else -> keep.takeIf { it in genreChoices }
        } ?: GenreChoice.All
        selectGenre(next)
        applyPendingRow()
    }

    private fun rebuildGenreChoices() {
        val linked = trakt()?.isLinked == true
        val mine = listOfNotNull(
            GenreChoice.Continue.takeIf { linked },
            GenreChoice.Recent.takeIf { prefs.recentTitles.isNotEmpty() },
        )
        val traktRows = if (linked) listOf(GenreChoice.Watchlist, GenreChoice.ForYou) else emptyList()
        // The latest search (started from the header button) keeps a chip for the session, so you can come back to it.
        val search = listOfNotNull(lastSearch)
        genreChoices = search + listOf(GenreChoice.More) + mine +
            listOf(GenreChoice.Trending, GenreChoice.All) +
            traktRows +
            prefs.recentGenres.map { GenreChoice.Kw(it) } +
            standardGenres.map { GenreChoice.Std(it) }
        genreAdapter.items = genreChoices
    }

    private fun onGenreClicked(c: GenreChoice) {
        when (c) {
            GenreChoice.More -> pickGenre.launch(Intent(this, GenrePickerActivity::class.java))
            else -> selectGenre(c)
        }
    }

    // ---------- Search ----------

    private var lastSearch: GenreChoice.Search? = null

    private fun showSearch() {
        if (tmdb == null) {
            toast(getString(R.string.no_key))
            return
        }
        val last = (currentGenre as? GenreChoice.Search)?.query ?: ""
        promptText("Search movies and shows", last, hint = "Title, e.g. Severance", onSave = ::search)
    }

    private fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        val choice = GenreChoice.Search(q)
        lastSearch = choice
        rebuildGenreChoices()
        selectGenre(choice)
        genreRow.post { focusSelectedGenre() }
    }

    /** The remote's search key (where the system lets a launcher see it) opens the search box. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            showSearch()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun selectGenre(c: GenreChoice) {
        currentGenre = c
        genreAdapter.notifyDataSetChanged()
        updateSortButton()
        val client = tmdb ?: return
        val gen = ++generation
        posterAdapter.clear()
        page = 0
        totalPages = 1
        loading = false
        currentKeyword = null
        lifecycleScope.launch {
            if (c is GenreChoice.Kw) {
                showStatus("Looking up “${c.query}”…", busy = true)
                val kw = try {
                    client.searchKeyword(c.query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (gen == generation) showStatus("Couldn't reach TMDB: ${e.message}")
                    return@launch
                }
                if (gen != generation) return@launch
                if (kw == null) {
                    showStatus("TMDB has no tag for “${c.query}”. Try different wording.")
                    return@launch
                }
                currentKeyword = kw
            }
            loadNextPage(gen)
        }
    }

    private fun enabledServices() = installed.filter { it.label !in prefs.disabledProviders }

    private fun enabledProviderIds(): Set<Int> =
        enabledServices().flatMap { providerIds[it.label] ?: emptySet() }.toSet()

    private suspend fun loadNextPage(gen: Int = generation) {
        val client = tmdb ?: return
        val choice = currentGenre ?: return
        if (choice is GenreChoice.Kw && currentKeyword == null) return
        if (loading || page >= totalPages) return

        val ids = enabledProviderIds()
        if (ids.isEmpty()) {
            showStatus(
                when {
                    installed.isEmpty() -> "None of Reel's supported streaming apps are installed."
                    enabledServices().isEmpty() -> "All your streaming apps are switched off. Turn one on in ⚙ → Streaming apps."
                    else -> "TMDB doesn't list your apps for region ${prefs.region}."
                }
            )
            return
        }

        // Personal rows normally show everything; with "included only" on, search and
        // continue-watching are checked like any other row. Recent is history, so it's never filtered.
        val raw = choice === GenreChoice.Recent || (choice.personal && !prefs.includedOnly)

        loading = true
        if (page == 0) showStatus("Loading…", busy = true)
        try {
            // Discover over-matches (see Tmdb.onlyStreamingOn), so a page can shrink to nothing
            // after checking. Show each page's survivors as soon as they're known, and keep
            // pulling pages until there's enough on screen, within a cap.
            var found = 0
            var pulled = 0
            do {
                val res = fetchPage(client, choice, page + 1)
                if (gen != generation) return
                page = res.page
                totalPages = res.totalPages
                if (page == 1 && posterAdapter.itemCount == 0 && !raw) showStatus("Checking your apps…", busy = true)
                val keep = if (raw) {
                    res.items
                } else {
                    client.onlyStreamingOn(res.items, ids, prefs.region) { t, on ->
                        // Anything besides YouTube is trusted; a YouTube-only title must have a live video.
                        on.any { it !in youtubeIds() } || playableOnYouTube(t, null)
                    }
                }
                if (gen != generation) return
                if (keep.isNotEmpty()) {
                    if (posterAdapter.itemCount == 0) {
                        hideStatus()
                        mark("first posters shown")
                    }
                    posterAdapter.append(keep)
                }
                found += keep.size
                pulled++
            } while (found < MIN_BATCH && page < totalPages && pulled < MAX_PAGES_PER_BATCH)
            if (posterAdapter.itemCount == 0) {
                if (page < totalPages) {
                    // Nothing yet, but more pages remain; keep going on the next call.
                    showStatus("Checking your apps…", busy = true)
                } else {
                    showStatus(emptyMessage(choice))
                }
            } else {
                hideStatus()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == generation) showStatus("Couldn't load titles: ${e.message}")
        } finally {
            if (gen == generation) loading = false
        }
        // Filtering may have left the grid too short to scroll; top it up until it can.
        if (gen == generation) grid.post { if (gen == generation) maybeLoadMore() }
    }

    /** One page of raw candidates for a row, before availability checks. */
    private suspend fun fetchPage(client: Tmdb, choice: GenreChoice, pageNo: Int): ResultPage = when (choice) {
        GenreChoice.Trending -> client.trending(prefs.mediaType, pageNo)
        is GenreChoice.Search -> client.search(choice.query, pageNo)
        GenreChoice.Recent -> {
            val titles = prefs.recentTitles.map { r ->
                r.title.copy(note = listOfNotNull(r.app.ifBlank { null }, ago(r.at)).joinToString(" · "))
            }
            ResultPage(1, 1, titles)
        }
        GenreChoice.Watchlist, GenreChoice.ForYou, GenreChoice.Continue -> {
            // Trakt lists come whole; look the titles up on TMDB and show them as a single page.
            val t = trakt() ?: throw IllegalStateException("Trakt isn't linked")
            val items = when (choice) {
                GenreChoice.Watchlist -> t.watchlist(prefs.mediaType)
                GenreChoice.ForYou -> t.recommendations(prefs.mediaType)
                else -> t.continueWatching()
            }.take(MAX_LIST_ITEMS)
            val notes = items.associate { (it.type to it.tmdbId) to it.note }
            val titles = client.detailsMany(items.map { it.type to it.tmdbId }).map { it.copy(note = notes[it.type to it.id]) }
            ResultPage(1, 1, titles)
        }
        else -> client.discover(
            type = prefs.mediaType,
            genreId = (choice as? GenreChoice.Std)?.genre?.id,
            keywordId = currentKeyword?.id,
            providerIds = enabledProviderIds(),
            region = prefs.region,
            page = pageNo,
            // Niche tags have few votes; don't filter them away.
            minVotes = if (choice is GenreChoice.Kw) 3 else 50,
            sort = prefs.sort,
        )
    }

    /** Short enough to share a poster's note line with an app name: "5m ago", "3h ago", "Oct 3". */
    private fun ago(at: Long): String {
        val mins = (System.currentTimeMillis() - at) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 24 * 60 -> "${mins / 60}h ago"
            mins < 7 * 24 * 60 -> "${mins / (24 * 60)}d ago"
            else -> DateUtils.formatDateTime(this, at, DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
        }
    }

    private fun emptyMessage(choice: GenreChoice) = when (choice) {
        GenreChoice.Watchlist -> "Nothing on your Trakt watchlist streams on your apps right now."
        GenreChoice.ForYou -> "None of Trakt's picks for you stream on your apps right now."
        GenreChoice.Trending -> "Nothing trending streams on your apps right now."
        GenreChoice.Continue ->
            if (prefs.includedOnly) "Nothing you're part-way through streams on your apps right now." else "Trakt has nothing in progress."
        GenreChoice.Recent -> "Nothing opened from Reel yet."
        is GenreChoice.Search ->
            if (prefs.includedOnly) "Nothing matching “${choice.query}” is included with your apps." else "TMDB found nothing for “${choice.query}”."
        else -> "Nothing in your apps for this genre right now."
    }

    /** Load another page when the grid is near its end (or can't scroll yet). */
    private fun maybeLoadMore() {
        if (loading || page >= totalPages) return
        val lm = grid.layoutManager as GridLayoutManager
        if (lm.findLastVisibleItemPosition() >= posterAdapter.itemCount - GRID_COLUMNS * 2) {
            lifecycleScope.launch { loadNextPage() }
        }
    }

    // ---------- Sort ----------

    private fun updateSortButton() {
        btnSort.text = prefs.sort.label
        btnSort.alpha = if (currentGenre?.sortable != false) 1f else 0.4f
    }

    private fun showSortPicker() {
        val sorts = Sort.entries
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(sorts.map { it.label }.toTypedArray(), sorts.indexOf(prefs.sort)) { d, which ->
                d.dismiss()
                if (sorts[which] != prefs.sort) {
                    prefs.sort = sorts[which]
                    updateSortButton()
                    currentGenre?.takeIf { it.sortable }?.let { selectGenre(it) }
                }
            }
            .show()
    }

    // ---------- Opening a title ----------

    /** Open the detail panel: what it is, who's in it, and where you can watch it. */
    private fun onTitleClicked(t: Title) {
        val client = tmdb ?: return
        val dialog = Dialog(this, R.style.Theme_Reel_Detail)
        dialog.setContentView(R.layout.dialog_detail)
        val v = DetailViews(dialog)

        v.title.text = t.name
        v.meta.text = t.year
        dialog.setOnDismissListener { detailJob?.cancel() }
        dialog.show()
        v.poster.load(t.posterPath?.let { Tmdb.IMAGE_BASE + it }) {
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
            listener(onError = { _, r -> Log.w(TAG, "poster failed", r.throwable) })
        }

        detailJob = lifecycleScope.launch {
            coroutineScope {
                val d = async { attempt { client.detail(t.type, t.id) } }
                val a = async { attempt { client.availability(t.type, t.id, prefs.region) } }
                // Links are a nice-to-have; a JustWatch hiccup must not block opening the app.
                val o = async { attempt { JustWatch.offers(t, prefs.region) } ?: emptyList() }
                val detail = d.await()
                if (detail != null) bindDetail(v, detail) else v.overview.text = "Couldn't load details from TMDB."
                bindActions(dialog, v, t, a.await(), o.await(), detail?.trailerKey)
            }
        }
    }

    private var detailJob: Job? = null

    private class DetailViews(d: Dialog) {
        val backdrop: ImageView = d.findViewById(R.id.detailBackdrop)
        val poster: ImageView = d.findViewById(R.id.detailPoster)
        val title: TextView = d.findViewById(R.id.detailTitle)
        val meta: TextView = d.findViewById(R.id.detailMeta)
        val tagline: TextView = d.findViewById(R.id.detailTagline)
        val overview: TextView = d.findViewById(R.id.detailOverview)
        val cast: TextView = d.findViewById(R.id.detailCast)
        val where: TextView = d.findViewById(R.id.detailWhere)
        val actions: LinearLayout = d.findViewById(R.id.detailActions)
        val progress: View = d.findViewById(R.id.detailProgress)
    }

    private fun bindDetail(v: DetailViews, d: TitleDetail) {
        v.backdrop.load(d.backdropPath?.let { Tmdb.BACKDROP_BASE + it }) {
            crossfade(true)
            listener(onError = { _, r -> Log.w(TAG, "backdrop failed", r.throwable) })
        }
        val parts = mutableListOf<String>()
        if (d.title.year.isNotBlank()) parts += d.title.year
        if (d.title.type == MediaType.MOVIE) {
            if (d.runtimeMin > 0) parts += "${d.runtimeMin / 60}h ${d.runtimeMin % 60}m".removePrefix("0h ")
        } else {
            if (d.seasons > 0) parts += "${d.seasons} season${if (d.seasons == 1) "" else "s"}"
            if (d.episodes > 0) parts += "${d.episodes} episode${if (d.episodes == 1) "" else "s"}"
            if (d.ended) parts += "Ended"
        }
        if (d.votes > 0) parts += "★ ${"%.1f".format(d.rating)}"
        if (d.genres.isNotEmpty()) parts += d.genres.joinToString(", ")
        v.meta.text = parts.joinToString("   ·   ")
        v.tagline.text = d.tagline
        v.tagline.visibility = if (d.tagline.isBlank()) View.GONE else View.VISIBLE
        v.overview.text = d.overview.ifBlank { "No description on TMDB." }
        v.cast.text = "Starring ${d.cast.joinToString(", ")}"
        v.cast.visibility = if (d.cast.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Fill the button row once availability is known; the first button takes focus. */
    private suspend fun bindActions(
        dialog: Dialog,
        v: DetailViews,
        t: Title,
        avail: Availability?,
        offers: List<Offer>,
        trailerKey: String?,
    ) {
        v.progress.visibility = View.GONE
        var streaming = if (avail == null) emptyList() else enabledServices().filter { it.hasAny(avail.stream) }
        var youtubeGone = false
        if (streaming.any { it.label == YOUTUBE } && !playableOnYouTube(t, offers)) {
            streaming = streaming.filter { it.label != YOUTUBE }
            youtubeGone = true
        }
        val rentOn = if (avail == null || prefs.includedOnly) emptyList() else installed.filter { it.hasAny(avail.rentBuy) && it !in streaming }
        // Providers that stream it and aren't one of your installed apps, matched by id (so
        // "Amazon Prime Video" counts as the Prime Video app, not as somewhere else).
        val mine = installed.flatMap { providerIds[it.label] ?: emptySet() }.toSet()
        val elsewhere = if (avail == null) emptyList() else names(avail.stream.filter { it !in mine }.toSet())

        v.where.text = when {
            avail == null -> "Couldn't check where it's streaming right now."
            streaming.isNotEmpty() -> buildString {
                append("Included with ${streaming.joinToString(", ") { it.label }}")
                if (elsewhere.isNotEmpty()) append("   ·   also on ${elsewhere.joinToString(", ")}")
            }
            else -> buildString {
                append("Not included with any of your streaming apps right now.")
                if (youtubeGone) append(" The free YouTube copy has been taken down.")
                if (elsewhere.isNotEmpty()) append("\nStreaming on ${elsewhere.joinToString(", ")}.")
            }
        }

        fun chip(label: String, onClick: () -> Unit): TextView {
            val tv = layoutInflater.inflate(R.layout.item_action, v.actions, false) as TextView
            tv.text = label
            tv.setOnClickListener { onClick() }
            v.actions.addView(tv)
            return tv
        }
        for (svc in streaming) chip("▶  Watch on ${svc.label}") { dialog.dismiss(); openOn(svc, t, offers) }
        for (svc in rentOn) chip("Rent or buy on ${svc.label}") { dialog.dismiss(); openOn(svc, t, offers) }
        if (trailerKey != null) chip("Trailer") { openTrailer(trailerKey) }
        chip("Close") { dialog.dismiss() }
        // Index 0 is the (hidden) progress spinner; focus the first real button once it's laid out.
        v.actions.getChildAt(1)?.let { first -> first.post { first.requestFocus() } }
    }

    private fun openTrailer(key: String) {
        if (!openYouTube(key)) toast("No app can play the trailer")
    }

    /** Play a video in the YouTube app, or whatever handles the link. */
    private fun openYouTube(videoId: String): Boolean {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val yt = ProviderCatalog.apps.first { it.label == YOUTUBE }.packages
            .firstOrNull { Launch.launchIntent(packageManager, it) != null }
        val direct = yt?.let { Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(it) }
        return (direct != null && Launch.start(this, direct)) || Launch.start(this, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Run [block], returning null on failure. Cancellation still propagates. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun InstalledService.hasAny(ids: Set<Int>) = (providerIds[label] ?: emptySet()).any { it in ids }

    private fun youtubeIds(): Set<Int> = providerIds[YOUTUBE] ?: emptySet()

    /**
     * TMDB says it's on YouTube Free; is the actual upload still public? [offers] may be passed in
     * when already fetched. No link or a private/removed video means no.
     */
    private suspend fun playableOnYouTube(t: Title, offers: List<Offer>?): Boolean {
        val list = offers ?: try {
            JustWatch.offers(t, prefs.region)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true // can't tell; leave it to the click
        }
        val yt = installed.firstOrNull { it.label == YOUTUBE }?.app ?: return false
        val ids = list.filter { it.isStreaming && yt.matches(Tmdb.normalize(it.providerName)) }
            .mapNotNull { YouTube.videoId(it.webUrl) }
            .distinct()
        if (ids.isEmpty()) return false
        return ids.any { YouTube.isPublic(it) }
    }

    /** Human-readable provider names, collapsing TMDB's variants ("Netflix", "Netflix Standard with Ads"). */
    private fun names(ids: Set<Int>): List<String> =
        ids.mapNotNull { providerNames[it] }
            .map { it.replace(Regex(" (with Ads|Standard with Ads|Free|Amazon Channel|Apple TV Channel|Roku Premium Channel)$"), "") }
            .distinct()
            .sorted()

    private fun openOn(svc: InstalledService, t: Title, offers: List<Offer>) {
        if (!Launch.openTitle(this, svc, t.name, offers)) {
            toast("Couldn't open ${svc.label}")
            return
        }
        val firstEver = prefs.recentTitles.isEmpty()
        prefs.addRecentTitle(t, svc.label)
        if (firstEver) rebuildGenreChoices()
    }

    /** Long press on a poster: in the Recent row, offer to forget it. */
    private fun onTitleLongClicked(t: Title): Boolean {
        if (currentGenre != GenreChoice.Recent) return false
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(arrayOf("Remove from Recent", "Clear all Recent")) { _, which ->
                when (which) {
                    0 -> {
                        prefs.removeRecentTitle(t)
                        posterAdapter.remove(t)
                        if (posterAdapter.itemCount == 0) showStatus(emptyMessage(GenreChoice.Recent))
                        if (prefs.recentTitles.isEmpty()) {
                            rebuildGenreChoices()
                            selectGenre(GenreChoice.All)
                        }
                    }
                    1 -> {
                        prefs.recentTitles = emptyList()
                        rebuildGenreChoices()
                        selectGenre(GenreChoice.All)
                    }
                }
            }
            .show()
        return true
    }

    // ---------- Weather ----------

    private fun startWeather() {
        weatherJob?.cancel()
        weatherJob = lifecycleScope.launch {
            while (true) {
                refreshWeather()
                delay(WEATHER_REFRESH_MS)
            }
        }
    }

    private suspend fun refreshWeather() {
        try {
            val place = resolvePlace() ?: run { weather.text = ""; return }
            val c = Weather.current(place, fahrenheit = prefs.region == "US")
            weather.text = "${c.icon} ${c.temp}°"
            weather.contentDescription = "${c.temp} degrees, ${place.name}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep whatever was showing; try again next cycle.
        }
    }

    /** Cached place for the current query, or resolve it (typed place, else the TV's IP). */
    private suspend fun resolvePlace(): Place? {
        val q = prefs.weatherQuery
        prefs.weatherPlace?.takeIf { it.query == q }?.let { return it }
        val p = if (q.isBlank()) Weather.locateByIp() else Weather.geocode(q)
        if (p != null) prefs.weatherPlace = p
        return p
    }

    // ---------- Trakt ----------

    private fun trakt(): Trakt? {
        val id = prefs.traktClientId
        return if (id.isBlank()) null else Trakt(id, prefs.traktClientSecret, prefs)
    }

    private fun linkTrakt() {
        val t = trakt()
        if (t == null) {
            AlertDialog.Builder(this)
                .setTitle("Trakt needs a client ID")
                .setMessage(
                    "Create an app at trakt.tv/oauth/applications (redirect URI ${Trakt.REDIRECT_URI}), " +
                        "then from your PC:\n\nadb shell am start -n dev.craig.reel/.MainActivity " +
                        "--es trakt_client_id ID\n\n(New Trakt apps have no secret; that's fine.)",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        var done = false
        var dialog: AlertDialog? = null
        val job = lifecycleScope.launch {
            val code = try {
                t.requestCode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't reach Trakt: ${e.message}")
                return@launch
            }
            dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Link Trakt")
                .setMessage("On your phone or PC, go to\n${code.url}\n\nand enter this code:\n\n${code.userCode}")
                .setNegativeButton("Cancel", null)
                .create()
            dialog?.show()
            val user = try {
                t.waitForApproval(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            done = true
            dialog?.dismiss()
            if (user != null) {
                toast("Trakt linked as $user")
                rebuildGenreChoices()
            } else {
                toast("Trakt link didn't complete")
            }
        }
        lifecycleScope.launch {
            // Cancelling the dialog stops the polling.
            while (dialog == null && job.isActive) delay(100)
            dialog?.setOnDismissListener { if (!done) job.cancel() }
        }
    }

    // ---------- YouTube subscriptions ----------

    /** Recent uploads from every subscription; the row is picked from these each time it's drawn. */
    private var uploads: List<Video>? = null
    /** Channel id -> position in YouTube's "most relevant" order of your subscriptions. */
    private var channelRank: Map<String, Int> = emptyMap()
    private var uploadsAt = 0L
    private var uploadsError: String? = null
    private var youtubeJob: Job? = null

    private fun subscriptions(): Subscriptions? {
        val id = prefs.googleClientId
        return if (id.isBlank()) null else Subscriptions(id, prefs.googleClientSecret, prefs, cache)
    }

    /** Redraw the row from what's known, and fetch uploads again when they're stale (or [force]). */
    private fun refreshYouTube(force: Boolean = false) {
        val hidden = prefs.youtubeRowHidden
        youtubeLabel.visibility = if (hidden) View.GONE else View.VISIBLE
        youtubeRow.visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) {
            youtubeJob?.cancel()
            return
        }
        val subs = subscriptions()?.takeIf { it.isLinked }
        if (subs == null) uploads = null
        renderYouTube()
        val stale = force || uploads == null || SystemClock.elapsedRealtime() - uploadsAt > YOUTUBE_REFRESH_MS
        if (subs == null || !stale || youtubeJob?.isActive == true) return
        youtubeJob = lifecycleScope.launch {
            try {
                channelRank = subs.channels().withIndex().associate { it.value.id to it.index }
                uploads = subs.recentUploads(prefs.inOrderChannels)
                uploadsAt = SystemClock.elapsedRealtime()
                uploadsError = null
                mark("youtube row loaded")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the last good uploads, if any.
                uploadsError = e.message ?: "network error"
            }
            renderYouTube()
        }
    }

    private fun renderYouTube() {
        val linked = subscriptions()?.isLinked == true
        val all = uploads
        val body = when {
            !linked -> listOf(RowItem.Message("▶  Link YouTube to see new videos from your subscriptions"))
            all == null -> listOf(RowItem.Message(uploadsError?.let { "Couldn't load YouTube: $it" } ?: "Loading your subscriptions…"))
            else -> Subscriptions.pickRow(all, prefs.watchedVideos, prefs.channelWatchSecs, prefs.inOrderChannels, channelRank, prefs.youtubeRowSort)
                .map { RowItem.Clip(it.video, it.more) }
                .ifEmpty { listOf(RowItem.Message("Nothing new from your subscriptions")) }
        }
        // If the card under the D-pad goes (you just watched it), land on whatever takes its place.
        val focused = youtubeRow.focusedChild?.let { youtubeRow.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        val items = listOf(RowItem.Settings) + body
        videoAdapter.items = items
        if (focused != RecyclerView.NO_POSITION) {
            val idx = focused.coerceAtMost(items.size - 1)
            youtubeRow.post { youtubeRow.layoutManager?.findViewByPosition(idx)?.requestFocus() }
        }
    }

    private fun onVideoClicked(v: Video) {
        if (!openYouTube(v.id)) return toast("Couldn't open YouTube")
        // Without playback tracking, opening it is the best sign you watched it.
        if (!PlaybackWatcher.isEnabled(this)) prefs.markVideosWatched(listOf(v.watchKey))
    }

    private fun onVideoLongClicked(v: Video) {
        val ch = Channel(v.channelId, v.channel)
        val inOrder = v.channelId in prefs.inOrderChannels
        val actions = listOfNotNull(
            "Mark watched" to {
                prefs.markVideosWatched(listOf(v.watchKey))
                renderYouTube()
            },
            "More from ${v.channel}" to { showChannelPage(ch, picking = false) },
            if (inOrder) "Start ${v.channel} somewhere else…" to { showChannelPage(ch, picking = true) } else null,
            if (inOrder) {
                "Stop watching ${v.channel} in order" to {
                    prefs.setInOrder(v.channelId, null)
                    toast("${v.channel}: back to the newest video")
                    refreshYouTube(force = true)
                }
            } else {
                "Watch ${v.channel} in order…" to { showChannelPage(ch, picking = true) }
            },
        )
        AlertDialog.Builder(this)
            .setTitle(v.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    // ---------- YouTube channel page ----------

    /** The open channel page's adapter, redrawn when you come back from playing one of its videos. */
    private var channelPage: VideoRowAdapter? = null

    /** Marked as watched, or older than where you started watching its channel in order. */
    private fun isWatched(v: Video): Boolean =
        v.watchKey in prefs.watchedVideos || prefs.inOrderChannels[v.channelId]?.let { v.published < it } == true

    /**
     * A channel's latest 50 uploads, newest first, watched ones dimmed. With [picking], choosing a
     * video starts watching the channel in order from it; otherwise it plays, and long-press offers
     * the rest.
     */
    private fun showChannelPage(ch: Channel, picking: Boolean) {
        val subs = subscriptions()?.takeIf { it.isLinked } ?: return toast("Link YouTube first")
        val dialog = Dialog(this, R.style.Theme_Reel_Detail)
        dialog.setContentView(R.layout.dialog_channel)
        val grid = dialog.findViewById<RecyclerView>(R.id.channelGrid)
        val status = dialog.findViewById<TextView>(R.id.channelStatus)
        dialog.findViewById<TextView>(R.id.channelTitle).text = ch.name
        val hint = dialog.findViewById<TextView>(R.id.channelHint)
        fun updateHint() {
            val start = prefs.inOrderChannels[ch.id]
            hint.text = when {
                picking -> "Pick the first video you haven't seen. Reel lines up the rest in order, oldest first."
                start != null -> "Watching in order from ${DateUtils.formatDateTime(this, start, DateUtils.FORMAT_ABBREV_MONTH)}. Long-press a video for more."
                else -> "Long-press a video to mark it watched or to start watching in order there."
            }
        }
        updateHint()

        var videos: List<Video> = emptyList()
        val adapter = VideoRowAdapter(
            note = { ago(it.published) },
            onClick = { v ->
                if (picking) {
                    dialog.dismiss()
                    startInOrder(v)
                } else {
                    onVideoClicked(v)
                }
            },
            onLongClick = { v -> showChannelVideoMenu(v, videos, onChange = ::updateHint) },
            watched = { isWatched(it) },
        )
        adapter.size = ThumbSize.SMALL
        val density = resources.displayMetrics.density
        // Card width plus its end margin; as many columns as fit beside the page margins.
        val span = ((resources.displayMetrics.widthPixels - 96 * density) / ((ThumbSize.SMALL.widthDp + 6 + 14) * density)).toInt()
        grid.layoutManager = GridLayoutManager(this, span.coerceAtLeast(1))
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: android.graphics.Rect, v: View, parent: RecyclerView, state: RecyclerView.State) {
                out.bottom = (14 * density).toInt()
            }
        })
        grid.adapter = adapter
        channelPage = adapter

        val job = lifecycleScope.launch {
            status.text = "Loading…"
            try {
                videos = subs.channelUploads(ch)
                adapter.items = videos.map { RowItem.Clip(it) }
                if (videos.isEmpty()) {
                    status.text = "No uploads found."
                } else {
                    status.visibility = View.GONE
                    grid.post { grid.getChildAt(0)?.requestFocus() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Couldn't load ${ch.name}: ${e.message}"
            }
        }
        dialog.setOnDismissListener {
            job.cancel()
            channelPage = null
            renderYouTube()
        }
        dialog.show()
    }

    private fun showChannelVideoMenu(v: Video, videos: List<Video>, onChange: () -> Unit) {
        val marked = v.watchKey in prefs.watchedVideos
        val actions = listOf(
            if (marked) {
                "Mark unwatched" to { prefs.markVideoUnwatched(v.watchKey) }
            } else {
                "Mark watched" to { prefs.markVideosWatched(listOf(v.watchKey)) }
            },
            "Mark watched up to here" to {
                prefs.markVideosWatched(videos.filter { it.published <= v.published }.map { it.watchKey })
            },
            "Watch in order from here" to { startInOrder(v) },
        )
        AlertDialog.Builder(this)
            .setTitle(v.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                channelPage?.notifyDataSetChanged()
                onChange()
            }
            .show()
    }

    /** From [v] on, the channel's card shows its oldest unwatched video instead of its newest. */
    private fun startInOrder(v: Video) {
        prefs.setInOrder(v.channelId, v.published)
        toast("${v.channel}: watching in order from “${v.title}”")
        refreshYouTube(force = true)
    }

    private fun rowSettingsFocused(): Boolean {
        val f = currentFocus ?: return false
        return f.parent === youtubeRow && youtubeRow.getChildAdapterPosition(f) == 0
    }

    /** Left from the first card: unfold the ⚙ tile in front of it and step onto it. */
    private fun revealRowSettings() {
        videoAdapter.settingsShown = true
        youtubeRow.doOnNextLayout {
            youtubeRow.scrollToPosition(0)
            youtubeRow.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
    }

    /** The ⚙ tile at the left end of the row. */
    private fun showYouTubeRowMenu() {
        val linked = subscriptions()?.isLinked == true
        val tracking = PlaybackWatcher.isEnabled(this)
        val actions = listOfNotNull(
            "Hide this row" to ::hideYouTubeRow,
            "Sort: ${prefs.youtubeRowSort.label}" to ::showRowSortPicker,
            "Thumbnail size (${prefs.youtubeThumbSize.label})" to ::showThumbSizePicker,
            ("Refresh" to { refreshYouTube(force = true) }).takeIf { linked },
            (if (tracking) "Watch tracking: on" else "Watch tracking: off") to ::explainWatchTracking,
            if (linked) "Unlink YouTube" to ::unlinkYouTube else "Link YouTube" to ::linkYouTube,
        )
        AlertDialog.Builder(this)
            .setTitle("YouTube row")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun hideYouTubeRow() {
        prefs.youtubeRowHidden = true
        refreshYouTube()
        appsRow.getChildAt(0)?.requestFocus()
        toast("Row hidden. Bring it back from ⚙ → YouTube row")
    }

    private fun showRowSortPicker() {
        val sorts = RowSort.entries
        AlertDialog.Builder(this)
            .setTitle("Sort the YouTube row")
            .setSingleChoiceItems(sorts.map { it.label }.toTypedArray(), sorts.indexOf(prefs.youtubeRowSort)) { d, which ->
                d.dismiss()
                if (sorts[which] != prefs.youtubeRowSort) {
                    prefs.youtubeRowSort = sorts[which]
                    renderYouTube()
                    youtubeRow.scrollToPosition(0)
                }
            }
            .show()
    }

    private fun showThumbSizePicker() {
        val sizes = ThumbSize.entries
        AlertDialog.Builder(this)
            .setTitle("Thumbnail size")
            .setSingleChoiceItems(sizes.map { it.label }.toTypedArray(), sizes.indexOf(prefs.youtubeThumbSize)) { d, which ->
                d.dismiss()
                prefs.youtubeThumbSize = sizes[which]
                videoAdapter.size = sizes[which]
            }
            .show()
    }

    private fun explainWatchTracking() {
        val on = PlaybackWatcher.isEnabled(this)
        AlertDialog.Builder(this)
            .setTitle(if (on) "Watch tracking is on" else "Watch tracking is off")
            .setMessage(
                if (on) {
                    "Reel sees what the YouTube app plays, wherever you start it. A video drops off this row once " +
                        "you've played most of it, and channels you spend the most time on come first."
                } else {
                    "Reel can see what the YouTube app plays once it has notification access, so videos you " +
                        "watch drop off this row and your most-watched channels come first. Google TV hides " +
                        "that setting; from your PC run:\n\n${PlaybackWatcher.GRANT_COMMAND}\n\n" +
                        "Until then, opening a video from this row counts as watching it."
                },
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun unlinkYouTube() {
        subscriptions()?.unlink()
        toast("YouTube unlinked")
        refreshYouTube()
    }

    private fun linkYouTube() {
        val subs = subscriptions()
        if (subs == null) {
            AlertDialog.Builder(this)
                .setTitle("YouTube needs a Google client ID")
                .setMessage(
                    "In console.cloud.google.com, enable YouTube Data API v3, then create an OAuth client ID " +
                        "of type “TVs and Limited Input devices”. Then from your PC:\n\n" +
                        "adb shell am start -n dev.craig.reel/.MainActivity --es google_client_id ID " +
                        "--es google_client_secret SECRET",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        var done = false
        var dialog: AlertDialog? = null
        val job = lifecycleScope.launch {
            val code = try {
                subs.requestCode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't reach Google: ${e.message}")
                return@launch
            }
            dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Link YouTube")
                .setMessage("On your phone or PC, go to\n${code.url}\n\nand enter this code:\n\n${code.userCode}")
                .setNegativeButton("Cancel", null)
                .create()
            dialog?.show()
            val ok = try {
                subs.waitForApproval(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Google: ${e.message}")
                false
            }
            done = true
            dialog?.dismiss()
            if (ok) {
                toast("YouTube linked")
                refreshYouTube(force = true)
            } else {
                toast("YouTube link didn't complete")
            }
        }
        lifecycleScope.launch {
            // Cancelling the dialog stops the polling.
            while (dialog == null && job.isActive) delay(100)
            dialog?.setOnDismissListener { if (!done) job.cancel() }
        }
    }

    // ---------- Settings ----------

    private fun showSettings() {
        val t = trakt()
        val traktLabel = when {
            t == null -> "Trakt (set up)"
            t.isLinked -> "Unlink Trakt (${prefs.traktUser.ifBlank { "linked" }})"
            else -> "Link Trakt"
        }
        val place = prefs.weatherPlace?.takeIf { it.query == prefs.weatherQuery }
        val weatherLabel = "Weather location (${place?.name?.ifBlank { null } ?: prefs.weatherQuery.ifBlank { "auto" }})"
        val rentLabel = if (prefs.includedOnly) "Rent, buy & unavailable titles: hidden" else "Rent, buy & unavailable titles: shown"
        val youtubeLinked = subscriptions()?.isLinked == true
        val items = arrayOf(
            "Your name (${prefs.userName.ifBlank { "not set" }})",
            "Streaming apps",
            rentLabel,
            traktLabel,
            if (youtubeLinked) "Unlink YouTube" else "Link YouTube",
            if (prefs.youtubeRowHidden) "YouTube row: hidden" else "YouTube row: shown",
            weatherLabel,
            "TMDB API key",
            "Region (${prefs.region})",
            "Unhide all apps",
            "Clear cached TMDB data (${cache.sizeBytes() / 1024} KB)",
            "Android TV settings",
        )
        AlertDialog.Builder(this)
            .setTitle("Reel settings · v${BuildConfig.VERSION_NAME}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptText("Your name, for the greeting", prefs.userName) {
                        prefs.userName = it
                        updateGreeting()
                    }
                    1 -> showProviderPicker()
                    2 -> {
                        prefs.includedOnly = !prefs.includedOnly
                        toast(if (prefs.includedOnly) "Showing only what's included with your apps" else "Showing rent, buy and unavailable titles too")
                        // Search and Continue watching are filtered differently now; redraw if one is open.
                        currentGenre?.takeIf { it.personal && it != GenreChoice.Recent }?.let { selectGenre(it) }
                    }
                    3 -> when {
                        t == null -> linkTrakt()
                        t.isLinked -> {
                            t.unlink()
                            toast("Trakt unlinked")
                            rebuildGenreChoices()
                            if (currentGenre?.trakt == true) selectGenre(GenreChoice.All)
                        }
                        else -> linkTrakt()
                    }
                    4 -> if (youtubeLinked) unlinkYouTube() else linkYouTube()
                    5 -> {
                        prefs.youtubeRowHidden = !prefs.youtubeRowHidden
                        refreshYouTube()
                    }
                    6 -> promptText("Weather location (city or postcode, blank = auto)", prefs.weatherQuery) {
                        prefs.weatherQuery = it
                        lifecycleScope.launch {
                            val p = try { resolvePlace() } catch (e: Exception) { null }
                            if (p == null && it.isNotBlank()) toast("Couldn't find “$it”")
                            startWeather()
                        }
                    }
                    7 -> promptText("TMDB API key", prefs.tmdbKey) {
                        prefs.tmdbKey = it
                        initCatalog()
                    }
                    8 -> promptText("Region (2-letter country code)", prefs.region) {
                        prefs.region = it
                        initCatalog()
                    }
                    9 -> {
                        prefs.hiddenApps = emptySet()
                        loadApps()
                    }
                    10 -> {
                        cache.clear()
                        toast("Cache cleared")
                        initCatalog()
                    }
                    11 -> if (!Launch.start(this, Intent(Settings.ACTION_SETTINGS))) toast("Settings unavailable")
                }
            }
            .show()
    }

    /** Which installed streaming apps count when filtering titles. */
    private fun showProviderPicker() {
        if (installed.isEmpty()) {
            toast("None of Reel's supported streaming apps are installed.")
            return
        }
        val off = prefs.disabledProviders
        val checked = BooleanArray(installed.size) { installed[it].label !in off }
        AlertDialog.Builder(this)
            .setTitle("Streaming apps")
            .setMultiChoiceItems(installed.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Done") { _, _ ->
                val newOff = installed.filterIndexed { i, _ -> !checked[i] }.map { it.label }.toSet()
                if (newOff != off) {
                    prefs.disabledProviders = newOff
                    currentGenre?.let { selectGenre(it) }
                }
            }
            .show()
    }

    private fun promptText(title: String, initial: String, hint: String? = null, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            setSelection(text.length)
            this.hint = hint
            imeOptions = if (hint != null) EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_DONE
        }
        var saved = false
        val save = {
            if (!saved) {
                saved = true
                onSave(input.text.toString())
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(if (hint != null) "Search" else "Save") { _, _ -> save() }
            .setNegativeButton("Cancel", null)
            .create()
        // The on-screen keyboard's action key counts as the positive button.
        input.setOnEditorActionListener { _, _, _ ->
            dialog.dismiss()
            save()
            true
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------- Helpers ----------

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showStatus("Couldn't reach TMDB: ${e.message}")
        }
    }

    private fun focusSelectedGenre() {
        val idx = genreChoices.indexOf(currentGenre)
        val target = if (idx >= 0) genreRow.layoutManager?.findViewByPosition(idx) else null
        (target ?: genreRow.getChildAt(0))?.requestFocus()
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    private fun showStatus(msg: String, busy: Boolean = false) {
        status.text = msg
        status.visibility = View.VISIBLE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
        progress.visibility = View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "Reel"
        private const val GRID_COLUMNS = 7
        private const val YOUTUBE = "YouTube"
        /** Pull more discover pages in one go until at least this many verified titles turn up. */
        private const val MIN_BATCH = GRID_COLUMNS * 2
        private const val MAX_PAGES_PER_BATCH = 4
        /** Trakt lists are checked title by title; cap the work. */
        private const val MAX_LIST_ITEMS = 80
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
        private const val QUOTE_INTERVAL_MS = 20_000L
        /** How often the subscriptions row looks for new uploads while you come and go. */
        private const val YOUTUBE_REFRESH_MS = 30 * 60_000L
        /** Anything on disk older than this is useless to every endpoint, so sweep it at startup. */
        private const val CACHE_MAX_AGE_MS = 14 * DiskCache.DAY
    }
}
