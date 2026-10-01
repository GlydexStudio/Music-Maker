package com.glydexstudio.musicmaker.core

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tiny native event bus. Subsystems emit events (model state, download progress,
 * beat playhead...) and the WebView bridge forwards them to JavaScript.
 * Listeners are always invoked on the main thread.
 */
class AppEvents {
    fun interface Listener {
        fun onEvent(name: String, data: JSONObject)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun emit(name: String, data: JSONObject = JSONObject()) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch(name, data)
        } else {
            main.post { dispatch(name, data) }
        }
    }

    fun emitDelayed(name: String, data: JSONObject, delayMs: Long) {
        main.postDelayed({ dispatch(name, data) }, delayMs)
    }

    private fun dispatch(name: String, data: JSONObject) {
        listeners.forEach { it.onEvent(name, data) }
    }
}
