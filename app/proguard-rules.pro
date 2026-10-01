# Release builds are not minified (see app/build.gradle.kts).
# If you enable R8, keep the llama-android JNI bridge:
-keep class dev.ffmpegkit.llama.** { *; }
