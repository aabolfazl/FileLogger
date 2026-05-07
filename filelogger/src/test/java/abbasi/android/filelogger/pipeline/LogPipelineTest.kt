/**
 * Covers `LogPipeline` ordering, sink failure isolation, `events` SharedFlow visibility,
 * idempotent shutdown, and the synthetic drop-notice path. Robolectric is required because the
 * pipeline registers a `ProcessLifecycleOwner` observer and reads `SystemClock.elapsedRealtime`
 * via the rate-limiter.
 *
 * Each test constructs the pipeline on an `UnconfinedTestDispatcher`-backed scope so the drain
 * coroutine progresses eagerly without explicit `advanceUntilIdle` calls.
 */
package abbasi.android.filelogger.pipeline

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.sink.FakeSink
import abbasi.android.filelogger.sink.LogSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogPipelineTest {

    private var testScope: CoroutineScope? = null

    @After
    fun tearDown() {
        testScope?.cancel()
        testScope = null
    }

    private fun newScope(): CoroutineScope {
        val s = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        testScope = s
        return s
    }

    private fun makeEvent(message: String, ts: Long = 0L): LogEvent = LogEvent(
        level = LogLevel.Info,
        tag = "T",
        lazyMessage = { message },
        throwable = null,
        timestampMs = ts,
        threadName = "t",
        mdc = emptyMap(),
    )

    @Test
    fun `events arrive at sinks in emit order`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))

        repeat(20) { pipe.emit(makeEvent("m$it")) }
        yield()

        assertEquals(20, fake.events.size)
        fake.events.forEachIndexed { i, ev -> assertEquals("m$i", ev.lazyMessage()) }
        pipe.shutdown(2_000)
    }

    @Test
    fun `failing sink does not affect other sinks`() = runTest {
        val s = newScope()
        val good = FakeSink(id = "good")
        val bad = FakeSink(id = "bad", onEmit = { throw RuntimeException("nope") })
        val pipe = LogPipeline(scope = s, sinks = listOf(bad, good))

        repeat(5) { pipe.emit(makeEvent("m$it")) }
        yield()

        assertEquals(5, good.events.size)
        pipe.shutdown(2_000)
    }

    @Test
    fun `subscriber on events flow sees emissions`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val seen = AtomicInteger(0)
        val collectorJob = s.launch {
            pipe.events.collect { seen.incrementAndGet() }
        }
        yield()
        repeat(3) { pipe.emit(makeEvent("m$it")) }
        yield()
        collectorJob.cancel()
        assertTrue("expected at least 1 collected, got ${seen.get()}", seen.get() >= 1)
        pipe.shutdown(2_000)
    }

    @Test
    fun `shutdown is idempotent`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        pipe.emit(makeEvent("m1"))
        yield()
        pipe.shutdown(2_000)
        val flushAfterFirst = fake.flushCount.get()
        val closeAfterFirst = fake.closeCount.get()
        pipe.shutdown(2_000)
        assertEquals(flushAfterFirst, fake.flushCount.get())
        assertEquals(closeAfterFirst, fake.closeCount.get())
    }

    @Test
    fun `shutdown flushes and closes every sink`() = runTest {
        val s = newScope()
        val a = FakeSink(id = "a")
        val b = FakeSink(id = "b")
        val pipe = LogPipeline(scope = s, sinks = listOf(a, b))
        pipe.shutdown(2_000)
        assertEquals(1, a.flushCount.get())
        assertEquals(1, b.flushCount.get())
        assertEquals(1, a.closeCount.get())
        assertEquals(1, b.closeCount.get())
    }

    @Test
    fun `flood does not crash the pipeline`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        repeat(2_000) { pipe.emit(makeEvent("m$it")) }
        yield()
        assertTrue("expected at least some events delivered", fake.events.isNotEmpty())
        pipe.shutdown(2_000)
    }

    @Test
    fun `drop counter under overflow surfaces a synthetic warning on next drain`() = runTest {
        val s = newScope()
        val gate = CompletableDeferred<Unit>()
        val captured = mutableListOf<LogEvent>()
        val sink = object : LogSink {
            override val id: String = "gated"
            private var seen = 0
            override suspend fun emit(event: LogEvent) {
                if (seen == 0) gate.await()
                seen++
                captured.add(event)
            }
            override suspend fun flush() = Unit
            override suspend fun close() = Unit
        }
        val pipe = LogPipeline(scope = s, sinks = listOf(sink))

        pipe.emit(makeEvent("first"))
        yield()

        repeat(2_000) { pipe.emit(makeEvent("flood$it")) }

        gate.complete(Unit)
        yield()

        val notice = captured.find { ev -> ev.tag == "FileLogger" && "dropped" in ev.lazyMessage() }
        assertEquals(LogLevel.Warning, notice?.level)
        val firstIndex = captured.indexOfFirst { it.lazyMessage() == "first" }
        val noticeIndex = captured.indexOf(notice)
        assertTrue("synthetic notice must come after the first event", noticeIndex > firstIndex)

        pipe.shutdown(2_000)
    }

    @Test
    fun `events flow signals when an event is fanned out`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val total = AtomicInteger(0)
        val collectorJob = s.launch {
            pipe.events.collect { total.incrementAndGet() }
        }
        yield()
        pipe.emit(makeEvent("first"))
        yield()
        collectorJob.cancel()
        assertNotEquals(0, total.get())
        pipe.shutdown(2_000)
    }
}
