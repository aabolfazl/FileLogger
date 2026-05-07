/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.pipeline

import abbasi.android.filelogger.file.LogLevel

/**
 * Immutable record of a single log emission. Producers capture raw inputs only — the message body
 * is held as a `() -> String` so formatting work is paid by the consuming sink (after any
 * `minLevel` filter), never by the caller. Timestamp is a raw `Long`; sinks format on demand.
 * `threadName` is captured at construction time on the producer thread, which is the desired
 * value (it identifies who emitted, not who drained). `mdc` is a snapshot of the producer's
 * mapped diagnostic context — it is `emptyMap()` (the singleton) when no context was active so
 * the common case allocates nothing.
 *
 * @param level severity at the time of emission.
 * @param tag optional tag override; sinks fall back to the configured default tag when null.
 * @param lazyMessage deferred message body; invoked at most once per sink that decides to render.
 * @param throwable optional throwable; rendered via `Throwable.stackTraceToString()` by the
 *  bundled formatters.
 * @param timestampMs `System.currentTimeMillis()` captured by the producer.
 * @param threadName the producer thread's name.
 * @param mdc immutable snapshot of the active mapped diagnostic context for this emission.
 */
public data class LogEvent(
    public val level: LogLevel,
    public val tag: String?,
    public val lazyMessage: () -> String,
    public val throwable: Throwable?,
    public val timestampMs: Long,
    public val threadName: String,
    public val mdc: Map<String, String>,
)
