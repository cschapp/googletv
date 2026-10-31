package dev.craig.reel

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

object Focus {
    /** Grow the focused card a little so it's obvious from the couch. */
    val scale = View.OnFocusChangeListener { v, hasFocus ->
        val s = if (hasFocus) 1.08f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        v.elevation = if (hasFocus) 8f else 0f
    }
}

// ---------- Apps row ----------

data class AppEntry(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?)

class AppVH(v: View) : RecyclerView.ViewHolder(v) {
    val image: ImageView = v.findViewById(R.id.appImage)
    val label: TextView = v.findViewById(R.id.appLabel)
}

class AppAdapter(
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppVH>() {

    private var list: List<AppEntry> = emptyList()

    var items: List<AppEntry>
        get() = list
        set(v) {
            list = v
            notifyDataSetChanged()
        }

    /** The app currently being rearranged; drawn dimmed so it stands out from its neighbours. */
    var movingPkg: String? = null

    /**
     * Swap two neighbours by rebinding both slots in place. No move animation and no scroll,
     * so the row doesn't shuffle under the user; the caller re-focuses the new slot.
     */
    fun swap(from: Int, to: Int) {
        val l = list.toMutableList()
        java.util.Collections.swap(l, from, to)
        list = l
        notifyItemChanged(from)
        notifyItemChanged(to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        AppVH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: AppVH, position: Int) {
        val e = items[position]
        if (e.banner != null) {
            h.image.setImageDrawable(e.banner)
            h.image.setPadding(0, 0, 0, 0)
            h.label.visibility = View.GONE
        } else {
            val pad = (18 * h.itemView.resources.displayMetrics.density).toInt()
            h.image.setImageDrawable(e.icon)
            h.image.setPadding(pad, pad / 2, pad, pad)
            h.label.text = e.label
            h.label.visibility = View.VISIBLE
        }
        h.itemView.contentDescription = e.label
        h.itemView.alpha = if (e.pkg == movingPkg) 0.6f else 1f
        h.itemView.setOnClickListener { onClick(e) }
        h.itemView.setOnLongClickListener { onLongClick(e); true }
        h.itemView.onFocusChangeListener = Focus.scale
    }
}

// ---------- YouTube subscriptions row ----------

/**
 * What the YouTube row holds: the ⚙ tile first, then either videos or one message card.
 * A channel page holds only videos. [Clip.more] counts what's waiting after it (in-order channels).
 */
sealed class RowItem {
    object Settings : RowItem()
    data class Message(val text: String) : RowItem()
    data class Clip(val video: Video, val more: Int = 0) : RowItem()
}

class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
    val thumb: ImageView = v.findViewById(R.id.videoThumb)
    val title: TextView = v.findViewById(R.id.videoTitle)
    val note: TextView = v.findViewById(R.id.videoNote)
}

class TileVH(val text: TextView) : RecyclerView.ViewHolder(text)

class VideoRowAdapter(
    private val note: (Video) -> String,
    private val onClick: (Video) -> Unit,
    private val onLongClick: (Video) -> Unit,
    private val onSettings: () -> Unit = {},
    private val onMessage: () -> Unit = {},
    /** Watched videos are drawn dimmed with a ✓ (the channel page shows them; the row never does). */
    private val watched: (Video) -> Boolean = { false },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        // Stable ids let the row keep the D-pad on the same card when it's refreshed.
        setHasStableIds(true)
    }

    var items: List<RowItem> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    var size: ThumbSize = ThumbSize.SMALL
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    /** The ⚙ tile stays folded away (no width, not focusable) until Left is pressed on the first card. */
    var settingsShown = false
        set(v) {
            if (field == v) return
            field = v
            if (items.firstOrNull() == RowItem.Settings) notifyItemChanged(0)
        }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long = when (val it = items[position]) {
        RowItem.Settings -> 0L
        is RowItem.Message -> 1L
        is RowItem.Clip -> (it.video.id.hashCode().toLong() and 0xffffffffL) + 2
    }

    override fun getItemViewType(position: Int) = if (items[position] is RowItem.Clip) TYPE_VIDEO else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoVH(inflater.inflate(R.layout.item_video, parent, false))
        } else {
            TileVH(inflater.inflate(R.layout.item_row_tile, parent, false) as TextView)
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val density = h.itemView.resources.displayMetrics.density
        val w = (size.widthDp * density).toInt()
        val thumbH = w * 9 / 16
        when (val item = items[position]) {
            is RowItem.Clip -> {
                val v = item.video
                h as VideoVH
                h.itemView.layoutParams = h.itemView.layoutParams.apply { width = w + (6 * density).toInt() }
                h.thumb.layoutParams = h.thumb.layoutParams.apply { height = thumbH }
                h.thumb.load(v.thumbUrl) {
                    crossfade(true)
                    placeholder(R.drawable.poster_placeholder)
                    error(R.drawable.poster_placeholder)
                }
                val seen = watched(v)
                h.title.text = v.title
                h.note.text = listOfNotNull(
                    "✓ Watched".takeIf { seen },
                    note(v),
                    "+${item.more} more".takeIf { item.more > 0 },
                ).joinToString(" · ")
                h.itemView.alpha = if (seen) 0.45f else 1f
                h.itemView.contentDescription = "${v.title}, ${h.note.text}"
                h.itemView.setOnClickListener { onClick(v) }
                h.itemView.setOnLongClickListener { onLongClick(v); true }
            }
            RowItem.Settings -> {
                h as TileVH
                h.text.layoutParams = (h.text.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    width = if (settingsShown) (64 * density).toInt() else 0
                    height = thumbH + (6 * density).toInt()
                    marginEnd = if (settingsShown) (14 * density).toInt() else 0
                }
                h.text.isFocusable = settingsShown
                h.text.visibility = if (settingsShown) View.VISIBLE else View.INVISIBLE
                h.text.text = "⚙"
                h.text.textSize = 22f
                h.text.contentDescription = "Row settings"
                h.text.setOnClickListener { onSettings() }
            }
            is RowItem.Message -> {
                h as TileVH
                h.text.layoutParams = (h.text.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    width = maxOf(w * 2, (360 * density).toInt())
                    height = thumbH + (6 * density).toInt()
                    marginEnd = (14 * density).toInt()
                }
                h.text.isFocusable = true
                h.text.visibility = View.VISIBLE
                h.text.text = item.text
                h.text.textSize = 15f
                h.text.contentDescription = item.text
                h.text.setOnClickListener { onMessage() }
            }
        }
        h.itemView.onFocusChangeListener = Focus.scale
    }

    companion object {
        private const val TYPE_TILE = 0
        private const val TYPE_VIDEO = 1
    }
}

// ---------- Chips (genres, providers, obscure picker) ----------

class ChipVH(val text: TextView) : RecyclerView.ViewHolder(text)

class ChipAdapter<T>(
    private val label: (T) -> String,
    private val isSelected: (T) -> Boolean,
    private val fill: Boolean = false,
    private val onClick: (T) -> Unit,
) : RecyclerView.Adapter<ChipVH>() {

    var items: List<T> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipVH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false) as TextView
        if (fill) {
            tv.layoutParams = tv.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        return ChipVH(tv)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: ChipVH, position: Int) {
        val item = items[position]
        h.text.text = label(item)
        h.text.isSelected = isSelected(item)
        h.text.setOnClickListener { onClick(item) }
    }
}

// ---------- Poster grid ----------

class PosterVH(v: View) : RecyclerView.ViewHolder(v) {
    val image: ImageView = v.findViewById(R.id.posterImage)
    val title: TextView = v.findViewById(R.id.posterTitle)
    val note: TextView = v.findViewById(R.id.posterNote)
}

class PosterAdapter(
    private val onClick: (Title) -> Unit,
    private val onLongClick: (Title) -> Boolean = { false },
) : RecyclerView.Adapter<PosterVH>() {

    private val items = mutableListOf<Title>()
    /** type/id of everything shown, so a title that drifts across TMDB pages isn't shown twice. */
    private val keys = HashSet<Pair<MediaType, Int>>()

    fun clear() {
        items.clear()
        keys.clear()
        notifyDataSetChanged()
    }

    fun append(more: List<Title>) {
        val fresh = more.filter { keys.add(it.type to it.id) }
        if (fresh.isEmpty()) return
        val start = items.size
        items.addAll(fresh)
        notifyItemRangeInserted(start, fresh.size)
    }

    fun remove(t: Title) {
        val idx = items.indexOfFirst { it.type == t.type && it.id == t.id }
        if (idx < 0) return
        items.removeAt(idx)
        keys.remove(t.type to t.id)
        notifyItemRemoved(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PosterVH(LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: PosterVH, position: Int) {
        val t = items[position]
        h.title.text = if (t.year.isNotBlank()) "${t.name} (${t.year})" else t.name
        h.image.load(t.posterPath?.let { Tmdb.IMAGE_BASE + it }) {
            crossfade(true)
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
        }
        h.note.text = t.note
        h.note.visibility = if (t.note.isNullOrBlank()) View.GONE else View.VISIBLE
        h.itemView.contentDescription = listOfNotNull(t.name, t.note).joinToString(", ")
        h.itemView.setOnClickListener { onClick(t) }
        h.itemView.setOnLongClickListener { onLongClick(t) }
        h.itemView.onFocusChangeListener = Focus.scale
    }
}
