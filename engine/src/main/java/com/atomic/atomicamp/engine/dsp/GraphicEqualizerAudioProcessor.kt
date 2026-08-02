package com.atomic.atomicamp.engine.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlin.math.pow

/**
 * Runtime-adjustable N-band graphic equalizer plus a preamp stage, implemented as a Media3
 * [AudioProcessor] so it runs inline in ExoPlayer's real audio pipeline (operating on float PCM)
 * rather than depending on the platform's [android.media.audiofx.Equalizer], which is low
 * precision, inconsistent across OEMs, and unavailable under float/offload output paths.
 *
 * Band gains and the preamp can be changed at any time during playback; this processor is always
 * kept active in the pipeline (see [onConfigure]) so adjustments take effect immediately without
 * restarting playback.
 */
class GraphicEqualizerAudioProcessor : BaseAudioProcessor() {

    companion object {
        val BAND_CENTER_FREQUENCIES_HZ =
            floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val BAND_COUNT = 10
        private const val BAND_Q = 1.41f
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
    }

    private val bandGainsDb = FloatArray(BAND_COUNT)

    @Volatile
    private var preampDb = 0f

    @Volatile
    private var enabled = true

    private var channelCount = 0
    private var sampleRateHz = 0
    private var isFloatFormat = false

    // filters[channel][band]
    private var filters: Array<Array<BiquadFilter>> = emptyArray()

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until BAND_COUNT) return
        bandGainsDb[bandIndex] = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        updateFilterCoefficients()
    }

    fun getBandGain(bandIndex: Int): Float = bandGainsDb.getOrElse(bandIndex) { 0f }

    /** Snapshot of every band gain, in [BAND_CENTER_FREQUENCIES_HZ] order. */
    fun getBandGains(): FloatArray = bandGainsDb.copyOf()

    /**
     * Applies a whole curve at once. Preferred over looping [setBandGain] when applying a preset:
     * coefficients are recomputed a single time rather than once per band.
     */
    fun setBandGains(gainsDb: FloatArray) {
        for (band in 0 until BAND_COUNT) {
            bandGainsDb[band] = gainsDb.getOrElse(band) { 0f }.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
        updateFilterCoefficients()
    }

    fun setPreampGain(gainDb: Float) {
        preampDb = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    fun getPreampGain(): Float = preampDb

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEqEnabled(): Boolean = enabled

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
        filters = Array(channelCount) { Array(BAND_COUNT) { BiquadFilter() } }
        updateFilterCoefficients()
        // Same encoding/rate/channels out as in -- this processor reshapes samples, not format.
        return inputAudioFormat
    }

    private fun updateFilterCoefficients() {
        if (sampleRateHz == 0) return
        for (channelFilters in filters) {
            for (band in 0 until BAND_COUNT) {
                channelFilters[band].setPeaking(
                    sampleRateHz,
                    BAND_CENTER_FREQUENCIES_HZ[band],
                    BAND_Q,
                    bandGainsDb[band],
                )
            }
        }
    }

    override fun onFlush() {
        for (channelFilters in filters) {
            for (filter in channelFilters) {
                filter.reset()
            }
        }
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - position
        if (remaining <= 0 || channelCount == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val preampLinear = 10f.pow(preampDb / 20f)

        if (isFloatFormat) {
            var i = position
            var channel = 0
            while (i < limit) {
                var sample = inputBuffer.getFloat(i)
                if (enabled) {
                    val channelFilters = filters[channel]
                    for (band in 0 until BAND_COUNT) {
                        sample = channelFilters[band].process(sample)
                    }
                    sample *= preampLinear
                }
                outputBuffer.putFloat(sample.coerceIn(-1f, 1f))
                i += 4
                channel = (channel + 1) % channelCount
            }
        } else {
            var i = position
            var channel = 0
            while (i < limit) {
                var sample = inputBuffer.getShort(i) / 32768f
                if (enabled) {
                    val channelFilters = filters[channel]
                    for (band in 0 until BAND_COUNT) {
                        sample = channelFilters[band].process(sample)
                    }
                    sample *= preampLinear
                }
                val clamped = sample.coerceIn(-1f, 1f)
                outputBuffer.putShort((clamped * 32767f).toInt().toShort())
                i += 2
                channel = (channel + 1) % channelCount
            }
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }
}
