package com.glydexstudio.musicmaker.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.glydexstudio.musicmaker.R
import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import org.json.JSONObject
import java.io.File

/** Builds share/export content and the Android intents that hand it to other apps. */
class ShareManager(private val context: Context) {

    private val sharedDir = File(context.cacheDir, "shared")

    fun copyToClipboard(text: String, label: String = "Music Maker") {
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: throw AppException(ErrorCode.INTERNAL, "Clipboard is not available.")
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun textIntent(text: String, subject: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        return Intent.createChooser(send, context.getString(R.string.share_chooser))
    }

    /** Writes [bytes] to the share cache and returns a chooser intent for it. */
    fun fileIntent(fileName: String, mime: String, bytes: ByteArray, text: String? = null): Intent {
        sharedDir.mkdirs()
        sharedDir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val file = File(sharedDir, fileName)
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            throw AppException(ErrorCode.STORAGE, "Could not prepare the file for sharing.", e)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.share_chooser))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Human-readable song sheet used for "Share" and the .txt export. */
    fun songSheet(song: JSONObject): String = buildString {
        appendLine(song.optString("title"))
        song.optString("artist").takeIf { it.isNotBlank() }?.let { appendLine(it) }
        val meta = listOfNotNull(
            song.optString("genre").takeIf { it.isNotBlank() },
            song.optString("mood").takeIf { it.isNotBlank() },
            song.optInt("bpm").takeIf { it > 0 }?.let { "$it BPM" },
            song.optString("key").takeIf { it.isNotBlank() }?.let { "Key $it" },
        )
        if (meta.isNotEmpty()) appendLine(meta.joinToString(" · "))
        val idea = song.optJSONObject("idea")
        idea?.optString("concept")?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Concept: $it")
        }
        val lyrics = song.optString("lyrics")
        if (lyrics.isNotBlank()) {
            appendLine()
            appendLine(lyrics)
        }
        appendLine()
        append("Made with Music Maker by Glydex Studio")
    }

    fun safeFileName(title: String, ext: String): String {
        val base = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(Regex("\\s+"), "_").take(60)
        return (base.ifBlank { "song" }) + "." + ext
    }
}
