/**
 * Covers `HttpSink` upload outcomes against `MockWebServer`: 2xx success, permanent 4xx
 * (no retry), transient 5xx (retried with backoff), in-memory queue overflow, and the
 * `WIFI_ONLY` connectivity gate. Robolectric supplies `Context` and the `ConnectivityManager`
 * shadow.
 *
 * `runBlocking` rather than `runTest` is used here because every test depends on real wall-clock
 * progress (network round-trips to `MockWebServer`, the uploader loop's idle poll, retry
 * back-off). `runTest` virtualises `delay`, which would short-circuit those waits and leave the
 * uploader idle. The `kotlinx-coroutines-test` rule lists `@Test` as a `runBlocking` exception
 * for exactly this scenario.
 *
 * Known limitations: precise backoff timings rely on `kotlin.random.Random` jitter and real
 * coroutine `delay`; the retry test asserts request count rather than exact delays.
 */
package abbasi.android.filelogger.sink

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.format.JsonFormatter
import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.time.TimeFormatter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HttpSinkTest {

    private lateinit var server: MockWebServer
    private lateinit var formatter: JsonFormatter
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        formatter = JsonFormatter(TimeFormatter("yyyy", followSystemTimeZone = false), "T")
        ensureActiveNetwork()
    }

    private fun ensureActiveNetwork() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return
        val existingCaps = cm.getNetworkCapabilities(active)
            ?: org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        val capShadow = Shadows.shadowOf(existingCaps)
        capShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        capShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        capShadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(active, existingCaps)
    }

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
        server.shutdown()
    }

    private fun newScope(): CoroutineScope {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        scope = s
        return s
    }

    private fun makeEvent(msg: String): LogEvent = LogEvent(
        level = LogLevel.Info,
        tag = "T",
        lazyMessage = { msg },
        throwable = null,
        timestampMs = 0L,
        threadName = "t",
        mdc = emptyMap(),
    )

    private fun makeSink(
        s: CoroutineScope,
        batchSize: Int = 1,
        maxRetries: Int = 1,
        maxQueuedBatches: Int = 500,
        networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    ): HttpSink = HttpSink(
        endpoint = server.url("/logs").toString(),
        context = ApplicationProvider.getApplicationContext(),
        scope = s,
        formatter = formatter,
        batchSize = batchSize,
        flushInterval = 30_000.milliseconds,
        maxRetries = maxRetries,
        networkPolicy = networkPolicy,
        maxQueuedBatches = maxQueuedBatches,
    )

    private suspend fun waitForRequests(count: Int, timeoutMs: Long = 5_000L): Int =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (server.requestCount >= count) return@withContext server.requestCount
                delay(50)
            }
            server.requestCount
        }

    @Test
    fun `200 success drops batch`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val s = newScope()
        val sink = makeSink(s, batchSize = 1)

        withContext(s.coroutineContext) { sink.emit(makeEvent("hello")) }
        waitForRequests(1)
        withContext(s.coroutineContext) { sink.close() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `permanent 4xx drops batch without retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200))
        val s = newScope()
        val sink = makeSink(s, batchSize = 1, maxRetries = 5)

        withContext(s.coroutineContext) { sink.emit(makeEvent("a")) }
        waitForRequests(1)
        withContext(s.coroutineContext) { sink.emit(makeEvent("b")) }
        waitForRequests(2)
        withContext(s.coroutineContext) { sink.close() }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `transient 5xx triggers a retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))
        val s = newScope()
        val sink = makeSink(s, batchSize = 1, maxRetries = 2)

        withContext(s.coroutineContext) { sink.emit(makeEvent("retried")) }
        waitForRequests(2, timeoutMs = 10_000L)
        withContext(s.coroutineContext) { sink.close() }
        assertTrue("expected at least 2 requests, got ${server.requestCount}", server.requestCount >= 2)
    }

    @Test
    fun `WIFI_ONLY policy gates uploads when no active network`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        Shadows.shadowOf(cm).clearAllNetworks()

        server.enqueue(MockResponse().setResponseCode(200))
        val s = newScope()
        val sink = makeSink(s, batchSize = 1, networkPolicy = NetworkPolicy.WIFI_ONLY)

        withContext(s.coroutineContext) { sink.emit(makeEvent("blocked")) }
        withContext(Dispatchers.IO) { delay(500) }
        withContext(s.coroutineContext) { sink.close() }
        assertEquals("expected upload to be gated by WIFI_ONLY", 0, server.requestCount)
    }

    @Test
    fun `queue overflow drops oldest and forwards subsequent batches`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val s = newScope()
        val sink = HttpSink(
            endpoint = server.url("/logs").toString(),
            context = ApplicationProvider.getApplicationContext(),
            scope = s,
            formatter = formatter,
            batchSize = 1,
            flushInterval = 30_000.milliseconds,
            maxRetries = 0,
            networkPolicy = NetworkPolicy.ANY,
            maxQueuedBatches = 1,
        )

        withContext(s.coroutineContext) {
            repeat(3) { sink.emit(makeEvent("evt-$it")) }
            sink.emit(makeEvent("after"))
        }
        waitForRequests(1)
        withContext(s.coroutineContext) { sink.close() }
        assertTrue("expected at least 1 successful request", server.requestCount >= 1)
    }
}
