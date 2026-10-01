package com.glydexstudio.musicmaker.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.glydexstudio.musicmaker.core.AppEvents
import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import com.glydexstudio.musicmaker.data.BeatPattern
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Real-time beat playback on a dedicated audio thread using a streaming [AudioTrack].
 * The thread lives while the sequencer is playing or while preview sounds are ringing out,
 * then releases the AudioTrack. Pattern edits are applied live without restarting.
 */
class BeatEngine(private val events: AppEvents) {

    private val lock = Any()
    private val sequencer = Sequencer(BeatPattern.from(null))
    private val pendingTriggers = ConcurrentLinkedQueue<String>()
    @Volatile private var thread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var pendingReset: Int? = null
    @Volatile private var latencyMs = 0L

    init {
        sequencer.onStep = { step, frameOffset ->
            val offsetMs = frameOffset * 1000L / DrumSynth.SAMPLE_RATE
            events.emitDelayed("beatStep", JSONObject().put("step", step), latencyMs + offsetMs)
        }
    }

    fun state(): JSONObject = JSONObject()
        .put("playing", playing)
        .put("step", sequencer.nextStep)
        .put("bpm", sequencer.pattern.bpm)

    /** Starts (or resumes) playback of [pattern]. */
    fun play(pattern: BeatPattern) {
        synchronized(lock) {
            sequencer.pattern = pattern
            playing = true
            ensureThread()
        }
        emitState()
    }

    /** Applies edits (steps, BPM, mutes) while playing. */
    fun update(pattern: BeatPattern) {
        sequencer.pattern = pattern
    }

    /** Stops advancing but keeps the position; ringing sounds decay naturally. */
    fun pause() {
        playing = false
        emitState()
    }

    /** Stops and rewinds to step 1. */
    fun stop() {
        playing = false
        pendingReset = 0
        emitState()
        events.emit("beatStep", JSONObject().put("step", -1))
    }

    /** Plays a single sound immediately (tap on a track label or a step). */
    fun preview(track: String) {
        if (track !in BeatPattern.TRACKS) throw AppException(ErrorCode.BAD_REQUEST, "Unknown track.")
        pendingTriggers.add(track)
        synchronized(lock) { ensureThread() }
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread(::audioLoop, "MusicMaker-Audio").apply { start() }
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(
            DrumSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return fail("Audio output is not available.")
        val bufferBytes = maxOf(minBuf, 4096)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(DrumSynth.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            return fail("Could not open audio output.")
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return fail("Could not open audio output.")
        }
        latencyMs = (bufferBytes / 2) * 1000L / DrumSynth.SAMPLE_RATE
        val frames = 256
        val floatBuf = FloatArray(frames)
        val pcm = ShortArray(frames)
        try {
            track.play()
            var idleChunks = 0
            while (true) {
                pendingReset?.let {
                    sequencer.reset(it)
                    pendingReset = null
                }
                while (true) {
                    val t = pendingTriggers.poll() ?: break
                    sequencer.trigger(t, 0.9f)
                }
                if (playing && !sequencer.sequencing) sequencer.reset(sequencer.nextStep)
                sequencer.sequencing = playing
                sequencer.render(floatBuf, frames)
                for (i in 0 until frames) pcm[i] = (floatBuf[i] * Short.MAX_VALUE).toInt().toShort()
                val w = track.write(pcm, 0, frames)
                if (w < 0) {
                    fail("Audio playback failed.")
                    break
                }
                // Exit after ~0.3 s of silence when nothing is playing.
                if (!playing && !sequencer.hasActiveVoices && pendingTriggers.isEmpty()) {
                    if (++idleChunks > 50) break
                } else {
                    idleChunks = 0
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            sequencer.clearVoices()
            synchronized(lock) {
                if (thread === Thread.currentThread()) thread = null
                // A play/preview request may have arrived while we were shutting down.
                if (playing || pendingTriggers.isNotEmpty()) ensureThread()
            }
        }
    }

    private fun fail(message: String) {
        playing = false
        events.emit("beatError", AppException(ErrorCode.AUDIO, message).toJson())
        emitState()
    }

    private fun emitState() = events.emit("beatState", state())
}
