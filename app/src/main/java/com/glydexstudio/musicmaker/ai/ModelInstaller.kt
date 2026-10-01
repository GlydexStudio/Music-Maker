package com.glydexstudio.musicmaker.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.glydexstudio.musicmaker.R
import com.glydexstudio.musicmaker.core.AppEvents
import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Gets the GGUF model onto the device, in one of two real ways:
 *  1. Download through Android's DownloadManager (survives app restarts, shows a notification).
 *  2. Import a .gguf file the user already has, through the system file picker.
 * Files are validated (GGUF magic + size) before the model manager accepts them.
 */
class ModelInstaller(
    private val context: Context,
    private val models: ModelManager,
    private val events: AppEvents,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("music_maker_install", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private var pollJob: Job? = null
    private var importJob: Job? = null
    @Volatile private var current: JSONObject = idle()

    init {
        // Resume tracking a download that was started in a previous session.
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) >= 0) startPolling()
    }

    fun status(): JSONObject = JSONObject(current.toString())

    fun startDownload(): JSONObject {
        if (isBusy()) throw AppException(ErrorCode.BUSY, "A model installation is already in progress.")
        if (models.installedFile() != null) throw AppException(ErrorCode.BAD_REQUEST, "The model is already installed.")
        requireSpace(ModelManager.DEFAULT_EXPECTED_BYTES)
        val dm = downloads ?: throw AppException(ErrorCode.INSTALL_FAILED, "Downloads are not available on this device.")

        File(models.modelDir, PART_NAME).delete()
        val request = DownloadManager.Request(Uri.parse(ModelManager.DEFAULT_DOWNLOAD_URL))
            .setTitle(context.getString(R.string.download_title))
            .setDescription(context.getString(R.string.download_description))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", PART_NAME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            throw AppException(ErrorCode.INSTALL_FAILED, "Could not start the download.", e)
        }
        prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        update(progress("download", "pending", 0, ModelManager.DEFAULT_EXPECTED_BYTES))
        startPolling()
        return status()
    }

    /** Copies a user-picked .gguf into the model directory with progress events. */
    fun startImport(uri: Uri): JSONObject {
        if (isBusy()) throw AppException(ErrorCode.BUSY, "A model installation is already in progress.")
        val (name, size) = queryNameAndSize(uri)
        if (!name.lowercase().endsWith(".gguf")) {
            throw AppException(ErrorCode.BAD_REQUEST, "Please choose a .gguf model file.")
        }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        if (size > 0) requireSpace(size)
        update(progress("import", "running", 0, size))
        importJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { copyModel(uri, safeName, size) }
                models.onModelInstalled(safeName)
                update(progress("import", "done", size, size).put("active", false))
            } catch (e: AppException) {
                update(failed("import", e))
            } catch (e: kotlinx.coroutines.CancellationException) {
                File(models.modelDir, "$safeName.part").delete()
                update(idle())
            } catch (e: Exception) {
                update(failed("import", AppException(ErrorCode.INSTALL_FAILED, "Import failed: ${e.message}", e)))
            }
        }
        return status()
    }

    fun cancel(): JSONObject {
        importJob?.cancel()
        importJob = null
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id >= 0) {
            downloads?.remove(id)
            prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
            File(models.modelDir, PART_NAME).delete()
        }
        pollJob?.cancel()
        update(idle())
        return status()
    }

    private suspend fun copyModel(uri: Uri, fileName: String, size: Long) {
        val part = File(models.modelDir, "$fileName.part")
        val target = File(models.modelDir, fileName)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw AppException(ErrorCode.INSTALL_FAILED, "Could not open the selected file.")
        input.use { stream ->
            val header = ByteArray(4)
            val read = stream.read(header)
            if (read < 4 || !ModelManager.isGgufHeader(header)) {
                throw AppException(ErrorCode.BAD_REQUEST, "This file is not a valid GGUF model.")
            }
            part.outputStream().buffered(1 shl 20).use { out ->
                out.write(header)
                val buf = ByteArray(1 shl 20)
                var copied = 4L
                var lastEmit = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (copied - lastEmit > 8L * 1024 * 1024) {
                        lastEmit = copied
                        update(progress("import", "running", copied, size))
                    }
                }
            }
        }
        if (size > 0 && part.length() != size) {
            part.delete()
            throw AppException(ErrorCode.INSTALL_FAILED, "The copy is incomplete. Please try again.")
        }
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw AppException(ErrorCode.STORAGE, "Could not finalize the model file.")
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val keepGoing = withContext(Dispatchers.IO) { pollOnce() }
                if (!keepGoing) break
                delay(1000)
            }
        }
    }

    /** Returns false when the download reached a terminal state. */
    private fun pollOnce(): Boolean {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val dm = downloads
        if (id < 0 || dm == null) return false
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                update(idle())
                return false
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                .takeIf { it > 0 } ?: ModelManager.DEFAULT_EXPECTED_BYTES
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_PENDING -> update(progress("download", "pending", done, total))
                DownloadManager.STATUS_RUNNING -> update(progress("download", "running", done, total))
                DownloadManager.STATUS_PAUSED -> update(progress("download", "paused", done, total).put("reason", pausedReason(reason)))
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    finishDownload(total)
                    return false
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    dm.remove(id)
                    update(failed("download", AppException(ErrorCode.INSTALL_FAILED, "Download failed (${failedReason(reason)}).")))
                    return false
                }
            }
        }
        return true
    }

    private fun finishDownload(total: Long) {
        val part = File(models.modelDir, PART_NAME)
        val target = File(models.modelDir, ModelManager.DEFAULT_FILE_NAME)
        if (!part.exists() || !ModelManager.isGguf(part)) {
            part.delete()
            update(failed("download", AppException(ErrorCode.INSTALL_FAILED, "The downloaded file is not a valid GGUF model.")))
            return
        }
        target.delete()
        if (!part.renameTo(target)) {
            update(failed("download", AppException(ErrorCode.STORAGE, "Could not finalize the downloaded model.")))
            return
        }
        models.onModelInstalled(ModelManager.DEFAULT_FILE_NAME)
        update(progress("download", "done", total, total).put("active", false))
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "model.gguf"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "model.gguf") to -1L
    }

    private fun requireSpace(bytes: Long) {
        val free = models.modelDir.usableSpace
        if (free < bytes + SPACE_MARGIN) {
            throw AppException(
                ErrorCode.NO_SPACE,
                "Not enough free storage. Needed ${mb(bytes + SPACE_MARGIN)} MB, available ${mb(free)} MB.",
            )
        }
    }

    private fun isBusy() = current.optBoolean("active")

    private fun update(o: JSONObject) {
        current = o
        events.emit("install", JSONObject(o.toString()))
    }

    private fun idle() = JSONObject().put("active", false).put("status", "idle")

    private fun progress(kind: String, status: String, done: Long, total: Long) = JSONObject()
        .put("active", true)
        .put("kind", kind)
        .put("status", status)
        .put("bytes", done)
        .put("total", total)
        .put("progress", if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0)

    private fun failed(kind: String, e: AppException) = JSONObject()
        .put("active", false)
        .put("kind", kind)
        .put("status", "failed")
        .put("error", e.toJson())

    private fun pausedReason(r: Int) = when (r) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Retrying soon"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
        else -> "Paused"
    }

    private fun failedReason(r: Int) = when (r) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough storage"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server error"
        DownloadManager.ERROR_CANNOT_RESUME -> "connection lost and could not resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage unavailable"
        in 400..599 -> "HTTP $r"
        else -> "error $r"
    }

    private fun mb(b: Long) = b / (1024 * 1024)

    companion object {
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val PART_NAME = "${ModelManager.DEFAULT_FILE_NAME}.part"
        private const val SPACE_MARGIN = 150L * 1024 * 1024
    }
}
