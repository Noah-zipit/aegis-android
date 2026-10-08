package com.aegis.browser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aegis.browser.data.Bookmark
import com.aegis.browser.data.BookmarkStore

/**
 * Bookmarks organised by folder. Supports add-folder, delete,
 * and tap-to-open (URL returned to MainActivity via setResult).
 */
class BookmarksActivity : AppCompatActivity() {

    private lateinit var store: BookmarkStore
    private lateinit var folderSpinner: Spinner
    private lateinit var adapter: BookmarkAdapter
    private lateinit var emptyView: TextView
    private var currentFolder: String = BookmarkStore.DEFAULT_FOLDER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.bookmarks_title)

        store = BookmarkStore(this)
        folderSpinner = findViewById(R.id.folder_spinner)
        emptyView = findViewById(R.id.empty_bookmarks)

        val list = findViewById<RecyclerView>(R.id.bookmarks_list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = BookmarkAdapter(
            onTap = { b ->
                setResult(RESULT_OK, Intent().putExtra("url", b.url))
                finish()
            },
            onDelete = { b ->
                store.remove(b.url, b.folder)
                refreshList()
                Toast.makeText(this, R.string.bookmark_deleted, Toast.LENGTH_SHORT).show()
            }
        )
        list.adapter = adapter

        refreshFolders()

        findViewById<Button>(R.id.btn_add_folder).setOnClickListener { showAddFolderDialog() }
    }

    override fun onResume() {
        super.onResume()
        // A bookmark may have been added from MainActivity while we were away.
        refreshFolders()
    }

    private fun refreshFolders() {
        val folders = store.folders()
        if (currentFolder !in folders) {
            currentFolder = folders.firstOrNull() ?: BookmarkStore.DEFAULT_FOLDER
        }
        val spinnerAdapter =
            ArrayAdapter(this, R.layout.item_spinner, folders.toMutableList())
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        folderSpinner.adapter = spinnerAdapter
        folderSpinner.setSelection(folders.indexOf(currentFolder).coerceAtLeast(0))
        folderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentFolder = folders[position]
                refreshList()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshList()
    }

    private fun refreshList() {
        val items = store.items(currentFolder)
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddFolderDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.folder_name_hint)
            setSingleLine()
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_folder)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (store.addFolder(name)) {
                    currentFolder = name
                    refreshFolders()
                } else {
                    Toast.makeText(this, R.string.folder_exists, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class BookmarkAdapter(
        private val onTap: (Bookmark) -> Unit,
        private val onDelete: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

        private var items: List<Bookmark> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.bookmark_title)
            val url: TextView = v.findViewById(R.id.bookmark_url)
            val delete: ImageButton = v.findViewById(R.id.bookmark_delete)
        }

        fun submit(newItems: List<Bookmark>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val b = items[position]
            h.title.text = b.title.ifEmpty { b.url }
            h.url.text = b.url
            h.itemView.setOnClickListener { onTap(b) }
            h.delete.setOnClickListener { onDelete(b) }
        }
    }
}
