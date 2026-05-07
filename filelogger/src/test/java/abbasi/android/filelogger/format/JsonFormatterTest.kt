/**
 * Covers `JsonFormatter` and the underlying `appendJsonString` escape table. Pure JVM — no
 * Robolectric. Each test asserts the exact resulting line so a regression in escape handling
 * surfaces immediately.
 */
package abbasi.android.filelogger.format

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.time.TimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JsonFormatterTest {

    private lateinit var formatter: JsonFormatter

    @Before
    fun setUp() {
        val tf = TimeFormatter("yyyy", followSystemTimeZone = false)
        formatter = JsonFormatter(tf, "DefaultTag")
    }

    private fun event(message: String, throwable: Throwable? = null, mdc: Map<String, String> = emptyMap()): LogEvent =
        LogEvent(
            level = LogLevel.Info,
            tag = "Tag",
            lazyMessage = { message },
            throwable = throwable,
            timestampMs = 0L,
            threadName = "t",
            mdc = mdc,
        )

    @Test
    fun `ascii passthrough`() {
        val line = formatter.format(event("hello world"))
        assertTrue(line.contains("\"msg\":\"hello world\""))
    }

    @Test
    fun `quote is escaped`() {
        val line = formatter.format(event("a\"b"))
        assertTrue(line.contains("\"msg\":\"a\\\"b\""))
    }

    @Test
    fun `backslash is escaped`() {
        val line = formatter.format(event("a\\b"))
        assertTrue(line.contains("\"msg\":\"a\\\\b\""))
    }

    @Test
    fun `newline carriage tab backspace formfeed are escaped`() {
        val line = formatter.format(event("a\nb\rc\td\bef"))
        assertTrue(line.contains("\\n"))
        assertTrue(line.contains("\\r"))
        assertTrue(line.contains("\\t"))
        assertTrue(line.contains("\\b"))
        assertTrue(line.contains("\\f"))
    }

    @Test
    fun `control char below 0x20 is escaped to unicode`() {
        val line = formatter.format(event("xy"))
        assertTrue(line.contains("\\u0001"))
    }

    @Test
    fun `high codepoint emoji passes through`() {
        val line = formatter.format(event("hi 😀"))
        assertTrue(line.contains("hi 😀"))
    }

    @Test
    fun `null throwable serialises as json null literal`() {
        val line = formatter.format(event("m", throwable = null))
        assertTrue(line.contains(",\"throwable\":null"))
        assertFalse(line.contains(",\"throwable\":\"null\""))
    }

    @Test
    fun `non-null throwable string field has newlines escaped`() {
        val ex = RuntimeException("boom")
        val line = formatter.format(event("m", throwable = ex))
        val idx = line.indexOf("\"throwable\":\"")
        assertTrue(idx >= 0)
        val tail = line.substring(idx + "\"throwable\":\"".length)
        assertTrue(tail.contains("\\n"))
        assertFalse(tail.substring(0, tail.indexOf("\"")).contains("\n"))
    }

    @Test
    fun `empty mdc omits the field`() {
        val line = formatter.format(event("m", mdc = emptyMap()))
        assertFalse(line.contains("\"mdc\""))
    }

    @Test
    fun `non-empty mdc serialises as json object`() {
        val line = formatter.format(event("m", mdc = mapOf("k" to "v")))
        assertTrue(line.contains("\"mdc\":{\"k\":\"v\"}"))
    }

    @Test
    fun `output ends with newline`() {
        val line = formatter.format(event("m"))
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun `tag field falls back to default when null`() {
        val ev = LogEvent(
            level = LogLevel.Info,
            tag = null,
            lazyMessage = { "m" },
            throwable = null,
            timestampMs = 0L,
            threadName = "t",
            mdc = emptyMap(),
        )
        val line = formatter.format(ev)
        assertTrue(line.contains("\"tag\":\"DefaultTag\""))
    }

    @Test
    fun `level renders as one letter abbreviation`() {
        val ev = event("m").copy(level = LogLevel.Error)
        val line = formatter.format(ev)
        assertTrue(line.contains("\"level\":\"E\""))
    }
}
