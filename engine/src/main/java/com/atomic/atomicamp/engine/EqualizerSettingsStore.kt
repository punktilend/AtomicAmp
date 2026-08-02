package com.atomic.atomicamp.engine

import android.content.Context
import com.atomic.atomicamp.engine.dsp.EqPresets
import com.atomic.atomicamp.engine.dsp.GraphicEqualizerAudioProcessor

/**
 * Persists equalizer state so it survives the playback service being torn down -- on a head unit
 * the process is killed routinely (ignition off), and losing the user's curve every time would
 * make the EQ effectively useless.
 *
 * Backed by [android.content.SharedPreferences] rather than DataStore: the payload is a dozen
 * primitives written on user interaction, so the extra dependency and coroutine plumbing buy
 * nothing here.
 */
internal class EqualizerSettingsStore(context: Context) {

    private companion object {
        const val PREFS_NAME = "atomicamp_eq"
        const val KEY_ENABLED = "enabled"
        const val KEY_PREAMP = "preamp_db"
        const val KEY_PRESET = "preset_name"
        const val KEY_BAND_PREFIX = "band_"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Name of the last applied preset, or [EqPresets.CUSTOM] once bands are hand-adjusted. */
    var presetName: String
        get() = prefs.getString(KEY_PRESET, EqPresets.FLAT.name) ?: EqPresets.FLAT.name
        private set(value) {
            prefs.edit().putString(KEY_PRESET, value).apply()
        }

    /** Restores saved state into [equalizer]. Defaults to a flat, enabled EQ on first run. */
    fun loadInto(equalizer: GraphicEqualizerAudioProcessor) {
        val gains = FloatArray(GraphicEqualizerAudioProcessor.BAND_COUNT) { band ->
            prefs.getFloat(KEY_BAND_PREFIX + band, 0f)
        }
        equalizer.setBandGains(gains)
        equalizer.setPreampGain(prefs.getFloat(KEY_PREAMP, 0f))
        equalizer.setEnabled(prefs.getBoolean(KEY_ENABLED, true))
    }

    fun save(equalizer: GraphicEqualizerAudioProcessor, presetName: String = this.presetName) {
        val editor = prefs.edit()
        equalizer.getBandGains().forEachIndexed { band, gain ->
            editor.putFloat(KEY_BAND_PREFIX + band, gain)
        }
        editor.putFloat(KEY_PREAMP, equalizer.getPreampGain())
        editor.putBoolean(KEY_ENABLED, equalizer.isEqEnabled())
        editor.putString(KEY_PRESET, presetName)
        editor.apply()
    }
}
