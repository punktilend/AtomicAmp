package com.atomic.atomicamp.engine

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Starts playback again when the head unit powers up.
 *
 * On a phone, auto-playing music at boot would be obnoxious. On a head unit it is the expected
 * behaviour: the unit boots when the ignition turns on, the driver expects the music to be back,
 * and nobody wants to open an app before pulling out of a parking space.
 *
 * The unit does not resume from sleep -- it kills the process at ignition-off -- so there is no
 * live player to unpause. [PlaybackService] rebuilds the queue and position from disk in onCreate,
 * and this only has to ask it to start.
 *
 * **It connects a [MediaController] rather than calling `startForegroundService`, and that detail
 * is the whole feature.** Starting the service directly was tried and measured: the receiver fired
 * and the player asked for audio focus, but a cold-booted unit cannot buffer a 24/192 FLAC inside
 * the five seconds `startForegroundService` allows, so Media3 had not yet posted its notification
 * and the system killed the service for never calling `startForeground` --
 * `bg anr ... did not then call Service.startForeground()`. Binding has no such deadline. An app
 * may always bind its own service from the background, and once playback actually begins Media3
 * promotes the service to the foreground itself, so releasing the controller afterwards is safe.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON
        ) {
            return
        }

        if (!EqualizerSettingsStore(context).resumeOnBoot) return

        // Keeps the receiver alive past onReceive; a bind result cannot arrive synchronously.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()

        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller == null) {
                    pendingResult.finish()
                    return@addListener
                }

                // An empty queue means there is nothing to resume -- a first install, or a
                // library that was never scanned. Starting a silent service helps nobody.
                if (controller.mediaItemCount > 0) {
                    // Re-apply the checkpoint before playing.
                    //
                    // PlaybackService already restores it in onCreate, and that restore is exact
                    // when the process is merely killed and relaunched -- measured: 4914 ms saved,
                    // 4914 ms restored. Coming back from an actual reboot it is not: the right
                    // track resumes at zero even though the checkpoint on disk was ten seconds in.
                    // Something on the cold-boot path loses the start position, so the resume does
                    // not trust it and seeks itself.
                    val checkpoint = PlaybackStateStore(appContext).savedPosition()
                    if (checkpoint != null && checkpoint.positionMs > 0) {
                        controller.seekTo(checkpoint.index, checkpoint.positionMs)
                    }
                    controller.prepare()
                    controller.play()
                }

                // Hold the binding briefly so the service is not torn down before playback has
                // begun and Media3 has taken over keeping it alive. Comfortably inside the
                // roughly ten seconds goAsync() allows.
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        runCatching { controller.release() }
                        pendingResult.finish()
                    },
                    HOLD_BINDING_MS,
                )
            },
            { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
        )
    }

    private companion object {
        /**
         * Several vendor ROMs -- head units especially -- fast-boot from a saved image and send
         * this instead of the standard broadcast. Cheap to listen for, and the difference between
         * working and not on exactly the device this app targets.
         */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

        const val HOLD_BINDING_MS = 6_000L
    }
}
