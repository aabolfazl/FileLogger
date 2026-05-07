/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.pipeline.LogEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Public entry point for emitting log events. Implementations are non-blocking: every method
 * returns immediately; rendering and I/O happen on the pipeline's background dispatcher. Both
 * the eager (`String`) and lazy (`() -> String`) overloads are supplied — prefer the lazy
 * variants for messages whose construction is non-trivial, since the lambda is invoked only when
 * `isLoggable(level, tag)` returns true. `tag` is optional; when null, the implementation falls
 * back to the configured default tag. `events` mirrors every fanned-out event; subscribers see
 * only emissions that occur after they begin collecting.
 */
public interface Logger {

    /**
     * Master switch. Set to `false` to short-circuit every `d/i/w/e` call without tearing down
     * the pipeline; in-flight events still drain. Thread-safe.
     */
    public var isEnabled: Boolean

    /**
     * Whether an event at `level` with the given `tag` would be emitted given the current
     * `minLevel` and per-tag overrides. `false` when `isEnabled` is `false`. Used by the lazy
     * overloads to skip lambda invocation when the event would be filtered out.
     */
    public fun isLoggable(level: LogLevel, tag: String? = null): Boolean

    /** Emit a `Debug` event with an eagerly-built message. */
    public fun d(tag: String? = null, message: String, throwable: Throwable? = null)

    /** Emit an `Info` event with an eagerly-built message. */
    public fun i(tag: String? = null, message: String, throwable: Throwable? = null)

    /** Emit a `Warning` event with an eagerly-built message. */
    public fun w(tag: String? = null, message: String, throwable: Throwable? = null)

    /** Emit an `Error` event with an eagerly-built message. */
    public fun e(tag: String? = null, message: String, throwable: Throwable? = null)

    /**
     * Emit a `Debug` event whose message body is built by `message`. The lambda is invoked at
     * most once and only when `isLoggable(Debug, tag)` returns true. Non-capturing lambdas (e.g.
     * `{ "message" }`) are singleton-cached by the Kotlin compiler and produce no per-call
     * allocation.
     */
    public fun d(tag: String? = null, throwable: Throwable? = null, message: () -> String)

    /** Lazy `Info` overload. See `d(tag, throwable, message)`. */
    public fun i(tag: String? = null, throwable: Throwable? = null, message: () -> String)

    /** Lazy `Warning` overload. See `d(tag, throwable, message)`. */
    public fun w(tag: String? = null, throwable: Throwable? = null, message: () -> String)

    /** Lazy `Error` overload. See `d(tag, throwable, message)`. */
    public fun e(tag: String? = null, throwable: Throwable? = null, message: () -> String)

    /**
     * Read-only mirror of fanned-out events. Subscribe with `events.collect { ... }` to observe
     * every emission post-init; before init, the flow never emits.
     */
    public val events: SharedFlow<LogEvent>
}
