/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.internal

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Single funnel for FileLogger's own errors. Authored code in `pipeline/`, `sink/`, `crash/`,
 * and `internal/` reports failures here instead of writing to stderr or recursing back into the
 * public `FileLogger.x(...)` API. `warnRateLimited` collapses repeated failures from the same
 * source (e.g. a permanently broken sink) to one entry per `intervalMs` window so logcat stays
 * readable.
 */
internal object FileLoggerInternalLog {
    private const val TAG = "FileLogger"
    private val lastWarnAtMs = ConcurrentHashMap<String, Long>()

    fun warn(msg: String, e: Throwable? = null) {
        Log.w(TAG, msg, e)
    }

    fun error(msg: String, e: Throwable? = null) {
        Log.e(TAG, msg, e)
    }

    fun warnRateLimited(key: String, msg: String, e: Throwable? = null, intervalMs: Long = 60_000) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastWarnAtMs[key]
        if (previous != null && now - previous < intervalMs) return
        lastWarnAtMs[key] = now
        Log.w(TAG, msg, e)
    }
}
