/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.sink

import abbasi.android.filelogger.pipeline.LogEvent

/**
 * Output target for a `LogEvent`. The pipeline drives every sink on a single ordered dispatcher
 * (`Dispatchers.IO.limitedParallelism(1)`), so implementations must not pin a dispatcher and must
 * not call back into `FileLogger` (recursion). Failures are isolated per event by the pipeline:
 * a sink may throw from `emit`, and other sinks still receive the event. `close()` must be
 * idempotent — the pipeline may invoke it once on graceful shutdown and again on JVM exit.
 */
public interface LogSink {
    /** Stable identifier used by `FileLoggerInternalLog` rate-limiting keys. Unique per sink. */
    public val id: String

    /**
     * Render and write `event`. Called serially on the pipeline dispatcher. May throw — the
     * pipeline isolates failures per event per sink.
     */
    public suspend fun emit(event: LogEvent)

    /** Force any buffered output to its underlying medium. May be a no-op. */
    public suspend fun flush()

    /** Release resources. Idempotent; may be invoked more than once during shutdown. */
    public suspend fun close()
}
