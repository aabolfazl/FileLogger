/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.sink

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.format.LogFormatter
import abbasi.android.filelogger.internal.FileLoggerInternalLog
import abbasi.android.filelogger.pipeline.LogEvent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Production HTTP sink. Pre-formats every event on `emit` so retries replay the same bytes that
 * the first attempt produced; batches are uploaded as JSON-Lines (one record per line) by a
 * dedicated uploader coroutine launched on the pipeline scope. The sink is single-threaded by
 * design — both buffers (`currentBatch` and `pendingBatches`) are touched only by the pipeline
 * dispatcher, so they need no locks.
 *
 * Backpressure: when [maxQueuedBatches] is reached the **oldest** batch is dropped and counted; a
 * synthetic warning surfaces in the next successful drain. The queue is volatile in-memory only;
 * batches that have not been delivered when [close] returns are documented as lost. Durable
 * upload (write-ahead log + replay) is intentionally out of scope for v2.0.
 *
 * Retries: transient failures (network errors, 5xx, non-permanent 4xx) use exponential backoff
 * with jitter capped at 60 s; after [maxRetries] the batch is dropped and a warn is funnelled to
 * `FileLoggerInternalLog`. Permanent 4xx (`400/401/403/404/410`) drop the batch immediately
 * without consuming a retry — a misconfigured endpoint is not a retryable condition. While the
 * configured [networkPolicy] is not satisfied the uploader sleeps for 5 s between checks rather
 * than burning retries.
 *
 * @param endpoint absolute URL the sink POSTs to.
 * @param context required for `ConnectivityManager`; the sink does not retain the activity-scoped
 *  context (it stores whatever is passed in, so prefer the application context).
 * @param scope pipeline scope; the periodic flush ticker and the uploader coroutine are children
 *  so a single `scope.cancel()` tears them down.
 * @param formatter shared formatter; invoked on the pipeline thread inside [emit] so that retries
 *  replay byte-identical text.
 * @param headers static request headers. `Content-Type` is forced to `application/json` and
 *  cannot be overridden.
 * @param batchSize roll the current batch into the pending queue when it reaches this many
 *  events. Defaults to 50.
 * @param flushInterval upper bound on how long an event waits before being rolled into a batch.
 *  Defaults to 30 s.
 * @param maxRetries upper bound on retries per batch. After this many transient failures the
 *  batch is dropped. Defaults to 5.
 * @param networkPolicy connectivity gate; the uploader pauses while the gate is closed instead of
 *  burning retries. Defaults to [NetworkPolicy.ANY].
 * @param maxQueuedBatches in-memory cap on pending batches. When reached the oldest batch is
 *  dropped. Defaults to 500.
 */
public class HttpSink(
    private val endpoint: String,
    private val context: Context,
    private val scope: CoroutineScope,
    private val formatter: LogFormatter,
    private val headers: Map<String, String> = emptyMap(),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    private val maxQueuedBatches: Int = DEFAULT_MAX_QUEUED_BATCHES,
) : LogSink {

    public override val id: String = "http"

    private val endpointUrl: URL = URL(endpoint)

    private val closed = AtomicBoolean(false)

    private var currentBatch: MutableList<String> = mutableListOf()
    private val pendingBatches: ArrayDeque<List<String>> = ArrayDeque()
    private var droppedDueToOverflow: Long = 0L

    private val uploaderJob: Job
    private val flushTickerJob: Job

    init {
        uploaderJob = scope.launch { uploaderLoop() }
        flushTickerJob = scope.launch { flushTickerLoop() }
    }

    public override suspend fun emit(event: LogEvent) {
        if (closed.get()) return
        emitOverflowNoticeIfNeeded(event.timestampMs)
        currentBatch.add(formatter.format(event))
        if (currentBatch.size >= batchSize) {
            rollCurrentBatch()
        }
    }

    public override suspend fun flush() {
        if (currentBatch.isNotEmpty()) {
            rollCurrentBatch()
        }
    }

    public override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        flushTickerJob.cancel()
        uploaderJob.cancel()
        uploaderJob.join()
        if (currentBatch.isNotEmpty()) {
            rollCurrentBatch()
        }
        withTimeoutOrNull(CLOSE_DRAIN_TIMEOUT_MS) {
            while (pendingBatches.isNotEmpty()) {
                val next = pendingBatches.removeFirst()
                if (uploadBatchOnce(next) == UploadOutcome.TransientFailure) {
                    pendingBatches.addFirst(next)
                    return@withTimeoutOrNull
                }
            }
        }
    }

    private fun rollCurrentBatch() {
        if (currentBatch.isEmpty()) return
        if (pendingBatches.size >= maxQueuedBatches) {
            pendingBatches.removeFirst()
            droppedDueToOverflow++
        }
        pendingBatches.addLast(currentBatch)
        currentBatch = mutableListOf()
    }

    private fun emitOverflowNoticeIfNeeded(timestampMs: Long) {
        if (droppedDueToOverflow == 0L) return
        val count = droppedDueToOverflow
        droppedDueToOverflow = 0L
        val notice = LogEvent(
            level = LogLevel.Warning,
            tag = "FileLogger",
            lazyMessage = { "HttpSink dropped $count batches due to in-memory queue overflow" },
            throwable = null,
            timestampMs = timestampMs,
            threadName = "FileLogger",
            mdc = emptyMap(),
        )
        currentBatch.add(formatter.format(notice))
    }

    private suspend fun flushTickerLoop() {
        while (currentCoroutineContext().isActive) {
            delay(flushInterval)
            if (closed.get()) return
            if (currentBatch.isNotEmpty()) rollCurrentBatch()
        }
    }

    private suspend fun uploaderLoop() {
        var attempt = 0
        var inFlight: List<String>? = null
        while (currentCoroutineContext().isActive) {
            val batch = inFlight ?: pendingBatches.removeFirstOrNull()
            if (batch == null) {
                delay(IDLE_POLL_MS)
                continue
            }
            if (!networkAvailable(networkPolicy)) {
                inFlight = batch
                delay(NO_NETWORK_BACKOFF_MS)
                continue
            }
            val outcome = uploadBatchOnce(batch)
            when (outcome) {
                UploadOutcome.Success, UploadOutcome.PermanentFailure -> {
                    inFlight = null
                    attempt = 0
                }
                UploadOutcome.TransientFailure -> {
                    attempt++
                    if (attempt > maxRetries) {
                        inFlight = null
                        FileLoggerInternalLog.warnRateLimited(
                            key = "http.exhausted",
                            msg = "HttpSink dropped batch after $maxRetries retries",
                        )
                        attempt = 0
                    } else {
                        inFlight = batch
                        val backoff = backoffMillis(attempt)
                        delay(backoff)
                    }
                }
            }
        }
    }

    private suspend fun uploadBatchOnce(batch: List<String>): UploadOutcome =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (endpointUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    headers.forEach { (k, v) ->
                        if (!k.equals("Content-Type", ignoreCase = true)) {
                            setRequestProperty(k, v)
                        }
                    }
                }
                BufferedOutputStream(connection.outputStream).use { out ->
                    val body = batch.joinToString("\n")
                    out.write(body.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                val code = connection.responseCode
                classifyResponse(code)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                FileLoggerInternalLog.warnRateLimited("http.io", "HttpSink upload IO failure", e)
                UploadOutcome.TransientFailure
            } catch (e: Exception) {
                FileLoggerInternalLog.warnRateLimited("http.unexpected", "HttpSink upload unexpected failure", e)
                UploadOutcome.TransientFailure
            } finally {
                connection?.disconnect()
            }
        }

    private fun classifyResponse(code: Int): UploadOutcome = when {
        code in 200..299 -> UploadOutcome.Success
        code in PERMANENT_4XX -> {
            FileLoggerInternalLog.warnRateLimited(
                key = "http.4xx",
                msg = "HttpSink dropped batch on permanent HTTP $code",
            )
            UploadOutcome.PermanentFailure
        }
        else -> UploadOutcome.TransientFailure
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = (1000L shl minOf(attempt - 1, BACKOFF_SHIFT_CAP)).coerceAtMost(MAX_BACKOFF_MS)
        return base + Random.nextLong(0L, BACKOFF_JITTER_MS)
    }

    private fun networkAvailable(policy: NetworkPolicy): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return when (policy) {
            NetworkPolicy.ANY -> true
            NetworkPolicy.UNMETERED_ONLY ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            NetworkPolicy.WIFI_ONLY ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    private enum class UploadOutcome { Success, PermanentFailure, TransientFailure }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 50
        val DEFAULT_FLUSH_INTERVAL: Duration = 30.seconds
        const val DEFAULT_MAX_RETRIES = 5
        const val DEFAULT_MAX_QUEUED_BATCHES = 500
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val IDLE_POLL_MS = 250L
        const val NO_NETWORK_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val BACKOFF_SHIFT_CAP = 6
        const val BACKOFF_JITTER_MS = 250L
        const val CLOSE_DRAIN_TIMEOUT_MS = 1_500L
        val PERMANENT_4XX = setOf(400, 401, 403, 404, 410)
    }
}

/**
 * Connectivity gate consulted by [HttpSink] before each upload attempt. The sink does not register
 * a `NetworkCallback`; it polls `ConnectivityManager` synchronously, which is cheap and avoids
 * holding a callback across the pipeline lifetime.
 */
public enum class NetworkPolicy {
    /** Upload over any network type, including metered cellular. */
    ANY,

    /** Upload only over networks reporting `NET_CAPABILITY_NOT_METERED`. */
    UNMETERED_ONLY,

    /** Upload only over a transport reporting `TRANSPORT_WIFI`. */
    WIFI_ONLY,
}
