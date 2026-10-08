package com.aegis.browser.shield

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Aegis Shield: real request-level ad/tracker blocking.
 *
 * Hooked into WebViewClient.shouldInterceptRequest. Every subresource request
 * is scanned; hosts on the bundled blocklists are answered with an empty
 * 200 response so nothing is fetched or rendered. Main-frame navigations are
 * never blocked. All counters stay on the device (SharedPreferences).
 */
object Shield {

    enum class Kind { AD, TRACKER }

    private const val PREFS = "aegis_shield"
    private const val K_BLOCK_ADS = "block_ads"
    private const val K_BLOCK_TRACKERS = "block_trackers"
    private const val K_ADS = "ads_blocked"
    private const val K_TRACKERS = "trackers_blocked"
    private const val K_SCANNED = "requests_scanned"
    private const val K_DOMAINS = "domain_counts"

    @Volatile var blockAds = true
    @Volatile var blockTrackers = true

    private val adsBlocked = AtomicLong(0)
    private val trackersBlocked = AtomicLong(0)
    private val requestsScanned = AtomicLong(0)
    private val domainCounts = HashMap<String, Long>()
    private val domainLock = Any()

    data class Stats(val ads: Long, val trackers: Long, val scanned: Long, val domains: Int)

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        blockAds = p.getBoolean(K_BLOCK_ADS, true)
        blockTrackers = p.getBoolean(K_BLOCK_TRACKERS, true)
        adsBlocked.set(p.getLong(K_ADS, 0))
        trackersBlocked.set(p.getLong(K_TRACKERS, 0))
        requestsScanned.set(p.getLong(K_SCANNED, 0))
        synchronized(domainLock) {
            domainCounts.clear()
            p.getString(K_DOMAINS, "").orEmpty().split(";").forEach { pair ->
                val kv = pair.split("=")
                if (kv.size == 2) kv[1].toLongOrNull()?.let { domainCounts[kv[0]] = it }
            }
        }
    }

    fun persist(context: Context) {
        val domainStr = synchronized(domainLock) {
            domainCounts.entries.sortedByDescending { it.value }.take(80)
                .joinToString(";") { "${it.key}=${it.value}" }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_BLOCK_ADS, blockAds)
            .putBoolean(K_BLOCK_TRACKERS, blockTrackers)
            .putLong(K_ADS, adsBlocked.get())
            .putLong(K_TRACKERS, trackersBlocked.get())
            .putLong(K_SCANNED, requestsScanned.get())
            .putString(K_DOMAINS, domainStr)
            .apply()
    }

    /**
     * Returns an empty response when the request should be blocked, null to allow it.
     * Called on WebView's background threads; must stay fast and thread-safe.
     */
    fun intercept(url: String?, isMainFrame: Boolean): WebResourceResponse? {
        if (url.isNullOrEmpty() || isMainFrame) return null
        val scheme = try {
            Uri.parse(url).scheme?.lowercase()
        } catch (_: Exception) {
            null
        }
        if (scheme != "http" && scheme != "https") return null
        requestsScanned.incrementAndGet()
        val kind = classify(url) ?: return null
        if (kind == Kind.AD && !blockAds) return null
        if (kind == Kind.TRACKER && !blockTrackers) return null
        if (kind == Kind.AD) adsBlocked.incrementAndGet() else trackersBlocked.incrementAndGet()
        hostOf(url)?.let { h ->
            synchronized(domainLock) { domainCounts[h] = (domainCounts[h] ?: 0L) + 1 }
        }
        return WebResourceResponse(
            "text/plain", "utf-8", 200, "OK",
            emptyMap(), ByteArrayInputStream(ByteArray(0))
        )
    }

    fun classify(url: String): Kind? {
        val host = hostOf(url) ?: return null
        if (matches(host, AD_HOSTS)) return Kind.AD
        if (matches(host, TRACKER_HOSTS)) return Kind.TRACKER
        return null
    }

    fun snapshot(): Stats = Stats(
        adsBlocked.get(), trackersBlocked.get(), requestsScanned.get(),
        synchronized(domainLock) { domainCounts.size }
    )

    fun topDomains(limit: Int): List<Pair<String, Long>> = synchronized(domainLock) {
        domainCounts.entries.sortedByDescending { it.value }.take(limit)
            .map { it.key to it.value }
    }

    private fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.lowercase()?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

    private fun matches(host: String, list: Set<String>): Boolean {
        for (d in list) if (host == d || host.endsWith(".$d")) return true
        return false
    }

    // Curated from widely-known ad-tech infrastructure. Suffix-matched, so
    // e.g. "googleads.g.doubleclick.net" is covered by "doubleclick.net".
    private val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "ads.yahoo.com", "amazon-adsystem.com",
        "criteo.com", "criteo.net", "outbrain.com", "taboola.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "adnxs.com",
        "adsrvr.org", "moatads.com", "demdex.net", "addthis.com",
        "sharethis.com", "bidswitch.net", "casalemedia.com", "lijit.com",
        "mathtag.com", "bluekai.com", "rlcdn.com", "agkn.com",
        "rfihub.com", "adform.net", "adform.com", "smartadserver.com",
        "media.net", "revcontent.com", "mgid.com", "adblade.com",
        "triplelift.com", "sharethrough.com", "nativo.com", "spotxchange.com",
        "springserve.com", "tremorhub.com", "lkqd.com", "ads.twitter.com",
        "static.ads-twitter.com", "ttd.com", "ads.linkedin.com",
        "ads.pinterest.com", "aaxads.com", "admatic.com"
    )

    private val TRACKER_HOSTS = setOf(
        "google-analytics.com", "googletagmanager.com", "hotjar.com",
        "fullstory.com", "mixpanel.com", "segment.io", "segment.com",
        "amplitude.com", "newrelic.com", "nr-data.net", "facebook.net",
        "fbevents.com", "scorecardresearch.com", "quantserve.com",
        "quantcast.com", "crazyegg.com", "luckyorange.com", "mouseflow.com",
        "inspectlet.com", "logrocket.com", "heap.io", "heapanalytics.com",
        "kissmetrics.com", "optimizely.com", "vwo.com", "abtasty.com",
        "hs-scripts.com", "pardot.com", "snap.licdn.com",
        "analytics.tiktok.com", "bat.bing.com", "clarity.ms",
        "c.clarity.ms", "stats.wp.com", "pixel.wp.com", "pingdom.net"
    )
}
