package com.aegis.browser.ai

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class ModelPack(
    val id: String,
    val name: String,
    val subtitle: String,
    val url: String,
    val sizeBytes: Long,
    val comingSoon: Boolean = false
)

/**
 * Downloads GGUF model packs into filesDir/models/ with progress callbacks.
 * Writes to a .tmp file first, verifies the exact byte size, then renames.
 */
object ModelManager {

    val NANO = ModelPack(
        id = "nano",
        name = "Nano",
        subtitle = "SmolLM2 135M · Q4_0 · ~87 MB",
        url = "https://huggingface.co/QuantFactory/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct.Q4_0.gguf",
        sizeBytes = 91726912L
    )

    val LITE = ModelPack(
        id = "lite",
        name = "Lite",
        subtitle = "SmolLM2 360M · Q4_0 · ~219 MB",
        url = "https://huggingface.co/QuantFactory/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct.Q4_0.gguf",
        sizeBytes = 229118592L
    )

    val STANDARD = ModelPack(
        id = "standard",
        name = "Standard",
        subtitle = "SmolLM2 1.7B · Q4_0 · ~945 MB",
        url = "https://huggingface.co/QuantFactory/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct.Q4_0.gguf",
        sizeBytes = 990728896L
    )

    val PACKS: List<ModelPack> = listOf(NANO, LITE, STANDARD)

    /** Currently selected pack. Nano (smallest) is the default. */
    var activePack: ModelPack = NANO

    private val cancelled = AtomicBoolean(false)

    fun modelsDir(filesDir: File): File = File(filesDir, "models").apply { mkdirs() }

    fun getModelFile(filesDir: File, pack: ModelPack): File =
        File(modelsDir(filesDir), pack.id + ".gguf")

    fun isModelReady(filesDir: File, pack: ModelPack): Boolean {
        if (pack.comingSoon) return false
        val f = getModelFile(filesDir, pack)
        return f.exists() && f.length() == pack.sizeBytes
    }

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Downloads [pack], calling [onProgress] with (bytesDownloaded, bytesTotal).
     * Returns true only if the file landed with the exact expected size.
     * Safe to call on a background thread only.
     */
    fun download(
        filesDir: File,
        pack: ModelPack,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean {
        cancelled.set(false)
        val dest = getModelFile(filesDir, pack)
        val tmp = File(dest.path + ".tmp")
        tmp.delete()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(pack.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true // Hugging Face 302s to a signed CDN URL
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AegisBrowser/1.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return false

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: pack.sizeBytes

            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (cancelled.get()) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }

            if (cancelled.get()) return false
            // Verify exact size before accepting the file.
            if (tmp.length() != pack.sizeBytes) return false

            if (dest.exists()) dest.delete()
            return tmp.renameTo(dest)
        } catch (e: Exception) {
            return false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
            if (tmp.exists() && !dest.exists()) tmp.delete()
        }
    }

    /** Human-readable byte count, e.g. "219 MB". */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.0f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
