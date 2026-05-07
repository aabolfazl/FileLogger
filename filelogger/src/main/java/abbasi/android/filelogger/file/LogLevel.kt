/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.file

/**
 * Severity of a log emission. Declaration order is severity order, so `level.ordinal` is the
 * single source of truth for filtering — a level is loggable when its ordinal is greater than or
 * equal to the configured `minLevel.ordinal`. `toString()` returns the one-letter abbreviation
 * (`D/I/W/E`) used by `PlainTextFormatter` and `JsonFormatter`; this is also the on-disk shape
 * v1.x emitted, so existing log files keep their schema after the upgrade.
 */
public enum class LogLevel(private val abbreviation: String) {
    /** Verbose / debug output. Filtered out by default in release configurations. */
    Debug("D"),

    /** Informational messages. The default `minLevel`. */
    Info("I"),

    /** Recoverable problems and unusual states. */
    Warning("W"),

    /** Failures the caller could not recover from. */
    Error("E"),
    ;

    public override fun toString(): String = abbreviation
}
