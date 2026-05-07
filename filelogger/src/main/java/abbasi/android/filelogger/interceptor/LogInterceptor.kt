/*
*
* Copyright (c) 2024 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.interceptor

import abbasi.android.filelogger.file.LogLevel

/**
 * Producer-side hook that rewrites a message before it is published to the pipeline. Implemented
 * by hosts that need to redact, augment, or normalise messages globally. Runs synchronously on
 * the caller's thread, so implementations must be cheap and pure — heavy work belongs in a sink.
 */
public interface LogInterceptor {
    /** Return the rewritten message; returning `message` unchanged is a valid no-op. */
    public fun intercept(level: LogLevel, tag: String, message: String, e: Throwable?): String
}
