package com.atomic.atomicamp.engine

import android.os.Handler
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.atomic.atomicamp.engine.dsp.FadeAudioProcessor

/**
 * Wraps the player so transport changes are faded rather than stepped.
 *
 * Pausing is deferred until the fade-out has actually reached the speakers: stopping the player
 * immediately would cut the signal mid-waveform and defeat the fade entirely. Resuming and seeking
 * start from silence and ramp up, which also covers the equalizer's filter state being flushed on
 * a discontinuity.
 *
 * The [MediaSession][androidx.media3.session.MediaSession] wraps this rather than the bare
 * ExoPlayer, so the behaviour applies to every controller — notification buttons and steering-wheel
 * controls included, not just this app's UI.
 */
internal class FadingPlayer(
    player: Player,
    private val fade: FadeAudioProcessor,
    private val handler: Handler,
) : ForwardingPlayer(player) {

    private var pendingPause: Runnable? = null

    override fun play() {
        cancelPendingPause()
        fade.fadeIn()
        super.play()
    }

    override fun pause() {
        if (!isPlaying) {
            super.pause()
            return
        }
        fade.fadeOut()
        schedulePause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        // Controllers outside this app toggle playback through this rather than play()/pause().
        if (playWhenReady) play() else pause()
    }

    override fun seekTo(positionMs: Long) {
        super.seekTo(positionMs)
        fade.restartFromSilence()
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        super.seekTo(mediaItemIndex, positionMs)
        fade.restartFromSilence()
    }

    override fun seekToNextMediaItem() {
        super.seekToNextMediaItem()
        fade.restartFromSilence()
    }

    override fun seekToPreviousMediaItem() {
        super.seekToPreviousMediaItem()
        fade.restartFromSilence()
    }

    override fun seekToNext() {
        super.seekToNext()
        fade.restartFromSilence()
    }

    override fun seekToPrevious() {
        super.seekToPrevious()
        fade.restartFromSilence()
    }

    override fun stop() {
        cancelPendingPause()
        super.stop()
    }

    override fun release() {
        cancelPendingPause()
        super.release()
    }

    /**
     * Waits for the audible fade to finish before pausing. Slightly longer than the ramp itself,
     * since already-buffered audio has to drain through the sink first.
     */
    private fun schedulePause() {
        cancelPendingPause()
        val runnable = Runnable {
            pendingPause = null
            super.pause()
        }
        pendingPause = runnable
        handler.postDelayed(runnable, fade.fadeMs.toLong() + SINK_DRAIN_MARGIN_MS)
    }

    private fun cancelPendingPause() {
        pendingPause?.let(handler::removeCallbacks)
        pendingPause = null
    }

    private companion object {
        const val SINK_DRAIN_MARGIN_MS = 60L
    }
}
