/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.config

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.interceptor.LogInterceptor

/**
 * Immutable configuration consumed by `FileLogger.init`. Build via the `fileLogger { }` DSL —
 * `Config.Builder` is preserved for v1.x source compatibility but deprecated. Defaults match
 * v1.x behaviour: plain-text format, debug minimum level, no per-tag overrides.
 *
 * @param directory parent directory; logs are written into `directory/fileLogs`.
 * @param defaultTag tag substituted when a call site supplies `tag = null`.
 * @param logcatEnable when true, a `LogcatSink` mirrors events to `android.util.Log`.
 * @param dataFormatterPattern `DateTimeFormatter` pattern for timestamps and file names.
 * @param startupData free-form metadata appended to the startup banner.
 * @param retentionPolicy deletion policy applied periodically; null disables sweeps.
 * @param logInterceptor optional message rewriter applied at the producer side.
 * @param fileRotationStrategy when to start a new file.
 * @param minLevel events strictly below this severity are filtered before reaching any sink.
 * @param tagOverrides per-tag overrides for `minLevel`.
 * @param formatter selects the on-disk format (`PlainText` or `Json`).
 */
public class Config internal constructor(
    public val directory: String,
    public val defaultTag: String,
    public val logcatEnable: Boolean,
    public val dataFormatterPattern: String,
    public val startupData: Map<String, String>?,
    public val retentionPolicy: RetentionPolicy?,
    public val logInterceptor: LogInterceptor?,
    public val fileRotationStrategy: FileRotationStrategy,
    public val minLevel: LogLevel,
    public val tagOverrides: Map<String, LogLevel>,
    public val formatter: FormatterChoice,
) {

    /**
     * Legacy builder retained for v1.x source compatibility. New code should use
     * `fileLogger(directory) { ... }` from the DSL — every setter below is also deprecated.
     */
    @Deprecated(
        message = "Use the fileLogger { } DSL.",
        replaceWith = ReplaceWith(
            "fileLogger(directory) { }",
            "abbasi.android.filelogger.dsl.fileLogger",
        ),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    public class Builder(private val directory: String) {
        private var defaultTag: String = DEFAULT_TAG
        private var logcatEnable: Boolean = LOGCAT_ENABLE
        private var dataFormatterPattern: String = DEFAULT_PATTERN
        private var startupData: Map<String, String>? = null
        private var retentionPolicy: RetentionPolicy? = null
        private var logInterceptor: LogInterceptor? = null
        private var fileRotationStrategy: FileRotationStrategy = FileRotationStrategy.None
        private var minLevel: LogLevel = LogLevel.Debug
        private var tagOverrides: Map<String, LogLevel> = emptyMap()
        private var formatter: FormatterChoice = FormatterChoice.PlainText

        @Deprecated(
            message = "Use the fileLogger { } DSL: defaultTag = …",
            replaceWith = ReplaceWith("apply { /* defaultTag = defaultTag */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setDefaultTag(defaultTag: String): Builder = apply {
            this.defaultTag = defaultTag
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: logcatEnabled = …",
            replaceWith = ReplaceWith("apply { /* logcatEnabled = logcatEnable */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setLogcatEnable(logcatEnable: Boolean): Builder = apply {
            this.logcatEnable = logcatEnable
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: startupData = …",
            replaceWith = ReplaceWith("apply { /* startupData = startupData */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setStartupData(startupData: Map<String, String>?): Builder = apply {
            this.startupData = startupData
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: retention = …",
            replaceWith = ReplaceWith("apply { /* retention = retentionPolicy */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setRetentionPolicy(retentionPolicy: RetentionPolicy?): Builder = apply {
            this.retentionPolicy = retentionPolicy
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: interceptor = …",
            replaceWith = ReplaceWith("apply { /* interceptor = logInterceptor */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setLogInterceptor(logInterceptor: LogInterceptor?): Builder = apply {
            this.logInterceptor = logInterceptor
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: rotation = …",
            replaceWith = ReplaceWith("apply { /* rotation = fileRotationStrategy */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setNewFileStrategy(fileRotationStrategy: FileRotationStrategy): Builder = apply {
            this.fileRotationStrategy = fileRotationStrategy
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: dateFormatPattern = …",
            replaceWith = ReplaceWith("apply { /* dateFormatPattern = pattern */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setDataFormatterPattern(pattern: String): Builder = apply {
            val sanitized = pattern.replace("/", "-")
                .replace(" ", "")
                .trim()
            this.dataFormatterPattern = if (pattern.isEmpty() || pattern.contains("/")) {
                DEFAULT_PATTERN
            } else {
                sanitized
            }
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: minLevel = …",
            replaceWith = ReplaceWith("apply { /* minLevel = level */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setMinLevel(level: LogLevel): Builder = apply {
            this.minLevel = level
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: tagOverrides = …",
            replaceWith = ReplaceWith("apply { /* tagOverrides = overrides */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setTagOverrides(overrides: Map<String, LogLevel>): Builder = apply {
            this.tagOverrides = overrides
        }

        @Deprecated(
            message = "Use the fileLogger { } DSL: formatter = …",
            replaceWith = ReplaceWith("apply { /* formatter = formatter */ }"),
            level = DeprecationLevel.WARNING,
        )
        public fun setFormatter(formatter: FormatterChoice): Builder = apply {
            this.formatter = formatter
        }

        public fun build(): Config = Config(
            directory = directory,
            defaultTag = defaultTag,
            logcatEnable = logcatEnable,
            dataFormatterPattern = dataFormatterPattern,
            startupData = startupData,
            retentionPolicy = retentionPolicy,
            logInterceptor = logInterceptor,
            fileRotationStrategy = fileRotationStrategy,
            minLevel = minLevel,
            tagOverrides = tagOverrides,
            formatter = formatter,
        )
    }
}
