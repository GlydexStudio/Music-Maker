package com.glydexstudio.musicmaker.core

import org.json.JSONArray
import org.json.JSONObject

/** Small, defensive helpers for reading untrusted JSON coming from the WebView or the model. */
object Json {
    private val controlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /** Trimmed string with control characters removed, capped at [max] chars. */
    fun str(o: JSONObject, key: String, max: Int = 200, default: String = ""): String {
        if (!o.has(key) || o.isNull(key)) return default
        return clean(o.opt(key)?.toString() ?: default, max)
    }

    fun clean(value: String, max: Int): String =
        value.replace("\r\n", "\n").replace(controlChars, "").trim().take(max)

    fun int(o: JSONObject, key: String, min: Int, max: Int, default: Int): Int {
        val v = o.opt(key)
        val n = when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        } ?: return default
        return n.coerceIn(min, max)
    }

    fun double(o: JSONObject, key: String, min: Double, max: Double, default: Double): Double {
        val v = o.opt(key)
        val n = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        } ?: return default
        if (n.isNaN()) return default
        return n.coerceIn(min, max)
    }

    fun bool(o: JSONObject, key: String, default: Boolean): Boolean {
        val v = o.opt(key)
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            is Number -> v.toInt() != 0
            else -> default
        }
    }

    fun oneOf(o: JSONObject, key: String, allowed: Collection<String>, default: String): String {
        val v = str(o, key, 64)
        return if (v in allowed) v else default
    }

    /** List of cleaned, non-blank strings. Accepts an array or a newline separated string. */
    fun stringList(value: Any?, maxItems: Int, maxLen: Int): List<String> {
        val raw = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it)?.toString() ?: "" }
            is String -> value.split("\n")
            else -> emptyList()
        }
        return raw.map { clean(it, maxLen).trimStart('-', '*', '•', ' ') }
            .filter { it.isNotBlank() }
            .take(maxItems)
    }

    fun parseObject(text: String?): JSONObject {
        if (text.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw AppException(ErrorCode.BAD_REQUEST, "Malformed request data.", e)
        }
    }
}
