/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.format

import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.time.TimeFormatter

/**
 * Renders a `LogEvent` as one human-readable line. The shape matches v1.x's `FileWriter` output
 * (`{timestamp} {level}/{tag}: {message}` followed by an optional stack-trace block) but the
 * timestamp prefix is now produced here so every sink — file, logcat-formatter consumers, future
 * HTTP — sees identical text. Throwables render via `Throwable.stackTraceToString()` so `cause`
 * and `suppressed` chains are preserved (the v1.x implementation iterated only the top frame's
 * `stackTrace` array and silently dropped both). When the event carries a non-empty MDC the map
 * is appended after the message as ` [k1=v1, k2=v2]`; an empty MDC produces no extra output and
 * no extra allocation.
 *
 * @param timeFormatter formats `event.timestampMs` into the leading timestamp prefix.
 * @param defaultTag tag used when `event.tag` is null.
 */
public class PlainTextFormatter(
    private val timeFormatter: TimeFormatter,
    private val defaultTag: String,
) : LogFormatter {

    public override fun format(event: LogEvent): String {
        val sb = StringBuilder(STARTING_CAPACITY)
        sb.append(timeFormatter.format(event.timestampMs))
        sb.append(' ')
        sb.append(event.level.toString())
        sb.append('/')
        sb.append(event.tag ?: defaultTag)
        sb.append(": ")
        sb.append(event.lazyMessage())
        if (event.mdc.isNotEmpty()) {
            sb.append(" [")
            var first = true
            for ((k, v) in event.mdc) {
                if (!first) sb.append(", ")
                sb.append(k).append('=').append(v)
                first = false
            }
            sb.append(']')
        }
        sb.append('\n')
        val throwable = event.throwable
        if (throwable != null) {
            sb.append(throwable.stackTraceToString())
        }
        return sb.toString()
    }

    private companion object {
        const val STARTING_CAPACITY = 256
    }
}
