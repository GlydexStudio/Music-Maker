package com.glydexstudio.musicmaker.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.glydexstudio.musicmaker.BuildConfig
import com.glydexstudio.musicmaker.MusicMakerApp
import com.glydexstudio.musicmaker.R
import com.glydexstudio.musicmaker.bridge.BridgeHost
import com.glydexstudio.musicmaker.bridge.ExportMime
import com.glydexstudio.musicmaker.bridge.MusicMakerBridge
import com.glydexstudio.musicmaker.data.CoverStore
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File

/**
 * Thin native shell: hosts the local WebView UI, wires the bridge, handles insets,
 * back navigation, system pickers and lifecycle. No UI or business logic lives here.
 */
class MainActivity : ComponentActivity(), BridgeHost {

    private val container by lazy { (application as MusicMakerApp).container }
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var bridge: MusicMakerBridge? = null
    private var keyboardVisible = false

    private var imageResult: CompletableDeferred<Uri?>? = null
    private var modelResult: CompletableDeferred<Uri?>? = null
    private var documentResult: CompletableDeferred<Uri?>? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        imageResult?.complete(it)
    }
    private val pickModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        modelResult?.complete(it)
    }
    private val createTextLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(ExportMime.TEXT)) {
        documentResult?.complete(it)
    }
    private val createJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(ExportMime.JSON)) {
        documentResult?.complete(it)
    }
    private val createWavLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(ExportMime.WAV)) {
        documentResult?.complete(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.mm_background)) }
        setContentView(root)
        applyInsets()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showFatal(getString(R.string.webview_missing))
            return
        }
        createWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The web UI owns navigation; it calls exitApp() when at the root.
                val b = bridge
                if (b != null && b.isConnected) b.emit("back") else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/covers/", WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, CoverStore.DIR_NAME)))
            .build()

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val view = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                // Respect the user's system font size, within a range the layout supports.
                textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(85, 130)
            }
            webViewClient = LocalOnlyClient(loader)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) Log.d("MusicMakerWeb", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
        }
        val newBridge = MusicMakerBridge(this, container, this)
        newBridge.attach(view)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = view
        bridge = newBridge
        view.loadUrl(START_URL)
    }

    private fun destroyWebView() {
        bridge?.detach()
        bridge = null
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
    }

    /** Only serves bundled assets; every other navigation is blocked. */
    private inner class LocalOnlyClient(private val loader: WebViewAssetLoader) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (request.url.host == MusicMakerBridge.ORIGIN_HOST) {
                loader.shouldInterceptRequest(request.url)
            } else {
                WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
            }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.host != MusicMakerBridge.ORIGIN_HOST

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer crashed or was killed for memory: rebuild the UI instead of crashing.
            destroyWebView()
            if (!isFinishing) createWebView()
            return true
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible != keyboardVisible) {
                keyboardVisible = imeVisible
                bridge?.emit("keyboard", JSONObject().put("visible", imeVisible))
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun showFatal(message: String) {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(64, 64, 64, 64)
        })
    }

    // ------------------------------------------------------------------ BridgeHost

    override suspend fun pickImage(): Uri? {
        val d = CompletableDeferred<Uri?>().also { imageResult = it }
        pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        return d.await()
    }

    override suspend fun pickModelFile(): Uri? {
        val d = CompletableDeferred<Uri?>().also { modelResult = it }
        pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        return d.await()
    }

    override suspend fun createDocument(fileName: String, mime: String): Uri? {
        val d = CompletableDeferred<Uri?>().also { documentResult = it }
        when (mime) {
            ExportMime.JSON -> createJsonLauncher.launch(fileName)
            ExportMime.WAV -> createWavLauncher.launch(fileName)
            else -> createTextLauncher.launch(fileName)
        }
        return d.await()
    }

    override fun startShare(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            bridge?.emit("toast", JSONObject().put("message", "No app available to share with."))
        }
    }

    override fun setSystemBarsLight(light: Boolean) {
        runOnUiThread {
            val bg = if (light) R.color.mm_background_light else R.color.mm_background
            root.setBackgroundColor(getColor(bg))
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    override fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun exitApp() {
        finish()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webView?.settings?.textZoom = (newConfig.fontScale * 100).toInt().coerceIn(85, 130)
        bridge?.emit("systemTheme", JSONObject().put("dark", isSystemDark()))
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        bridge?.emit("resume")
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Leaving the app stops the beat; the sequencer is a studio tool, not a background player.
        container.beatEngine.pause()
        super.onStop()
    }

    override fun onDestroy() {
        imageResult?.complete(null)
        modelResult?.complete(null)
        documentResult?.complete(null)
        destroyWebView()
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://appassets.androidplatform.net/assets/web/index.html"
    }
}
