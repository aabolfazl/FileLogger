/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.crash

import abbasi.android.filelogger.FileLogger
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.internal.FileLoggerInternalLog
import abbasi.android.filelogger.mdc.Mdc
import abbasi.android.filelogger.pipeline.LogEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs an `UncaughtExceptionHandler` that emits a fatal `LogEvent` and drains the pipeline
 * before delegating to whatever handler was previously installed (typically the platform default
 * or a Crashlytics shim). Idempotent — a second [install] while one is active returns without
 * touching the chain. Use [uninstall] to restore the previous handler.
 *
 * The installed handler is intentionally minimal:
 *  1. A `ThreadLocal` re-entry guard — if FileLogger itself crashes inside the handler, the
 *     re-entered call is forwarded directly to the previous handler instead of recursing.
 *  2. Synthesise a fatal `LogEvent` and route it through `FileLogger.emitFatal` so it bypasses
 *     `isLoggable` and reaches every sink (file, HTTP, etc.) before the process dies.
 *  3. `runBlocking { FileLogger.shutdown(flushTimeoutMs) }` — same justification as the JVM
 *     shutdown hook in `LogPipeline.kt:171`: the uncaught-exception thread is the last context
 *     in which work runs before the platform terminates the process, so blocking it briefly to
 *     drain the pipeline is the only way to guarantee the crash event is on disk.
 *  4. If [rethrow] is true, chain to the previous handler (which typically terminates the
 *     process). Otherwise return; the JVM's own `dispatchUncaughtException` semantics apply.
 *
 * Any exception thrown by the body of the handler (formatter blow-up, pipeline state
 * misbehaviour) is caught and funnelled to `FileLoggerInternalLog`. The handler itself never
 * throws — the JVM is already unwinding, and a thrown exception here would do nothing useful.
 */
public object CrashHandlerInstaller {

    private val installed = AtomicBoolean(false)
    private val inHandler: ThreadLocal<Boolean> = ThreadLocal()

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Install the FileLogger crash handler. Idempotent — a second call while a previous install
     * is active returns immediately without altering the chain.
     *
     * @param rethrow when true, after FileLogger has flushed, the previous handler is invoked
     *  with the original `(thread, exception)` so platform / Crashlytics behaviour is preserved.
     *  When false the handler returns and FileLogger does not chain — the caller is responsible
     *  for what happens to the process next.
     * @param flushTimeoutMs upper bound on the synchronous shutdown drain. Defaults to 2 s; the
     *  underlying `LogPipeline.shutdown` enforces the limit.
     */
    public fun install(rethrow: Boolean = true, flushTimeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS) {
        if (!installed.compareAndSet(false, true)) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            handle(thread, exception, rethrow, flushTimeoutMs)
        }
    }

    /**
     * Restore whatever handler was active before [install] ran. Idempotent: calling twice (or
     * before [install]) is safe and leaves the JVM's current handler untouched on the second
     * call. After [uninstall] the installer can be re-installed.
     */
    public fun uninstall() {
        if (!installed.compareAndSet(true, false)) return
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        previousHandler = null
    }

    private fun handle(
        thread: Thread,
        exception: Throwable,
        rethrow: Boolean,
        flushTimeoutMs: Long,
    ) {
        if (inHandler.get() == true) {
            previousHandler?.uncaughtException(thread, exception)
            return
        }
        inHandler.set(true)
        try {
            try {
                val now = System.currentTimeMillis()
                val threadName = thread.name
                val event = LogEvent(
                    level = LogLevel.Error,
                    tag = "CrashHandler",
                    lazyMessage = { "uncaught exception on thread $threadName" },
                    throwable = exception,
                    timestampMs = now,
                    threadName = threadName,
                    mdc = Mdc.currentSnapshot(),
                )
                FileLogger.emitFatal(event)
                // runBlocking justification: this is the uncaught-exception thread; the JVM is
                // about to terminate the process and there is no surviving caller to suspend
                // back to. Mirrors LogPipeline.kt:171 (JVM shutdown hook).
                runBlocking { FileLogger.shutdown(flushTimeoutMs) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                FileLoggerInternalLog.error("CrashHandler failed before chaining", e)
            }
            if (rethrow) {
                try {
                    previousHandler?.uncaughtException(thread, exception)
                } catch (e: Exception) {
                    FileLoggerInternalLog.error("CrashHandler previous handler threw", e)
                }
            }
        } finally {
            inHandler.remove()
        }
    }

    private const val DEFAULT_FLUSH_TIMEOUT_MS: Long = 2_000L
}
