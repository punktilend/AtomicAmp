package com.atomic.atomicamp.engine.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Applies a short volume envelope to the output, so playback never starts or stops on a waveform
 * discontinuity.
 *
 * Starting or stopping mid-waveform steps the signal instantly, which is audible as a click through
 * a car's amplifier. Seeks are worse: the equalizer's filters are flushed on a discontinuity, so
 * the first samples after a seek come from cleared filter state.
 *
 * Sits after the equalizer in the chain, so it attenuates the finished signal rather than feeding
 * ramped input into the filters.
 */
class FadeAudioProcessor : BaseAudioProcessor() {

    companion object {
        /** Long enough to remove a click, short enough not to feel like a delay on a skip. */
        const val DEFAULT_FADE_MS = 80
    }

    private val ramp = LinearRamp(initialValue = 1f)

    @Volatile
    var fadeMs: Int = DEFAULT_FADE_MS
        set(value) {
            field = value.coerceAtLeast(0)
            if (sampleRateHz > 0) ramp.configure(sampleRateHz, field)
        }

    private var channelCount = 0
    private var sampleRateHz = 0
    private var isFloatFormat = false

    /** True once a fade-out has fully reached silence — the cue to actually pause. */
    val isSilent: Boolean get() = ramp.value == 0f

    fun fadeIn() {
        ramp.rampTo(1f)
    }

    fun fadeOut() {
        ramp.rampTo(0f)
    }

    /** Drops to silence and fades up, for a seek or track change where continuity is already lost. */
    fun restartFromSilence() {
        ramp.snapTo(0f)
        ramp.rampTo(1f)
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
        ramp.configure(sampleRateHz, fadeMs)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - position
        if (remaining <= 0 || channelCount == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val bytesPerSample = if (isFloatFormat) 4 else 2

        var i = position
        var channel = 0
        var gain = ramp.value
        while (i < limit) {
            // One ramp step per frame, not per sample, so channels stay in step with each other.
            if (channel == 0) gain = ramp.nextFrame()

            if (isFloatFormat) {
                outputBuffer.putFloat(inputBuffer.getFloat(i) * gain)
            } else {
                outputBuffer.putShort((inputBuffer.getShort(i) * gain).toInt().toShort())
            }
            i += bytesPerSample
            channel = (channel + 1) % channelCount
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }
}
