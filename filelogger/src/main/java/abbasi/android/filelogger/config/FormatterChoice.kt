/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.config

/**
 * Selects the on-disk format used by `FileSink`. The DSL exposes this enum rather than a raw
 * `LogFormatter` instance because the formatter requires a `TimeFormatter` and a default tag —
 * both of which the DSL already owns. `FileLogger.init` constructs the actual `LogFormatter`
 * from this choice so consumers do not handle wiring.
 */
public enum class FormatterChoice {
    /** Human-readable single-line format (`{timestamp} {level}/{tag}: {message}`). The default. */
    PlainText,

    /** JSON-Lines format suitable for log aggregators that ingest `.jsonl`. */
    Json,
}
