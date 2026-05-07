/*
*
* Copyright (c) 2025 Abolfazl Abbasi
*
* */

package abbasi.android.filelogger.okhttp

import abbasi.android.filelogger.FileLogger
import abbasi.android.filelogger.Logger
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp [Interceptor] that routes request/response diagnostics to a [Logger] instance instead of
 * stdout. Modelled after `okhttp3.logging.HttpLoggingInterceptor` but with three differences:
 *
 *  1. Output target is the supplied [logger] (defaults to [FileLogger]) — diagnostics flow through
 *     the FileLogger pipeline, so they hit every configured sink (file, HTTP forwarder, etc.).
 *  2. Header redaction is configurable via [redactedHeaders]; defaults cover `Authorization`,
 *     `Cookie`, `Set-Cookie`, and `Proxy-Authorization`.
 *  3. Body capture caps at [maxBodyBytes] — bodies above the cap are truncated and tagged so
 *     leaking memory on a multi-megabyte response is impossible by default.
 *
 * Threading: OkHttp invokes interceptors synchronously from the dispatcher thread. The [Logger]
 * methods are non-suspending, so this interceptor never uses `runBlocking`.
 *
 * Failure semantics: if `chain.proceed` throws, the failure is logged via [logger] and rethrown
 * verbatim. The interceptor never swallows.
 *
 * @param logger sink for the diagnostics. Defaults to the singleton [FileLogger].
 * @param tag tag attached to every log line emitted by this interceptor.
 * @param level verbosity. See [Level] for the four supported values.
 * @param redactedHeaders set of header names whose values are replaced with `"[REDACTED]"`.
 *  Comparison is case-insensitive.
 * @param maxBodyBytes upper bound on captured body bytes, in either direction. Beyond the cap the
 *  body is truncated with a marker. Defaults to 1 MiB.
 */
public class FileLoggerOkHttpInterceptor(
    private val logger: Logger = FileLogger,
    private val tag: String = DEFAULT_TAG,
    private val level: Level = Level.HEADERS,
    redactedHeaders: Set<String> = DEFAULT_REDACTED_HEADERS,
    private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) : Interceptor {

    private val redactedHeadersLower: Set<String> =
        redactedHeaders.mapTo(HashSet()) { it.lowercase() }

    /** Verbosity gate. Maps to OkHttp's `HttpLoggingInterceptor.Level` for familiarity. */
    public enum class Level {
        /** Skip logging entirely. */
        NONE,

        /** Log request line + response line + duration. */
        BASIC,

        /** Log request/response lines, all headers (with redaction), and durations. */
        HEADERS,

        /** Log everything plus request and response bodies up to `maxBodyBytes`. */
        BODY,
    }

    public override fun intercept(chain: Interceptor.Chain): Response {
        if (level == Level.NONE) return chain.proceed(chain.request())

        val request = chain.request()
        val requestBody = request.body
        val hasRequestBody = requestBody != null

        val requestLine = buildString {
            append("--> ").append(request.method).append(' ').append(request.url)
            if (hasRequestBody && level == Level.BASIC) {
                append(" (").append(requestBody!!.contentLength()).append("-byte body)")
            }
        }
        logger.i(tag = tag, message = requestLine)

        if (level == Level.HEADERS || level == Level.BODY) {
            for (i in 0 until request.headers.size) {
                val name = request.headers.name(i)
                val value = if (redactedHeadersLower.contains(name.lowercase())) {
                    REDACTED
                } else {
                    request.headers.value(i)
                }
                logger.i(tag = tag, message = "$name: $value")
            }
        }

        if (level == Level.BODY && hasRequestBody) {
            logRequestBody(requestBody!!)
        }

        val startNs = System.nanoTime()
        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            logger.e(tag = tag, message = "<-- HTTP FAILED for ${request.url}", throwable = e)
            throw e
        }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        val responseBody = response.body
        val contentLength = responseBody?.contentLength() ?: -1L
        val bodySizeText = if (contentLength != -1L) "$contentLength-byte" else "unknown-length"

        logger.i(
            tag = tag,
            message = "<-- ${response.code} ${response.message} ${response.request.url} (${tookMs}ms, $bodySizeText body)",
        )

        if (level == Level.HEADERS || level == Level.BODY) {
            for (i in 0 until response.headers.size) {
                val name = response.headers.name(i)
                val value = if (redactedHeadersLower.contains(name.lowercase())) {
                    REDACTED
                } else {
                    response.headers.value(i)
                }
                logger.i(tag = tag, message = "$name: $value")
            }
        }

        if (level == Level.BODY && responseBody != null && contentLength != 0L) {
            logResponseBody(response)
        }

        return response
    }

    private fun logRequestBody(body: okhttp3.RequestBody) {
        try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val total = buffer.size
            val readable = minOf(total, maxBodyBytes)
            val text = buffer.readString(readable, Charsets.UTF_8)
            logger.i(tag = tag, message = "request body: $text${if (total > readable) " ... (truncated, $total bytes total)" else ""}")
        } catch (e: IOException) {
            logger.w(tag = tag, message = "failed to read request body", throwable = e)
        }
    }

    private fun logResponseBody(response: Response) {
        try {
            val source = response.body?.source() ?: return
            source.request(maxBodyBytes)
            val total = source.buffer.size
            val readable = minOf(total, maxBodyBytes)
            val text = source.buffer.clone().readString(readable, Charsets.UTF_8)
            logger.i(tag = tag, message = "response body: $text${if (total > readable) " ... (truncated, $total bytes total)" else ""}")
        } catch (e: IOException) {
            logger.w(tag = tag, message = "failed to read response body", throwable = e)
        }
    }

    private companion object {
        const val DEFAULT_TAG = "OkHttp"
        const val REDACTED = "[REDACTED]"
        const val DEFAULT_MAX_BODY_BYTES: Long = 1L * 1024L * 1024L
        val DEFAULT_REDACTED_HEADERS: Set<String> = setOf(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "Proxy-Authorization",
        )
    }
}
