package com.aegis.browser.ai

import android.os.Bundle
import com.aegis.browser.R
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device AI chat (llama.cpp via JNI, CPU only).
 *
 * States:
 *  (a) model not downloaded -> pack list
 *  (b) downloading         -> real progress bar (bytes + %)
 *  (c) ready               -> chat with streaming tokens + Stop
 *  (d) error               -> message + retry
 */
class AiChatActivity : AppCompatActivity() {

    private lateinit var packListView: View
    private lateinit var downloadView: View
    private lateinit var chatView: View
    private lateinit var errorView: View

    private lateinit var packContainer: LinearLayout
    private lateinit var tvDownloadName: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnCancelDownload: Button

    private lateinit var rvChat: RecyclerView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvHeaderSub: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button

    private lateinit var tvError: TextView
    private lateinit var btnRetry: Button

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter

    private var modelLoaded = false
    private var generating = false
    private var downloadJob: Job? = null
    private var downloadingPack: ModelPack? = null
    private var lastError: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_chat)

        packListView = findViewById(R.id.packListView)
        downloadView = findViewById(R.id.downloadView)
        chatView = findViewById(R.id.chatView)
        errorView = findViewById(R.id.errorView)

        packContainer = findViewById(R.id.packContainer)
        tvDownloadName = findViewById(R.id.tvDownloadName)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        btnCancelDownload = findViewById(R.id.btnCancelDownload)

        rvChat = findViewById(R.id.rvChat)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvHeaderSub = findViewById(R.id.tvHeaderSub)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)

        tvError = findViewById(R.id.tvError)
        btnRetry = findViewById(R.id.btnRetry)

        adapter = ChatAdapter(messages)
        rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvChat.adapter = adapter

        btnSend.setOnClickListener { onSendOrStop() }
        btnCancelDownload.setOnClickListener { cancelDownload() }
        btnRetry.setOnClickListener { retryAfterError() }

        messages.addAll(ChatStore.load(filesDir))
        buildPackList()

        // Pick the best pack already on disk.
        val ready = ModelManager.PACKS.firstOrNull { ModelManager.isModelReady(filesDir, it) }
        if (ready != null) {
            ModelManager.activePack = ready
            enterChat()
        } else {
            showOnly(packListView)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.ai_clear))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            messages.clear()
            adapter.notifyDataSetChanged()
            ChatStore.clear(filesDir)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        ModelManager.cancel()
        if (modelLoaded) {
            Thread { runCatching { LlamaBridge.nativeUnload() } }.start()
            modelLoaded = false
        }
        super.onDestroy()
    }

    // ---------- state switching ----------

    private fun showOnly(view: View) {
        packListView.visibility = if (view == packListView) View.VISIBLE else View.GONE
        downloadView.visibility = if (view == downloadView) View.VISIBLE else View.GONE
        chatView.visibility = if (view == chatView) View.VISIBLE else View.GONE
        errorView.visibility = if (view == errorView) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        lastError = message
        tvError.text = message
        showOnly(errorView)
    }

    private fun retryAfterError() {
        // Retry = re-attempt whatever failed: reload the model if a file is ready,
        // otherwise go back to the pack list.
        val ready = ModelManager.PACKS.firstOrNull { ModelManager.isModelReady(filesDir, it) }
        if (ready != null) {
            ModelManager.activePack = ready
            enterChat()
        } else {
            showOnly(packListView)
        }
    }

    // ---------- (a) pack list ----------

    private fun buildPackList() {
        packContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (pack in ModelManager.PACKS) {
            val row = inflater.inflate(R.layout.item_model_pack, packContainer, false)
            val tvName: TextView = row.findViewById(R.id.tvPackName)
            val tvSub: TextView = row.findViewById(R.id.tvPackSub)
            val btn: Button = row.findViewById(R.id.btnPackAction)
            tvName.text = pack.name
            tvSub.text = pack.subtitle
            if (pack.comingSoon) {
                btn.isEnabled = false
                btn.text = getString(R.string.ai_coming_soon)
            } else if (ModelManager.isModelReady(filesDir, pack)) {
                btn.text = getString(R.string.ai_open)
                btn.setOnClickListener {
                    ModelManager.activePack = pack
                    enterChat()
                }
            } else {
                btn.setOnClickListener { startDownload(pack) }
            }
            packContainer.addView(row)
        }
    }

    // ---------- (b) download ----------

    private fun startDownload(pack: ModelPack) {
        downloadingPack = pack
        tvDownloadName.text = getString(R.string.ai_downloading) + " " + pack.name
        progressBar.progress = 0
        tvProgress.text = ""
        showOnly(downloadView)

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val ok = ModelManager.download(filesDir, pack) { downloaded, total ->
                launch(Dispatchers.Main) {
                    if (total > 0) {
                        progressBar.progress = ((downloaded * 1000L) / total).toInt().coerceIn(0, 1000)
                        val pct = (downloaded * 100L) / total
                        tvProgress.text = "${ModelManager.formatBytes(downloaded)} / ${ModelManager.formatBytes(total)} · $pct%"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val finishedPack = downloadingPack
                downloadingPack = null
                if (finishedPack == null) return@withContext // cancelled by user
                if (ok) {
                    ModelManager.activePack = pack
                    enterChat()
                } else {
                    showError(getString(R.string.ai_download_failed))
                }
            }
        }
    }

    private fun cancelDownload() {
        ModelManager.cancel()
        downloadJob?.cancel()
        downloadingPack = null
        showOnly(packListView)
    }

    // ---------- (c) chat ----------

    private fun enterChat() {
        val pack = ModelManager.activePack
        tvHeaderSub.text = "On-device · ${pack.subtitle.substringBefore(" ·")} · CPU"
        showOnly(chatView)
        adapter.notifyDataSetChanged()
        scrollToEnd()
        if (messages.isEmpty()) {
            Toast.makeText(this, getString(R.string.ai_empty_chat), Toast.LENGTH_LONG).show()
        }
        if (!modelLoaded) loadModelAsync(pack)
    }

    private fun loadModelAsync(pack: ModelPack) {
        tvModelStatus.text = getString(R.string.ai_loading_model)
        setInputEnabled(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val path = ModelManager.getModelFile(filesDir, pack).absolutePath
            val ok = runCatching { LlamaBridge.nativeInit(path) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                modelLoaded = ok
                if (ok) {
                    tvModelStatus.text = getString(R.string.ai_model_ready)
                    setInputEnabled(true)
                } else {
                    showError(getString(R.string.ai_load_failed))
                }
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        etInput.isEnabled = enabled
        // Send needs a loaded model; Stop must always work while generating.
        btnSend.isEnabled = enabled || generating
    }

    private fun onSendOrStop() {
        if (generating) {
            LlamaBridge.nativeStop()
            return
        }
        if (!modelLoaded) return
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.text.clear()

        val now = System.currentTimeMillis()
        messages.add(ChatMessage("user", text, now))
        messages.add(ChatMessage("assistant", "", now))
        val assistantIdx = messages.size - 1
        adapter.notifyItemRangeInserted(messages.size - 2, 2)
        scrollToEnd()
        ChatStore.save(filesDir, messages)

        generating = true
        btnSend.text = getString(R.string.ai_stop)
        etInput.isEnabled = false

        val prompt = buildPrompt(text)
        val sb = StringBuilder()

        lifecycleScope.launch(Dispatchers.IO) {
            val callback = LlamaBridge.TokenCallback { token ->
                sb.append(token)
                runOnUiThread {
                    if (assistantIdx < messages.size) {
                        messages[assistantIdx] =
                            messages[assistantIdx].copy(content = sb.toString())
                        adapter.notifyItemChanged(assistantIdx)
                        scrollToEnd()
                    }
                }
            }
            val result = runCatching {
                LlamaBridge.nativeGenerateStream(prompt, 256, callback)
            }.getOrDefault(-1)

            withContext(Dispatchers.Main) {
                generating = false
                btnSend.text = getString(R.string.ai_send)
                etInput.isEnabled = true
                val finalText = sb.toString().trim()
                if (result < 0 && finalText.isEmpty() && assistantIdx < messages.size) {
                    messages.removeAt(assistantIdx)
                    adapter.notifyItemRemoved(assistantIdx)
                    Toast.makeText(
                        this@AiChatActivity,
                        getString(R.string.ai_load_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (assistantIdx < messages.size) {
                    messages[assistantIdx] =
                        messages[assistantIdx].copy(content = finalText)
                    adapter.notifyItemChanged(assistantIdx)
                }
                ChatStore.save(filesDir, messages)
                scrollToEnd()
            }
        }
    }

    /**
     * The native side applies the model's chat template to system + one user message,
     * so recent history is folded into the user turn as a plain transcript.
     * Kept short (last 3 exchanges) to respect the 2048-token context.
     */
    private fun buildPrompt(newMessage: String): String {
        val recent = messages.dropLast(1).takeLast(6) // exclude the placeholder just added
        val sb = StringBuilder()
        if (recent.isNotEmpty()) {
            sb.append("Conversation so far (most recent last):\n")
            for (m in recent) {
                sb.append(if (m.role == "user") "User: " else "Assistant: ")
                    .append(m.content.trim().take(800))
                    .append('\n')
            }
            sb.append('\n')
        }
        sb.append("User's new message: ").append(newMessage.trim())
            .append("\n\nReply to the new message.")
        return sb.toString()
    }

    private fun scrollToEnd() {
        if (adapter.itemCount > 0) rvChat.scrollToPosition(adapter.itemCount - 1)
    }

    // ---------- adapter ----------

    private inner class ChatAdapter(private val items: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val bubble: LinearLayout = view.findViewById(R.id.bubble)
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m = items[position]
            val isUser = m.role == "user"
            holder.bubble.layoutParams = (holder.bubble.layoutParams as LinearLayout.LayoutParams).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
            }
            holder.bubble.background = ContextCompat.getDrawable(
                holder.itemView.context,
                if (isUser) R.drawable.bubble_user else R.drawable.bubble_assistant
            )
            holder.tvMessage.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (isUser) R.color.aegis_bg else R.color.aegis_text
                )
            )
            holder.tvMessage.text = m.content.ifEmpty { "…" }
        }
    }
}
