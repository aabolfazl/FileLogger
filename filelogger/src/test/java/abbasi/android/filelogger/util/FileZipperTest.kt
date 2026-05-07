/**
 * Regression coverage for `FileZipper.compressFiles`. The v1.x implementation invoked the supplied
 * callback twice when the inner zip work failed (`callback(null)` from the inner `catch`, then
 * `callback(zipFile)` from the fall-through after `finally`). These tests pin the corrected
 * one-callback-per-call contract on the happy path and on two distinct failure paths.
 */
package abbasi.android.filelogger.util

import abbasi.android.filelogger.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [33])
class FileZipperTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var logsDir: File
    private var testScope: CoroutineScope? = null

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        logsDir = File(rootDir, "fileLogs").apply { mkdirs() }
    }

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

    @Suppress("DEPRECATION")
    private fun makeConfig(directory: String = rootDir.absolutePath): Config =
        Config.Builder(directory).build()

    @Test
    fun `happy path invokes callback exactly once with the zip file`() = runTest {
        File(logsDir, "a.txt").writeText("alpha")
        File(logsDir, "b.txt").writeText("beta")

        val zipper = FileZipper(newScope())
        val callbackCount = AtomicInteger(0)
        val received = AtomicReference<File?>()

        zipper.compressFiles(makeConfig()) { result ->
            callbackCount.incrementAndGet()
            received.set(result)
        }
        yield()

        assertEquals(1, callbackCount.get())
        assertNotNull(received.get())
        assertEquals(true, received.get()!!.exists())
    }

    @Test
    fun `inner failure (zip path is a non-empty directory) invokes callback exactly once with null`() = runTest {
        File(logsDir, "a.txt").writeText("alpha")
        val zipAsDir = File(logsDir, "Logs.zip")
        zipAsDir.mkdir()
        File(zipAsDir, "blocker.txt").writeText("non-empty so File.delete() returns false")

        val zipper = FileZipper(newScope())
        val callbackCount = AtomicInteger(0)
        val received = AtomicReference<File?>()

        zipper.compressFiles(makeConfig()) { result ->
            callbackCount.incrementAndGet()
            received.set(result)
        }
        yield()

        assertEquals(1, callbackCount.get())
        assertNull(received.get())
    }

    @Test
    fun `inner failure (non-existent log directory) invokes callback exactly once with null`() = runTest {
        val ghostRoot = File(tempFolder.root, "does-not-exist").absolutePath
        val zipper = FileZipper(newScope())
        val callbackCount = AtomicInteger(0)
        val received = AtomicReference<File?>()

        zipper.compressFiles(makeConfig(directory = ghostRoot)) { result ->
            callbackCount.incrementAndGet()
            received.set(result)
        }
        yield()

        assertEquals(1, callbackCount.get())
        assertNull(received.get())
    }
}
