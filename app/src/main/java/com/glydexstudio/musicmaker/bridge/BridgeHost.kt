package com.glydexstudio.musicmaker.bridge

import android.content.Intent
import android.net.Uri

/** Activity-level capabilities the bridge needs (pickers, intents, window). */
interface BridgeHost {
    suspend fun pickImage(): Uri?
    suspend fun pickModelFile(): Uri?
    /** Shows the system "save as" dialog. [mime] must be one of [ExportMime]. */
    suspend fun createDocument(fileName: String, mime: String): Uri?
    fun startShare(intent: Intent)
    fun setSystemBarsLight(light: Boolean)
    fun isSystemDark(): Boolean
    fun exitApp()
}

object ExportMime {
    const val TEXT = "text/plain"
    const val JSON = "application/json"
    const val WAV = "audio/wav"
}
