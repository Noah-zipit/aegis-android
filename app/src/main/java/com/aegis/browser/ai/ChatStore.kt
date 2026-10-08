package com.aegis.browser.ai

import java.io.File

/** A single chat message. role is "user" or "assistant". */
data class ChatMessage(val role: String, val content: String, val ts: Long)

/**
 * Persists chat history as JSON in filesDir/chat_history.json.
 * Hand-rolled JSON (no Room, no Gson) for the fixed schema:
 * [{"role":"user","content":"...","ts":123}, ...]
 */
object ChatStore {
    private const val FILE_NAME = "chat_history.json"

    fun load(filesDir: File): MutableList<ChatMessage> {
        val f = File(filesDir, FILE_NAME)
        if (!f.exists()) return mutableListOf()
        return try {
            parseArray(f.readText())
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(filesDir: File, messages: List<ChatMessage>) {
        try {
            File(filesDir, FILE_NAME).writeText(buildArray(messages))
        } catch (e: Exception) {
            // Best-effort persistence; chat still works in memory.
        }
    }

    fun clear(filesDir: File) {
        File(filesDir, FILE_NAME).delete()
    }

    // ---- minimal JSON writer ----

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun buildArray(messages: List<ChatMessage>): String {
        val sb = StringBuilder("[")
        messages.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"role\":\"").append(esc(m.role))
                .append("\",\"content\":\"").append(esc(m.content))
                .append("\",\"ts\":").append(m.ts).append('}')
        }
        return sb.append(']').toString()
    }

    // ---- minimal JSON parser (fixed schema only) ----

    private fun parseArray(json: String): MutableList<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        var i = 0
        fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun expect(c: Char) { skipWs(); if (i >= json.length || json[i] != c) throw IllegalStateException("bad json"); i++ }
        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = json[i++]
                        sb.append(when (e) {
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'u' -> { val hex = json.substring(i, i + 4); i += 4; hex.toInt(16).toChar() }
                            else -> e
                        })
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }
        fun parseLong(): Long {
            skipWs()
            val start = i
            while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
            return json.substring(start, i).toLong()
        }

        expect('[')
        skipWs()
        if (i < json.length && json[i] == ']') { i++; return out }
        while (true) {
            expect('{')
            var role = ""; var content = ""; var ts = 0L
            while (true) {
                val key = parseString()
                expect(':')
                when (key) {
                    "role" -> role = parseString()
                    "content" -> content = parseString()
                    "ts" -> ts = parseLong()
                    else -> parseString() // skip unknown string values
                }
                skipWs()
                if (i < json.length && json[i] == ',') { i++; continue }
                break
            }
            expect('}')
            out.add(ChatMessage(role, content, ts))
            skipWs()
            if (i < json.length && json[i] == ',') { i++; continue }
            break
        }
        expect(']')
        return out
    }
}
