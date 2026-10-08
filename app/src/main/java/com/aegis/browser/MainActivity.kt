package com.aegis.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aegis.browser.crash.CrashReporter
import com.aegis.browser.data.BookmarkStore
import com.aegis.browser.data.HistoryStore
import com.aegis.browser.jev.JevClient
import com.aegis.browser.shield.Shield
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browser core: address bar, tabbed WebViews, bottom toolbar, home view,
 * downloads, history recording, fullscreen video, file upload, long-press links.
 *
 * NOTE: the manifest must declare
 *   android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout"
 * on this activity so WebViews survive rotation (see ACTIVITIES.txt).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "aegis_prefs"
        const val KEY_ENGINE = "search_engine"
        const val KEY_HOMEPAGE = "homepage"
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_JEV_KEY = "jev_key"
        const val KEY_JEV_ENDPOINT = "jev_endpoint"
        const val KEY_JEV_MODEL = "jev_model"
    }

    private data class Tab(val webView: WebView, var title: String = "", var url: String = "")

    private lateinit var prefs: SharedPreferences
    private lateinit var historyStore: HistoryStore
    private lateinit var bookmarkStore: BookmarkStore

    private lateinit var topBar: View
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var homeHolder: FrameLayout
    private lateinit var homeWallpaper: ImageView
    private lateinit var homeClock: TextView
    private lateinit var homeDate: TextView
    private lateinit var bottomToolbar: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnTabs: LinearLayout
    private lateinit var tabCountText: TextView
    private lateinit var fabAi: View
    private lateinit var fullscreenHolder: FrameLayout

    private val tabs = mutableListOf<Tab>()
    private var activeIndex = -1

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var tabsDialog: AlertDialog? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            homeClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            homeDate.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            clockHandler.postDelayed(this, 1000L)
        }
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            cb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    /** Receives a URL picked from HistoryActivity / BookmarksActivity via setResult. */
    private val pageOpenerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra("url")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { openInActiveTab(it) }
            }
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(this)
        Shield.init(this)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        historyStore = HistoryStore(this)
        bookmarkStore = BookmarkStore(this)

        topBar = findViewById(R.id.top_bar)
        addressBar = findViewById(R.id.address_bar)
        progressBar = findViewById(R.id.progress_bar)
        webContainer = findViewById(R.id.web_container)
        homeView = findViewById(R.id.home_view)
        homeHolder = findViewById(R.id.home_holder)
        homeWallpaper = findViewById(R.id.home_wallpaper)
        homeClock = findViewById(R.id.home_clock)
        homeDate = findViewById(R.id.home_date)
        bottomToolbar = findViewById(R.id.bottom_toolbar)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnReload = findViewById(R.id.btn_reload)
        btnTabs = findViewById(R.id.btn_tabs)
        tabCountText = findViewById(R.id.tab_count)
        fabAi = findViewById(R.id.fab_ai)
        fullscreenHolder = findViewById(R.id.fullscreen_holder)

        addressBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadInput(v.text.toString())
                true
            } else false
        }
        addressBar.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) (v as EditText).selectAll()
        }

        btnBack.setOnClickListener { activeWebView()?.takeIf { it.canGoBack() }?.goBack() }
        btnForward.setOnClickListener { activeWebView()?.takeIf { it.canGoForward() }?.goForward() }
        btnReload.setOnClickListener { activeWebView()?.reload() }
        btnTabs.setOnClickListener { showTabsDialog() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showOverflowMenu(it) }
        fabAi.setOnClickListener { openAiChat() }

        val quickLinks = listOf(
            R.id.chip_google to "https://www.google.com",
            R.id.chip_duckduckgo to "https://duckduckgo.com",
            R.id.chip_youtube to "https://m.youtube.com",
            R.id.chip_wikipedia to "https://www.wikipedia.org"
        )
        for ((chipId, url) in quickLinks) {
            findViewById<Chip>(chipId).setOnClickListener { openInActiveTab(url) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideFullscreen()
                    activeWebView()?.canGoBack() == true -> activeWebView()?.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (tabs.isEmpty()) newTab()
        showPendingCrash()
    }

    override fun onResume() {
        super.onResume()
        activeWebView()?.onResume()
        homeWallpaper.visibility =
            if (prefs.getBoolean(KEY_WALLPAPER, true)) View.VISIBLE else View.GONE
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        activeWebView()?.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        Shield.persist(this)
        super.onPause()
    }

    override fun onDestroy() {
        tabsDialog?.dismiss()
        for (t in tabs) {
            (t.webView.parent as? ViewGroup)?.removeView(t.webView)
            t.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tabs

    private fun activeTab(): Tab? = tabs.getOrNull(activeIndex)
    private fun activeWebView(): WebView? = activeTab()?.webView

    private fun newTab(url: String? = null) {
        val tab = Tab(createWebView())
        tabs.add(tab)
        switchTo(tabs.lastIndex)
        val target = url ?: prefs.getString(KEY_HOMEPAGE, "").orEmpty().trim()
        if (target.isNotEmpty()) openInActiveTab(resolveInput(target)) else showHome()
        updateTabCount()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val wasActive = index == activeIndex
        val tab = tabs.removeAt(index)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        when {
            tabs.isEmpty() -> activeIndex = -1
            wasActive -> switchTo(index.coerceAtMost(tabs.lastIndex))
            index < activeIndex -> {
                activeIndex--
                switchTo(activeIndex)
            }
            // otherwise the active tab is unaffected
        }
        updateTabCount()
    }

    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        val tab = tabs[index]
        for (t in tabs) {
            if (t !== tab) (t.webView.parent as? ViewGroup)?.removeView(t.webView)
        }
        if (tab.webView.parent == null) webContainer.addView(tab.webView, 0)
        setHomeVisible(tab.url.isEmpty())
        if (!addressBar.hasFocus()) addressBar.setText(tab.url)
        updateNavButtons()
    }

    private fun showHome() {
        val tab = activeTab() ?: return
        tab.url = ""
        tab.title = getString(R.string.new_tab)
        setHomeVisible(true)
        if (!addressBar.hasFocus()) addressBar.setText("")
        updateNavButtons()
    }

    private fun updateTabCount() {
        tabCountText.text = tabs.size.toString()
    }

    private fun updateNavButtons() {
        val wv = activeWebView()
        btnBack.isEnabled = wv?.canGoBack() == true
        btnForward.isEnabled = wv?.canGoForward() == true
        btnBack.alpha = if (btnBack.isEnabled) 1f else 0.35f
        btnForward.alpha = if (btnForward.isEnabled) 1f else 0.35f
    }

    private fun showTabsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_tabs, null)
        val list = view.findViewById<RecyclerView>(R.id.tabs_list)
        list.layoutManager = LinearLayoutManager(this)
        val adapter = TabAdapter()
        list.adapter = adapter
        view.findViewById<View>(R.id.btn_new_tab).setOnClickListener {
            newTab()
            tabsDialog?.dismiss()
        }
        tabsDialog = AlertDialog.Builder(this).setView(view).create()
        tabsDialog?.show()
    }

    private inner class TabAdapter : RecyclerView.Adapter<TabAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tab_title)
            val url: TextView = v.findViewById(R.id.tab_url)
            val close: ImageButton = v.findViewById(R.id.tab_close)
            val marker: View = v.findViewById(R.id.tab_active_marker)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

        override fun getItemCount(): Int = tabs.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val tab = tabs[position]
            h.title.text = tab.title.ifEmpty { getString(R.string.new_tab) }
            h.url.text = tab.url.ifEmpty { getString(R.string.home) }
            h.marker.visibility = if (position == activeIndex) View.VISIBLE else View.INVISIBLE
            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    switchTo(pos)
                    tabsDialog?.dismiss()
                }
            }
            h.close.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    closeTab(pos)
                    if (tabs.isEmpty()) {
                        tabsDialog?.dismiss()
                        newTab()
                    } else {
                        notifyDataSetChanged()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ navigation

    private fun loadInput(raw: String) {
        val url = resolveInput(raw)
        if (url.isEmpty()) return
        openInActiveTab(url)
    }

    private fun resolveInput(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true)
        ) return t
        if (!t.contains(' ') && t.contains('.')) return "https://$t"
        val base = when (prefs.getString(KEY_ENGINE, "google")) {
            "duckduckgo" -> "https://duckduckgo.com/?q="
            "brave" -> "https://search.brave.com/search?q="
            "bing" -> "https://www.bing.com/search?q="
            else -> "https://www.google.com/search?q="
        }
        return base + URLEncoder.encode(t, "UTF-8")
    }

    private fun openInActiveTab(url: String) {
        val tab = activeTab() ?: return
        tab.url = url
        setHomeVisible(false)
        addressBar.clearFocus()
        hideKeyboard()
        activeWebView()?.loadUrl(url)
        if (!addressBar.hasFocus()) addressBar.setText(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Aegis Shield: block ad/tracker subresources. Main frames never blocked.
                return Shield.intercept(
                    request?.url?.toString(),
                    request?.isForMainFrame == true
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false // keep navigation in-app
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        // No handler for this scheme; stay put.
                    }
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (view == activeWebView()) {
                    progressBar.visibility = View.VISIBLE
                    if (!addressBar.hasFocus() && !url.isNullOrEmpty()) addressBar.setText(url)
                }
                tabs.find { it.webView == view }?.let { it.url = url.orEmpty() }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val finishedUrl = url.orEmpty()
                val tab = tabs.find { it.webView == view }
                tab?.let {
                    it.url = finishedUrl
                    it.title = view?.title?.takeIf { t -> t.isNotBlank() } ?: finishedUrl
                    historyStore.record(it.title, finishedUrl)
                }
                if (view == activeWebView()) {
                    progressBar.visibility = View.GONE
                    setHomeVisible(false)
                    updateNavButtons()
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view == activeWebView()) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tabs.find { it.webView == view }?.title = title
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val params = fileChooserParams ?: return false
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = params.createIntent()
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, getString(R.string.choose_file))
                    )
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                showFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                hideFullscreen()
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }

        wv.setOnLongClickListener { v ->
            val result = (v as WebView).hitTestResult
            val link = result.extra
            val type = result.type
            if (!link.isNullOrEmpty() &&
                (type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                        type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
            ) {
                showLinkDialog(link)
                true
            } else {
                false // let WebView handle text selection etc.
            }
        }

        return wv
    }

    // ------------------------------------------------------------------ fullscreen

    private fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        topBar.visibility = View.GONE
        bottomToolbar.visibility = View.GONE
        fabAi.visibility = View.GONE
        setHomeVisible(false)
        fullscreenHolder.visibility = View.VISIBLE
        fullscreenHolder.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun hideFullscreen() {
        customView?.let { fullscreenHolder.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenHolder.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        bottomToolbar.visibility = View.VISIBLE
        fabAi.visibility = View.VISIBLE
        setHomeVisible(activeTab()?.url.isNullOrEmpty())
    }

    /**
     * Toggles the whole home layer (wallpaper + home content). The wallpaper
     * must never stay visible over web content, so both are switched together.
     */
    private fun setHomeVisible(visible: Boolean) {
        homeHolder.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ downloads

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription(getString(R.string.downloading))
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ menu / actions

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_tab -> {
                    newTab()
                    true
                }
                R.id.action_ai_chat -> {
                    openAiChat()
                    true
                }
                R.id.action_page_verdict -> {
                    pageVerdict()
                    true
                }
                R.id.action_privacy -> {
                    startActivity(Intent(this, PrivacyActivity::class.java))
                    true
                }
                R.id.action_add_bookmark -> {
                    addBookmarkCurrent()
                    true
                }
                R.id.action_bookmarks -> {
                    pageOpenerLauncher.launch(Intent(this, BookmarksActivity::class.java))
                    true
                }
                R.id.action_history -> {
                    pageOpenerLauncher.launch(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.action_downloads -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                R.id.action_clear_data -> {
                    confirmClearData()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openAiChat() {
        try {
            startActivity(Intent(this, com.aegis.browser.ai.AiChatActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.ai_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------- Jev page verdict

    private fun pageVerdict() {
        val url = activeTab()?.url.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.verdict_no_page, Toast.LENGTH_SHORT).show()
            return
        }
        val key = prefs.getString(KEY_JEV_KEY, "").orEmpty()
        if (key.isBlank()) {
            Toast.makeText(this, R.string.jev_key_needed, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val endpoint = prefs.getString(KEY_JEV_ENDPOINT, JevClient.DEFAULT_ENDPOINT).orEmpty()
            .ifBlank { JevClient.DEFAULT_ENDPOINT }
        val model = prefs.getString(KEY_JEV_MODEL, JevClient.DEFAULT_MODEL).orEmpty()
            .ifBlank { JevClient.DEFAULT_MODEL }

        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.verdict_title)
            .setMessage(R.string.verdict_checking)
            .setNegativeButton(R.string.cancel, null)
            .show()

        val wv = activeWebView()
        if (wv == null) {
            progress.dismiss()
            return
        }
        wv.evaluateJavascript(
            "(function(){var t=document.body?document.body.innerText:'';" +
                "return JSON.stringify({title:document.title||''," +
                "text:(t||'').slice(0,4000)});})()"
        ) { json ->
            val (title, text) = try {
                val o = JSONObject(json)
                o.optString("title") to o.optString("text")
            } catch (_: Exception) {
                "" to ""
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val verdict = JevClient.evaluate(endpoint, key, model, url, title, text)
                withContext(Dispatchers.Main) {
                    if (!isFinishing) {
                        progress.dismiss()
                        showVerdict(url, verdict)
                    }
                }
            }
        }
    }

    private fun showVerdict(url: String, v: JevClient.Verdict) {
        if (v.error != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.verdict_title)
                .setMessage(v.error)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val trustLabel = when {
            v.trust == null -> getString(R.string.verdict_uncertain)
            v.trust >= 0.7 -> getString(R.string.verdict_trustworthy)
            v.trust <= 0.35 -> getString(R.string.verdict_suspicious)
            else -> getString(R.string.verdict_uncertain)
        }
        val trustPct = v.trust?.let { "${(it * 100).toInt()}%" } ?: "—"
        val kindName = v.pageKind
            ?.replace('_', ' ')
            ?.replaceFirstChar { c -> c.uppercaseChar() } ?: "—"
        val kindConf = v.kindConfidence?.let { " (${(it * 100).toInt()}%)" } ?: ""
        val quality = v.quality?.let { String.format("%.1f", it) } ?: "—"
        val msg = "$trustLabel · $trustPct\n\n" +
            "${getString(R.string.verdict_kind_label)}: $kindName$kindConf\n" +
            "${getString(R.string.verdict_quality_label)}: $quality\n\n$url"
        AlertDialog.Builder(this)
            .setTitle(R.string.verdict_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------- crash reports

    private fun showPendingCrash() {
        val reports = CrashReporter.pendingReports(this)
        if (reports.isEmpty()) return
        val file = reports.first()
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_message)
            .setPositiveButton(R.string.crash_view) { _, _ ->
                CrashReporter.showReportDialog(this, file)
            }
            .setNeutralButton(R.string.crash_share) { _, _ ->
                CrashReporter.shareReport(this, CrashReporter.readReport(file))
            }
            .setNegativeButton(R.string.crash_dismiss, null)
            .show()
    }

    private fun addBookmarkCurrent() {
        val tab = activeTab()
        val url = tab?.url.orEmpty()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_bookmark, Toast.LENGTH_SHORT).show()
            return
        }
        bookmarkStore.add(
            tab?.title?.ifEmpty { url } ?: url,
            url,
            BookmarkStore.DEFAULT_FOLDER
        )
        Toast.makeText(this, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showLinkDialog(linkUrl: String) {
        val items = arrayOf(
            getString(R.string.open_in_new_tab),
            getString(R.string.copy_link)
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> newTab(linkUrl)
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("link", linkUrl))
                        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setPositiveButton(R.string.clear) { _, _ -> clearBrowsingData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearBrowsingData() {
        historyStore.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        for (t in tabs) {
            t.webView.clearCache(true)
            t.webView.clearHistory()
        }
        updateNavButtons()
        Toast.makeText(this, R.string.data_cleared, Toast.LENGTH_SHORT).show()
    }
}
