/**
 * On-device smoke test for FileLogger. Runs against a real Android runtime, exercising the
 * paths a unit test cannot — `ProcessLifecycleOwner` registration, real `StatFs`, real
 * `android.util.Log` JNI, real file I/O on the app's `filesDir`. Run from the host with:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 */
package abbasi.android.filelogger.sample

import abbasi.android.filelogger.FileLogger
import abbasi.android.filelogger.dsl.fileLogger
import abbasi.android.filelogger.file.LogLevel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FileLoggerSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var rootDir: String
    private lateinit var logsDir: File

    @Before
    fun setUp() {
        rootDir = File(context.filesDir, "smoke-${System.nanoTime()}").also { it.mkdirs() }.absolutePath
        logsDir = File(rootDir, "fileLogs")
        FileLogger.init(
            context,
            fileLogger(rootDir) {
                defaultTag = "Smoke"
                logcatEnabled = true
                minLevel = LogLevel.Debug
            },
        )
    }

    @After
    fun tearDown() {
        runBlocking { FileLogger.shutdown(2_000) }
        File(rootDir).deleteRecursively()
    }

    @Test
    fun emit_d_i_w_e_writes_into_log_file() {
        FileLogger.d(message = "debug-line")
        FileLogger.i(message = "info-line")
        FileLogger.w(message = "warn-line")
        FileLogger.e(message = "error-line")

        val deadline = System.currentTimeMillis() + 5_000
        var written = false
        while (System.currentTimeMillis() < deadline) {
            val files = logsDir.listFiles().orEmpty()
            if (files.isNotEmpty() && files.first().length() > 0) {
                written = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue("expected log file with content under $logsDir within 5s", written)
        val text = logsDir.listFiles().orEmpty().first().readText()
        assertTrue("expected info-line in log content", text.contains("info-line"))
    }

    @Test
    fun compress_logs_zip_produces_a_zip_file() {
        FileLogger.i(message = "before-zip")

        val latch = CountDownLatch(1)
        var produced: File? = null
        FileLogger.compressLogsInZipFile { result ->
            produced = result
            latch.countDown()
        }
        assertTrue("zip callback did not fire within 5s", latch.await(5, TimeUnit.SECONDS))
        assertNotNull("zip callback received null", produced)
        assertTrue("zip file does not exist on disk", produced!!.exists())
        assertTrue("zip file is empty", produced!!.length() > 0)
    }
}
