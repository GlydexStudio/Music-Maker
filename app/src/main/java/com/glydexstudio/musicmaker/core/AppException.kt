package com.glydexstudio.musicmaker.core

import org.json.JSONObject

/** Stable error codes shared with the WebView (see js/native.js for user-facing messages). */
object ErrorCode {
    const val BAD_REQUEST = "BAD_REQUEST"
    const val NOT_FOUND = "NOT_FOUND"
    const val STORAGE = "STORAGE"
    const val MODEL_MISSING = "MODEL_MISSING"
    const val MODEL_LOAD_FAILED = "MODEL_LOAD_FAILED"
    const val UNSUPPORTED_DEVICE = "UNSUPPORTED_DEVICE"
    const val OUT_OF_MEMORY = "OUT_OF_MEMORY"
    const val BUSY = "BUSY"
    const val GENERATION_FAILED = "GENERATION_FAILED"
    const val INVALID_RESPONSE = "INVALID_RESPONSE"
    const val INSTALL_FAILED = "INSTALL_FAILED"
    const val NO_SPACE = "NO_SPACE"
    const val AUDIO = "AUDIO"
    const val CANCELLED = "CANCELLED"
    const val INTERNAL = "INTERNAL"
}

/** Expected, user-explainable failure. Anything else is reported as [ErrorCode.INTERNAL]. */
class AppException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    fun toJson(): JSONObject = JSONObject().put("code", code).put("message", message ?: code)
}
