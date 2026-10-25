package dev.craig.reel

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        /** The chip that opens the search box. Never selected itself. */
        object SearchPrompt : GenreChoice()

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
    private var tmdb: Tmdb? = null

    private lateinit var appsSection: View
    private lateinit var appsRow: RecyclerView
    private lateinit var genreRow: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnMediaType: TextView
    private lateinit var btnSort: TextView
    private lateinit var weather: TextView

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
                GenreChoice.SearchPrompt -> "🔍"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        applyIntentExtras(intent)

        appsSection = findViewById(R.id.appsSection)
        appsRow = findViewById(R.id.appsRow)
        genreRow = findViewById(R.id.genreRow)
        grid = findViewById(R.id.grid)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnMediaType = findViewById(R.id.btnMediaType)
        btnSort = findViewById(R.id.btnSort)
        weather = findViewById(R.id.weather)

        appsRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        appsRow.adapter = appAdapter
        // No item animations: rebinding a slot must not swap the focused view out from under the D-pad.
        appsRow.itemAnimator = null
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
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }

        // Give the poster grid the whole screen while you're browsing it.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val v = if (newFocus != null && isInside(newFocus, grid)) View.GONE else View.VISIBLE
            if (appsSection.visibility != v) appsSection.visibility = v
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

        loadApps()
        initCatalog()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        startWeather()
    }

    override fun onPause() {
        super.onPause()
        weatherJob?.cancel()
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
        i?.getStringExtra("weather")?.let {
            prefs.weatherQuery = it
            if (::weather.isInitialized) startWeather()
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

    private fun loadApps() {
        val pm = packageManager
        val hidden = prefs.hiddenApps
        val seen = HashSet<String>()
        val entries = mutableListOf<AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            @Suppress("DEPRECATION")
            val infos = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            for (ri in infos) {
                val ai = ri.activityInfo
                val pkg = ai.packageName
                if (pkg == packageName || pkg in hidden || !seen.add(pkg)) continue
                entries += AppEntry(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString(),
                    banner = ai.loadBanner(pm) ?: ai.applicationInfo?.loadBanner(pm),
                    icon = ri.loadIcon(pm),
                )
            }
        }
        // Your arranged order first, then everything else A–Z.
        val rank = prefs.appOrder.withIndex().associate { it.value to it.index }
        appAdapter.items = entries.sortedWith(compareBy({ rank[it.pkg] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
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
        val client = Tmdb(key)
        tmdb = client
        lifecycleScope.launch {
            showStatus("Loading…", busy = true)
            safely {
                val region = prefs.region
                val providers = client.providers(MediaType.MOVIE, region) + client.providers(MediaType.TV, region)
                providerIds = ProviderCatalog.mapIds(installed, providers)
                providerNames = providers.associate { it.id to it.name }
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
        // The latest search keeps a chip for the session, so you can come back to it.
        val search = listOfNotNull(lastSearch)
        genreChoices = listOf(GenreChoice.SearchPrompt) + search + listOf(GenreChoice.More) + mine +
            listOf(GenreChoice.Trending, GenreChoice.All) +
            traktRows +
            prefs.recentGenres.map { GenreChoice.Kw(it) } +
            standardGenres.map { GenreChoice.Std(it) }
        genreAdapter.items = genreChoices
    }

    private fun onGenreClicked(c: GenreChoice) {
        when (c) {
            GenreChoice.More -> pickGenre.launch(Intent(this, GenrePickerActivity::class.java))
            GenreChoice.SearchPrompt -> showSearch()
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

        loading = true
        if (page == 0) showStatus("Loading…", busy = true)
        try {
            // Discover over-matches (see Tmdb.onlyStreamingOn), so a page can shrink to nothing
            // after checking. Keep pulling pages until there's enough to show, within a cap.
            val found = mutableListOf<Title>()
            var pulled = 0
            do {
                val res = fetchPage(client, choice, page + 1)
                if (gen != generation) return
                page = res.page
                totalPages = res.totalPages
                if (page == 1 && posterAdapter.itemCount == 0 && !choice.personal) showStatus("Checking your apps…", busy = true)
                found += if (choice.personal) {
                    res.items
                } else {
                    client.onlyStreamingOn(res.items, ids, prefs.region) { t, on ->
                        // Anything besides YouTube is trusted; a YouTube-only title must have a live video.
                        on.any { it !in youtubeIds() } || playableOnYouTube(t, null)
                    }
                }
                if (gen != generation) return
                pulled++
            } while (found.size < MIN_BATCH && page < totalPages && pulled < MAX_PAGES_PER_BATCH)
            posterAdapter.append(found)
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
        GenreChoice.Continue -> "Trakt has nothing in progress."
        GenreChoice.Recent -> "Nothing opened from Reel yet."
        is GenreChoice.Search -> "TMDB found nothing for “${choice.query}”."
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

    private fun onTitleClicked(t: Title) {
        val client = tmdb ?: return
        lifecycleScope.launch {
            val lookup = try {
                coroutineScope {
                    val a = async { client.availability(t.type, t.id, prefs.region) }
                    // Links are a nice-to-have; a JustWatch hiccup must not block opening the app.
                    val o = async {
                        try {
                            JustWatch.offers(t, prefs.region)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    a.await() to o.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't check where ${t.name} is streaming: ${e.message}")
                return@launch
            }
            val (avail, offers) = lookup
            val enabled = enabledServices()
            var streaming = enabled.filter { svc -> svc.hasAny(avail.stream) }
            var youtubeGone = false
            if (streaming.any { it.label == YOUTUBE } && !playableOnYouTube(t, offers)) {
                streaming = streaming.filter { it.label != YOUTUBE }
                youtubeGone = true
            }
            when (streaming.size) {
                0 -> showUnavailable(t, avail, offers, youtubeGone)
                1 -> openOn(streaming[0], t, offers)
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("Watch “${t.name}” on")
                    .setItems(streaming.map { it.label }.toTypedArray()) { _, which ->
                        openOn(streaming[which], t, offers)
                    }
                    .show()
            }
        }
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

    /** None of the enabled apps streams it: say where it actually is, and offer rent/buy on an installed app. */
    private fun showUnavailable(t: Title, avail: Availability, offers: List<Offer>, youtubeGone: Boolean = false) {
        val installedLabels = installed.map { it.label }.toSet()
        val streamingElsewhere = names(avail.stream).filter { it !in installedLabels }
        val rentOn = installed.filter { it.hasAny(avail.rentBuy) }
        val msg = buildString {
            append("“${t.name}” isn't included with any of your streaming apps right now.")
            if (youtubeGone) append("\n\nThe free YouTube copy has been taken down.")
            if (streamingElsewhere.isNotEmpty()) append("\n\nStreaming on: ${streamingElsewhere.joinToString(", ")}")
            if (rentOn.isNotEmpty()) append("\n\nRent or buy on: ${rentOn.joinToString(", ") { it.label }}")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(t.name)
            .setMessage(msg)
            .setNegativeButton("OK", null)
        if (rentOn.size == 1) dialog.setPositiveButton("Open ${rentOn[0].label}") { _, _ -> openOn(rentOn[0], t, offers) }
        dialog.show()
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
        val items = arrayOf(
            "Streaming apps",
            traktLabel,
            weatherLabel,
            "TMDB API key",
            "Region (${prefs.region})",
            "Unhide all apps",
            "Android TV settings",
        )
        AlertDialog.Builder(this)
            .setTitle("Reel settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showProviderPicker()
                    1 -> when {
                        t == null -> linkTrakt()
                        t.isLinked -> {
                            t.unlink()
                            toast("Trakt unlinked")
                            rebuildGenreChoices()
                            if (currentGenre?.trakt == true) selectGenre(GenreChoice.All)
                        }
                        else -> linkTrakt()
                    }
                    2 -> promptText("Weather location (city or postcode, blank = auto)", prefs.weatherQuery) {
                        prefs.weatherQuery = it
                        lifecycleScope.launch {
                            val p = try { resolvePlace() } catch (e: Exception) { null }
                            if (p == null && it.isNotBlank()) toast("Couldn't find “$it”")
                            startWeather()
                        }
                    }
                    3 -> promptText("TMDB API key", prefs.tmdbKey) {
                        prefs.tmdbKey = it
                        initCatalog()
                    }
                    4 -> promptText("Region (2-letter country code)", prefs.region) {
                        prefs.region = it
                        initCatalog()
                    }
                    5 -> {
                        prefs.hiddenApps = emptySet()
                        loadApps()
                    }
                    6 -> if (!Launch.start(this, Intent(Settings.ACTION_SETTINGS))) toast("Settings unavailable")
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
        private const val GRID_COLUMNS = 7
        private const val YOUTUBE = "YouTube"
        /** Pull more discover pages in one go until at least this many verified titles turn up. */
        private const val MIN_BATCH = GRID_COLUMNS * 2
        private const val MAX_PAGES_PER_BATCH = 4
        /** Trakt lists are checked title by title; cap the work. */
        private const val MAX_LIST_ITEMS = 80
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
    }
}
