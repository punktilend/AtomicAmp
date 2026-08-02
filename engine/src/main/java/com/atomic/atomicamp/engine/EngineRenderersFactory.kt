package com.atomic.atomicamp.engine

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.atomic.atomicamp.engine.dsp.GraphicEqualizerAudioProcessor

/**
 * Installs [equalizer] directly into ExoPlayer's audio sink pipeline. This is the extension
 * point that lets our own DSP sit inline in the real playback path, instead of depending on the
 * platform's [android.media.audiofx.Equalizer] attached via audio session id.
 */
internal class EngineRenderersFactory(
    context: Context,
    private val equalizer: GraphicEqualizerAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(equalizer))
            .build()
    }
}
