package com.glydexstudio.musicmaker.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes mono 16-bit PCM WAV files. */
object WavWriter {
    fun write(out: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)              // PCM chunk size
            putShort(1)             // PCM format
            putShort(1)             // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)  // byte rate
            putShort(2)             // block align
            putShort(16)            // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        out.write(header.array())
        val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        out.write(pcm.array())
    }
}
