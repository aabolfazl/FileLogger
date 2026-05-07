/**
 * Covers `CrashHandlerInstaller.install`/`uninstall` chain semantics and idempotency. The
 * `runBlocking { FileLogger.shutdown(...) }` path is intentionally not exercised here because
 * the live integration is the determinism boundary; we focus on chain integrity. Robolectric is
 * required so `FileLoggerInternalLog` (which calls `android.util.Log` and `SystemClock`) does
 * not blow up when the handler funnels an internal warning.
 */
package abbasi.android.filelogger.crash

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashHandlerInstallerTest {

    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        CrashHandlerInstaller.uninstall()
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Test
    fun `install then uninstall restores previous handler`() {
        val captured = CapturingHandler()
        Thread.setDefaultUncaughtExceptionHandler(captured)

        CrashHandlerInstaller.install(rethrow = true)
        assertNotNull(Thread.getDefaultUncaughtExceptionHandler())

        CrashHandlerInstaller.uninstall()
        assertSame(captured, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `install is idempotent`() {
        CrashHandlerInstaller.install(rethrow = false)
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashHandlerInstaller.install(rethrow = false)
        val second = Thread.getDefaultUncaughtExceptionHandler()
        assertSame(first, second)
    }

    @Test
    fun `uninstall is idempotent`() {
        CrashHandlerInstaller.install(rethrow = false)
        CrashHandlerInstaller.uninstall()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        CrashHandlerInstaller.uninstall()
        val after = Thread.getDefaultUncaughtExceptionHandler()
        assertSame(before, after)
    }

    @Test
    fun `uninstall before install does not throw`() {
        CrashHandlerInstaller.uninstall()
        CrashHandlerInstaller.uninstall()
    }

    private class CapturingHandler : Thread.UncaughtExceptionHandler {
        var thread: Thread? = null
        var exception: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) {
            thread = t
            exception = e
        }
    }
}
