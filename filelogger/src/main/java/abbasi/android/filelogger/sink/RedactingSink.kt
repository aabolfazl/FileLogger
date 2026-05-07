/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.sink

import abbasi.android.filelogger.pipeline.LogEvent

/**
 * Decorator sink that runs every [LogEvent]'s rendered message through a list of regex patterns,
 * replacing matches with [replacement] before forwarding to [delegate]. The decorator owns the
 * order: redaction happens **before** the delegate sees the event, so any sink the delegate fans
 * out to (file, HTTP, logcat) only ever observes the redacted text.
 *
 * Patterns are pre-compiled into a [List] of [Regex]; the canonical defaults ([EMAIL_REGEX],
 * [CREDIT_CARD_REGEX], [BEARER_TOKEN_REGEX], [IPV4_REGEX]) cover the most common leakage sources.
 * Construct with `patterns = emptyList()` to forward unchanged, or pass a custom list for
 * application-specific tokens.
 *
 * Limitations:
 *  - Only the rendered message body is redacted. Stack-trace strings produced by formatters
 *    (`Throwable.stackTraceToString()`) are **not** redacted because the [Throwable] is forwarded
 *    untouched. Wrap callers' messages with a redaction filter higher up the stack if you need
 *    stack-trace-level redaction.
 *  - Messages whose UTF-16 length exceeds [maxMessageBytes] are forwarded **without** redaction
 *    (regex on a multi-megabyte string can dominate the pipeline thread). This is a defensive
 *    cutoff, not a hard limit on log size.
 *
 * Allocation cost: one `String.replace` per pattern per event. The decorator copies the event via
 * `data class` `copy` so future fields on [LogEvent] propagate automatically.
 *
 * @param delegate sink that receives the redacted event. The delegate's `flush`/`close` are
 *  forwarded verbatim; idempotency therefore inherits from the delegate.
 * @param patterns ordered list of regexes to apply. Each pattern's matches are replaced with
 *  [replacement]. Defaults to a built-in list of common leakage sources.
 * @param replacement string substituted for every match. Defaults to `"[REDACTED]"`.
 * @param maxMessageBytes per-message size cutoff (estimated as `length * 2` for UTF-16); messages
 *  above this size are forwarded unchanged. Defaults to 256 KiB.
 */
public class RedactingSink(
    private val delegate: LogSink,
    private val patterns: List<Regex> = DEFAULT_PATTERNS,
    private val replacement: String = "[REDACTED]",
    private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
) : LogSink {

    public override val id: String = "redacting(${delegate.id})"

    public override suspend fun emit(event: LogEvent) {
        val original = event.lazyMessage()
        if (original.length * UTF16_BYTES_PER_CHAR > maxMessageBytes) {
            delegate.emit(event)
            return
        }
        var redacted = original
        for (pattern in patterns) {
            redacted = pattern.replace(redacted, replacement)
        }
        if (redacted === original) {
            delegate.emit(event)
            return
        }
        val redactedEvent = event.copy(lazyMessage = { redacted })
        delegate.emit(redactedEvent)
    }

    public override suspend fun flush() {
        delegate.flush()
    }

    public override suspend fun close() {
        delegate.close()
    }

    private companion object {
        const val UTF16_BYTES_PER_CHAR = 2
        const val DEFAULT_MAX_MESSAGE_BYTES = 256 * 1024
    }
}

/** Matches RFC-5321-ish email addresses. */
public val EMAIL_REGEX: Regex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

/** Matches 13-19 digit numbers separated by spaces or hyphens. Coarse; tune for false positives. */
public val CREDIT_CARD_REGEX: Regex = Regex("""\b(?:\d[ -]*?){13,19}\b""")

/** Matches `Bearer <token>` Authorization header values, case-insensitive. */
public val BEARER_TOKEN_REGEX: Regex = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""")

/** Matches dotted IPv4 addresses. */
public val IPV4_REGEX: Regex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

/**
 * Canonical default redaction list used by [RedactingSink] when the consumer does not supply one.
 * Order matters: more specific patterns (e.g. bearer tokens) are matched before broader ones.
 */
public val DEFAULT_PATTERNS: List<Regex> = listOf(
    BEARER_TOKEN_REGEX,
    EMAIL_REGEX,
    CREDIT_CARD_REGEX,
    IPV4_REGEX,
)
