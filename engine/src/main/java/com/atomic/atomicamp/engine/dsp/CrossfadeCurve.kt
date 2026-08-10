package com.atomic.atomicamp.engine.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * Gain pair for a crossfade, as a function of how far through the overlap we are.
 *
 * Two tracks playing at once sum together, so the naive choice — fading one down linearly while
 * the other rises linearly — makes the middle of the overlap noticeably quiet. Two uncorrelated
 * signals at 0.5 gain each sum to about 0.707 of full power, not 1.0, and the dip is audible as a
 * sag right at the transition.
 *
 * An equal-power curve keeps the *sum of squares* constant instead, which holds perceived loudness
 * steady across the overlap. Pure arithmetic, so the shape can be tested without audio.
 */
object CrossfadeCurve {

    private const val HALF_PI = Math.PI / 2.0

    /** Gain for the track fading out, at [progress] 0..1 through the overlap. */
    fun outgoingGain(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return cos(t * HALF_PI).toFloat()
    }

    /** Gain for the track fading in, at [progress] 0..1 through the overlap. */
    fun incomingGain(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return sin(t * HALF_PI).toFloat()
    }

    /**
     * Combined power of both tracks at [progress]. Constant at 1.0 across the whole overlap, which
     * is the property that makes the transition sound even.
     */
    fun combinedPower(progress: Float): Float {
        val out = outgoingGain(progress)
        val incoming = incomingGain(progress)
        return out * out + incoming * incoming
    }
}
