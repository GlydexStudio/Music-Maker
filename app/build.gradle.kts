plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glydexstudio.musicmaker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glydexstudio.musicmaker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // llama-android ships prebuilt native libraries for arm64-v8a only.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Keep .so files uncompressed so they can be mapped directly from the APK.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // On-device LLM inference (prebuilt llama.cpp, no NDK/CMake needed).
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    // Coroutines for off-main-thread model loading, inference and storage.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ComponentActivity: edge-to-edge, back dispatcher, activity result pickers.
    implementation("androidx.activity:activity-ktx:1.9.3")
    // WebViewAssetLoader (local https origin) + origin-restricted WebMessageListener bridge.
    implementation("androidx.webkit:webkit:1.12.1")

    // JVM unit tests for parsing, validation and the sequencer (not packaged in the APK).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
