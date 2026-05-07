/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.mdc

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Mapped Diagnostic Context for FileLogger. Holds a thread-local immutable `Map<String, String>`
 * that is automatically captured into every emitted `LogEvent`. Use `Mdc.with(...) { ... }` for
 * synchronous code and `Mdc.withSuspending(...) { ... }` for code that crosses suspension points
 * — the suspending variant pins the map to the coroutine via a `ThreadContextElement` so it
 * survives dispatcher hops. The empty case is allocation-free: `currentSnapshot()` returns the
 * `emptyMap()` singleton when no scope has been pushed, so logging without MDC pays nothing.
 */
public object Mdc {

    private val threadLocal: ThreadLocal<Map<String, String>> = ThreadLocal()

    /**
     * Push a context layer for the duration of `block`, returning whatever `block` returns. The
     * caller's pairs win on key collision with the current snapshot. The previous map is restored
     * in a `finally`, so an exception thrown from `block` does not leak context to subsequent
     * calls on the same thread. Inline so non-capturing blocks do not allocate a closure.
     */
    public inline fun <R> with(vararg pairs: Pair<String, String>, block: () -> R): R {
        val previous = currentSnapshotInternal()
        val merged = if (pairs.isEmpty()) previous else previous + pairs
        setInternal(merged)
        try {
            return block()
        } finally {
            setInternal(previous)
        }
    }

    /**
     * Suspending counterpart to `with`. Pins the merged map to the coroutine context via a
     * `ThreadContextElement` so it survives dispatcher switches and structured-concurrency forks.
     * Outside the `block`, the thread-local is restored to whatever it was before the call.
     */
    public suspend fun <R> withSuspending(
        vararg pairs: Pair<String, String>,
        block: suspend () -> R,
    ): R {
        val previous = currentSnapshotInternal()
        val merged = if (pairs.isEmpty()) previous else previous + pairs
        return withContext(MdcContextElement(merged)) { block() }
    }

    /**
     * Immutable snapshot of the current MDC for capture into a `LogEvent`. Returns `emptyMap()`
     * (the JVM singleton) when no scope is active, so the empty case is allocation-free.
     */
    public fun currentSnapshot(): Map<String, String> = currentSnapshotInternal()

    @PublishedApi
    internal fun currentSnapshotInternal(): Map<String, String> = threadLocal.get() ?: emptyMap()

    @PublishedApi
    internal fun setInternal(map: Map<String, String>) {
        if (map.isEmpty()) threadLocal.remove() else threadLocal.set(map)
    }

    private class MdcContextElement(
        private val map: Map<String, String>,
    ) : ThreadContextElement<Map<String, String>> {
        override val key: CoroutineContext.Key<*> = Key

        override fun updateThreadContext(context: CoroutineContext): Map<String, String> {
            val previous = currentSnapshotInternal()
            setInternal(map)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Map<String, String>) {
            setInternal(oldState)
        }

        companion object Key : CoroutineContext.Key<MdcContextElement>
    }
}
