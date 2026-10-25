package dev.craig.reel

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Full-screen picker for niche genres, plus a box to type any genre at all. */
class GenrePickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_genre_picker)

        val input = findViewById<EditText>(R.id.customGenre)
        val go = findViewById<TextView>(R.id.btnGo)
        val grid = findViewById<RecyclerView>(R.id.obscureGrid)

        val adapter = ChipAdapter<String>(
            label = { it },
            isSelected = { false },
            fill = true,
            onClick = { finishWith(it) },
        )
        adapter.items = ObscureGenres.all
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = adapter
        grid.post { grid.getChildAt(0)?.requestFocus() }

        go.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) finishWith(q)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                go.performClick()
                true
            } else {
                false
            }
        }
    }

    private fun finishWith(query: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_QUERY, query))
        finish()
    }

    companion object {
        const val EXTRA_QUERY = "query"
    }
}
