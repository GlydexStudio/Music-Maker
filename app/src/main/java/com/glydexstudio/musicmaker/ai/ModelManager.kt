package com.glydexstudio.musicmaker.ai

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.glydexstudio.musicmaker.core.AppEvents
import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import com.glydexstudio.musicmaker.data.SettingsRepository
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaException
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Owns the one and only [LlamaModel] in the process.
 *
 * - Loading is guarded by [loadMutex], so concurrent requests never load two copies.
 * - Inference is guarded by [inferenceMutex]; a second request while generating is
 *   rejected with BUSY instead of silently queueing.
 * - The model is loaded lazily (first AI use or explicit "Load model"), never at startup.
 */
class ModelManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val events: AppEvents,
    private val scope: CoroutineScope,
) {
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val prefs = context.getSharedPreferences("music_maker_model", Context.MODE_PRIVATE)

    @Volatile private var model: LlamaModel? = null
    @Volatile private var loadedConfig: LlamaConfig? = null
    @Volatile private var lastError: AppException? = null
    @Volatile private var lastLoadMs: Long = 0
    @Volatile private var lastTokensPerSecond: Float = 0f
    @Volatile var state: ModelState = ModelState.NOT_INSTALLED
        private set

    /** Directory for model files. App-specific external storage: no permission needed. */
    val modelDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    init {
        state = if (installedFile() != null) ModelState.INSTALLED else ModelState.NOT_INSTALLED
    }

    val isDeviceSupported: Boolean
        get() = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /** File name of the active model (the bundled default unless the user imported another GGUF). */
    val activeFileName: String
        get() = prefs.getString(KEY_ACTIVE_FILE, DEFAULT_FILE_NAME) ?: DEFAULT_FILE_NAME

    fun installedFile(): File? {
        val f = File(modelDir, activeFileName)
        return f.takeIf { it.isFile && it.length() > MIN_MODEL_BYTES && isGguf(it) }
    }

    fun status(): JSONObject {
        val file = installedFile()
        val loaded = loadedConfig
        val wanted = desiredConfig()
        val o = JSONObject()
            .put("state", state.name)
            .put("modelName", displayName(activeFileName))
            .put("fileName", activeFileName)
            .put("installed", file != null)
            .put("sizeBytes", file?.length() ?: 0L)
            .put("expectedBytes", DEFAULT_EXPECTED_BYTES)
            .put("loaded", model?.isLoaded == true)
            .put("deviceSupported", isDeviceSupported)
            .put("lastLoadMs", lastLoadMs)
            .put("tokensPerSecond", lastTokensPerSecond.toDouble())
            .put("freeBytes", modelDir.usableSpace)
            .put("reloadRequired", loaded != null && loaded != wanted)
        lastError?.let { if (state == ModelState.ERROR) o.put("error", it.toJson()) }
        return o
    }

    /** Loads the model if needed and returns it. Safe to call from many coroutines at once. */
    suspend fun ensureLoaded(): LlamaModel = loadMutex.withLock {
        model?.takeIf { it.isLoaded }?.let { return@withLock it }
        if (!isDeviceSupported) {
            fail(AppException(ErrorCode.UNSUPPORTED_DEVICE, "On-device AI needs a 64-bit ARM (arm64-v8a) device."))
        }
        val file = installedFile()
            ?: fail(AppException(ErrorCode.MODEL_MISSING, "The AI model is not installed yet."), ModelState.NOT_INSTALLED)
        checkMemory(file)

        val config = desiredConfig()
        setState(ModelState.LOADING)
        val started = SystemClock.elapsedRealtime()
        try {
            val loadedModel = Llama.loadModel(file.absolutePath, config)
            model = loadedModel
            loadedConfig = config
            lastLoadMs = SystemClock.elapsedRealtime() - started
            lastError = null
            setState(ModelState.READY)
            loadedModel
        } catch (e: LlamaException) {
            fail(AppException(ErrorCode.MODEL_LOAD_FAILED, "The model could not be loaded. The file may be damaged, or the device may not have enough free memory.", e))
        } catch (e: OutOfMemoryError) {
            fail(AppException(ErrorCode.OUT_OF_MEMORY, "Not enough memory to load the model. Close other apps and try again.", e))
        } catch (e: LinkageError) {
            fail(AppException(ErrorCode.UNSUPPORTED_DEVICE, "The on-device AI runtime is not available on this device.", e))
        }
    }

    /**
     * Runs [block] with exclusive access to the loaded model.
     * Rejects immediately with BUSY if another generation is running.
     */
    suspend fun <T> withModel(block: suspend (LlamaModel) -> T): T {
        if (inferenceMutex.isLocked) {
            throw AppException(ErrorCode.BUSY, "The AI is already working on something. Wait for it to finish.")
        }
        val m = ensureLoaded()
        return inferenceMutex.withLock {
            setState(ModelState.GENERATING)
            try {
                block(m)
            } catch (e: LlamaException.ModelReleased) {
                model = null
                throw AppException(ErrorCode.GENERATION_FAILED, "The model was unloaded during generation. Try again.", e)
            } catch (e: LlamaException) {
                throw AppException(ErrorCode.GENERATION_FAILED, e.message ?: "Generation failed.", e)
            } catch (e: OutOfMemoryError) {
                throw AppException(ErrorCode.OUT_OF_MEMORY, "The device ran out of memory while generating.", e)
            } finally {
                setState(if (model?.isLoaded == true) ModelState.READY else stateFromDisk())
            }
        }
    }

    fun recordSpeed(tokensPerSecond: Float) {
        if (tokensPerSecond > 0f) lastTokensPerSecond = tokensPerSecond
    }

    /** Frees native model memory. Waits for a running generation to finish first. */
    suspend fun unload() {
        inferenceMutex.withLock {
            loadMutex.withLock {
                val m = model ?: return@withLock
                withContext(Dispatchers.Default) { Llama.releaseModel(m) }
                model = null
                loadedConfig = null
                setState(stateFromDisk())
            }
        }
    }

    suspend fun reload() {
        unload()
        ensureLoaded()
    }

    suspend fun deleteModelFile() {
        unload()
        withContext(Dispatchers.IO) {
            modelDir.listFiles { f -> f.name.endsWith(".gguf") }?.forEach { it.delete() }
        }
        prefs.edit().remove(KEY_ACTIVE_FILE).apply()
        lastError = null
        setState(ModelState.NOT_INSTALLED)
    }

    /** Called by [ModelInstaller] when a validated GGUF file is in [modelDir]. */
    fun onModelInstalled(fileName: String) {
        prefs.edit().putString(KEY_ACTIVE_FILE, fileName).apply()
        lastError = null
        if (model == null) setState(stateFromDisk()) else emitStatus()
    }

    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val shouldRelease = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && settings.ai().unloadInBackground)
        if (shouldRelease && model != null && !inferenceMutex.isLocked) {
            scope.launch { runCatching { unload() } }
        }
    }

    fun emitStatus() = events.emit("model", status())

    private fun desiredConfig(): LlamaConfig {
        val ai = settings.ai()
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (ai.threads > 0) ai.threads else (cores / 2).coerceIn(2, 6)
        return LlamaConfig(
            contextSize = ai.contextSize,
            threads = threads,
            gpuLayers = 0,
            temperature = ai.creativity,
            topP = 0.9f,
            topK = 40,
            seed = -1,
        )
    }

    private fun checkMemory(file: File) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // GGUF weights are memory-mapped, so not all of it must be resident; but a device whose
        // total RAM is below the model size simply cannot run it.
        if (info.totalMem in 1 until file.length() + 512L * 1024 * 1024) {
            fail(AppException(ErrorCode.OUT_OF_MEMORY, "This device does not have enough RAM for this model."))
        }
    }

    private fun stateFromDisk() = if (installedFile() != null) ModelState.INSTALLED else ModelState.NOT_INSTALLED

    private fun fail(e: AppException, newState: ModelState = ModelState.ERROR): Nothing {
        lastError = e
        setState(newState)
        throw e
    }

    private fun setState(s: ModelState) {
        state = s
        emitStatus()
    }

    companion object {
        const val DEFAULT_FILE_NAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val DEFAULT_DOWNLOAD_URL = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        /** Size of the published Q4_K_M file (bytes) - used for space checks and progress. */
        const val DEFAULT_EXPECTED_BYTES = 1_107_409_472L
        private const val MIN_MODEL_BYTES = 50L * 1024 * 1024
        private const val KEY_ACTIVE_FILE = "active_file"

        fun displayName(fileName: String): String = when (fileName) {
            DEFAULT_FILE_NAME -> "Qwen3 1.7B · Q4_K_M"
            else -> fileName.removeSuffix(".gguf")
        }

        /** GGUF files start with the ASCII magic "GGUF". */
        fun isGguf(file: File): Boolean = try {
            RandomAccessFile(file, "r").use { raf ->
                val b = ByteArray(4)
                raf.readFully(b)
                String(b, Charsets.US_ASCII) == "GGUF"
            }
        } catch (e: Exception) {
            false
        }

        fun isGgufHeader(bytes: ByteArray): Boolean =
            bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "GGUF"
    }
}
