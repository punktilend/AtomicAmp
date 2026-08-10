package com.atomic.atomicamp.engine

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.atomic.atomicamp.engine.dsp.CrossfadeCurve
import com.atomic.atomicamp.engine.dsp.GraphicEqualizerAudioProcessor

/**
 * Overlaps the end of one track with the start of the next.
 *
 * ExoPlayer decodes a single stream at a time, so a real overlap needs a second player. Rather
 * than putting both behind a facade that re-implements queue handling, the primary player keeps
 * owning the timeline, queue and media session exactly as before — the second player only carries
 * the *tail* of the outgoing track while the primary jumps early to the next one. Nothing about
 * resume, playlists, or notification controls has to change.
 *
 * The tail player deliberately does not take audio focus: it is the same logical playback as the
 * primary, and requesting focus twice would make the system think two apps were playing.
 */
internal class CrossfadeController(
    private val context: Context,
    private val primary: ExoPlayer,
    private val primaryEqualizer: GraphicEqualizerAudioProcessor,
    private val handler: Handler,
) {

    private companion object {
        /** How often to check whether a track is near its end. */
        const val WATCH_INTERVAL_MS = 200L

        /** Ramp resolution during the overlap; fine enough that gain steps are inaudible. */
        const val RAMP_INTERVAL_MS = 40L

        /** Below this there is no room to overlap anything meaningful. */
        const val MIN_CROSSFADE_MS = 500
    }

    /** Overlap length; 0 disables crossfade entirely and restores plain gapless behaviour. */
    @Volatile
    var crossfadeMs: Int = 0

    private var tail: ExoPlayer? = null
    private var tailEqualizer: GraphicEqualizerAudioProcessor? = null
    private var fading = false

    /** Guards against re-triggering for the same track once a crossfade has been started. */
    private var lastFadedItemIndex = C.INDEX_UNSET

    private val watch = object : Runnable {
        override fun run() {
            maybeBeginCrossfade()
            handler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(watch)
        handler.postDelayed(watch, WATCH_INTERVAL_MS)
    }

    fun release() {
        handler.removeCallbacks(watch)
        tail?.release()
        tail = null
        tailEqualizer = null
    }

    private fun maybeBeginCrossfade() {
        val overlapMs = crossfadeMs
        if (overlapMs < MIN_CROSSFADE_MS || fading) return
        if (!primary.isPlaying || !primary.hasNextMediaItem()) return

        val duration = primary.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        val remaining = duration - primary.currentPosition
        // Only fade a track long enough to have a tail worth overlapping.
        if (remaining > overlapMs || duration < overlapMs * 2L) return
        if (primary.currentMediaItemIndex == lastFadedItemIndex) return

        beginCrossfade(overlapMs)
    }

    /**
     * Hands the remainder of the current track to the tail player and advances the primary early,
     * so both sound together for [overlapMs].
     */
    private fun beginCrossfade(overlapMs: Int) {
        val outgoingItem = primary.currentMediaItem ?: return
        val outgoingPosition = primary.currentPosition

        val tailPlayer = obtainTailPlayer()
        // Snapshot the equaliser so the fading tail is shaped like what was already playing;
        // without this the outgoing half of every transition would be audibly unequalised.
        copyEqualizerSettings()

        lastFadedItemIndex = primary.currentMediaItemIndex
        fading = true

        tailPlayer.volume = 1f
        tailPlayer.setMediaItem(outgoingItem, outgoingPosition)
        tailPlayer.prepare()
        tailPlayer.play()

        primary.volume = 0f
        primary.seekToNextMediaItem()
        primary.play()

        rampFrom(System.currentTimeMillis(), overlapMs, tailPlayer)
    }

    private fun rampFrom(startedAtMs: Long, overlapMs: Int, tailPlayer: ExoPlayer) {
        val step = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startedAtMs
                val progress = (elapsed.toFloat() / overlapMs).coerceIn(0f, 1f)

                tailPlayer.volume = CrossfadeCurve.outgoingGain(progress)
                primary.volume = CrossfadeCurve.incomingGain(progress)

                if (progress >= 1f) {
                    finishCrossfade(tailPlayer)
                } else {
                    handler.postDelayed(this, RAMP_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(step, RAMP_INTERVAL_MS)
    }

    private fun finishCrossfade(tailPlayer: ExoPlayer) {
        tailPlayer.stop()
        tailPlayer.clearMediaItems()
        primary.volume = 1f
        fading = false
    }

    /** Abandons any overlap in progress, e.g. because the user skipped or paused. */
    fun cancel() {
        if (!fading) return
        tail?.let(::finishCrossfade)
        lastFadedItemIndex = C.INDEX_UNSET
    }

    private fun obtainTailPlayer(): ExoPlayer {
        tail?.let { return it }

        val equalizer = GraphicEqualizerAudioProcessor()
        val player = ExoPlayer.Builder(
            context,
            EngineRenderersFactory(context, equalizer, com.atomic.atomicamp.engine.dsp.FadeAudioProcessor()),
        )
            // No focus handling: this is the same logical playback as the primary, and asking for
            // focus a second time would look like a second app starting.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ false,
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // The tail ran out before the overlap finished; nothing left to fade.
                if (playbackState == Player.STATE_ENDED) player.stop()
            }
        })

        tail = player
        tailEqualizer = equalizer
        return player
    }

    /**
     * Copies the live equaliser onto the tail player's own instance.
     *
     * A snapshot taken as the overlap starts is enough: it lasts a few seconds, and a band moved
     * mid-fade changing only one half of the transition would sound stranger than it not changing
     * at all.
     */
    private fun copyEqualizerSettings() {
        val target = tailEqualizer ?: return
        target.setBandGains(primaryEqualizer.getBandGains())
        target.setPreampGain(primaryEqualizer.getPreampGain())
        target.setEnabled(primaryEqualizer.isEqEnabled())
    }
}
