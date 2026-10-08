package com.aegis.browser

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** About screen: shield, tagline, privacy framing, version. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about_title)

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.version_format, appVersion())
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
