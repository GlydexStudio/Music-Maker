package com.glydexstudio.musicmaker.bridge

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.glydexstudio.musicmaker.AppContainer
import com.glydexstudio.musicmaker.BuildConfig
import com.glydexstudio.musicmaker.audio.DrumSynth
import com.glydexstudio.musicmaker.audio.Sequencer
import com.glydexstudio.musicmaker.audio.WavWriter
import com.glydexstudio.musicmaker.core.AppEvents
import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import com.glydexstudio.musicmaker.core.Json
import com.glydexstudio.musicmaker.data.BeatPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The only door between JavaScript and native code.
 *
 * Security model:
 * - Injected with WebViewCompat.addWebMessageListener restricted to the app's own local origin
 *   (https://appassets.androidplatform.net). Other origins never see the object.
 * - No Java objects or reflection are exposed: JS posts {id, method, payload} messages and only
 *   the methods listed in [dispatch] exist. Payloads are validated by the receiving subsystem.
 *
 * Protocol (all JSON strings):
 *   JS  -> native: {"id": "42", "method": "getSongs", "payload": {...}}
 *   native -> JS : {"type": "result", "id": "42", "ok": true, "data": ...}
 *                  {"type": "result", "id": "42", "ok": false, "error": {"code", "message"}}
 *                  {"type": "event", "name": "model", "data": {...}}
 */
class MusicMakerBridge(
    private val context: Context,
    private val app: AppContainer,
    private val host: BridgeHost,
) : WebViewCompat.WebMessageListener, AppEvents.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var replyProxy: JavaScriptReplyProxy? = null

    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView) {
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT_NAME, setOf(ORIGIN), this)
        app.events.addListener(this)
    }

    fun detach() {
        app.events.removeListener(this)
        replyProxy = null
        scope.cancel()
    }

    /** Forwards native events to the page. */
    override fun onEvent(name: String, data: JSONObject) {
        post(JSONObject().put("type", "event").put("name", name).put("data", data))
    }

    fun emit(name: String, data: JSONObject = JSONObject()) = onEvent(name, data)

    /** True once the page has sent its first message (so it can receive events). */
    val isConnected: Boolean get() = replyProxy != null

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame || sourceOrigin.scheme != "https" || sourceOrigin.host != ORIGIN_HOST) return
        this.replyProxy = replyProxy
        val raw = message.data ?: return
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = msg.optString("id")
        val method = msg.optString("method")
        val payload = msg.optJSONObject("payload") ?: JSONObject()
        scope.launch {
            val response = JSONObject().put("type", "result").put("id", id)
            try {
                response.put("ok", true).put("data", dispatch(method, payload) ?: JSONObject.NULL)
            } catch (e: AppException) {
                response.put("ok", false).put("error", e.toJson())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Bridge call $method failed", e)
                response.put("ok", false).put(
                    "error",
                    AppException(ErrorCode.INTERNAL, "Something went wrong. Please try again.").toJson(),
                )
            }
            post(response)
        }
    }

    private fun post(o: JSONObject) {
        runCatching { replyProxy?.postMessage(o.toString()) }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun dispatch(method: String, p: JSONObject): Any? = when (method) {
        // ---- app
        "getAppInfo" -> appInfo()
        "exitApp" -> { host.exitApp(); null }
        "setSystemTheme" -> { host.setSystemBarsLight(Json.bool(p, "light", false)); null }

        // ---- model
        "getModelStatus" -> modelStatus()
        "loadModel" -> { app.modelManager.ensureLoaded(); modelStatus() }
        "reloadModel" -> { app.modelManager.reload(); modelStatus() }
        "unloadModel" -> { app.modelManager.unload(); modelStatus() }
        "deleteModel" -> { app.modelManager.deleteModelFile(); modelStatus() }
        "downloadModel" -> { app.modelInstaller.startDownload(); modelStatus() }
        "importModel" -> {
            val uri = host.pickModelFile() ?: throw AppException(ErrorCode.CANCELLED, "No file selected.")
            app.modelInstaller.startImport(uri); modelStatus()
        }
        "cancelModelInstall" -> { app.modelInstaller.cancel(); modelStatus() }

        // ---- AI
        "generateLyrics" -> app.aiEngine.generateLyrics(p)
        "generateSongIdea" -> app.aiEngine.generateSongIdea(p)
        "generatePromotion" -> app.aiEngine.generatePromotion(p)

        // ---- songs
        "getSongs" -> io { app.songs.list() }
        "getSong" -> io { app.songs.get(id(p)) }
        "saveSong" -> io { app.songs.save(p.optJSONObject("song") ?: p) }
        "deleteSong" -> io { app.songs.delete(id(p)); null }
        "duplicateSong" -> io { app.songs.duplicate(id(p)) }
        "renameSong" -> io { app.songs.rename(id(p), Json.str(p, "title", 120)) }
        "setFavorite" -> io { app.songs.setFavorite(id(p), Json.bool(p, "favorite", false)) }
        "saveBeat" -> io { app.songs.saveBeat(id(p), p.optJSONObject("beat") ?: JSONObject()) }
        "loadBeat" -> io { app.songs.loadBeat(id(p)) }
        "addPromotion" -> io { app.songs.addPromotion(id(p), p.optJSONObject("item") ?: JSONObject()) }
        "pickCoverImage" -> {
            val songId = id(p)
            val uri = host.pickImage() ?: throw AppException(ErrorCode.CANCELLED, "No image selected.")
            io {
                app.covers.importImage(uri, songId)
                app.songs.setCover(songId, JSONObject().put("type", "image").put("version", System.currentTimeMillis()))
            }
        }
        "resetCover" -> io {
            val songId = id(p)
            app.covers.delete(songId)
            app.songs.setCover(songId, JSONObject().put("type", "gradient").put("seed", (Math.random() * Int.MAX_VALUE).toInt()))
        }
        "deleteAllProjects" -> io { app.beatEngine.stop(); app.songs.deleteAll(); null }
        "resetAppData" -> {
            app.beatEngine.stop()
            io { app.songs.deleteAll(); app.settings.reset() }
            app.settings.get()
        }
        "getStorageInfo" -> io { storageInfo() }

        // ---- beat audio
        "playBeat" -> { app.beatEngine.play(BeatPattern.from(p.optJSONObject("beat"))); app.beatEngine.state() }
        "updateBeat" -> { app.beatEngine.update(BeatPattern.from(p.optJSONObject("beat"))); null }
        "pauseBeat" -> { app.beatEngine.pause(); app.beatEngine.state() }
        "stopBeat" -> { app.beatEngine.stop(); app.beatEngine.state() }
        "previewSound" -> { app.beatEngine.preview(Json.str(p, "track", 10)); null }
        "getBeatState" -> app.beatEngine.state()

        // ---- sharing & export
        "copyText" -> { app.shareManager.copyToClipboard(requireText(p)); null }
        "shareText" -> { host.startShare(app.shareManager.textIntent(requireText(p), Json.str(p, "subject", 120))); null }
        "shareSong" -> {
            val song = io { app.songs.get(id(p)) }
            host.startShare(app.shareManager.textIntent(app.shareManager.songSheet(song), song.optString("title")))
            null
        }
        "shareBeat" -> {
            val pattern = BeatPattern.from(p.optJSONObject("beat"))
            if (!pattern.hasNotes()) throw AppException(ErrorCode.BAD_REQUEST, "The beat is empty.")
            val bytes = withContext(Dispatchers.Default) { wavBytes(pattern, Json.int(p, "bars", 1, 16, 4)) }
            val name = app.shareManager.safeFileName(Json.str(p, "title", 80).ifBlank { "beat" }, "wav")
            host.startShare(app.shareManager.fileIntent(name, ExportMime.WAV, bytes))
            null
        }
        "exportSong" -> exportSong(p)

        // ---- settings
        "getSettings" -> app.settings.get()
        "saveSettings" -> {
            val saved = app.settings.save(p.optJSONObject("settings") ?: p)
            app.modelManager.emitStatus() // reloadRequired may have changed
            saved
        }

        else -> throw AppException(ErrorCode.BAD_REQUEST, "Unknown bridge method: $method")
    }

    private fun modelStatus(): JSONObject =
        app.modelManager.status().put("install", app.modelInstaller.status())

    private fun appInfo(): JSONObject {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return JSONObject()
            .put("name", "Music Maker")
            .put("developer", "Glydex Studio")
            .put("packageName", context.packageName)
            .put("version", info.versionName ?: "")
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
            .put("debug", BuildConfig.DEBUG)
            .put("android", Build.VERSION.RELEASE)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("totalRamBytes", mem.totalMem)
            .put("systemDark", host.isSystemDark())
    }

    private fun storageInfo(): JSONObject {
        val stats = app.songs.stats()
        val model = app.modelManager.installedFile()
        return stats
            .put("modelBytes", model?.length() ?: 0L)
            .put("freeBytes", app.modelManager.modelDir.usableSpace)
    }

    private suspend fun exportSong(p: JSONObject): JSONObject {
        val format = Json.oneOf(p, "format", setOf("txt", "json", "wav"), "txt")
        val song = io { app.songs.get(id(p)) }
        val title = song.optString("title")
        val (mime, bytes) = when (format) {
            "json" -> ExportMime.JSON to song.toString(2).toByteArray(Charsets.UTF_8)
            "wav" -> {
                val pattern = BeatPattern.from(song.optJSONObject("beat"))
                if (song.optJSONObject("beat") == null || !pattern.hasNotes()) {
                    throw AppException(ErrorCode.BAD_REQUEST, "This song has no beat to export yet.")
                }
                ExportMime.WAV to withContext(Dispatchers.Default) { wavBytes(pattern, Json.int(p, "bars", 1, 16, 4)) }
            }
            else -> ExportMime.TEXT to app.shareManager.songSheet(song).toByteArray(Charsets.UTF_8)
        }
        val fileName = app.shareManager.safeFileName(title, format)
        val uri = host.createDocument(fileName, mime) ?: throw AppException(ErrorCode.CANCELLED, "Export cancelled.")
        io {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw AppException(ErrorCode.STORAGE, "Could not write the exported file.")
            } catch (e: AppException) {
                throw e
            } catch (e: Exception) {
                throw AppException(ErrorCode.STORAGE, "Could not write the exported file.", e)
            }
        }
        return JSONObject().put("fileName", fileName).put("bytes", bytes.size)
    }

    private fun wavBytes(pattern: BeatPattern, bars: Int): ByteArray {
        val samples = Sequencer.renderOffline(pattern, bars)
        return ByteArrayOutputStream(samples.size * 2 + 44).also {
            WavWriter.write(it, samples, DrumSynth.SAMPLE_RATE)
        }.toByteArray()
    }

    private fun id(p: JSONObject): String =
        Json.str(p, "id", 64).ifBlank { throw AppException(ErrorCode.BAD_REQUEST, "Missing song id.") }

    private fun requireText(p: JSONObject): String =
        Json.str(p, "text", 20_000).ifBlank { throw AppException(ErrorCode.BAD_REQUEST, "Nothing to share.") }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val TAG = "MusicMakerBridge"
        const val JS_OBJECT_NAME = "MusicMakerBridge"
        const val ORIGIN_HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$ORIGIN_HOST"
    }
}
