package com.atomic.atomicamp.app.library.scan

/** Running state of a library scan. */
data class ScanProgress(
    val filesScanned: Int,
    val elapsedMs: Long,
) {
    /** Files per second so far, or null before enough time has passed to mean anything. */
    val filesPerSecond: Double?
        get() = if (elapsedMs < 500L) null else filesScanned * 1000.0 / elapsedMs
}
