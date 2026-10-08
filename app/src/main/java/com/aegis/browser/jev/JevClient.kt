package com.aegis.browser.jev

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for Jev-compatible decision APIs (TypeSafe /v1/systemone schema).
 *
 * Sends the current page as state with three questions (trust/noul, kind/choice,
 * quality/score) and parses the typed answers. Runs on the caller's thread —
 * call from a background thread. The API key is the user's own (BYOK) and is
 * only ever sent to the configured endpoint.
 */
object JevClient {

    const val DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    const val DEFAULT_MODEL = "jev-latest"

    data class Verdict(
        val trust: Double?,
        val pageKind: String?,
        val kindConfidence: Double?,
        val kindProbs: Map<String, Double>,
        val quality: Double?,
        val qualityConfidence: Double?,
        val error: String?
    )

    fun evaluate(
        endpoint: String,
        apiKey: String,
        model: String,
        pageUrl: String,
        pageTitle: String,
        pageText: String
    ): Verdict {
        try {
            val body = JSONObject()
            val state = JSONObject()
            state.put("url", pageUrl)
            state.put("title", pageTitle)
            state.put("text", pageText)
            body.put("state", state)
            body.put("model", model.ifBlank { "jev-latest" })

            val questions = JSONObject()
            questions.put(
                "trust",
                JSONObject()
                    .put("type", "noul")
                    .put("instructions", "This page is trustworthy and safe for the user to use.")
                    .put(
                        "criteria", JSONObject()
                            .put("true", "The page is trustworthy and safe")
                            .put("false", "The page is not trustworthy or not safe")
                    )
            )
            questions.put(
                "kind",
                JSONObject()
                    .put("type", "choice")
                    .put("instructions", "What best describes this page?")
                    .put(
                        "criteria", JSONObject()
                            .put("article", "News article, blog post, or informational content")
                            .put("product", "Product, store, or shopping page")
                            .put("service", "Web app, tool, or online service")
                            .put("spam_or_scam", "Spam, scam, phishing, or misleading page")
                            .put("other", "None of the above")
                    )
            )
            questions.put(
                "quality",
                JSONObject()
                    .put("type", "score")
                    .put("instructions", "Rate the quality and usefulness of this page.")
                    .put(
                        "criteria", JSONObject()
                            .put("low", "Low quality")
                            .put("medium", "Medium quality")
                            .put("high", "High quality")
                    )
            )
            body.put("questions", questions)

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 25000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                val hint = when (code) {
                    401 -> " (bad API key)"
                    422 -> " (malformed request)"
                    429 -> " (rate limited — try again shortly)"
                    else -> ""
                }
                return Verdict(null, null, null, emptyMap(), null, null,
                    "HTTP $code$hint: ${raw.take(160)}")
            }
            return parse(JSONObject(raw))
        } catch (e: Exception) {
            return Verdict(null, null, null, emptyMap(), null, null,
                e.message ?: "Network error")
        }
    }

    private fun parse(root: JSONObject): Verdict {
        // Answers are keyed by our question ids, either under "answers" or top-level.
        val answers = root.optJSONObject("answers") ?: root
        fun qa(id: String): JSONObject = answers.optJSONObject(id) ?: JSONObject()

        val trustObj = qa("trust")
        val trust = trustObj.optDouble("noul", Double.NaN).takeIf { !it.isNaN() }

        val kindObj = qa("kind")
        val pageKind = kindObj.optString("choice", "").ifEmpty { null }
        val kindConf = kindObj.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }
        val kindProbs = mutableMapOf<String, Double>()
        kindObj.optJSONObject("probabilities")?.let { probs ->
            probs.keys().forEach { k ->
                probs.optDouble(k, Double.NaN).takeIf { !it.isNaN() }?.let { kindProbs[k] = it }
            }
        }

        val qualityObj = qa("quality")
        val quality = qualityObj.optDouble("score", Double.NaN).takeIf { !it.isNaN() }
        val qualityConf = qualityObj.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }

        return Verdict(trust, pageKind, kindConf, kindProbs, quality, qualityConf, null)
    }
}
