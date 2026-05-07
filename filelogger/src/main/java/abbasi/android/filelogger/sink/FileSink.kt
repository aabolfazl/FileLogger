/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.sink

import abbasi.android.filelogger.config.FileRotationStrategy
import abbasi.android.filelogger.config.RetentionPolicy
import abbasi.android.filelogger.file.DiskSpaceGuard
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.file.RetentionPolicyChecker
import abbasi.android.filelogger.format.LogFormatter
import abbasi.android.filelogger.internal.FileLoggerInternalLog
import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.time.TimeFormatter
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Production file sink. Owns the open writer for the current log file, batches flushes
 * (every `batchEvery` events or `batchInterval`, whichever first), rotates per
 * `FileRotationStrategy`, and drives a periodic retention sweep when a `RetentionPolicy` is
 * configured. The writer chain is `OutputStreamWriter(BufferedOutputStream(FileOutputStream(_,
 * append=true)))` so process restarts do not truncate prior content. The disk-space guard
 * short-circuits `emit` when free space falls below the configured threshold; recovery
 * synthesises a single `warn` describing how many events were dropped while paused. `close()` is
 * idempotent via `AtomicBoolean.compareAndSet`. Background loops (flush, retention, disk-guard)
 * are children of the pipeline scope and unwind on `scope.cancel()`.
 *
 * @param rootDir parent directory; the sink writes into `rootDir/fileLogs`.
 * @param scope pipeline scope; periodic flush, retention sweep, and disk-guard polling are
 *  launched as children so a single `scope.cancel()` tears them down.
 * @param formatter shared formatter; the startup banner and synthetic notices flow through it
 *  so all output keeps a single shape (plain text or JSON).
 * @param rotation rotation strategy; `None` keeps a single file per session.
 * @param retention deletion policy applied periodically and after each rotation; `null` disables
 *  background sweeps.
 * @param startupData arbitrary key-value pairs appended to the startup banner.
 * @param timeFormatter formats timestamps for both file names and the banner header.
 * @param diskGuard short-circuits writes when free space falls below the configured threshold.
 * @param batchEvery flush after this many `emit` calls.
 * @param batchInterval flush at least this often even when below `batchEvery`.
 * @param retentionInterval period between retention sweeps when `retention` is non-null.
 */
public class FileSink(
    rootDir: String,
    private val scope: CoroutineScope,
    private val formatter: LogFormatter,
    private val rotation: FileRotationStrategy,
    private val retention: RetentionPolicy?,
    private val startupData: Map<String, String>?,
    private val timeFormatter: TimeFormatter,
    private val diskGuard: DiskSpaceGuard,
    private val batchEvery: Int = DEFAULT_BATCH_EVERY,
    private val batchInterval: Duration = DEFAULT_BATCH_INTERVAL,
    private val retentionInterval: Duration = DEFAULT_RETENTION_INTERVAL,
) : LogSink {

    public override val id: String = "file"

    private val logsDirectory: File = File(rootDir, LOGS_SUBDIR)

    @Volatile
    private var currentFile: File = File(logsDirectory, fileNameForNow())

    @Volatile
    private var writer: OutputStreamWriter? = null

    private var unflushed: Int = 0
    private var lastFlushAtMs: Long = 0L
    private var fileCreatedAtMs: Long = 0L
    private var droppedDueToDiskFull: Long = 0L
    private val closed = AtomicBoolean(false)

    private val retentionChecker = RetentionPolicyChecker(
        directory = logsDirectory,
        currentFile = { currentFile },
    )

    init {
        if (!logsDirectory.exists() && !logsDirectory.mkdirs()) {
            FileLoggerInternalLog.warn("FileSink could not create directory ${logsDirectory.absolutePath}")
        }
        openInitialFile()
        diskGuard.start(scope)
        scope.launch { batchFlushLoop() }
        retention?.let { policy -> scope.launch { retentionLoop(policy) } }
    }

    public override suspend fun emit(event: LogEvent) {
        if (closed.get()) return
        if (!diskGuard.canWrite()) {
            droppedDueToDiskFull++
            return
        }
        emitDiskRecoveryNoticeIfNeeded(event.timestampMs)
        rotateIfNeeded()
        val w = writer ?: return
        try {
            w.write(formatter.format(event))
            unflushed++
            if (unflushed >= batchEvery) flushNow()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            FileLoggerInternalLog.warnRateLimited("file-sink.write", "FileSink write failed", e)
        }
    }

    public override suspend fun flush() {
        flushNow()
    }

    public override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            flushNow()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("FileSink flushNow on close failed", e)
        }
        try {
            writer?.close()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("FileSink writer close failed", e)
        } finally {
            writer = null
        }
    }

    private fun openInitialFile() {
        try {
            currentFile = File(logsDirectory, fileNameForNow())
            writer = openWriter(currentFile)
            fileCreatedAtMs = System.currentTimeMillis()
            lastFlushAtMs = SystemClock.elapsedRealtime()
            writeStartupBanner()
            flushNow()
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("FileSink openInitialFile failed", e)
            writer = null
        }
    }

    private fun openWriter(file: File): OutputStreamWriter {
        file.parentFile?.takeUnless { it.exists() }?.mkdirs()
        if (!file.exists()) file.createNewFile()
        return OutputStreamWriter(BufferedOutputStream(FileOutputStream(file, true)), Charsets.UTF_8)
    }

    private fun writeStartupBanner() {
        val w = writer ?: return
        val now = System.currentTimeMillis()
        val sb = StringBuilder("File logger started at ").append(timeFormatter.format(now))
        startupData?.forEach { (k, v) -> sb.append('\n').append(k).append(": ").append(v) }
        val bannerText = sb.toString()
        val event = LogEvent(
            level = LogLevel.Info,
            tag = "FileLogger",
            lazyMessage = { bannerText },
            throwable = null,
            timestampMs = now,
            threadName = "FileLogger",
            mdc = emptyMap(),
        )
        try {
            w.write(formatter.format(event))
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("FileSink writeStartupBanner failed", e)
        }
    }

    private fun rotateIfNeeded() {
        val strategy = rotation
        if (strategy !is FileRotationStrategy.TimeBased) return
        val now = System.currentTimeMillis()
        if (now - fileCreatedAtMs <= strategy.intervalInMillis) return
        rotate(now)
    }

    private fun rotate(now: Long) {
        try {
            flushNow()
            writer?.close()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("FileSink close on rotate failed", e)
        } finally {
            writer = null
        }
        try {
            currentFile = File(logsDirectory, fileNameForNow())
            writer = openWriter(currentFile)
            fileCreatedAtMs = now
            lastFlushAtMs = SystemClock.elapsedRealtime()
            unflushed = 0
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("FileSink open on rotate failed", e)
            writer = null
        }
        val policy = retention ?: return
        scope.launch {
            try {
                retentionChecker(policy)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                FileLoggerInternalLog.warnRateLimited("retention.rotate", "retention sweep on rotate failed", e)
            }
        }
    }

    private fun flushNow() {
        val w = writer ?: return
        try {
            w.flush()
            unflushed = 0
            lastFlushAtMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            FileLoggerInternalLog.warnRateLimited("file-sink.flush", "FileSink flush failed", e)
        }
    }

    private suspend fun batchFlushLoop() {
        while (currentCoroutineContext().isActive) {
            delay(batchInterval)
            if (closed.get()) return
            if (unflushed > 0) flushNow()
        }
    }

    private suspend fun retentionLoop(policy: RetentionPolicy) {
        while (currentCoroutineContext().isActive) {
            delay(retentionInterval)
            if (closed.get()) return
            try {
                retentionChecker(policy)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                FileLoggerInternalLog.warnRateLimited("retention.loop", "retention sweep failed", e)
            }
        }
    }

    private fun emitDiskRecoveryNoticeIfNeeded(timestampMs: Long) {
        if (droppedDueToDiskFull == 0L) return
        val w = writer ?: return
        val count = droppedDueToDiskFull
        droppedDueToDiskFull = 0L
        val notice = LogEvent(
            level = LogLevel.Warning,
            tag = "FileLogger",
            lazyMessage = { "FileSink resumed; dropped $count events while disk full" },
            throwable = null,
            timestampMs = timestampMs,
            threadName = "FileLogger",
            mdc = emptyMap(),
        )
        try {
            w.write(formatter.format(notice))
            unflushed++
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("FileSink recovery notice write failed", e)
        }
    }

    internal fun purgeOldFiles() {
        val open = currentFile
        try {
            logsDirectory.listFiles()?.forEach { file ->
                if (file.isFile && file != open) file.delete()
            }
        } catch (e: SecurityException) {
            FileLoggerInternalLog.warn("FileSink purgeOldFiles failed", e)
        }
    }

    private fun fileNameForNow(): String =
        "${timeFormatter.format(System.currentTimeMillis())}.txt"

    private companion object {
        const val LOGS_SUBDIR = "fileLogs"
        const val DEFAULT_BATCH_EVERY = 100
        val DEFAULT_BATCH_INTERVAL: Duration = 250.milliseconds
        val DEFAULT_RETENTION_INTERVAL: Duration = 5.minutes
    }
}
