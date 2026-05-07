/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger

import abbasi.android.filelogger.config.Config
import abbasi.android.filelogger.config.FormatterChoice
import abbasi.android.filelogger.file.DiskSpaceGuard
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.format.JsonFormatter
import abbasi.android.filelogger.format.LogFormatter
import abbasi.android.filelogger.format.PlainTextFormatter
import abbasi.android.filelogger.interceptor.LogInterceptor
import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.pipeline.LogPipeline
import abbasi.android.filelogger.pipeline.newPipelineScope
import abbasi.android.filelogger.sink.FileSink
import abbasi.android.filelogger.sink.LogSink
import abbasi.android.filelogger.sink.LogcatSink
import abbasi.android.filelogger.time.TimeFormatter
import abbasi.android.filelogger.util.FileZipper
import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide entry point. Thin adapter over a `LoggerImpl` that is constructed during `init`.
 * Before `init`, all log calls are no-ops and `events` returns a never-emitting `SharedFlow`.
 * `isEnabled` is settable any time and propagates to the active delegate.
 */
public object FileLogger : Logger {

    private val _isEnabled = AtomicBoolean(true)

    @Volatile
    private var delegate: LoggerImpl? = null

    @Volatile
    private var pipeline: LogPipeline? = null

    @Volatile
    private var fileSink: FileSink? = null

    @Volatile
    private var config: Config? = null

    @Volatile
    private var interceptor: LogInterceptor? = null

    private val _events: MutableSharedFlow<LogEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Whether logging is enabled. Disable to short-circuit all `d/i/w/e` calls without tearing
     * down the pipeline; in-flight events still drain. Thread-safe.
     */
    public override var isEnabled: Boolean
        get() = _isEnabled.get()
        set(value) {
            _isEnabled.set(value)
            delegate?.isEnabled = value
        }

    /**
     * Misspelled v1.x alias for `isEnabled`. Reads and writes go through `isEnabled` so the two
     * stay in sync.
     */
    @Deprecated(
        message = "Use isEnabled.",
        replaceWith = ReplaceWith("isEnabled"),
        level = DeprecationLevel.WARNING,
    )
    public var isEnable: Boolean
        get() = isEnabled
        set(value) { isEnabled = value }

    public override val events: SharedFlow<LogEvent> = _events.asSharedFlow()

    /**
     * Initialise the pipeline and underlying `LoggerImpl`. Idempotent — subsequent calls are
     * ignored. Wires a `FileSink` (always) and a `LogcatSink` (when `config.logcatEnable` is
     * true). Pipeline construction also installs a JVM shutdown hook and a
     * `ProcessLifecycleOwner` observer so a normal process exit always flushes.
     */
    public fun init(context: Context, config: Config) {
        synchronized(this) {
            if (delegate != null) return
            this.config = config
            this.interceptor = config.logInterceptor

            val dateFormat = TimeFormatter(config.dataFormatterPattern, Locale.US)
            val scope = newPipelineScope()
            val formatter: LogFormatter = when (config.formatter) {
                FormatterChoice.PlainText -> PlainTextFormatter(dateFormat, config.defaultTag)
                FormatterChoice.Json -> JsonFormatter(dateFormat, config.defaultTag)
            }
            val diskGuard = DiskSpaceGuard(File(config.directory, "fileLogs"))

            val sink = FileSink(
                rootDir = config.directory,
                scope = scope,
                formatter = formatter,
                rotation = config.fileRotationStrategy,
                retention = config.retentionPolicy,
                startupData = config.startupData,
                timeFormatter = dateFormat,
                diskGuard = diskGuard,
            )
            fileSink = sink

            val sinks = mutableListOf<LogSink>(sink)
            if (config.logcatEnable) {
                sinks.add(LogcatSink())
            }

            val newPipeline = LogPipeline(scope = scope, sinks = sinks)
            pipeline = newPipeline

            scope.launch {
                newPipeline.events.collect { ev -> _events.tryEmit(ev) }
            }

            val newDelegate = LoggerImpl(
                pipeline = newPipeline,
                defaultTag = config.defaultTag,
                minLevel = config.minLevel,
                tagOverrides = config.tagOverrides,
            )
            newDelegate.isEnabled = _isEnabled.get()
            delegate = newDelegate
        }
    }

    public override fun isLoggable(level: LogLevel, tag: String?): Boolean =
        delegate?.isLoggable(level, tag) ?: false

    public override fun d(tag: String?, message: String, throwable: Throwable?) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Debug, tag)) return
        d.d(tag, applyInterceptor(LogLevel.Debug, tag, message, throwable), throwable)
    }

    public override fun i(tag: String?, message: String, throwable: Throwable?) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Info, tag)) return
        d.i(tag, applyInterceptor(LogLevel.Info, tag, message, throwable), throwable)
    }

    public override fun w(tag: String?, message: String, throwable: Throwable?) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Warning, tag)) return
        d.w(tag, applyInterceptor(LogLevel.Warning, tag, message, throwable), throwable)
    }

    public override fun e(tag: String?, message: String, throwable: Throwable?) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Error, tag)) return
        d.e(tag, applyInterceptor(LogLevel.Error, tag, message, throwable), throwable)
    }

    public override fun d(tag: String?, throwable: Throwable?, message: () -> String) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Debug, tag)) return
        d.d(tag, applyInterceptor(LogLevel.Debug, tag, message(), throwable), throwable)
    }

    public override fun i(tag: String?, throwable: Throwable?, message: () -> String) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Info, tag)) return
        d.i(tag, applyInterceptor(LogLevel.Info, tag, message(), throwable), throwable)
    }

    public override fun w(tag: String?, throwable: Throwable?, message: () -> String) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Warning, tag)) return
        d.w(tag, applyInterceptor(LogLevel.Warning, tag, message(), throwable), throwable)
    }

    public override fun e(tag: String?, throwable: Throwable?, message: () -> String) {
        val d = delegate ?: return
        if (!d.isLoggable(LogLevel.Error, tag)) return
        d.e(tag, applyInterceptor(LogLevel.Error, tag, message(), throwable), throwable)
    }

    /**
     * Delete every rotated log file in the configured directory. The currently-open file is
     * preserved. Runs on the pipeline scope.
     */
    public fun deleteFiles() {
        val sink = fileSink ?: return
        val pipe = pipeline ?: return
        i(message = "FileLogger delete files called")
        pipe.scope.launch { sink.purgeOldFiles() }
    }

    /**
     * Compress every file in the log directory into a single zip and invoke `callback` with the
     * resulting file (or `null` on failure).
     *
     * Threading: the zip work and the callback run on the pipeline's `Dispatchers.IO`-backed
     * coroutine scope. **The callback thread has no Android `Looper`**, so any UI work inside it
     * (e.g. `Toast.makeText`, `Activity.startActivity`, view updates) must be dispatched back to
     * the main thread by the caller — typically via `runOnUiThread { ... }` from an Activity or
     * `lifecycleScope.launch(Dispatchers.Main) { ... }`.
     */
    public fun compressLogsInZipFile(
        zipFileName: String? = null,
        callback: (zipFile: File?) -> Unit,
    ) {
        val cfg = config ?: return
        val pipe = pipeline ?: return
        FileZipper(pipe.scope).compressFiles(cfg, zipFileName, callback)
    }

    /**
     * Flush every sink, close the pipeline, and release internal references. Idempotent: a second
     * call after the pipeline is gone returns immediately. The underlying pipeline applies a
     * `withTimeoutOrNull(timeoutMs)` to the drain join, so this method returns within roughly
     * `timeoutMs` even if a sink hangs. Subsequent log calls become no-ops; call [init] again to
     * resume.
     *
     * @param timeoutMs upper bound on the pipeline's drain-join timeout. Defaults to 2 s.
     */
    public suspend fun shutdown(timeoutMs: Long = DEFAULT_SHUTDOWN_TIMEOUT_MS) {
        pipeline?.shutdown(timeoutMs)
        delegate = null
        pipeline = null
        fileSink = null
        config = null
        interceptor = null
    }

    internal fun emitFatal(event: LogEvent) {
        pipeline?.emit(event)
    }

    private const val DEFAULT_SHUTDOWN_TIMEOUT_MS: Long = 2_000L

    private fun applyInterceptor(
        level: LogLevel,
        tag: String?,
        message: String,
        throwable: Throwable?,
    ): String {
        val ic = interceptor ?: return message
        return ic.intercept(level, tag.orEmpty(), message, throwable)
    }
}
