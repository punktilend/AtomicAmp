package com.atomic.atomicamp.engine

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.atomic.atomicamp.engine.cloud.B2Client
import com.atomic.atomicamp.engine.cloud.B2Settings
import com.atomic.atomicamp.engine.cloud.B2Uris
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.atomic.atomicamp.engine.dsp.EqPresets
import com.atomic.atomicamp.engine.dsp.FadeAudioProcessor
import com.atomic.atomicamp.engine.dsp.GraphicEqualizerAudioProcessor
import com.atomic.atomicamp.engine.dsp.LevelerAudioProcessor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground [MediaSessionService] hosting a single [ExoPlayer]. Standard transport controls
 * (play/pause/seek/skip/queue) flow through the [MediaSession] as usual; EQ control is exposed
 * as custom session commands since per-band gain isn't part of the standard controller API.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val COMMAND_SET_BAND_GAIN = "atomicamp.SET_BAND_GAIN"
        const val COMMAND_SET_PREAMP = "atomicamp.SET_PREAMP"
        const val COMMAND_SET_EQ_ENABLED = "atomicamp.SET_EQ_ENABLED"
        const val COMMAND_APPLY_PRESET = "atomicamp.APPLY_PRESET"
        const val COMMAND_GET_EQ_STATE = "atomicamp.GET_EQ_STATE"
        const val COMMAND_SET_LEVELER = "atomicamp.SET_LEVELER"
        const val COMMAND_SET_SLEEP_TIMER = "atomicamp.SET_SLEEP_TIMER"

        const val EXTRA_LEVELER_ENABLED = "leveler_enabled"

        /** Wall-clock time the timer fires at, or 0 for off. */
        const val EXTRA_SLEEP_END_MS = "sleep_end_ms"
        const val EXTRA_BAND_INDEX = "band_index"
        const val EXTRA_GAIN_DB = "gain_db"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_PRESET_NAME = "preset_name"
        const val EXTRA_BAND_GAINS = "band_gains"

        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }

    val equalizer = GraphicEqualizerAudioProcessor()
    private val fade = FadeAudioProcessor()
    private val leveler = LevelerAudioProcessor()

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var settingsStore: EqualizerSettingsStore
    private lateinit var playbackStateStore: PlaybackStateStore

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Sleep timer, as a wall-clock deadline rather than a remaining duration.
     *
     * A deadline survives being asked about at any time and needs no ticking to stay correct,
     * so the UI can render a countdown from it without the service pushing updates. It is
     * deliberately **not** persisted: waking the car up to music that stops in four minutes
     * because of a timer set last night would be baffling.
     */
    private var sleepTimerEndMs = 0L

    private val sleepRunnable = Runnable {
        // Pause rather than stop, so the queue and position stay exactly where they were.
        player.pause()
        sleepTimerEndMs = 0L
    }

    private fun setSleepTimer(endMs: Long) {
        mainHandler.removeCallbacks(sleepRunnable)
        sleepTimerEndMs = endMs
        val delay = endMs - System.currentTimeMillis()
        if (endMs > 0L && delay > 0L) {
            mainHandler.postDelayed(sleepRunnable, delay)
        } else {
            sleepTimerEndMs = 0L
        }
    }

    /**
     * Periodic position checkpoint. Transitions and pauses are saved as they happen, but a process
     * killed mid-track (ignition off) gets no callback at all, so position is also flushed on a
     * timer while playing.
     */
    private val savePositionRunnable = object : Runnable {
        override fun run() {
            playbackStateStore.savePosition(player)
            mainHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        settingsStore = EqualizerSettingsStore(this)
        // Restore before the sink is built so the first buffer already has the user's curve.
        settingsStore.loadInto(equalizer)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            // DEFAULT leaves this UNKNOWN. Car audio policy uses content type to decide routing
            // and how to duck under navigation prompts, so declare music explicitly.
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        leveler.enabled = settingsStore.levelerEnabled

        // Cloud tracks are stored as b2:// and resolved to a signed URL at open time, so an
        // expiring token never reaches the database. Local file:// and content:// tracks pass
        // straight through the same factory untouched.
        val b2Client = B2Client(B2Settings(this))
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
        ) { dataSpec ->
            if (B2Uris.isB2(dataSpec.uri)) {
                val signed = b2Client.signedUrl(B2Uris.pathOf(dataSpec.uri))
                if (signed != null) dataSpec.withUri(Uri.parse(signed)) else dataSpec
            } else {
                dataSpec
            }
        }

        player = ExoPlayer.Builder(this, EngineRenderersFactory(this, equalizer, leveler, fade))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            // Pause when the audio route drops (Bluetooth disconnect, headset unplug) instead of
            // continuing out loud on the unit's speakers.
            .setHandleAudioBecomingNoisy(true)
            // A head unit blanks its screen while driving; without a wake lock the CPU can sleep
            // mid-track and stall playback.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        playbackStateStore = PlaybackStateStore(this)
        playbackStateStore.restoreInto(player)
        player.addListener(PlaybackPersistenceListener())

        // The session wraps the fading player, so notification and steering-wheel controls fade
        // too -- not just this app's own transport buttons.
        mediaSession = MediaSession.Builder(this, FadingPlayer(player, fade, mainHandler))
            .setCallback(EqualizerSessionCallback())
            .build()
    }

    /** Saves queue/position on the events that change them, and while playback is running. */
    private inner class PlaybackPersistenceListener : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playbackStateStore.save(player)
            mainHandler.removeCallbacks(savePositionRunnable)
        mainHandler.removeCallbacks(sleepRunnable)
            if (isPlaying) {
                mainHandler.postDelayed(savePositionRunnable, POSITION_SAVE_INTERVAL_MS)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playbackStateStore.save(player)
            // Judge the new track's level on its own, rather than carrying over a correction that
            // was right for the last one.
            leveler.resetForNewTrack()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            playbackStateStore.save(player)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            playbackStateStore.save(player)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            playbackStateStore.save(player)
        }
    }

    /** Current equalizer state, as sent back to controllers. */
    private fun eqStateBundle(): Bundle = Bundle().apply {
        putFloatArray(EXTRA_BAND_GAINS, equalizer.getBandGains())
        putFloat(EXTRA_GAIN_DB, equalizer.getPreampGain())
        putBoolean(EXTRA_ENABLED, equalizer.isEqEnabled())
        putString(EXTRA_PRESET_NAME, settingsStore.presetName)
        putBoolean(EXTRA_LEVELER_ENABLED, leveler.enabled)
        putLong(EXTRA_SLEEP_END_MS, sleepTimerEndMs)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    override fun onDestroy() {
        mainHandler.removeCallbacks(savePositionRunnable)
        // Last chance to checkpoint: after release() the player reports no queue and no position.
        playbackStateStore.save(player)
        mediaSession.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    /** Swiping the app away should also checkpoint, since the service may be torn down after. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackStateStore.save(player)
        super.onTaskRemoved(rootIntent)
    }

    private inner class EqualizerSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(COMMAND_SET_BAND_GAIN, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_PREAMP, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_EQ_ENABLED, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_APPLY_PRESET, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_GET_EQ_STATE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_LEVELER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                connectionResult.availablePlayerCommands,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_SET_BAND_GAIN -> {
                    val band = args.getInt(EXTRA_BAND_INDEX)
                    val gain = args.getFloat(EXTRA_GAIN_DB)
                    equalizer.setBandGain(band, gain)
                    // Hand-adjusting a band means the curve is no longer whichever preset was
                    // selected -- unless it happens to land exactly on another one.
                    val preset = EqPresets.matching(equalizer.getBandGains())
                    settingsStore.save(equalizer, preset?.name ?: EqPresets.CUSTOM)
                }

                COMMAND_SET_PREAMP -> {
                    val gain = args.getFloat(EXTRA_GAIN_DB)
                    equalizer.setPreampGain(gain)
                    settingsStore.save(equalizer)
                }

                COMMAND_SET_EQ_ENABLED -> {
                    equalizer.setEnabled(args.getBoolean(EXTRA_ENABLED))
                    settingsStore.save(equalizer)
                }

                COMMAND_APPLY_PRESET -> {
                    val name = args.getString(EXTRA_PRESET_NAME)
                    val preset = name?.let { EqPresets.byName(it) }
                    if (preset != null) {
                        equalizer.setBandGains(preset.gainsDb)
                        settingsStore.save(equalizer, preset.name)
                    }
                }

                COMMAND_SET_SLEEP_TIMER -> {
                    setSleepTimer(args.getLong(EXTRA_SLEEP_END_MS))
                }

                COMMAND_SET_LEVELER -> {
                    val on = args.getBoolean(EXTRA_LEVELER_ENABLED)
                    leveler.enabled = on
                    settingsStore.saveLevelerEnabled(on)
                }

                COMMAND_GET_EQ_STATE -> {
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, eqStateBundle()),
                    )
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
