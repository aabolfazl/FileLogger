/**
 * Covers `LoggerImpl` filtering: global `minLevel`, per-tag overrides, master `isEnabled`, lazy
 * lambda short-circuit, and `LogEvent` construction shape. Uses a real `LogPipeline` with a
 * `FakeSink` to assert what the pipeline ultimately receives. Robolectric is required because the
 * pipeline registers a `ProcessLifecycleOwner` observer at construction time.
 */
package abbasi.android.filelogger

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.pipeline.LogPipeline
import abbasi.android.filelogger.sink.FakeSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoggerImplTest {

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

    @Test
    fun `minLevel info filters debug`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(pipeline = pipe, defaultTag = "T", minLevel = LogLevel.Info)

        logger.d(message = "drop me")
        logger.i(message = "keep me")
        yield()

        assertEquals(1, fake.events.size)
        assertEquals(LogLevel.Info, fake.events.single().level)
        pipe.shutdown(2_000)
    }

    @Test
    fun `tag override raises threshold for one tag`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(
            pipeline = pipe,
            defaultTag = "T",
            minLevel = LogLevel.Debug,
            tagOverrides = mapOf("Network" to LogLevel.Warning),
        )

        logger.d(tag = "Network", message = "skip")
        logger.d(tag = "Other", message = "keep")
        logger.w(tag = "Network", message = "keep too")
        yield()

        assertEquals(2, fake.events.size)
        assertTrue(fake.events.none { it.tag == "Network" && it.level == LogLevel.Debug })
        pipe.shutdown(2_000)
    }

    @Test
    fun `isEnabled false short-circuits everything`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(pipeline = pipe, minLevel = LogLevel.Debug)
        logger.isEnabled = false

        logger.i(message = "nope")
        logger.e(message = "still nope")
        yield()

        assertTrue(fake.events.isEmpty())
        pipe.shutdown(2_000)
    }

    @Test
    fun `lazy overload lambda is not invoked when filtered`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(pipeline = pipe, minLevel = LogLevel.Warning)
        var counted = 0

        logger.d { counted++; "expensive" }
        logger.i { counted++; "expensive" }
        yield()

        assertEquals(0, counted)
        pipe.shutdown(2_000)
    }

    @Test
    fun `eager overload still works under filter`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(pipeline = pipe, minLevel = LogLevel.Info)

        logger.i(message = "hi")
        yield()

        assertEquals("hi", fake.events.single().lazyMessage())
        pipe.shutdown(2_000)
    }

    @Test
    fun `null tag resolves to default tag`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(pipeline = pipe, defaultTag = "Default")

        logger.i(message = "x")
        yield()

        assertEquals("Default", fake.events.single().tag)
        pipe.shutdown(2_000)
    }

    @Test
    fun `event reaches pipeline with correct level tag and throwable`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(pipeline = pipe, defaultTag = "T")
        val ex = RuntimeException("boom")

        logger.e(tag = "Boot", message = "broke", throwable = ex)
        yield()

        val ev = fake.events.single()
        assertEquals(LogLevel.Error, ev.level)
        assertEquals("Boot", ev.tag)
        assertEquals("broke", ev.lazyMessage())
        assertEquals(ex, ev.throwable)
        pipe.shutdown(2_000)
    }

    @Test
    fun `null throwable on info`() = runTest {
        val s = newScope()
        val fake = FakeSink()
        val pipe = LogPipeline(scope = s, sinks = listOf(fake))
        val logger = LoggerImpl(pipeline = pipe)

        logger.i(message = "ok")
        yield()

        assertNull(fake.events.single().throwable)
        pipe.shutdown(2_000)
    }
}
