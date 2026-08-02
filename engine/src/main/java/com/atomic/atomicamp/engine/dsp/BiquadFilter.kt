package com.atomic.atomicamp.engine.dsp

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Second-order IIR peaking-EQ filter (RBJ Audio EQ Cookbook), Direct Form I.
 * One instance is needed per audio channel since filter state is per-channel.
 */
internal class BiquadFilter {

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    // Direct Form I state: previous two input and output samples.
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    /** Configures this filter as an RBJ peaking-EQ band. */
    fun setPeaking(sampleRateHz: Int, centerFreqHz: Float, q: Float, gainDb: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = (2.0 * Math.PI * centerFreqHz / sampleRateHz).toFloat()
        val cosW0 = cos(w0.toDouble()).toFloat()
        val sinW0 = sin(w0.toDouble()).toFloat()
        val alpha = sinW0 / (2f * q)

        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW0) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha / a) / a0
    }

    /** Clears filter memory. Call on seek/flush to avoid clicks from stale state. */
    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    fun process(x0: Float): Float {
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
        return y0
    }
}
