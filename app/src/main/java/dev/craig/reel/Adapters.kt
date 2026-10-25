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
}

class PosterAdapter(private val onClick: (Title) -> Unit) : RecyclerView.Adapter<PosterVH>() {

    private val items = mutableListOf<Title>()

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun append(more: List<Title>) {
        if (more.isEmpty()) return
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
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
        h.itemView.contentDescription = t.name
        h.itemView.setOnClickListener { onClick(t) }
        h.itemView.onFocusChangeListener = Focus.scale
    }
}
