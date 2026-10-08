package com.aegis.browser

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aegis.browser.data.HistoryStore

/**
 * Search engine selector, homepage URL, clear browsing data, version info.
 * Engine + homepage persist in SharedPreferences "aegis_prefs".
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)

        // --- search engine radios ---
        val group = findViewById<RadioGroup>(R.id.engine_group)
        when (prefs.getString(MainActivity.KEY_ENGINE, "google")) {
            "duckduckgo" -> findViewById<RadioButton>(R.id.rb_duckduckgo).isChecked = true
            "brave" -> findViewById<RadioButton>(R.id.rb_brave).isChecked = true
            "bing" -> findViewById<RadioButton>(R.id.rb_bing).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_google).isChecked = true
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rb_duckduckgo -> "duckduckgo"
                R.id.rb_brave -> "brave"
                R.id.rb_bing -> "bing"
                else -> "google"
            }
            prefs.edit().putString(MainActivity.KEY_ENGINE, value).apply()
            Toast.makeText(this, R.string.engine_saved, Toast.LENGTH_SHORT).show()
        }

        // --- homepage ---
        val homepageField = findViewById<EditText>(R.id.homepage_url)
        homepageField.setText(prefs.getString(MainActivity.KEY_HOMEPAGE, ""))
        findViewById<Button>(R.id.btn_save_homepage).setOnClickListener {
            prefs.edit()
                .putString(MainActivity.KEY_HOMEPAGE, homepageField.text.toString().trim())
                .apply()
            Toast.makeText(this, R.string.homepage_saved, Toast.LENGTH_SHORT).show()
        }

        // --- clear browsing data ---
        findViewById<Button>(R.id.btn_clear_data).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_data_title)
                .setMessage(R.string.clear_data_message)
                .setPositiveButton(R.string.clear) { _, _ -> clearBrowsingData() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // --- version ---
        findViewById<TextView>(R.id.version_info).text =
            getString(R.string.version_format, appVersion())
    }

    private fun clearBrowsingData() {
        HistoryStore(this).clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        try {
            // No WebView lives in this activity; a throwaway instance clears the shared cache.
            WebView(this).apply {
                clearCache(true)
                destroy()
            }
        } catch (e: Exception) {
            // Cache clear is best-effort here.
        }
        Toast.makeText(this, R.string.data_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun appVersion(): String {
        return try {
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            pi.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
