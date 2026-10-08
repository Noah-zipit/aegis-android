package com.aegis.browser

import android.app.DownloadManager
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Lists downloads via DownloadManager.query (title, status).
 * Tapping a completed download opens it.
 */
class DownloadsActivity : AppCompatActivity() {

    private data class DlItem(
        val id: Long,
        val title: String,
        val status: Int,
        val mimeType: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.downloads_title)

        val items = queryDownloads()
        findViewById<View>(R.id.empty_downloads).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE

        val list = findViewById<RecyclerView>(R.id.downloads_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = DlAdapter(items) { openDownload(it) }
    }

    private fun queryDownloads(): List<DlItem> {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val out = mutableListOf<DlItem>()
        var cursor: Cursor? = null
        try {
            cursor = dm.query(DownloadManager.Query())
            val idCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val statusCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            while (cursor.moveToNext()) {
                out.add(
                    DlItem(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.downloading),
                        status = cursor.getInt(statusCol),
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    )
                )
            }
        } catch (e: Exception) {
            // Query failures surface as an empty list; nothing to crash over.
        } finally {
            cursor?.close()
        }
        return out
    }

    private fun openDownload(item: DlItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, R.string.download_incomplete, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri: Uri = dm.getUriForDownloadedFile(item.id)
                ?: throw IllegalStateException("no uri")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.open_file)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class DlAdapter(
        private val items: List<DlItem>,
        private val onTap: (DlItem) -> Unit
    ) : RecyclerView.Adapter<DlAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.download_title)
            val status: TextView = v.findViewById(R.id.download_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val d = items[position]
            h.title.text = d.title
            h.status.text = statusText(d.status)
            h.itemView.setOnClickListener { onTap(d) }
        }

        private fun statusText(status: Int): String = when (status) {
            DownloadManager.STATUS_PENDING -> getString(R.string.dl_pending)
            DownloadManager.STATUS_RUNNING -> getString(R.string.dl_running)
            DownloadManager.STATUS_PAUSED -> getString(R.string.dl_paused)
            DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.dl_complete)
            DownloadManager.STATUS_FAILED -> getString(R.string.dl_failed)
            else -> getString(R.string.dl_unknown)
        }
    }
}
