package com.glydexstudio.musicmaker

import android.app.Application
import com.glydexstudio.musicmaker.ai.ModelInstaller
import com.glydexstudio.musicmaker.ai.ModelManager
import com.glydexstudio.musicmaker.ai.MusicAiEngine
import com.glydexstudio.musicmaker.audio.BeatEngine
import com.glydexstudio.musicmaker.core.AppEvents
import com.glydexstudio.musicmaker.data.CoverStore
import com.glydexstudio.musicmaker.data.SettingsRepository
import com.glydexstudio.musicmaker.data.SongRepository
import com.glydexstudio.musicmaker.share.ShareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. Every native subsystem exists exactly once per process,
 * so there is only ever one model instance, one audio engine and one storage writer.
 */
class AppContainer(app: Application) {
    /** Process-wide scope for work that must outlive a single Activity (model load, installs). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val events = AppEvents()
    val settings = SettingsRepository(app)
    val covers = CoverStore(app)
    val songs = SongRepository(app, covers)
    val modelManager = ModelManager(app, settings, events, appScope)
    val modelInstaller = ModelInstaller(app, modelManager, events, appScope)
    val aiEngine = MusicAiEngine(modelManager, settings)
    val beatEngine = BeatEngine(events)
    val shareManager = ShareManager(app)
}
