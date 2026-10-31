package dev.craig.reel

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * ScrollView that survives a focused card being removed from a RecyclerView inside it.
 * When the row's size changes in the same pass (Mark watched dropping the last card of a
 * channel, say), ScrollView.onSizeChanged asks where the still-"focused" card is after the
 * RecyclerView has already detached it, and the framework throws "parameter must be a
 * descendant of this view". Skipping that one auto-scroll is harmless; the row re-focuses a
 * card straight after and the ScrollView scrolls to it then.
 */
class FocusSafeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        try {
            super.onSizeChanged(w, h, oldw, oldh)
        } catch (e: IllegalArgumentException) {
            // See class comment.
        }
    }
}
