/**
 * Test-internal `LogSink` that captures emitted events into a list. Used across redaction and
 * pipeline tests as the terminal sink so behaviour can be asserted directly. `flush` and `close`
 * counters are exposed so delegation can be verified.
 */
package abbasi.android.filelogger.sink

import abbasi.android.filelogger.pipeline.LogEvent
import java.util.concurrent.atomic.AtomicInteger

internal class FakeSink(
    override val id: String = "fake",
    private val onEmit: ((LogEvent) -> Unit)? = null,
) : LogSink {

    val events: MutableList<LogEvent> = mutableListOf()
    val flushCount: AtomicInteger = AtomicInteger(0)
    val closeCount: AtomicInteger = AtomicInteger(0)

    override suspend fun emit(event: LogEvent) {
        events.add(event)
        onEmit?.invoke(event)
    }

    override suspend fun flush() {
        flushCount.incrementAndGet()
    }

    override suspend fun close() {
        closeCount.incrementAndGet()
    }
}
