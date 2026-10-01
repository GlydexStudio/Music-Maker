# Music Maker

**Glydex Studio** · package `com.glydexstudio.musicmaker`

A mobile music studio with an on-device AI companion. Workflow: **Idea → Lyrics → Beat → Song → Promotion**, saved locally, fully offline once the AI model is installed.

The UI is HTML/CSS/vanilla JavaScript in a local WebView. AI, storage, audio, files and sharing run in native Kotlin.

---

## Build

Toolchain (already set in the project, nothing to install manually):

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.10.1 |
| Gradle (wrapper) | 8.11.1 |
| Kotlin | 2.1.21 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| JDK | 17+ (Code On The Go ships JDK 21) |

### Code On The Go
1. Clone the repo or copy the `MusicMaker` folder onto the phone.
2. Open the folder as a project in Code On The Go.
3. The first build must be **online** (Gradle downloads AGP, Kotlin and the 4 dependencies). After that, offline builds work.
4. Run **assembleDebug**. The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`.

### Termux
```sh
./gradlew assembleDebug
```
(Termux needs an Android SDK and `ANDROID_HOME` / `local.properties` set.)

### GitHub Actions
Every push builds a debug APK (`.github/workflows/build.yml`). Download it from the run's **Artifacts**.

This exact project was compiled with `./gradlew assembleDebug` (BUILD SUCCESSFUL, no Kotlin warnings) and `lintDebug` (0 errors).

---

## Dependencies (and why)

| Dependency | Why |
|---|---|
| `dev.ffmpegkit-maintained:llama-android:0.1.1` | Prebuilt llama.cpp for Android (arm64-v8a `.so` files included). Runs GGUF models on-device with **no NDK/CMake build**. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Keeps model loading, inference and storage off the UI thread. |
| `androidx.activity:activity-ktx` | Edge-to-edge, back handling, system pickers (image / file / save-as) without permissions. |
| `androidx.webkit:webkit` | `WebViewAssetLoader` (serves the UI from a secure local `https://` origin) and `addWebMessageListener` (bridge restricted to that origin). |

No React, Vite, npm, Node, Tailwind, CDN, Python, CMake or NDK.

---

## The AI model

Model: **Qwen3-1.7B-Q4_K_M.gguf** (1,107,409,472 bytes ≈ 1.03 GiB).

The model is **not bundled** in the APK (an APK that large isn't practical). Settings → AI gives two options:

1. **Download.** Uses Android DownloadManager (shows a notification, keeps going if the app is closed, resumes when the connection comes back) from
   `https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf`
2. **Import .gguf.** Pick a model file already on the phone via the system file picker (no storage permission needed).

Both paths check the GGUF header and free space. The file is stored in the app's private external folder (`Android/data/com.glydexstudio.musicmaker/files/models/`).

The model loads **lazily**: the first time you use an AI feature, or when you tap "Load model". It never blocks startup.

---

## Architecture

```
WebView (assets/web)            Native (Kotlin)
──────────────────────          ───────────────────────────────────────────
index.html / css / js  ──msg──▶ MusicMakerBridge ── whitelisted dispatch
   native.js (client)  ◀─evt──      │
                                    ├── ai/MusicAiEngine ── PromptBuilder, ResponseParser
                                    │        └── ai/ModelManager ── llama-android (one model)
                                    ├── ai/ModelInstaller (download / import)
                                    ├── data/SongRepository (JSON files, atomic writes)
                                    ├── data/SettingsRepository (SharedPreferences JSON)
                                    ├── data/CoverStore (cover images)
                                    ├── audio/BeatEngine ── Sequencer ── DrumSynth
                                    └── share/ShareManager (share sheet, clipboard, export)
```

### Native files (`app/src/main/java/com/glydexstudio/musicmaker/`)
| File | Responsibility |
|---|---|
| `MusicMakerApp.kt`, `AppContainer.kt` | Process singletons (one model, one audio engine, one store) |
| `ui/MainActivity.kt` | WebView shell, insets/keyboard, back button, pickers, lifecycle |
| `bridge/MusicMakerBridge.kt` | The only JS↔native door; whitelisted methods, JSON protocol |
| `ai/ModelManager.kt` | Model state machine, single-load lock, inference lock, memory handling |
| `ai/ModelInstaller.kt` | DownloadManager download + SAF import with progress and validation |
| `ai/MusicAiEngine.kt` | Shared engine for lyrics, ideas and promotion; one retry on bad JSON |
| `ai/PromptBuilder.kt` | All prompts, schemas, language rules, token budgets |
| `ai/ResponseParser.kt` | Tolerant JSON extraction + strict field validation |
| `data/SongRepository.kt` | Song projects, the source of truth (`files/songs/<id>.json`) |
| `data/BeatPattern.kt` | Validated 5×16 pattern shared by storage, playback and export |
| `audio/DrumSynth.kt` | Synthesizes kick, snare, hi-hat, clap, 808 (no sample files) |
| `audio/Sequencer.kt` | Sample-accurate sequencer + mixer (same code for live and WAV) |
| `audio/BeatEngine.kt` | Real-time AudioTrack thread, live pattern edits, preview sounds |
| `audio/WavWriter.kt` | 16-bit PCM WAV export |
| `share/ShareManager.kt` | Share sheet, clipboard, song text sheet, FileProvider |

### Web files (`app/src/main/assets/web/`)
`index.html`, `css/style.css`, `css/animations.css`, and in `js/`: `app.js` (boot), `native.js` (bridge client), `state.js`, `navigation.js` (tabs + stack router), `ui.js` (components), `theme.js`, `data.js`, `projects.js`, `home.js`, `songs.js`, `create.js`, `lyrics.js`, `song-idea.js`, `beat.js`, `promotion.js`, `song-studio.js`, `settings.js`, `components/model-status.js`, `components/beat-editor.js`, `components/song-picker.js`.

### Model states
`NOT_INSTALLED` · `INSTALLED` (file on disk, not in memory) · `LOADING` · `READY` · `GENERATING` · `ERROR`.
`INSTALLED` was added to the five states in the spec because "on disk but not loaded" is a real, separate state once loading is lazy.

### Bridge protocol
JS posts `{"id","method","payload"}`. Native replies `{"type":"result","id","ok","data"|"error"}` and pushes events `{"type":"event","name","data"}` (`model`, `install`, `beatStep`, `beatState`, `beatError`, `back`, `keyboard`, `systemTheme`, `resume`, `toast`).

Methods: `getAppInfo, getModelStatus, loadModel, reloadModel, unloadModel, deleteModel, downloadModel, importModel, cancelModelInstall, generateLyrics, generateSongIdea, generatePromotion, getSongs, getSong, saveSong, deleteSong, duplicateSong, renameSong, setFavorite, saveBeat, loadBeat, addPromotion, pickCoverImage, resetCover, deleteAllProjects, resetAppData, getStorageInfo, playBeat, updateBeat, pauseBeat, stopBeat, previewSound, getBeatState, copyText, shareText, shareSong, shareBeat, exportSong, getSettings, saveSettings, setSystemTheme, exitApp`.

### Security
- The UI is served from `https://appassets.androidplatform.net/assets/web/` via `WebViewAssetLoader`. File and content access are off.
- Every request to another host is blocked, and so is every navigation away from the app.
- The bridge is injected with `addWebMessageListener` for that one origin only. No `addJavascriptInterface` and no reflection. Unknown methods are rejected.
- The CSP in `index.html` blocks inline scripts, `eval`, and remote content.
- Model output is shown with `textContent` and never parsed as HTML.

### Structured AI output
Every operation asks for one JSON object with a fixed schema (lyrics `{title, lyrics}`, punchlines `{punchlines[]}`, idea `{title, concept, story, hook, verseIdeas[], mood, genre, bpm, key, beatDirection}`, promotion `{text, hashtags[]}`). `ResponseParser` cleans up the usual model defects: `<think>` blocks, code fences, raw newlines inside strings, trailing commas. It then validates and clamps each field (BPM 60–200, key normalized to `Am`/`F#` form, hashtags cleaned). If the output can't be used, the engine retries once with a stricter instruction and then shows a friendly error. It never crashes. Qwen3's reasoning mode is turned off with `/no_think` to keep answers short.

### Languages
English, Română and Magyar can be picked for every AI request, with a default set in Settings. Prompts tell the model to write in that language and keep JSON keys in English. The app's own interface text is English.

---

## Known limitations

- **64-bit ARM only.** llama-android ships `arm64-v8a` libraries only, so the AI is disabled on 32-bit or x86 devices (the app explains why). Everything else still works there.
- **A generation can't be cancelled mid-way.** llama-android 0.1.1 has no cancel call, so the UI doesn't offer one. Each run has a bounded token budget instead. A second request while one is running is rejected (`BUSY`), never queued behind it.
- **No token streaming.** The library returns the full answer at once, so the UI shows a progress overlay with elapsed time.
- **Creativity, threads and context size apply when the model loads.** Temperature is fixed in `LlamaConfig` at load time. Settings shows "Reload to apply" when they change.
- **CPU inference only.** GPU offload requires the library's Pro build.
- **The beat engine is a drum machine**, not a DAW. It has no vocal recording or multitrack audio, and the spec doesn't ask for them.
- **Release signing:** `assembleRelease` needs your own keystore (`signingConfigs`). Debug builds install directly.
# Music-Maker
