/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.dsl

import abbasi.android.filelogger.config.Config
import abbasi.android.filelogger.config.DEFAULT_PATTERN
import abbasi.android.filelogger.config.DEFAULT_TAG
import abbasi.android.filelogger.config.FileRotationStrategy
import abbasi.android.filelogger.config.FormatterChoice
import abbasi.android.filelogger.config.RetentionPolicy
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.interceptor.LogInterceptor
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Marker for FileLogger's configuration DSL. Prevents nested receiver scopes from accidentally
 * resolving against an outer DSL.
 */
@DslMarker
public annotation class FileLoggerDsl

/**
 * Mutable builder backing the `fileLogger { }` DSL. Each property maps directly to a `Config`
 * field; `build()` is internal because instantiation flows through the top-level `fileLogger`
 * function. Custom `LogSink` lists are intentionally not exposed here — sinks need the pipeline
 * coroutine scope, which does not exist at config time. Custom-sink support will land in a
 * future minor release.
 *
 * @param directory parent directory; logs are written into `directory/fileLogs`. Required, so
 *  the DSL takes it as a positional argument and exposes it read-only.
 */
@FileLoggerDsl
public class FileLoggerConfigBuilder internal constructor(public val directory: String) {

    /** Tag substituted when a call site supplies `tag = null`. Defaults to `"FileLogger"`. */
    public var defaultTag: String = DEFAULT_TAG

    /** When true, attach a `LogcatSink` so events also reach `android.util.Log`. */
    public var logcatEnabled: Boolean = true

    /** `DateTimeFormatter` pattern used for both timestamps and file names. */
    public var dateFormatPattern: String = DEFAULT_PATTERN

    /** Events strictly below this severity are filtered before reaching any sink. */
    public var minLevel: LogLevel = LogLevel.Debug

    /** Per-tag override map. An entry overrides `minLevel` for that tag only. */
    public var tagOverrides: Map<String, LogLevel> = emptyMap()

    /** Periodic deletion policy; `null` disables retention sweeps. */
    public var retention: RetentionPolicy? = null

    /** When to start a new file. `None` keeps a single file per session. */
    public var rotation: FileRotationStrategy = FileRotationStrategy.None

    /** Optional message rewriter applied at the producer side. */
    public var interceptor: LogInterceptor? = null

    /** Free-form metadata appended to the startup banner. */
    public var startupData: Map<String, String>? = null

    /** Selects the on-disk format. Defaults to plain text to match v1.x output. */
    public var formatter: FormatterChoice = FormatterChoice.PlainText

    @Suppress("DEPRECATION")
    internal fun build(): Config {
        try {
            DateTimeFormatter.ofPattern(dateFormatPattern, Locale.US)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid dateFormatPattern \"$dateFormatPattern\": ${e.message}",
                e,
            )
        }
        return Config(
            directory = directory,
            defaultTag = defaultTag,
            logcatEnable = logcatEnabled,
            dataFormatterPattern = dateFormatPattern,
            startupData = startupData,
            retentionPolicy = retention,
            logInterceptor = interceptor,
            fileRotationStrategy = rotation,
            minLevel = minLevel,
            tagOverrides = tagOverrides,
            formatter = formatter,
        )
    }
}

/**
 * Top-level entry point for building a `Config`. Idiomatic usage:
 *
 * ```
 * val config = fileLogger(path) {
 *     defaultTag = "MyApp"
 *     minLevel = LogLevel.Info
 *     formatter = FormatterChoice.Json
 * }
 * FileLogger.init(context, config)
 * ```
 *
 * @param directory parent directory; logs are written into `directory/fileLogs`.
 * @param block configuration block; properties default to v1.x-compatible values.
 */
public fun fileLogger(
    directory: String,
    block: FileLoggerConfigBuilder.() -> Unit,
): Config = FileLoggerConfigBuilder(directory).apply(block).build()
