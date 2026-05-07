/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.format

import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.time.TimeFormatter

/**
 * Emits one JSON object per event in JSON-Lines (`.jsonl`) form. Hand-rolls escaping rather than
 * pulling in `org.json` or `kotlinx-serialization`: keeping `:filelogger` zero-runtime-dep is the
 * point of this module. The line is `{"ts":...,"level":"I","tag":...,"msg":...,"thread":...,
 * "throwable":...,"mdc":{...}}` followed by `\n`. A null throwable serialises as the JSON literal
 * `null`, not the string `"null"`. The `mdc` field is omitted entirely when the event's context
 * is empty so common-case lines stay compact and parsers see one less field. Output is UTF-8;
 * non-ASCII bytes pass through unchanged because the file charset is also UTF-8.
 *
 * @param timeFormatter formats `event.timestampMs` into the `"ts"` field.
 * @param defaultTag tag used when `event.tag` is null.
 */
public class JsonFormatter(
    private val timeFormatter: TimeFormatter,
    private val defaultTag: String,
) : LogFormatter {

    public override fun format(event: LogEvent): String {
        val sb = StringBuilder(STARTING_CAPACITY)
        sb.append('{')
        sb.append("\"ts\":")
        sb.appendJsonString(timeFormatter.format(event.timestampMs))
        sb.append(",\"level\":")
        sb.appendJsonString(event.level.toString())
        sb.append(",\"tag\":")
        sb.appendJsonString(event.tag ?: defaultTag)
        sb.append(",\"msg\":")
        sb.appendJsonString(event.lazyMessage())
        sb.append(",\"thread\":")
        sb.appendJsonString(event.threadName)
        sb.append(",\"throwable\":")
        val throwable = event.throwable
        if (throwable == null) {
            sb.append("null")
        } else {
            sb.appendJsonString(throwable.stackTraceToString())
        }
        if (event.mdc.isNotEmpty()) {
            sb.append(",\"mdc\":{")
            var first = true
            for ((k, v) in event.mdc) {
                if (!first) sb.append(',')
                sb.appendJsonString(k)
                sb.append(':')
                sb.appendJsonString(v)
                first = false
            }
            sb.append('}')
        }
        sb.append('}')
        sb.append('\n')
        return sb.toString()
    }

    private companion object {
        const val STARTING_CAPACITY = 384
    }
}

internal fun StringBuilder.appendJsonString(value: String) {
    append('"')
    var i = 0
    val len = value.length
    while (i < len) {
        val c = value[i]
        when (c.code) {
            QUOTE -> append("\\\"")
            BACKSLASH -> append("\\\\")
            NEWLINE -> append("\\n")
            CARRIAGE_RETURN -> append("\\r")
            TAB -> append("\\t")
            BACKSPACE -> append("\\b")
            FORM_FEED -> append("\\f")
            else -> {
                if (c.code < 0x20) {
                    append("\\u")
                    val hex = Integer.toHexString(c.code)
                    repeat(4 - hex.length) { append('0') }
                    append(hex)
                } else {
                    append(c)
                }
            }
        }
        i++
    }
    append('"')
}

private const val QUOTE: Int = 0x22
private const val BACKSLASH: Int = 0x5C
private const val NEWLINE: Int = 0x0A
private const val CARRIAGE_RETURN: Int = 0x0D
private const val TAB: Int = 0x09
private const val BACKSPACE: Int = 0x08
private const val FORM_FEED: Int = 0x0C
