package com.aegis.browser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aegis.browser.data.HistoryEntry
import com.aegis.browser.data.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browsing history, newest first, with clear-all.
 * Tapping an entry returns its URL to MainActivity via setResult.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: HistoryStore
    private lateinit var adapter: HistoryAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history_title)

        store = HistoryStore(this)
        emptyView = findViewById(R.id.empty_history)

        val list = findViewById<RecyclerView>(R.id.history_list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter { entry ->
            setResult(RESULT_OK, Intent().putExtra("url", entry.url))
            finish()
        }
        list.adapter = adapter
        refresh()

        findViewById<Button>(R.id.btn_clear_history).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.clear) { _, _ ->
                    store.clear()
                    refresh()
                    Toast.makeText(this, R.string.data_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refresh() {
        val items = store.all()
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class HistoryAdapter(
        private val onTap: (HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private var items: List<HistoryEntry> = emptyList()
        private val dateFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.history_title)
            val url: TextView = v.findViewById(R.id.history_url)
            val time: TextView = v.findViewById(R.id.history_time)
        }

        fun submit(newItems: List<HistoryEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.title.text = e.title.ifEmpty { e.url }
            h.url.text = e.url
            h.time.text = dateFormat.format(Date(e.timestamp))
            h.itemView.setOnClickListener { onTap(e) }
        }
    }
}
