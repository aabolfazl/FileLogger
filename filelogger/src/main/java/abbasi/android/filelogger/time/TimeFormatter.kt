/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/**
 * Hot-path timestamp formatter backed by `DateTimeFormatter`. Thread-safe; the pattern is
 * validated at construction. When `followSystemTimeZone` is true the current zone is resolved on
 * every `format` call so mid-session TZ changes are honoured; when false the zone is captured
 * once. Formatters are cached per zone so steady-state allocations are limited to one `Instant`
 * and the result `String` per call.
 *
 * @param pattern `DateTimeFormatter`-compatible pattern. Validated at construction.
 * @param locale rendering locale; defaults to `Locale.US` for stable, parser-friendly output.
 * @param followSystemTimeZone when true (default), each call uses the current `ZoneId.systemDefault()`.
 */
public class TimeFormatter(
    pattern: String,
    locale: Locale = Locale.US,
    followSystemTimeZone: Boolean = true,
) {
    private val base: DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, locale)
    private val zonedCache: ConcurrentHashMap<ZoneId, DateTimeFormatter> = ConcurrentHashMap()
    private val zonedBuilder: Function<ZoneId, DateTimeFormatter> = Function { base.withZone(it) }
    private val fixedFormatter: DateTimeFormatter? =
        if (followSystemTimeZone) null else base.withZone(ZoneId.systemDefault())

    /** Format `epochMs` using the configured pattern and the current (or fixed) system zone. */
    public fun format(epochMs: Long): String {
        val formatter = fixedFormatter ?: zonedCache.computeIfAbsent(ZoneId.systemDefault(), zonedBuilder)
        return formatter.format(Instant.ofEpochMilli(epochMs))
    }
}
