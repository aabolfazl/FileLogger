/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.format

import abbasi.android.filelogger.pipeline.LogEvent

/**
 * Stateless contract for serialising a `LogEvent` into a single textual record (typically one
 * line, terminated by `\n`). Implementations must be pure and non-suspending: sinks call them on
 * the pipeline thread, and any I/O or coroutine work must happen in the sink, not here.
 */
public interface LogFormatter {
    /** Render `event` as a self-contained record. Pure; must not perform I/O. */
    public fun format(event: LogEvent): String
}
