package com.atomic.atomicamp.engine.dsp

/**
 * A named set of band gains, one per band of [GraphicEqualizerAudioProcessor], ordered to match
 * [GraphicEqualizerAudioProcessor.BAND_CENTER_FREQUENCIES_HZ] (31Hz .. 16kHz).
 */
class EqPreset(val name: String, gainsDb: FloatArray) {

    /** Defensive copy: callers must not be able to mutate a shared preset curve. */
    private val gains: FloatArray = gainsDb.copyOf()

    val gainsDb: FloatArray get() = gains.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is EqPreset && name == other.name && gains.contentEquals(other.gains))

    override fun hashCode(): Int = 31 * name.hashCode() + gains.contentHashCode()

    override fun toString(): String = name
}

/**
 * Built-in preset curves. These are conventional graphic-EQ shapes rather than measurement-derived
 * corrections -- per-headphone AutoEq-style profiles would need a target-response database and are
 * a separate concern.
 */
object EqPresets {

    const val CUSTOM = "Custom"

    val FLAT = EqPreset("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))

    val ALL: List<EqPreset> = listOf(
        FLAT,
        //                                  31   62  125  250  500   1k   2k   4k   8k  16k
        EqPreset("Rock", floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 2f, 4f, 5f, 5f)),
        EqPreset("Pop", floatArrayOf(-1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1f, -1f)),
        EqPreset("Jazz", floatArrayOf(4f, 3f, 1f, 2f, -1f, -1f, 0f, 1f, 3f, 4f)),
        EqPreset("Classical", floatArrayOf(4f, 3f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 4f)),
        EqPreset("Bass Boost", floatArrayOf(8f, 7f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Treble Boost", floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f)),
        EqPreset("Vocal", floatArrayOf(-2f, -2f, 0f, 3f, 5f, 5f, 4f, 2f, 0f, -1f)),
        EqPreset("Loudness", floatArrayOf(6f, 5f, 2f, 0f, -1f, -1f, 0f, 2f, 5f, 6f)),
    )

    fun byName(name: String): EqPreset? = ALL.firstOrNull { it.name == name }

    /** The preset whose curve matches [gainsDb] exactly, or null when the user has gone off-preset. */
    fun matching(gainsDb: FloatArray): EqPreset? = ALL.firstOrNull { it.gainsDb.contentEquals(gainsDb) }
}
