/*
*
* Copyright (c) 2024 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.file

import abbasi.android.filelogger.config.RetentionPolicy
import abbasi.android.filelogger.internal.FileLoggerInternalLog
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Walks the log directory and deletes files that fall outside the active `RetentionPolicy`. The
 * currently-open log file is supplied by `currentFile` and is always skipped. Deletion is
 * cooperative: every loop iteration checks the coroutine for cancellation so a long sweep
 * unwinds promptly when the pipeline shuts down. Creation time is read from `File.lastModified()`
 * — v1.x kept a SharedPreferences mirror, which doubled the I/O for no benefit because rotation
 * never rewrites old files. Failures during a single delete do not abort the whole sweep.
 */
internal class RetentionPolicyChecker(
    private val directory: File,
    private val currentFile: () -> File?,
) {
    suspend operator fun invoke(policy: RetentionPolicy) {
        val open = currentFile()
        val all = directory.listFiles()?.filter { it.isFile && it != open } ?: return
        if (all.isEmpty()) return

        when (policy) {
            is RetentionPolicy.FileCountLimit -> applyFileCount(all, policy.count)
            is RetentionPolicy.FileSizeLimit -> applyFileSize(all, policy.sizeInBytes)
            is RetentionPolicy.TimeToLive -> applyTimeToLive(all, policy.durationInMillis)
        }
    }

    private suspend fun applyFileCount(files: List<File>, max: Int) {
        if (files.size <= max) return
        val sorted = files.sortedBy { it.lastModified() }
        val toDelete = sorted.take(sorted.size - max)
        for (file in toDelete) {
            coroutineContext.ensureActive()
            deleteSafely(file)
        }
    }

    private suspend fun applyFileSize(files: List<File>, maxBytes: Long) {
        val sorted = files.sortedBy { it.lastModified() }
        var total = sorted.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in sorted) {
            coroutineContext.ensureActive()
            if (total <= maxBytes) return
            val len = file.length()
            if (deleteSafely(file)) {
                total -= len
            }
        }
    }

    private suspend fun applyTimeToLive(files: List<File>, ttlMs: Long) {
        val now = System.currentTimeMillis()
        for (file in files) {
            coroutineContext.ensureActive()
            if (now - file.lastModified() > ttlMs) {
                deleteSafely(file)
            }
        }
    }

    private fun deleteSafely(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: SecurityException) {
            FileLoggerInternalLog.warnRateLimited(
                key = "retention.delete",
                msg = "retention delete failed for ${file.name}",
                e = e,
            )
            false
        }
    }
}
