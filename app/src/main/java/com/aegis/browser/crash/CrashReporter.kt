package com.aegis.browser.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aegis.browser.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporting without any backend: the uncaught-exception handler writes a
 * report file into internal storage, and the app offers it for viewing,
 * copying, or sharing on the next launch (see MainActivity + Settings).
 * Nothing ever leaves the phone unless the user explicitly shares a report.
 */
object CrashReporter {

    private const val DIR = "crashes"
    private const val MAX_KEPT = 10

    fun init(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Exception) {
                // Never interfere with the crash itself.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun pendingReports(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun readReport(file: File): String = try {
        file.readText().take(24_000)
    } catch (_: Exception) {
        "Could not read the report."
    }

    fun deleteReport(file: File) {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    /** Full view/copy/share/delete dialog for one report. */
    fun showReportDialog(activity: AppCompatActivity, file: File) {
        val text = readReport(file)
        val tv = TextView(activity).apply {
            setText(text)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(32, 24, 32, 24)
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                Toast.makeText(activity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.crash_share) { _, _ -> shareReport(activity, text) }
            .setNegativeButton(R.string.crash_delete) { _, _ ->
                deleteReport(file)
                Toast.makeText(activity, R.string.crash_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun shareReport(activity: AppCompatActivity, text: String) {
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                activity.getString(R.string.crash_share)
            )
        )
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_${stamp}.txt")

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val version = try {
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }

        val report = buildString {
            appendLine("Aegis crash report")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("App version: $version")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(sw.toString().take(16_000))
        }
        file.writeText(report)

        // Keep only the newest reports.
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEPT)
            ?.forEach { runCatching { it.delete() } }
    }
}
