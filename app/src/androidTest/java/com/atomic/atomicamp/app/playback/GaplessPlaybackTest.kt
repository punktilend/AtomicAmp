package com.atomic.atomicamp.app.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Is playback actually gapless?
 *
 * It has been listed as "unknown" for a long time, and by ear it is not answerable: a seam of a
 * few tens of milliseconds is audible as a click but impossible to measure by listening, and a
 * confident wrong answer is worse than an admitted unknown.
 *
 * So it is measured instead. The two assets are one continuous tone cut in half, 44,100 frames
 * each at 44.1 kHz. Played back to back they must deliver exactly 88,200 frames to the audio
 * sink. Anything more is silence the player inserted at the join, which is precisely what a gap
 * is; anything less is audio it dropped.
 *
 * Counting at the sink rather than the decoder is deliberate. That is the last point before the
 * hardware, so it accounts for the whole pipeline including the app's own processor chain.
 */
@RunWith(AndroidJUnit4::class)
class GaplessPlaybackTest {

    /** Passes audio through untouched and counts the frames that reach the sink. */
    private class FrameCounter : BaseAudioProcessor() {
        @Volatile
        var frames: Long = 0
            private set

        private var bytesPerFrame = 2

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
            AudioProcessor.AudioFormat {
            bytesPerFrame = inputAudioFormat.bytesPerFrame
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            frames += remaining / bytesPerFrame
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
        }
    }

    /**
     * The assets ship in the *test* apk, so they are read from the instrumentation context and
     * written into the app under test's cache for the player to open.
     */
    private fun assetToFile(assetContext: Context, targetContext: Context, name: String): File {
        val file = File(targetContext.cacheDir, name.substringAfterLast('/'))
        assetContext.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    @Test
    fun consecutiveTracksPlayWithoutInsertedSilence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val first = assetToFile(instrumentation.context, context, "gapless/first.flac")
        val second = assetToFile(instrumentation.context, context, "gapless/second.flac")

        val counter = FrameCounter()
        val ended = CountDownLatch(1)
        var player: ExoPlayer? = null

        instrumentation.runOnMainSync {
            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(counter))
                    .build()
            }
            player = ExoPlayer.Builder(context, renderersFactory).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) ended.countDown()
                    }
                })
                setMediaItems(
                    listOf(
                        MediaItem.fromUri(first.toURI().toString()),
                        MediaItem.fromUri(second.toURI().toString()),
                    ),
                )
                prepare()
                play()
            }
        }

        assertTrue("playback did not finish", ended.await(30, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { player?.release() }

        // 44,100 frames per file. A gap would show up as extra frames of silence at the join.
        assertEquals(
            "frames delivered to the sink should equal the two files end to end",
            88_200L,
            counter.frames,
        )
    }
}
