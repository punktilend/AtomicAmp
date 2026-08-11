package com.atomic.atomicamp.app.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash where the *device* can show it.
 *
 * The head unit exposes no ADB and hides Developer options, so a crash there is otherwise a
 * silent disappearance -- the app vanishes and takes the only evidence with it. Writing the
 * trace to a file the Diagnostics screen reads back turns "it crashed" into something
 * actionable without a cable.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /**
     * Chains rather than replaces: the platform handler is what actually kills the process and
     * reports to the system, and swallowing that would turn a crash into a hang.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(context.filesDir, FILE_NAME).writeText(
            "time:   $stamp\nthread: ${thread.name}\n\n$stack",
        )
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE_NAME)
            .takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
