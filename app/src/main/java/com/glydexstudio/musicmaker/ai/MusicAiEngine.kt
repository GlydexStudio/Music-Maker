package com.glydexstudio.musicmaker.ai

import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import com.glydexstudio.musicmaker.data.SettingsRepository
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaModel
import org.json.JSONObject

/**
 * The single AI abstraction used by every feature (lyrics, song ideas, promotion).
 * Builds prompts, runs inference through [ModelManager], parses and validates the output,
 * and retries once with a stricter instruction if the model returns malformed JSON.
 */
class MusicAiEngine(
    private val models: ModelManager,
    private val settings: SettingsRepository,
) {
    suspend fun generateLyrics(request: JSONObject): JSONObject {
        val prompt = builder().lyrics(request)
        val punchlines = request.optString("action") == "punchlines"
        return run(prompt) { ResponseParser.lyrics(it, punchlines) }
    }

    suspend fun generateSongIdea(request: JSONObject): JSONObject =
        run(builder().songIdea(request)) { ResponseParser.songIdea(it) }

    suspend fun generatePromotion(request: JSONObject): JSONObject =
        run(builder().promotion(request)) { ResponseParser.promotion(it) }

    private fun builder(): PromptBuilder {
        val ai = settings.ai()
        return PromptBuilder(ai.contextSize, ai.length)
    }

    private suspend fun run(prompt: Prompt, parse: (String) -> JSONObject): JSONObject =
        models.withModel { model ->
            var attempt = prompt
            var lastError: AppException? = null
            for (i in 0 until MAX_ATTEMPTS) {
                val result = complete(model, attempt)
                try {
                    val parsed = parse(ResponseParser.clean(result.first))
                    parsed.put("meta", result.second)
                    return@withModel parsed
                } catch (e: AppException) {
                    lastError = e
                    attempt = builder().strictRetry(prompt)
                }
            }
            throw lastError ?: AppException(ErrorCode.INVALID_RESPONSE, "The AI returned an unreadable answer.")
        }

    private suspend fun complete(model: LlamaModel, prompt: Prompt): Pair<String, JSONObject> {
        val r = Llama.complete(model, prompt.user, prompt.system, prompt.maxTokens)
        models.recordSpeed(r.tokensPerSecond)
        val meta = JSONObject()
            .put("tokens", r.tokensGenerated)
            .put("tokensPerSecond", r.tokensPerSecond.toDouble())
            .put("durationMs", r.promptEvalTimeMs + r.generateTimeMs)
        if (r.text.isBlank()) throw AppException(ErrorCode.GENERATION_FAILED, "The AI returned an empty answer. Try again.")
        return r.text to meta
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
    }
}
