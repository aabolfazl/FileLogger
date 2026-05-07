/**
 * Covers the default redaction patterns, identity-equality fast path, oversized passthrough, and
 * `flush`/`close` delegation. `FakeSink` captures the (possibly redacted) events so assertions
 * are straightforward.
 */
package abbasi.android.filelogger.sink

import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.pipeline.LogEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingSinkTest {

    private fun event(message: String): LogEvent = LogEvent(
        level = LogLevel.Info,
        tag = "T",
        lazyMessage = { message },
        throwable = null,
        timestampMs = 0L,
        threadName = "t",
        mdc = emptyMap(),
    )

    @Test
    fun `email is redacted`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        sink.emit(event("contact me at john@example.com"))
        assertEquals("contact me at [REDACTED]", fake.events.single().lazyMessage())
    }

    @Test
    fun `bearer token is redacted`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        sink.emit(event("Authorization: Bearer abc.def-123"))
        assertTrue(fake.events.single().lazyMessage().contains("[REDACTED]"))
        assertTrue(!fake.events.single().lazyMessage().contains("abc.def-123"))
    }

    @Test
    fun `credit card number is redacted`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        sink.emit(event("card 4111 1111 1111 1111 ok"))
        assertTrue(fake.events.single().lazyMessage().contains("[REDACTED]"))
    }

    @Test
    fun `ipv4 address is redacted`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        sink.emit(event("from 10.0.0.5 connected"))
        assertEquals("from [REDACTED] connected", fake.events.single().lazyMessage())
    }

    @Test
    fun `multiple patterns redact in one message`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        sink.emit(event("user a@b.co from 1.2.3.4"))
        val msg = fake.events.single().lazyMessage()
        assertTrue(!msg.contains("a@b.co"))
        assertTrue(!msg.contains("1.2.3.4"))
    }

    @Test
    fun `empty patterns list passes through unchanged`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake, patterns = emptyList())
        val ev = event("a@b.co 1.2.3.4")
        sink.emit(ev)
        assertSame(ev, fake.events.single())
    }

    @Test
    fun `identity equality fast path forwards the same event when no match`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        val ev = event("plain message no secrets")
        sink.emit(ev)
        assertSame(ev, fake.events.single())
    }

    @Test
    fun `oversized message is forwarded unredacted`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake, maxMessageBytes = 32)
        val big = "user@example.com " + "x".repeat(1024)
        val ev = event(big)
        sink.emit(ev)
        assertSame(ev, fake.events.single())
        assertTrue(fake.events.single().lazyMessage().contains("user@example.com"))
    }

    @Test
    fun `flush delegates to wrapped sink`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        sink.flush()
        sink.flush()
        assertEquals(2, fake.flushCount.get())
    }

    @Test
    fun `close delegates to wrapped sink`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        sink.close()
        assertEquals(1, fake.closeCount.get())
    }

    @Test
    fun `redacted event preserves level and tag`() = runTest {
        val fake = FakeSink()
        val sink = RedactingSink(fake)
        val ev = event("user@x.io").copy(level = LogLevel.Error, tag = "Custom")
        sink.emit(ev)
        val emitted = fake.events.single()
        assertEquals(LogLevel.Error, emitted.level)
        assertEquals("Custom", emitted.tag)
        assertNotEquals("user@x.io", emitted.lazyMessage())
    }
}
