package com.glydexstudio.musicmaker

import android.app.Application

/** Application entry point. Owns the single [AppContainer] for the process. */
class MusicMakerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.modelManager.onTrimMemory(level)
    }
}
