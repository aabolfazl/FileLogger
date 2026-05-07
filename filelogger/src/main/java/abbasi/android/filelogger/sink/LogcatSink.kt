/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.sink

import abbasi.android.filelogger.config.DEFAULT_TAG
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.pipeline.LogEvent
import android.util.Log

/**
 * Mirrors events to Android's `logcat` via `Log.i/d/w/e`. Holds no state and performs no I/O
 * beyond the JNI calls into `liblog`; `flush` and `close` are no-ops. Behaviour change vs. v1.x:
 * logcat dispatch now happens on the pipeline thread asynchronously rather than inline at the
 * call site. For a logger that's the right tradeoff — the producer never blocks on JNI — and it
 * matches Timber / log4j / slf4j. No formatter is applied: logcat owns its own framing
 * (timestamp, PID, TID, level, tag), and re-rendering the same metadata twice is wasted work.
 * `minLevel` filters early so dropped events do not pay even the JNI cost.
 *
 * @param minLevel events with `event.level.ordinal` strictly less than `minLevel.ordinal` are
 *  skipped.
 */
public class LogcatSink(
    private val minLevel: LogLevel = LogLevel.Debug,
) : LogSink {
    public override val id: String = "logcat"

    public override suspend fun emit(event: LogEvent) {
        if (event.level.ordinal < minLevel.ordinal) return
        val tag = event.tag ?: DEFAULT_TAG
        val msg = event.lazyMessage()
        when (event.level) {
            LogLevel.Debug -> Log.d(tag, msg, event.throwable)
            LogLevel.Info -> Log.i(tag, msg, event.throwable)
            LogLevel.Warning -> Log.w(tag, msg, event.throwable)
            LogLevel.Error -> Log.e(tag, msg, event.throwable)
        }
    }

    public override suspend fun flush(): Unit = Unit

    public override suspend fun close(): Unit = Unit
}
