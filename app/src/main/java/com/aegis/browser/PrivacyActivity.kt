package com.aegis.browser

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.aegis.browser.shield.Shield

/**
 * Privacy Report: everything Shield refused on the user's behalf.
 * Counters are real (see Shield) and never leave the device.
 */
class PrivacyActivity : AppCompatActivity() {

    private lateinit var tvTrackers: TextView
    private lateinit var tvAds: TextView
    private lateinit var tvScanned: TextView
    private lateinit var tvDomains: TextView
    private lateinit var swAds: SwitchCompat
    private lateinit var swTrackers: SwitchCompat
    private lateinit var topDomains: LinearLayout
    private lateinit var topDomainsEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.privacy_title)

        tvTrackers = findViewById(R.id.stat_trackers)
        tvAds = findViewById(R.id.stat_ads)
        tvScanned = findViewById(R.id.stat_scanned)
        tvDomains = findViewById(R.id.stat_domains)
        swAds = findViewById(R.id.switch_block_ads)
        swTrackers = findViewById(R.id.switch_block_trackers)
        topDomains = findViewById(R.id.top_domains)
        topDomainsEmpty = findViewById(R.id.top_domains_empty)

        swAds.isChecked = Shield.blockAds
        swTrackers.isChecked = Shield.blockTrackers
        swAds.setOnCheckedChangeListener { _, checked ->
            Shield.blockAds = checked
            Shield.persist(this)
        }
        swTrackers.setOnCheckedChangeListener { _, checked ->
            Shield.blockTrackers = checked
            Shield.persist(this)
        }

        findViewById<Button>(R.id.btn_story).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        Shield.persist(this)
        super.onPause()
    }

    private fun refresh() {
        val s = Shield.snapshot()
        tvTrackers.text = s.trackers.toString()
        tvAds.text = s.ads.toString()
        tvScanned.text = s.scanned.toString()
        tvDomains.text = s.domains.toString()

        topDomains.removeAllViews()
        val tops = Shield.topDomains(8)
        topDomainsEmpty.visibility = if (tops.isEmpty()) View.VISIBLE else View.GONE
        topDomains.visibility = if (tops.isEmpty()) View.GONE else View.VISIBLE
        tops.forEachIndexed { index, (host, count) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            val name = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = host
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setTextColor(getColor(R.color.aegis_text))
            }
            val num = TextView(this).apply {
                text = "${count}×"
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setTextColor(getColor(R.color.aegis_text_dim))
            }
            row.addView(name)
            row.addView(num)
            topDomains.addView(row)
            if (index < tops.size - 1) {
                topDomains.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(getColor(R.color.aegis_border))
                })
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
