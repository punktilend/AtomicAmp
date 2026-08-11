package com.atomic.atomicamp.engine.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Decides how much gain to apply to even out volume differences between tracks.
 *
 * This exists because the library has no ReplayGain tags — checked across every file — so there is
 * no precomputed loudness to read. Level has to be judged from the audio as it plays.
 *
 * The whole difficulty is *rate*. Correcting quickly is indistinguishable from compression: it
 * pulls down drum hits and pushes up decay tails, and the music breathes audibly. Correcting slowly
 * — on the order of a dB per second — instead behaves like someone easing the volume knob between
 * tracks, which is the goal.
 *
 * Deliberately separated from audio buffer handling so the tuning can be tested directly; a
 * mistuned leveler is far easier to hear than to reason about.
 */
class LoudnessLeveler {

    companion object {
        /**
         * Target RMS, well below full scale. Music averages far under its peaks, so aiming near
         * 1.0 would demand impossible gain and sit permanently against the limiter.
         */
        const val DEFAULT_TARGET_RMS = 0.1f

        /** Bounds on correction. Beyond this, a track is better left as recorded. */
        const val MAX_GAIN_DB = 12f
        const val MIN_GAIN_DB = -12f

        /** How fast gain may move. The single most important value here. */
        const val MAX_GAIN_CHANGE_DB_PER_SEC = 1f

        /** Below this the signal is silence or a fade, and adapting to it would ramp gain up. */
        const val SILENCE_RMS_THRESHOLD = 0.001f

        /** Peaks above this get pulled down, leaving room for boost without clipping. */
        const val LIMITER_CEILING = 0.95f
    }

    var targetRms: Float = DEFAULT_TARGET_RMS

    /** Current gain as a linear multiplier. Starts at unity so playback never opens wrong. */
    var gain: Float = 1f
        private set

    private var gainDb: Float = 0f

    /**
     * Folds a block of audio into the running estimate and advances the gain.
     *
     * @param blockRms RMS of the block just measured.
     * @param elapsedSeconds how much audio that block represents, which bounds how far gain may move.
     */
    fun update(blockRms: Float, elapsedSeconds: Float) {
        // Silence carries no information about how loud the track is. Holding gain steady here is
        // what stops a quiet intro from ramping up and then blasting.
        if (blockRms < SILENCE_RMS_THRESHOLD) return

        val desiredDb = (20f * log10(targetRms / blockRms)).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val maxStep = MAX_GAIN_CHANGE_DB_PER_SEC * elapsedSeconds.coerceAtLeast(0f)

        gainDb += (desiredDb - gainDb).coerceIn(-maxStep, maxStep)
        gainDb = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        gain = 10f.pow(gainDb / 20f)
    }

    /** Back to unity, for a new track or when leveling is switched off. */
    fun reset() {
        gainDb = 0f
        gain = 1f
    }

    /**
     * Applies gain with a soft ceiling. Boosting a quiet track raises its peaks too, and without
     * this they would clip — which is far more audible than the loudness difference being fixed.
     */
    fun applyTo(sample: Float): Float {
        val amplified = sample * gain
        if (abs(amplified) <= LIMITER_CEILING) return amplified
        // Preserve sign, flatten the excess rather than wrapping or hard-clamping to full scale.
        return if (amplified > 0f) LIMITER_CEILING else -LIMITER_CEILING
    }
}

/** Running RMS over a block of samples. */
fun rmsOf(samples: FloatArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) {
        val s = samples[i]
        sum += s.toDouble() * s
    }
    return sqrt(sum / count).toFloat()
}
