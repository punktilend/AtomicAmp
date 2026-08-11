package com.atomic.atomicamp.engine

import android.content.Context

/**
 * The engine's settings that the UI can change but the audio pipeline never reads.
 *
 * Everything the pipeline consumes -- EQ curve, preamp, leveller -- travels as a session command
 * so the running player hears about it immediately. Resume-on-boot has no such urgency: its only
 * reader is [BootReceiver] on the next power-up, so it goes straight to preferences and skips the
 * session entirely.
 *
 * This exists so [EqualizerSettingsStore] can stay internal to the engine rather than being made
 * public just to expose one boolean.
 */
object PlaybackPreferences {

    fun resumeOnBoot(context: Context): Boolean =
        EqualizerSettingsStore(context).resumeOnBoot

    fun setResumeOnBoot(context: Context, enabled: Boolean) {
        EqualizerSettingsStore(context).resumeOnBoot = enabled
    }
}
