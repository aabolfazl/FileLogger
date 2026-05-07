/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.file

import abbasi.android.filelogger.internal.FileLoggerInternalLog
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Periodic available-space probe over `StatFs(rootDir)`. Runs on the pipeline scope at
 * `pollInterval` cadence and exposes the latest verdict via `canWrite()`. `FileSink.emit` reads
 * this on the hot path, so the boolean is `@Volatile`; under `limitedParallelism(1)` poller and
 * sink are serialised, but the discipline costs nothing and protects against future scope
 * changes. Falling below `minFreeBytes` flips writes off and emits one `warn`; recovery flips
 * them back on with a second `warn`. `StatFs` failures are rate-limited so a permanently broken
 * filesystem does not flood logcat.
 *
 * @param rootDir directory whose containing filesystem is probed. The parent is used when
 *  `rootDir` itself does not yet exist.
 * @param minFreeBytes threshold below which `canWrite()` returns false.
 * @param pollInterval cadence of the background probe.
 */
public class DiskSpaceGuard(
    private val rootDir: File,
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
) {
    @Volatile
    private var hasSpace: Boolean = true

    /** Latest verdict from the most recent poll. Volatile read; safe from any thread. */
    public fun canWrite(): Boolean = hasSpace

    /** Launch the polling loop on `scope`. Idempotent only at construction; do not call twice. */
    public fun start(scope: CoroutineScope) {
        scope.launch {
            while (currentCoroutineContext().isActive) {
                runCheck()
                delay(pollInterval)
            }
        }
    }

    private fun runCheck() {
        try {
            val target = if (rootDir.exists()) rootDir else rootDir.parentFile ?: rootDir
            val stat = StatFs(target.absolutePath)
            val available = stat.availableBytes
            val previous = hasSpace
            val now = available >= minFreeBytes
            hasSpace = now
            if (previous && !now) {
                FileLoggerInternalLog.warn(
                    "DiskSpaceGuard: paused writes (free=$available bytes < min=$minFreeBytes)",
                )
            } else if (!previous && now) {
                FileLoggerInternalLog.warn(
                    "DiskSpaceGuard: resumed writes (free=$available bytes)",
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            FileLoggerInternalLog.warnRateLimited(
                key = "disk-guard",
                msg = "DiskSpaceGuard StatFs failed for ${rootDir.absolutePath}",
                e = e,
            )
        }
    }

    private companion object {
        const val DEFAULT_MIN_FREE_BYTES: Long = 50L * 1024 * 1024
        val DEFAULT_POLL_INTERVAL: Duration = 30.seconds
    }
}
