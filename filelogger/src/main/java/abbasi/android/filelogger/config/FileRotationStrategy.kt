/*
*
* Copyright (c) 2024 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.config

/**
 * Decides when `FileSink` should close the current file and open a new one. Subtypes are
 * exhaustive — adding one is a binary break.
 */
public sealed interface FileRotationStrategy {
    /** Single file per session; never rotate. */
    public object None : FileRotationStrategy

    /** Rotate when the current file's age exceeds `intervalInMillis`. */
    public data class TimeBased(public val intervalInMillis: Long) : FileRotationStrategy
}
