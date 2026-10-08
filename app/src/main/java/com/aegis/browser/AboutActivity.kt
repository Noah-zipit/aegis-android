package com.aegis.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** About screen: shield, principles, stats, links, version. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about_title)

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.version_format, appVersion())

        findViewById<Button>(R.id.btn_github).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Noah-zipit/aegis-android")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, R.string.cannot_open_file, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_mit).setOnClickListener {
            Toast.makeText(this, R.string.about_mit_toast, Toast.LENGTH_LONG).show()
        }
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
