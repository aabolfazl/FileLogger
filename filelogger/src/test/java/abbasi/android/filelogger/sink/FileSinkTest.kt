/**
 * Covers `FileSink` append-across-lifetimes, batched flush, idempotent close, and `purgeOldFiles`.
 * Robolectric supplies `SystemClock` and `StatFs`. The temp directory always has plenty of free
 * space, so a guard with `minFreeBytes = 0` returns true.
 *
 * Known limitations:
 *  - Time-based rotation and TTL retention depend on wall-clock `System.currentTimeMillis()` and
 *    `File.lastModified()`, which `runTest`'s virtual time cannot advance and which `Thread.sleep`
 *    is forbidden to drive in tests. Those paths are exercised by the sample app and integration
 *    runs.
 *  - Disk-full short-circuit requires injecting a guard whose `canWrite()` returns `false`;
 *    `DiskSpaceGuard` is non-`open` so a fake cannot subclass it. Covered by integration runs.
 */
package abbasi.android.filelogger.sink

import abbasi.android.filelogger.config.FileRotationStrategy
import abbasi.android.filelogger.file.DiskSpaceGuard
import abbasi.android.filelogger.file.LogLevel
import abbasi.android.filelogger.format.PlainTextFormatter
import abbasi.android.filelogger.pipeline.LogEvent
import abbasi.android.filelogger.time.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileSinkTest {

    private lateinit var rootDir: File
    private lateinit var logsDir: File
    private lateinit var formatter: PlainTextFormatter
    private lateinit var timeFormatter: TimeFormatter
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("fl-test").toFile()
        logsDir = File(rootDir, "fileLogs")
        timeFormatter = TimeFormatter("yyyyMMddHHmmssSSS", followSystemTimeZone = false)
        formatter = PlainTextFormatter(timeFormatter, "T")
    }

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
        rootDir.deleteRecursively()
    }

    private fun makeScope(): CoroutineScope {
        val s = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        scope = s
        return s
    }

    private fun makeSink(
        scope: CoroutineScope,
        rotation: FileRotationStrategy = FileRotationStrategy.None,
        batchEvery: Int = 100,
        diskGuard: DiskSpaceGuard = DiskSpaceGuard(logsDir, minFreeBytes = 0L),
    ): FileSink = FileSink(
        rootDir = rootDir.absolutePath,
        scope = scope,
        formatter = formatter,
        rotation = rotation,
        retention = null,
        startupData = null,
        timeFormatter = timeFormatter,
        diskGuard = diskGuard,
        batchEvery = batchEvery,
    )

    private fun makeEvent(msg: String): LogEvent = LogEvent(
        level = LogLevel.Info,
        tag = "T",
        lazyMessage = { msg },
        throwable = null,
        timestampMs = System.currentTimeMillis(),
        threadName = "t",
        mdc = emptyMap(),
    )

    @Test
    fun `append mode preserves prior file content across sink lifetimes`() = runTest {
        val s1 = makeScope()
        val sink1 = makeSink(s1, batchEvery = 1)
        repeat(5) { sink1.emit(makeEvent("first-$it")) }
        sink1.close()
        s1.cancel()

        val s2 = makeScope()
        val sink2 = makeSink(s2, batchEvery = 1)
        repeat(3) { sink2.emit(makeEvent("second-$it")) }
        sink2.close()
        s2.cancel()

        val combined = logsDir.listFiles().orEmpty()
            .joinToString("\n") { it.readText() }
        repeat(5) { assertTrue("missing first-$it", combined.contains("first-$it")) }
        repeat(3) { assertTrue("missing second-$it", combined.contains("second-$it")) }
    }

    @Test
    fun `batched flush writes content to disk after threshold`() = runTest {
        val s = makeScope()
        val sink = makeSink(s, batchEvery = 3)
        repeat(4) { sink.emit(makeEvent("flushed-$it")) }
        sink.flush()
        sink.close()
        val files = logsDir.listFiles().orEmpty()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("flushed-0"))
        assertTrue(content.contains("flushed-3"))
    }

    @Test
    fun `close is idempotent`() = runTest {
        val s = makeScope()
        val sink = makeSink(s, batchEvery = 1)
        sink.emit(makeEvent("once"))
        sink.close()
        sink.close()
        val files = logsDir.listFiles().orEmpty()
        assertEquals(1, files.size)
    }

    @Test
    fun `purgeOldFiles deletes everything except the open file`() = runTest {
        val s = makeScope()
        val sink = makeSink(s, batchEvery = 1)
        sink.emit(makeEvent("kept"))
        File(logsDir, "stale-1.txt").writeText("old1")
        File(logsDir, "stale-2.txt").writeText("old2")
        assertTrue(logsDir.listFiles().orEmpty().size >= 3)
        sink.purgeOldFiles()
        val remaining = logsDir.listFiles().orEmpty()
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().readText().contains("kept"))
        sink.close()
    }

    @Test
    fun `events are written under default real-disk guard`() = runTest {
        val s = makeScope()
        val sink = makeSink(s, batchEvery = 1)
        sink.emit(makeEvent("written"))
        sink.flush()
        sink.close()
        val content = logsDir.listFiles().orEmpty().joinToString("\n") { it.readText() }
        assertTrue(content.contains("written"))
    }
}
