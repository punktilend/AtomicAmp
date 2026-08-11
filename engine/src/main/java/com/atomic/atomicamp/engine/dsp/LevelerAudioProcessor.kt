package com.atomic.atomicamp.engine.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Evens out volume differences between tracks, using [LoudnessLeveler] for the decision and this
 * class only for measuring and applying it to buffers.
 *
 * Sits *after* the equalizer, so it levels the signal as actually heard: a heavy bass boost changes
 * loudness considerably, and measuring before the filters would ignore that.
 */
class LevelerAudioProcessor : BaseAudioProcessor() {

    private val leveler = LoudnessLeveler()

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) leveler.reset()
        }

    private var channelCount = 0
    private var sampleRateHz = 0
    private var isFloatFormat = false

    /** Reused between buffers so measurement doesn't allocate on the audio path. */
    private var scratch = FloatArray(0)

    val currentGain: Float get() = leveler.gain

    /** New track: start judging its level afresh rather than inheriting the previous one's. */
    fun resetForNewTrack() {
        leveler.reset()
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRateHz = inputAudioFormat.sampleRate
        isFloatFormat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - position
        if (remaining <= 0 || channelCount == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val bytesPerSample = if (isFloatFormat) 4 else 2
        val sampleCount = remaining / bytesPerSample

        if (!enabled) {
            // Pass through untouched rather than multiplying by a unity gain.
            outputBuffer.put(inputBuffer)
            inputBuffer.position(limit)
            outputBuffer.flip()
            return
        }

        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)

        var index = 0
        var i = position
        while (i < limit) {
            scratch[index++] = if (isFloatFormat) {
                inputBuffer.getFloat(i)
            } else {
                inputBuffer.getShort(i) / 32768f
            }
            i += bytesPerSample
        }

        // Advance the gain by however much audio this buffer represents, so the rate limit is in
        // real seconds regardless of buffer size.
        val frames = sampleCount / channelCount
        val elapsedSeconds = if (sampleRateHz > 0) frames.toFloat() / sampleRateHz else 0f
        leveler.update(rmsOf(scratch, sampleCount), elapsedSeconds)

        for (s in 0 until sampleCount) {
            val leveled = leveler.applyTo(scratch[s])
            if (isFloatFormat) {
                outputBuffer.putFloat(leveled)
            } else {
                outputBuffer.putShort((leveled * 32767f).toInt().toShort())
            }
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }
}
