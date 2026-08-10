package com.atomic.atomicamp.engine.dsp

/**
 * A gain value that slides toward a target at a fixed rate rather than jumping.
 *
 * Stepping a gain instantly produces a discontinuity in the waveform, which is audible as a click.
 * Ramping over even a few milliseconds removes it.
 *
 * Pure arithmetic with no audio dependencies, so the envelope behaviour can be tested directly.
 */
internal class LinearRamp(initialValue: Float = 1f) {

    var value: Float = initialValue
        private set

    private var target: Float = initialValue
    private var startValue: Float = initialValue

    /** Frames for a full 0..1 traverse; a partial move takes proportionally fewer, keeping rate constant. */
    private var fullTraverseFrames: Int = 1
    private var rampFrames: Int = 1
    private var framesElapsed: Int = 0

    val isAtTarget: Boolean get() = value == target

    /** Sets how long a full 0..1 traverse takes at [sampleRateHz]. */
    fun configure(sampleRateHz: Int, rampMs: Int) {
        val frames = (sampleRateHz.toLong() * rampMs / 1000L).toInt()
        // A zero-length ramp still has to make progress, or the value would never reach target.
        fullTraverseFrames = frames.coerceAtLeast(1)
        restartRamp()
    }

    fun rampTo(newTarget: Float) {
        val clamped = newTarget.coerceIn(0f, 1f)
        if (clamped == target) return
        target = clamped
        restartRamp()
    }

    /** Jumps immediately, e.g. when restarting silent after a seek. */
    fun snapTo(newValue: Float) {
        value = newValue.coerceIn(0f, 1f)
        target = value
        restartRamp()
    }

    /**
     * Advances one audio frame and returns the gain to apply to it.
     *
     * Interpolates from a frame counter rather than accumulating a per-frame step. Repeatedly
     * adding a float step drifts — over a 100ms ramp it lands around 0.99998 instead of 1.0, so a
     * fade-out never becomes exactly silent and "has the fade finished" can never be answered by
     * comparing against the target.
     */
    fun nextFrame(): Float {
        if (value == target) return value

        framesElapsed++
        value = if (framesElapsed >= rampFrames) {
            target
        } else {
            // Integer ratio: reaches exactly 1.0 at the final frame, so value lands exactly on target.
            val progress = framesElapsed.toFloat() / rampFrames.toFloat()
            startValue + (target - startValue) * progress
        }
        return value
    }

    private fun restartRamp() {
        startValue = value
        framesElapsed = 0
        val distance = kotlin.math.abs(target - startValue)
        rampFrames = (fullTraverseFrames * distance).toInt().coerceAtLeast(1)
    }
}
