package com.atomic.atomicamp.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.atomic.atomicamp.app.MainActivity
import com.atomic.atomicamp.app.R
import com.atomic.atomicamp.engine.PlaybackService

/**
 * A now-playing widget for the home screen.
 *
 * It is driven by a broadcast from [PlaybackService] rather than polling. A widget that polls is
 * either stale or wasteful, and the service already knows the moment anything changes.
 *
 * Controls do not talk to the player directly. They bind a [MediaController] the same way the boot
 * receiver does, because that is the supported way in and it works whether the app's UI is running
 * or the service is asleep.
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, null))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PlaybackService.ACTION_STATE_CHANGED -> pushUpdate(context, intent)
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREVIOUS -> sendToPlayer(context, intent.action!!)
        }
    }

    private fun pushUpdate(context: Context, intent: Intent) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
        if (ids.isEmpty()) return
        val views = buildViews(context, intent)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun buildViews(context: Context, state: Intent?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

        val title = state?.getStringExtra(PlaybackService.EXTRA_TITLE)
        val artist = state?.getStringExtra(PlaybackService.EXTRA_ARTIST)
        val isPlaying = state?.getBooleanExtra(PlaybackService.EXTRA_IS_PLAYING, false) ?: false

        views.setTextViewText(
            R.id.widget_title,
            title ?: context.getString(R.string.widget_nothing_playing),
        )
        views.setTextViewText(R.id.widget_artist, artist.orEmpty())
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )

        // Artwork arrives as a content:// uri the widget host can read; decoding it here keeps the
        // RemoteViews self-contained rather than relying on the host resolving a provider.
        val artPath = state?.getStringExtra(PlaybackService.EXTRA_ART_PATH)
        val bitmap = artPath?.let { path ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        }
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_art, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
        }

        views.setOnClickPendingIntent(R.id.widget_play_pause, action(context, ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.widget_prev, action(context, ACTION_PREVIOUS))
        views.setOnClickPendingIntent(
            R.id.widget_title,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    private fun action(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, NowPlayingWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Binding, not starting: an app may always bind its own service from the background. */
    private fun sendToPlayer(context: Context, action: String) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) {
                    when (action) {
                        ACTION_PLAY_PAUSE -> if (controller.isPlaying) controller.pause() else controller.play()
                        ACTION_NEXT -> controller.seekToNextMediaItem()
                        ACTION_PREVIOUS -> controller.seekToPreviousMediaItem()
                    }
                    controller.release()
                }
                pending.finish()
            },
            { it.run() },
        )
    }

    private companion object {
        const val ACTION_PLAY_PAUSE = "atomicamp.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "atomicamp.widget.NEXT"
        const val ACTION_PREVIOUS = "atomicamp.widget.PREVIOUS"
    }
}
