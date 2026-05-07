/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger

import abbasi.android.filelogger.config.DEFAULT_TAG
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.mdc.Mdc
import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.pipeline.LogPipeline
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Non-singleton `Logger` backed by a `LogPipeline`. Multiple instances may coexist on top of
 * different pipelines (useful for tests and for hosts that segregate diagnostics by feature). All
 * eager methods construct the `LogEvent` and hand it to the pipeline; lazy methods short-circuit
 * via `isLoggable` before invoking the message lambda or capturing the MDC snapshot, so a
 * filtered call pays for at most one map lookup.
 *
 * @param pipeline pipeline that owns the sinks and dispatcher.
 * @param defaultTag tag substituted when a call site supplies `tag = null`.
 * @param minLevel events strictly below this severity are dropped.
 * @param tagOverrides per-tag override map; an entry overrides `minLevel` for that tag only.
 */
public class LoggerImpl(
    private val pipeline: LogPipeline,
    private val defaultTag: String = DEFAULT_TAG,
    private val minLevel: LogLevel = LogLevel.Debug,
    private val tagOverrides: Map<String, LogLevel> = emptyMap(),
) : Logger {

    private val _isEnabled = AtomicBoolean(true)

    public override var isEnabled: Boolean
        get() = _isEnabled.get()
        set(value) { _isEnabled.set(value) }

    public override val events: SharedFlow<LogEvent>
        get() = pipeline.events

    public override fun isLoggable(level: LogLevel, tag: String?): Boolean {
        if (!_isEnabled.get()) return false
        val threshold = tag?.let { tagOverrides[it] } ?: minLevel
        return level.ordinal >= threshold.ordinal
    }

    public override fun d(tag: String?, message: String, throwable: Throwable?) {
        if (!isLoggable(LogLevel.Debug, tag)) return
        postLog(LogLevel.Debug, tag, message, throwable)
    }

    public override fun i(tag: String?, message: String, throwable: Throwable?) {
        if (!isLoggable(LogLevel.Info, tag)) return
        postLog(LogLevel.Info, tag, message, throwable)
    }

    public override fun w(tag: String?, message: String, throwable: Throwable?) {
        if (!isLoggable(LogLevel.Warning, tag)) return
        postLog(LogLevel.Warning, tag, message, throwable)
    }

    public override fun e(tag: String?, message: String, throwable: Throwable?) {
        if (!isLoggable(LogLevel.Error, tag)) return
        postLog(LogLevel.Error, tag, message, throwable)
    }

    public override fun d(tag: String?, throwable: Throwable?, message: () -> String) {
        if (!isLoggable(LogLevel.Debug, tag)) return
        postLog(LogLevel.Debug, tag, message(), throwable)
    }

    public override fun i(tag: String?, throwable: Throwable?, message: () -> String) {
        if (!isLoggable(LogLevel.Info, tag)) return
        postLog(LogLevel.Info, tag, message(), throwable)
    }

    public override fun w(tag: String?, throwable: Throwable?, message: () -> String) {
        if (!isLoggable(LogLevel.Warning, tag)) return
        postLog(LogLevel.Warning, tag, message(), throwable)
    }

    public override fun e(tag: String?, throwable: Throwable?, message: () -> String) {
        if (!isLoggable(LogLevel.Error, tag)) return
        postLog(LogLevel.Error, tag, message(), throwable)
    }

    private fun postLog(
        level: LogLevel,
        tag: String?,
        message: String,
        throwable: Throwable?,
    ) {
        val resolvedTag = tag ?: defaultTag
        val event = LogEvent(
            level = level,
            tag = resolvedTag,
            lazyMessage = { message },
            throwable = throwable,
            timestampMs = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            mdc = Mdc.currentSnapshot(),
        )
        pipeline.emit(event)
    }
}
