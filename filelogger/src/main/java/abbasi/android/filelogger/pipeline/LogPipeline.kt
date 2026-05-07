/*
*
* Copyright (c) 2022 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.pipeline

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.internal.FileLoggerInternalLog
import abbasi.android.filelogger.sink.LogSink
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owner of FileLogger's coroutine pipeline. The scope is constructed by `newPipelineScope()` and
 * passed in so every sink that needs background work (periodic flush, retention sweep, disk-space
 * poll) shares the same lifetime; `shutdown()` cancels it. Producers call `emit` (non-suspending)
 * and a single drain coroutine fans events out to every registered sink in order. Backpressure is
 * handled by `Channel(DROP_OLDEST)` plus a drop counter that materialises a synthetic warning
 * event on the next successful drain. JVM shutdown and `ON_STOP` lifecycle events are wired here
 * so a normal process exit always flushes; sink failures are isolated per event and rate-limited
 * via `FileLoggerInternalLog`. The `events` flow is the read-only public observation point —
 * consumers can collect it for in-memory mirroring, never push to it.
 *
 * @param scope coroutine scope that owns the drain job and every child sink loop. Build it via
 *  `newPipelineScope()` so `Dispatchers.IO.limitedParallelism(1)` keeps event ordering.
 * @param sinks ordered list of output targets. Failures in one do not affect the others.
 */
public class LogPipeline(
    public val scope: CoroutineScope,
    private val sinks: List<LogSink>,
) {
    private val dropCounter = AtomicLong(0L)

    private val channel = Channel<LogEvent>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { dropCounter.incrementAndGet() },
    )
    private val shutdownRequested = AtomicBoolean(false)

    private val _events = MutableSharedFlow<LogEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Read-only mirror of every successfully fanned-out event. Non-replaying; subscribers receive
     * only events emitted after they start collecting. Backed by a `DROP_OLDEST` buffer of 64 so
     * a slow collector can't stall the pipeline.
     */
    public val events: SharedFlow<LogEvent> = _events.asSharedFlow()

    private val drainJob: Job

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            scope.launch { flush() }
        }
    }

    init {
        drainJob = scope.launch { drain() }
        registerShutdownHook()
        registerLifecycleObserver()
    }

    /**
     * Non-suspending producer entry point. Returns immediately; if the channel buffer is full the
     * oldest event is dropped and counted (a synthetic warn surfaces on the next successful
     * drain). After `shutdown()` returns, calls become no-ops.
     */
    public fun emit(event: LogEvent) {
        channel.trySend(event)
    }

    /** Force every sink to flush. Failures are funnelled through `FileLoggerInternalLog`. */
    public suspend fun flush() {
        sinks.forEach { sink ->
            try {
                sink.flush()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                FileLoggerInternalLog.warnRateLimited(
                    key = "sink.${sink.id}.flush",
                    msg = "sink ${sink.id} flush failed",
                    e = e,
                )
            }
        }
    }

    /**
     * Idempotent. Closes the channel, joins the drain (bounded by `timeoutMs`), then flushes and
     * closes every sink before cancelling the scope. Subsequent calls return without re-running.
     */
    public suspend fun shutdown(timeoutMs: Long = 2_000) {
        if (!shutdownRequested.compareAndSet(false, true)) return
        channel.close()
        withTimeoutOrNull(timeoutMs) { drainJob.join() }
        sinks.forEach { sink ->
            try {
                sink.flush()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                FileLoggerInternalLog.warn("sink ${sink.id} flush on shutdown", e)
            }
            try {
                sink.close()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                FileLoggerInternalLog.warn("sink ${sink.id} close on shutdown", e)
            }
        }
        scope.cancel()
    }

    private suspend fun drain() {
        for (event in channel) {
            val pending = dropCounter.getAndSet(0L)
            if (pending > 0L) {
                fanOut(buildDropNotice(pending, event.timestampMs))
            }
            fanOut(event)
        }
    }

    private suspend fun fanOut(event: LogEvent) {
        sinks.forEach { sink ->
            try {
                sink.emit(event)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                FileLoggerInternalLog.warnRateLimited(
                    key = "sink.${sink.id}",
                    msg = "sink ${sink.id} emit failed",
                    e = e,
                )
            }
        }
        try {
            _events.emit(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            FileLoggerInternalLog.warnRateLimited(
                key = "events.flow",
                msg = "events flow emit failed",
                e = e,
            )
        }
    }

    private fun buildDropNotice(count: Long, timestampMs: Long): LogEvent {
        val text = "dropped $count events due to backpressure"
        return LogEvent(
            level = LogLevel.Warning,
            tag = "FileLogger",
            lazyMessage = { text },
            throwable = null,
            timestampMs = timestampMs,
            threadName = "FileLogger",
            mdc = emptyMap(),
        )
    }

    private fun registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    // runBlocking permitted on JVM shutdown thread (rules/coroutines-dispatchers.md)
                    runBlocking { shutdown(2_000) }
                },
            )
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("failed to register JVM shutdown hook", e)
        }
    }

    private fun registerLifecycleObserver() {
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        } catch (e: Exception) {
            FileLoggerInternalLog.warn("failed to register process lifecycle observer", e)
        }
    }
}
