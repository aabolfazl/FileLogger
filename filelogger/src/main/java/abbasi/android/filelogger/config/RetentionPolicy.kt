/*
*
* Copyright (c) 2024 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.config

/**
 * Strategy for deleting old log files. Applied periodically by `FileSink` and after every
 * rotation. The currently-open file is never deleted regardless of policy. Subtypes are exhaustive
 * — adding one is a binary break.
 */
public sealed interface RetentionPolicy {
    /** Keep at most `count` rotated files; oldest are deleted first. */
    public data class FileCountLimit(public val count: Int) : RetentionPolicy

    /** Keep total size of rotated files at or below `sizeInBytes`; oldest are deleted first. */
    public data class FileSizeLimit(public val sizeInBytes: Long) : RetentionPolicy

    /** Delete files whose `lastModified()` is older than `durationInMillis` from now. */
    public data class TimeToLive(public val durationInMillis: Long) : RetentionPolicy
}
