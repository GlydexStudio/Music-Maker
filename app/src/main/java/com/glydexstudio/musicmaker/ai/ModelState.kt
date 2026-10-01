package com.glydexstudio.musicmaker.ai

/**
 * Lifecycle of the local model.
 * INSTALLED means the GGUF file is on disk but not loaded into memory.
 */
enum class ModelState { NOT_INSTALLED, INSTALLED, LOADING, READY, GENERATING, ERROR }
